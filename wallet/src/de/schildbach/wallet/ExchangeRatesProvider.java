/*
 * Copyright 2011-2013 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Currency;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import android.util.Log;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.util.GenericUtils;
import de.schildbach.wallet.util.Io;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.format.DateUtils;

/**
 * @author Andreas Schildbach, Litecoin Dev Team, AuroraCoin Dev Team
 */
public class ExchangeRatesProvider extends ContentProvider
{
    static final protected String TAG = ExchangeRatesProvider.class.getName();
    static final protected Object lock = new Object();

	public static class ExchangeRate
	{
		public ExchangeRate(@Nonnull final String currencyCode, @Nonnull final BigInteger rate, @Nonnull final String source)
		{
			this.currencyCode = currencyCode;
			this.rate = rate;
			this.source = source;
		}

		public final String currencyCode;
		public final BigInteger rate;
		public final String source;

		@Override
		public String toString()
		{
			return getClass().getSimpleName() + '[' + currencyCode + ':' + GenericUtils.formatValue(rate, Constants.BTC_MAX_PRECISION, 0) + ']';
		}
	}

	public static final String KEY_CURRENCY_CODE = "currency_code";
	private static final String KEY_RATE = "rate";
	private static final String KEY_SOURCE = "source";

	@CheckForNull
	private Map<String, ExchangeRate> exchangeRates = null;
	private long lastUpdated = 0;

    //private static final URL BTCE_URL;
    //private static final String[] BTCE_FIELDS = new String[] { "avg" };
    //private static final URL BTCE_EURO_URL;
    //private static final String[] BTCE_EURO_FIELDS = new String[] { "avg" };
	//private static final URL VIRCUREX_URL;
	//private static final String[] VIRCUREX_FIELDS = new String[] { "value" };
	private static final URL CRYPTSY_URL;
	private static final String[] CRYPTSY_FIELDS = new String[] { "lasttradeprice" };

	private static final URL POLONIEX_URL;
	private static final String[] POLONIEX_FIELDS = new String[] { "BTC_AUR" };

	private static final URL BTCE_BTC_USD_URL;
    private static final String[] BTCE_BTC_USD_URL_FIELDS = new String[] { "avg" };
    private static final URL BTCE_BTC_EUR_URL;
    private static final String[] BTCE_BTC_EUR_URL_FIELDS = new String[] { "avg" };
    private static final URL GOOGLE_USD_ISK_URL;
    private static final String[] GOOGLE_USD_ISK_URL_FIELDS = new String[] { "rate" };
    private static final URL GOOGLE_EUR_ISK_URL;
    private static final String[] GOOGLE_EUR_ISK_URL_FIELDS = new String[] { "rate" };
    private static final URL MOOLAH_AUR_ISK_URL;
    private static final String[] MOOLAH_EUR_ISK_URL_FIELDS = null; //just get the rate
    
	static
	{
		try
		{
            //BTCE_URL = new URL("https://btc-e.com/api/2/ltc_usd/ticker");
            //BTCE_EURO_URL = new URL("https://btc-e.com/api/2/ltc_eur/ticker");
            //VIRCUREX_URL = new URL("https://api.vircurex.com/api/get_last_trade.json?base=LTC&alt=USD");
            CRYPTSY_URL = new URL("http://pubapi.cryptsy.com/api.php?method=singlemarketdata&marketid=160");
            POLONIEX_URL = new URL("https://poloniex.com/public?command=returnTicker");
            BTCE_BTC_USD_URL = new URL("https://btc-e.com/api/2/btc_usd/ticker");
            BTCE_BTC_EUR_URL = new URL("https://btc-e.com/api/2/btc_eur/ticker");
            GOOGLE_USD_ISK_URL = new URL("http://rate-exchange.appspot.com/currency?from=USD&to=ISK");
            GOOGLE_EUR_ISK_URL = new URL("http://rate-exchange.appspot.com/currency?from=EUR&to=ISK");
            MOOLAH_AUR_ISK_URL = new URL("https://moolah.io/api/rates?f=AUR&t=ISK&a=1");

		}
		catch (final MalformedURLException x)
		{
			throw new RuntimeException(x); // cannot happen
		}
	}

	private static final long UPDATE_FREQ_MS = 10 * DateUtils.MINUTE_IN_MILLIS;

	private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

	@Override
	public boolean onCreate()
	{
		return true;
	}

	public static Uri contentUri(@Nonnull final String packageName)
	{
		return Uri.parse("content://" + packageName + '.' + "exchange_rates");
	}

	@Override
	public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs, final String sortOrder)
	{
		if (Constants.BUG_OPENSSL_HEARTBLEED) {
			return null;
		}
		final long now = System.currentTimeMillis();
		synchronized (lock)
		// quite long synced block, but the second thread has to wait until first has updated
		{
			if (exchangeRates == null || (now - lastUpdated) > UPDATE_FREQ_MS)
			{
				Map<String, ExchangeRate> newExchangeRates = null;
				//Map<String, ExchangeRate> newExchangeRates = new TreeMap<String, ExchangeRate>();
				//Map<String, ExchangeRate> poloExchangeRates = requestExchangeRates(POLONIEX_URL, "BTC", POLONIEX_FIELDS);
				//Map<String, ExchangeRate> cyptExchangeRates = requestExchangeRates(CRYPTSY_URL, "BTC", CRYPTSY_FIELDS);
	
				//newExchangeRates.putAll(poloExchangeRates);
				//newExchangeRates.putAll(cyptExchangeRates);
	
				// Attempt to get BTC exchange rates from all providers.  Stop after first.
				if (newExchangeRates == null)
					newExchangeRates = requestExchangeRates(CRYPTSY_URL, "BTC", CRYPTSY_FIELDS);
				if (newExchangeRates == null)
					newExchangeRates = requestExchangeRates(POLONIEX_URL, "BTC", POLONIEX_FIELDS);
				//if (exchangeRates == null && newExchangeRates == null)
				//	newExchangeRates = requestExchangeRates(VIRCUREX_URL, "USD", VIRCUREX_FIELDS);
	
	            // Get ISK, Euro and USD rates
				Map<String, ExchangeRate> aurIskRate = requestExchangeRates(MOOLAH_AUR_ISK_URL, "ISK", MOOLAH_EUR_ISK_URL_FIELDS);
	            Map<String, ExchangeRate> btcEurRates = requestExchangeRates(BTCE_BTC_EUR_URL, "EUR", BTCE_BTC_EUR_URL_FIELDS);
	            //requestExchangeRates(BTCE_EURO_URL, "EUR", BTCE_EURO_FIELDS);
	            Map<String, ExchangeRate> btcUsdRates = requestExchangeRates(BTCE_BTC_USD_URL, "USD", BTCE_BTC_USD_URL_FIELDS);
	            //requestExchangeRates(BTCE_EURO_URL, "EUR", BTCE_EURO_FIELDS);
	            if(aurIskRate != null) {
	                if(newExchangeRates != null)
	                    newExchangeRates.putAll(aurIskRate);
	                else
	                    newExchangeRates = aurIskRate;
	            }
				if (newExchangeRates != null)
				{ // generate AUR -> USD rate from AUR -> BTC -> USD
					log.info("Generating aur-> usd and aur->eur");
	    			ExchangeRate aurBtcRate = newExchangeRates.get("BTC");
	    			if (aurBtcRate != null)
					{
		    			if (btcEurRates != null)
						{
			    			ExchangeRate btcEurRate = btcEurRates.get("EUR");
				            if (btcEurRate != null)
							{
			    				log.info("BTCEUR rate found, generating btc-> EUR");
				    			Map<String, ExchangeRate> newAurEurRates = new TreeMap<String, ExchangeRate>();
				    			BigInteger rate = aurBtcRate.rate.multiply(btcEurRate.rate);
				    			rate = rate.divide(BigInteger.valueOf(100000000));
				    			ExchangeRate AurEurRate = new ExchangeRate("EUR", rate, aurBtcRate.source+"--"+btcEurRate.source);
				    			newExchangeRates.put("EUR", AurEurRate);
			    				log.info("AUR -> EUR "+rate);
			    				if (!newExchangeRates.containsKey("ISK"))
								{
				    	            Map<String, ExchangeRate> eurIskRates = requestExchangeRates(GOOGLE_EUR_ISK_URL, "ISK", GOOGLE_EUR_ISK_URL_FIELDS);
				    				if (eurIskRates != null)
									{
						    			ExchangeRate eurIskRate = eurIskRates.get("ISK");
					    				if (eurIskRate != null) 
										{
						    				log.info("ISK rate found, generating eur-> isk");
							    			//Map<String, ExchangeRate> newAurUsdRates = new TreeMap<String, ExchangeRate>();
							    			BigInteger iskRate = eurIskRate.rate.multiply(AurEurRate.rate);
							    			iskRate = iskRate.divide(BigInteger.valueOf(100000000));
							    			ExchangeRate AurISKRate = new ExchangeRate("ISK", iskRate, "AUR-BTC-EUR-ISK");
						    				log.info("AUR -> ISK "+iskRate);
						    				newExchangeRates.put("ISK", AurISKRate);
							            }
						            }
					            }
				            }
			            }
		    			if (btcUsdRates != null)
						{
			    			ExchangeRate btcUsdRate = btcUsdRates.get("USD");
		    				log.info("aurBTC rate found, generating btc-> usd and btc->eur");
				            if (btcUsdRate != null)
							{
			    				log.info("BTCUSD rate found, generating btc-> usd");
				    			Map<String, ExchangeRate> newAurUsdRates = new TreeMap<String, ExchangeRate>();
				    			BigInteger rate = aurBtcRate.rate.multiply(btcUsdRate.rate);
				    			rate = rate.divide(BigInteger.valueOf(100000000));
				    			ExchangeRate AurUsdRate = new ExchangeRate("USD", rate, aurBtcRate.source+"--"+btcUsdRate.source);
			    				log.info("AUR -> USD "+rate);
			    				newExchangeRates.put("USD", AurUsdRate);
			    				if (!newExchangeRates.containsKey("ISK"))
								{
				    	            Map<String, ExchangeRate> usdIskRates = requestExchangeRates(GOOGLE_USD_ISK_URL, "ISK", GOOGLE_USD_ISK_URL_FIELDS);
				    				if (usdIskRates != null) 
									{
						    			ExchangeRate usdIskRate = usdIskRates.get("ISK");
					    				if (usdIskRate != null) 
										{
						    				log.info("ISK rate found, generating usd-> isk");
							    			//Map<String, ExchangeRate> newAurIskRates = new TreeMap<String, ExchangeRate>();
							    			BigInteger iskRate = usdIskRate.rate.multiply(AurUsdRate.rate);
							    			iskRate = iskRate.divide(BigInteger.valueOf(100000000));
							    			ExchangeRate AurISKRate = new ExchangeRate("ISK", iskRate, "AUR-BTC-USD-ISK");
						    				log.info("AUR -> ISK "+iskRate);
						    				newExchangeRates.put("ISK", AurISKRate);
							            }
						            }
					            }
				            }
			            }
		            }
	            }
	            
	            
	            if (newExchangeRates != null)
				{
	                // Get USD conversion exchange rates from Google
	            	/*
	                ExchangeRate usdRate = newExchangeRates.get("USD");
	                if (usdRate != null) 
	    			{
	    				log.info("getting Google rates & Yahoo rates ");
		                RateProvider providers[] = {new GoogleRatesProvider(), new YahooRatesProvider()};
		                Map<String, ExchangeRate> fiatRates;
		                for(RateProvider provider : providers) {
		    				log.info("Provider: "+provider.toString());
		                    fiatRates = provider.getRates(usdRate);
		                    if(fiatRates != null) {
			    				log.info(" adding fiat rates. ");
		                        // Remove EUR if we have a better source above
		                        //if(euroRate != null)
		                        //    fiatRates.remove("EUR");
		                        newExchangeRates.putAll(fiatRates);
		                        break;
		                    } else 
		        			{
			    				log.info(" No fiat rates found. ");
			                }
		                }
	                }*/
	
					exchangeRates = newExchangeRates;
					lastUpdated = now;
				}
			}

			if (exchangeRates == null)
				return null;
		} // synchronized

		final MatrixCursor cursor = new MatrixCursor(new String[] { BaseColumns._ID, KEY_CURRENCY_CODE, KEY_RATE, KEY_SOURCE });

		if (selection == null)
		{
			for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet())
			{
				final ExchangeRate rate = entry.getValue();
				cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
			}
		}
		else if (selection.equals(KEY_CURRENCY_CODE))
		{
			final String selectedCode = selectionArgs[0];
			ExchangeRate rate = selectedCode != null ? exchangeRates.get(selectedCode) : null;

			if (rate == null)
			{
				final String defaultCode = defaultCurrencyCode();
				rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

				if (rate == null)
				{
					rate = exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);

					if (rate == null)
						return null;
				}
			}

			cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
		}

		return cursor;
	}

	private String defaultCurrencyCode()
	{
		try
		{
			return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
		}
		catch (final IllegalArgumentException x)
		{
			return null;
		}
	}

	public static ExchangeRate getExchangeRate(@Nonnull final Cursor cursor)
	{
		final String currencyCode = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_CODE));
		final BigInteger rate = BigInteger.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE)));
		final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

		return new ExchangeRate(currencyCode, rate, source);
	}

	@Override
	public Uri insert(final Uri uri, final ContentValues values)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(final Uri uri, final String selection, final String[] selectionArgs)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public String getType(final Uri uri)
	{
		throw new UnsupportedOperationException();
	}

	
	private static String digJSONObject(final JSONObject blob, final String field, final int depth) { 
		for (final Iterator<String> i = blob.keys(); i.hasNext();)
		{
			String nextField = i.next();
			//log.info("Iterating level "+depth+":"+i);
			final JSONObject o = blob.optJSONObject(nextField);
			if (o != null)
			{
				final String value = digJSONObject(o, field, depth+1);
		    	if (value != null) 
				{
		    		return value;
			    }
			} else { // simple value, leaf
				if (field.equals(nextField)) 
				{
					//log.info("leaf "+nextField+ " FOUND");
			    	final String value = blob.optString(nextField, null);
			    	if (value != null) 
					{
						log.info(nextField + " = " + value);
			    		return value;
				    }
			    	final Double dValue = blob.optDouble(nextField, -1);
			    	if (dValue >= 0) 
					{
						log.info(nextField + " = " + dValue.toString());
			    		return dValue.toString();
				    }
			    } else 
				{
					//log.info("leaf "+nextField);
			    }
		    }
		} 	
		return null;
	} 	

	private static Map<String, ExchangeRate> requestExchangeRates(final URL url, final String currencyCode, final String... fields)
	{
		HttpURLConnection connection = null;
		Reader reader = null;

		try
		{
			log.info("requestExchangeRates connecting to : " + url);
			connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.connect();

			final int responseCode = connection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK)
			{
				reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024), Constants.UTF_8);
				final StringBuilder content = new StringBuilder();
				Io.copy(reader, content);

				final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
				log.info("Got response, parsing "); //+content.toString());

				if (fields == null)
				{
					final String rateStr = content.toString();
					if (rateStr != null)
					{
						log.info("Got: " + rateStr);
						try
						{
							final BigInteger rate = GenericUtils.toNanoCoins(rateStr, 0);

							if (rate.signum() > 0)
							{
								log.info("Putting: " + currencyCode + " rate: " + rate);
								rates.put(currencyCode, new ExchangeRate(currencyCode, rate, url.getHost()));
							}
						}
						catch (final ArithmeticException x)
						{
							log.warn("problem fetching exchange rate: " + currencyCode, x);
						}
					}
				} else
				{
					final JSONObject head = new JSONObject(content.toString());
					for (final String field : fields)
					{
						log.info("Fetching field: " + field);
						final String rateStr = digJSONObject(head,field,0);
						if (rateStr != null)
						{
							log.info("Got: " + rateStr);
							try
							{
								final BigInteger rate = GenericUtils.toNanoCoins(rateStr, 0);
	
								if (rate.signum() > 0)
								{
									log.info("Putting: " + currencyCode + "rate: " + rate);
									rates.put(currencyCode, new ExchangeRate(currencyCode, rate, url.getHost()));
									break;
								}
							}
							catch (final ArithmeticException x)
							{
								log.warn("problem fetching exchange rate: " + currencyCode, x);
							}
						}
					}
				}
					
				/*
				for (final Iterator<String> i = head.keys(); i.hasNext();)
				{
					String code = i.next();
					if (!"timestamp".equals(code) && !"success".equals(code))
					{
						final JSONObject o = head.getJSONObject(code);

						for (final String field : fields)
						{
							log.info("Fetching field: " + field);
							final String rateStr = o.optString(field, null);

							if (rateStr != null)
							{
								log.info("Got: " + rateStr);
								try
								{
									final BigInteger rate = GenericUtils.toNanoCoins(rateStr, 0);

									if (rate.signum() > 0)
									{
										log.info("Putting: " + currencyCode + "rate: " + rate);
										rates.put(currencyCode, new ExchangeRate(currencyCode, rate, url.getHost()));
										break;
									}
								}
								catch (final ArithmeticException x)
								{
									log.warn("problem fetching exchange rate: " + currencyCode, x);
								}
							}
						}
					}
				}
				*/
				log.info("fetched exchange rates from " + url);

				return rates;
			}
			else
			{
				log.warn("http status " + responseCode + " when fetching " + url);
			}
		}
		catch (final Exception x)
		{
			log.warn("problem fetching exchange rates", x);
		}
		finally
		{
			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (final IOException x)
				{
					// swallow
				}
			}

			if (connection != null)
				connection.disconnect();
		}

		return null;
	}

    abstract class RateProvider {
        abstract Map<String, ExchangeRate> getRates(ExchangeRate usdRate);
    }

    class GoogleRatesProvider extends RateProvider {
        public Map<String, ExchangeRate> getRates(ExchangeRate usdRate) {
            URL url = null;
            final BigDecimal decUsdRate = GenericUtils.fromNanoCoins(usdRate.rate, 0);
            try {
                url = new URL("http://spreadsheets.google.com/feeds/list/0Av2v4lMxiJ1AdE9laEZJdzhmMzdmcW90VWNfUTYtM2c/2/public/basic?alt=json");
				log.warn("Connecting to google for rates" + url);
            } catch(MalformedURLException e) {
                Log.i(ExchangeRatesProvider.TAG, "Failed to parse Google Spreadsheets URL");
                return null;
            }
            HttpURLConnection connection = null;
            Reader reader = null;

            try
            {
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
                connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
                connection.connect();

                final int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK)
                {
                    reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024), Constants.UTF_8);
                    final StringBuilder content = new StringBuilder();
                    Io.copy(reader, content);

    				log.info("Got response, parsing :"+content.toString());
                    final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
                    JSONObject head = new JSONObject(content.toString());
                    JSONArray resultArray;

                    try {
                        head = head.getJSONObject("feed");
                        resultArray = head.getJSONArray("entry");
                        // Format: eg. _cpzh4: 3.673
                        Pattern p = Pattern.compile("_cpzh4: ([\\d\\.]+)");
                        for(int i = 0; i < resultArray.length(); ++i) {
                            String currencyCd = resultArray.getJSONObject(i).getJSONObject("title").getString("$t");
                            String rateStr = resultArray.getJSONObject(i).getJSONObject("content").getString("$t");
                            Matcher m = p.matcher(rateStr);
                            if(m.matches())
                            {
                                // Just get the good part
                                rateStr = m.group(1);
                                Log.d(ExchangeRatesProvider.TAG, "Currency: " + currencyCd);
                                Log.d(ExchangeRatesProvider.TAG, "Rate: " + rateStr);
                                Log.d(ExchangeRatesProvider.TAG, "USD Rate: " + decUsdRate.toString());
                                BigDecimal rate = new BigDecimal(rateStr);
                                Log.d(ExchangeRatesProvider.TAG, "Converted Rate: " + rate.toString());
                                rate = decUsdRate.multiply(rate);
                                Log.d(ExchangeRatesProvider.TAG, "Final Rate: " + rate.toString());
                                if (rate.signum() > 0)
                                {
                                    rates.put(currencyCd, new ExchangeRate(currencyCd,
                                            GenericUtils.toNanoCoins(rate.toString(), 0), url.getHost()));
                                }
                            }
                        }
                    } catch(JSONException e) {
                        Log.i(ExchangeRatesProvider.TAG, "Bad JSON response from Google Spreadsheets!: " + content.toString());
                        return null;
                    }
                    return rates;
                } else {
                    Log.i(ExchangeRatesProvider.TAG, "Bad response code from Google Spreadsheets!: " + responseCode);
                }
            }
            catch (final Exception x)
            {
                Log.w(ExchangeRatesProvider.TAG, "Problem fetching exchange rates", x);
            }
            finally
            {
                if (reader != null)
                {
                    try
                    {
                        reader.close();
                    }
                    catch (final IOException x)
                    {
                        // swallow
                    }
                }

                if (connection != null)
                    connection.disconnect();
            }
            return null;
        }
    }

    class YahooRatesProvider extends RateProvider{
        public Map<String, ExchangeRate> getRates(ExchangeRate usdRate) {
            // Fetch all the currencies from Yahoo!
            URL url = null;
            final BigDecimal decUsdRate = GenericUtils.fromNanoCoins(usdRate.rate, 0);
            try {
                Log.i(ExchangeRatesProvider.TAG, "Connecting to Yahoo for rates");
                // TODO: make this look less crappy and make it easier to add currencies.
                url = new URL("http://query.yahooapis.com/v1/public/yql?q=select%20id%2C%20Rate%20from%20yahoo.finance.xchange" +
                        "%20where%20pair%20in%20(%22USDEUR%22%2C%20%22USDJPY%22%2C%20%22USDBGN%22%2C%20%22USDCZK%22%2C%20" +
                        "%22USDDKK%22%2C%20%22USDGBP%22%2C%20%22USDHUF%22%2C%20%22USDLTL%22%2C%20%22USDLVL%22%2C%20%22USDPLN" +
                        "%22%2C%20%22USDRON%22%2C%20%22USDSEK%22%2C%20%22USDCHF%22%2C%20%22USDNOK%22%2C%20%22USDHRK%22%2C%20" +
                        "%22USDRUB%22%2C%20%22USDTRY%22%2C%20%22USDAUD%22%2C%20%22USDBRL%22%2C%20%22USDCAD%22%2C%20%22USDCNY" +
                        "%22%2C%20%22USDHKD%22%2C%20%22USDIDR%22%2C%20%22USDILS%22%2C%20%22USDINR%22%2C%20%22USDKRW%22%2C%20" +
                        "%22USDMXN%22%2C%20%22USDMYR%22%2C%20%22USDNZD%22%2C%20%22USDPHP%22%2C%20%22USDSGD%22%2C%20%22USDTHB" +
                        "%22%2C%20%22USDZAR%22%2C%20%22USDISK%22)&format=json&env=store%3A%2F%2Fdatatables.org" +
                        "%2Falltableswithkeys&callback=");
				log.warn("Connecting to yahoo for rates" + url);
            } catch (MalformedURLException e) {
                Log.i(ExchangeRatesProvider.TAG, "Failed to parse Yahoo! Finance URL");
                return null;
            }

            HttpURLConnection connection = null;
            Reader reader = null;

            try
            {
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
                connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
                connection.connect();

                final int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK)
                {
    				//log.warn("Response Ok" );
                    reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024), Constants.UTF_8);
                    final StringBuilder content = new StringBuilder();
                    Io.copy(reader, content);

    				log.info("Got response, parsing :"+content.toString());
                    final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
                    JSONObject head = new JSONObject(content.toString());
                    JSONArray resultArray;
                    try {
                        head = head.getJSONObject("query");
                        head = head.getJSONObject("results");
                        resultArray = head.getJSONArray("rate");
                    } catch(JSONException e) {
                        Log.i(ExchangeRatesProvider.TAG, "Bad JSON response from Yahoo!: " + content.toString());
                        return null;
                    }
                    for(int i = 0; i < resultArray.length(); ++i) {
                        final JSONObject rateObj = resultArray.getJSONObject(i);
                        String currencyCd = rateObj.getString("id").substring(3);
                        Log.d(ExchangeRatesProvider.TAG, "Currency: " + currencyCd);
                        String rateStr = rateObj.getString("Rate");
                        Log.d(ExchangeRatesProvider.TAG, "Rate: " + rateStr);
                        Log.d(ExchangeRatesProvider.TAG, "USD Rate: " + decUsdRate.toString());
                        BigDecimal rate = new BigDecimal(rateStr);
                        Log.d(ExchangeRatesProvider.TAG, "Converted Rate: " + rate.toString());
                        rate = decUsdRate.multiply(rate);
                        Log.d(ExchangeRatesProvider.TAG, "Final Rate: " + rate.toString());
                        if (rate.signum() > 0)
                        {
                            rates.put(currencyCd, new ExchangeRate(currencyCd,
                                    GenericUtils.toNanoCoins(rate.toString(), 0), url.getHost()));
                        }
                    }
                    Log.i(ExchangeRatesProvider.TAG, "Fetched exchange rates from " + url);
                    return rates;
                } else {
                    Log.i(ExchangeRatesProvider.TAG, "Bad response code from Yahoo!: " + responseCode);
                }
            }
            catch (final Exception x)
            {
                Log.w(ExchangeRatesProvider.TAG, "Problem fetching exchange rates", x);
            }
            finally
            {
                if (reader != null)
                {
                    try
                    {
                        reader.close();
                    }
                    catch (final IOException x)
                    {
                        // swallow
                    }
                }

                if (connection != null)
                    connection.disconnect();
            }

            return null;
        }
    }
}
