package bisq.markets.api;

// {{{ import
import static com.google.appengine.api.urlfetch.FetchOptions.Builder.*;

//import bisq.markets.api.beans.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.Filter;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.CompositeFilter;
import com.google.appengine.api.datastore.Query.CompositeFilterOperator;
import com.google.appengine.api.datastore.Query.SortDirection;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Text;
import com.google.appengine.api.datastore.Transaction;

import com.google.appengine.api.memcache.Expiration;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheService.SetPolicy;
import com.google.appengine.api.memcache.MemcacheServiceFactory;

import com.google.appengine.api.utils.SystemProperty;
import com.google.appengine.api.urlfetch.HTTPHeader;
import com.google.appengine.api.urlfetch.HTTPMethod;
import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;
import com.google.appengine.api.urlfetch.URLFetchService;
import com.google.appengine.api.urlfetch.URLFetchServiceFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonParseException;
import org.graalvm.compiler.graph.Graph;

// }}}

@SuppressWarnings("serial")
public class CachingProxy extends HttpServlet
{
    // {{{ get appengine API instances
    private static final DatastoreService DS = DatastoreServiceFactory.getDatastoreService();
    private static final MemcacheService mc = MemcacheServiceFactory.getMemcacheService();
    private static final Logger LOG = Logger.getLogger(CachingProxy.class.getName());
    // }}}
    // {{{ static constants
    // google cache should be >61 seconds and < 1 week
    private static final int API_CACHE_SECONDS = 300;
    // bisq markets node can sometimes be slow to respond
    private static final double REQUEST_DEADLINE = 5.0;

    // hostnames used by this CDN app
    private static final String FRONTEND_HOSTNAME_PRODUCTION = "markets.bisq.network";
    private static final String FRONTEND_HOSTNAME_DEVELOPMENT = "bisq-markets.appspot.com";

    // hostnames of bisq markets node to source data from
    private static final String RISQNODE_HOSTNAME_PRODUCTION = "https://markets.wiz.biz";
    private static final String RISQNODE_HOSTNAME_DEVELOPMENT = "https://markets.wiz.biz";
    private static final String RISQNODE_HOSTNAME_DEVSERVER = "http://127.0.0.1:7477";

    // init gson
    public static final Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    // }}}

    private static final class MarketsApiResponseCache // {{{
    {
        private static final String KIND = "MarketsApiResponseCache";
        private static final String RISQNODE_URL = "marketsapi_url";
        private static final String RISQNODE_RESPONSE = "marketsapi_response";
    } // }}}

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res)
    throws IOException
    {
        // {{{ init
        // autodetect locale from Accept-Language http request header
        Locale langLocale = req.getLocale();

        // get request source IP
        String reqIP = req.getRemoteAddr();

        // set default cache headers
        setResponsePrivateCacheHeaders(res, 0);

        // set content type
        res.setContentType("application/json; charset=UTF-8");

        // get request URL
        String reqURI = req.getRequestURI().toString();

        // get query string, strip forceUpdate if present
        String queryString = req.getQueryString();
            if (queryString != null) queryString = queryString.replaceAll("&forceUpdate=1", "");

        // flag to force update of datastore/memcache
        boolean forceUpdate = false;
        if (req.getParameter("forceUpdate") != null)
            forceUpdate = true;

        // settings to use datastore
        boolean useCacheForMarketsApi = true;
        boolean isDevelopment = isDevelopmentMode(req);
        if (isDevelopment)
        {
            useCacheForMarketsApi = false;
            LOG.log(Level.WARNING, "Caching disabled in development mode");
        }

        // get URI after /api for apiPath
        String apiPath = reqURI.substring( "/api".length(), reqURI.length() );
        //LOG.log(Level.WARNING, "reqURI is "+reqURI);
        //LOG.log(Level.WARNING, "apiPath is "+apiPath);

        // set CORS headers
        setResponseCORS(res);

        // response nugget
        Object responseData = new HashMap<String, Object>();
        // }}}
        if (apiPath.equals("/ping")) // {{{
        {
            res.getWriter().println("pong");
            return;
        } // }}}
        else if ( // {{{
            apiPath.startsWith("/currencies") ||
            apiPath.startsWith("/depth") ||
            apiPath.startsWith("/hloc") ||
            apiPath.startsWith("/markets") ||
            apiPath.startsWith("/offers") ||
            apiPath.startsWith("/ticker") ||
            apiPath.startsWith("/trades") ||
            apiPath.startsWith("/volumes")
        )
        {
            responseData = getCachedRisqData(req, reqURI, queryString, API_CACHE_SECONDS, useCacheForMarketsApi, forceUpdate);
        } // }}}
        else // {{{ 404
        {
            res.sendError(404);
            return;
        } // }}}
        //{{{ send response

        // return 503 if unable to get data
        if (responseData == null)
        {
            res.sendError(503);
            return;
        }
        if (responseData instanceof GraphQLQuery.ErrorResponse) {
            res.sendError(400);
            return;
        }

        // set cache header if not a forced update
        if (!forceUpdate)
            setResponsePublicCacheHeaders(res, API_CACHE_SECONDS);

        // create result objects
        String responseJsonString = gson.toJson(responseData);

        // send on wire
        res.getWriter().println(responseJsonString);
        // }}}
    }

    private boolean isDevelopmentMode(HttpServletRequest req) // {{{
    {
        boolean dev = false;

        if (SystemProperty.environment.value() == SystemProperty.Environment.Value.Development)
            dev = true;
        if (req.getServerName().equals(FRONTEND_HOSTNAME_DEVELOPMENT))
            dev = true;

        return dev;
    } // }}}

    private void proxyRequestToMarketsApi(HttpServletRequest req, HttpServletResponse res, HTTPMethod reqMethod, String bisqMarketsURI, Class incomingRequestBean, Class outgoingRequestResponseBean) // {{{
    throws IOException
    {
        // sanitize json bodies by parsing to JSON bean objects
        Object incomingRequestData = null;
        Object outgoingRequestResponseData = null;
        String sanitizedReqBody = null;
        String queryString = req.getQueryString();
        String reqBody = null;

        if (incomingRequestBean != null)
        {
            try // parse json body of incoming request
            {
                reqBody = getBodyFromRequest(req);
                LOG.log(Level.WARNING, "Got body: "+reqBody);
                incomingRequestData = gson.fromJson(reqBody, incomingRequestBean);
            }
            catch (Exception e)
            {
                LOG.log(Level.WARNING, "Unable to parse body of incoming request");
                e.printStackTrace();
                res.sendError(400);
                // TODO: send json body as error response
                return;
            }

            // convert sanitized request body back to json and send in outgoing request to backend
            sanitizedReqBody = gson.toJson(incomingRequestData);
        }

        if (reqMethod == HTTPMethod.POST && incomingRequestBean != null && incomingRequestData == null)
        {
            if (req.getHeader("Content-Encoding") != null)
                LOG.log(Level.WARNING, "Content-Encoding is "+req.getHeader("Content-Encoding"));
            if (req.getHeader("Content-Type") != null)
                LOG.log(Level.WARNING, "Content-Type is "+req.getHeader("Content-Type"));
            if (req.getHeader("Content-Length") != null)
                LOG.log(Level.WARNING, "Content-Length is "+req.getHeader("Content-Length"));
            LOG.log(Level.WARNING, "incomingRequestData is null!");
            res.sendError(400);
            return;
        }

        String reqURI = bisqMarketsURI;

        HTTPResponse bisqMarketsResponse = null;
        if (incomingRequestBean == Map.class)
        {
            // pass original raw body to outgoing request
            bisqMarketsResponse = requestData(reqMethod, buildRisqGraphURL(req, reqURI), getSafeHeadersFromRequest(req), reqBody);
        }
        else
        {
            // pass sanitized body to outgoing request
            bisqMarketsResponse = requestData(reqMethod, buildRisqGraphURL(req, reqURI), getSafeHeadersFromRequest(req), sanitizedReqBody);
        }

        if (bisqMarketsResponse == null) // request failed
        {
            res.sendError(503);
            return;
        }

        // get response code of outgoing request, set on incoming request response
        Integer resCode = bisqMarketsResponse.getResponseCode();
        res.setStatus(resCode);

        // pass outgoing request's response's headers
        setResponseHeadersFromBackendResponse(res, bisqMarketsResponse);

        String bisqMarketsResponseBody = null;
        try // parse json body of outgoing request's response
        {
            if (resCode >= 400)
            {
                bisqMarketsResponseBody = getBodyFromResponse(bisqMarketsResponse);
                //outgoingRequestResponseData = new MarketsApiErrorResponse(resCode, null, bisqMarketsResponseBody);
            }
            else if (outgoingRequestResponseBean != null)
            {
                bisqMarketsResponseBody = getBodyFromResponse(bisqMarketsResponse);
                outgoingRequestResponseData = gson.fromJson(bisqMarketsResponseBody, outgoingRequestResponseBean);
            }
        }
        catch (Exception e)
        {
            LOG.log(Level.WARNING, "Unable to parse outgoing request's response json body: "+e.toString());
            LOG.log(Level.WARNING, "Response body: "+bisqMarketsResponseBody);
            e.printStackTrace();
            res.sendError(500);
            // TODO: send json body as error response
            return;
        }

        // send 503 or default empty response object if no body
        if (outgoingRequestResponseData == null)
        {
            //outgoingRequestResponseData = new MarketsApiErrorResponse(resCode);
            res.sendError(503);
            return;
        }

        // pass sanitized body to incoming request's response
        sendResponse(res, outgoingRequestResponseData);
    } //}}}

    private void sendResponse(HttpServletResponse res, Object nugget) // {{{
    throws IOException
    {
        String jsonString = gson.toJson(nugget);
        res.getWriter().println(jsonString);
        // FIXME unescape JSON encoded HTML entities? ie. & is getting sent as \u0026
    } // }}}
    private void sendResponse(HttpServletResponse res, String str) // {{{
    throws IOException
    {
        res.getWriter().println(str);
    } // }}}

    private Map<String, String> getQueryMap(String query) // {{{
    {
        String[] params = query.split("&");
        Map<String, String> map = new HashMap<String, String>();
        for (String param : params)
        {
            String name, value;
            try { name = param.split("=")[0]; }
            catch (Exception e) { name = ""; }
            try { value = param.split("=")[1]; }
            catch (Exception e) { value = ""; }
            map.put(name, value);
        }
        return map;
    } // }}}
    private String getBodyFromRequest(HttpServletRequest req) // {{{
    {
        String contentType = req.getHeader("Content-Type");

        if (contentType.equals("application/x-www-form-urlencoded"))
        {
            LOG.log(Level.WARNING, "Parsing as application/x-www-form-urlencoded");
            try // convert to json string
            {
                Map<String, String> map = new HashMap<String,String>();
                Map<String, String[]> parameters = req.getParameterMap();
                for (String key : parameters.keySet())
                {
                    String[] values = req.getParameterValues(key);
                    if (values != null)
                    map.put(key, values[0]);
                }
                String jsonString = gson.toJson(map);
                LOG.log(Level.INFO, "Got json string: "+jsonString);
                return jsonString;
            }
            catch (Exception e)
            {
                e.printStackTrace();
                return null;
            }
        }

        // read request body
        StringBuffer sb = new StringBuffer();
        InputStream inputStream = null;
        BufferedReader reader = null;

        String line = null;
        try
        {
            inputStream = req.getInputStream();
            if (inputStream != null)
            {
                reader = new BufferedReader(new InputStreamReader(inputStream));
                char[] charBuffer = new char[65535];
                int bytesRead = -1;
                while ((bytesRead = reader.read(charBuffer)) > 0)
                {
                    sb.append(charBuffer, 0, bytesRead);
                }
            }
            else
            {
                sb.append("");
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            if (reader != null)
            {
                try
                {
                    reader.close();
                }
                catch (IOException e)
                {
                    LOG.log(Level.WARNING, "Unable to read request body");
                    e.printStackTrace();
                    return null;
                }
            }
        }

        // convert payload bytes to string
        String reqBody = sb.toString();
        //if (reqBody != null && !reqBody.isEmpty())
        //LOG.log(Level.WARNING, "got payload: "+reqBody);

        return reqBody;
    } // }}}
    private String getBodyFromResponse(HTTPResponse response) // {{{
    {
        String str = null;

        try // if we got response, convert to UTF-8 string
        {
            str = new String(response.getContent(), "UTF-8");
        }
        catch (UnsupportedEncodingException e)
        {
            LOG.log(Level.WARNING, e.toString(), e);
            str = null;
        }
        return str;
    } // }}}

    private List<HTTPHeader> getSafeHeadersFromRequest(HttpServletRequest req) // {{{
    {
        List<HTTPHeader> headers = new ArrayList<HTTPHeader>();
        String safeHeaders[] = {
            "Cookie"
            ,"User-Agent"
        };

        // copy headers in "safe" list above
        for (String headerName : safeHeaders)
            if (req.getHeader(headerName) != null)
                headers.add(new HTTPHeader(headerName, req.getHeader(headerName)));

        // add original IP header
        /*
        String reqIP = req.getRemoteAddr();
        HTTPHeader headerOriginalIP = new HTTPHeader("X-Original-IP", reqIP);
        headers.add(headerOriginalIP);
        */

        return headers;
    } // }}}
    private void setResponseHeadersFromBackendResponse(HttpServletResponse res1, HTTPResponse res2) // {{{
    {
        List<HTTPHeader> headers = res2.getHeaders();
        String safeHeaders[] = {
            "Set-Cookie"
        };

        for (HTTPHeader header : headers)
            if (Arrays.asList(safeHeaders).contains(header.getName()))
                res1.setHeader(header.getName(), header.getValue());
    } // }}}

    private void setResponseCORS(HttpServletResponse res) // {{{
    {
        res.setHeader("Access-Control-Allow-Headers", "Access-Control-Allow-Headers, Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");
        res.setHeader("Access-Control-Allow-Method", "GET,HEAD,OPTIONS,POST,PUT");
        res.setHeader("Access-Control-Allow-Origin", "*");
    } // }}}
    private void setResponsePublicCacheHeaders(HttpServletResponse res, int seconds) // {{{
    {
        res.setDateHeader("Expires", System.currentTimeMillis() + (1000 * seconds) );
        res.setHeader("Cache-Control", "public, max-age="+seconds);
    } // }}}
    private void setResponsePrivateCacheHeaders(HttpServletResponse res, int seconds) // {{{
    {
        res.setDateHeader("Expires", System.currentTimeMillis() + (1000 * seconds) );
        res.setHeader("Cache-Control", "private, max-age="+seconds);
    } // }}}

    private URL buildRisqGraphURL(HttpServletRequest req, String bisqMarketsURI) // {{{
    {
        URL risqGraphURL = null;

        try // build risqGraphURL
        {
            // determine API endpoint
            String risqNodeBaseURL = null;

            if (SystemProperty.environment.value() == SystemProperty.Environment.Value.Development)
            {
                // for local devserver - don't use TLS
                risqNodeBaseURL = RISQNODE_HOSTNAME_DEVSERVER;
            }
            else if (req.getServerName().equals(FRONTEND_HOSTNAME_DEVELOPMENT)) // cloud development
            {
                risqNodeBaseURL = RISQNODE_HOSTNAME_DEVELOPMENT;
            }
            else // cloud production
            {
                risqNodeBaseURL = RISQNODE_HOSTNAME_PRODUCTION;
            }

            risqGraphURL = new URL(risqNodeBaseURL + "/graphql");
        }
        catch (MalformedURLException e)
        {
            risqGraphURL = null;
        }
        return risqGraphURL;
    } // }}}

    private Object getCachedRisqData(HttpServletRequest req, String bisqMarketsURI, String bisqMarketsQueryString, int secondsToMemcache, boolean useCache, boolean forceUpdate) // {{{
    {
        URL risqGraphURL = buildRisqGraphURL(req, bisqMarketsURI);
        return getCachedData(risqGraphURL.toString(), bisqMarketsURI, bisqMarketsQueryString, secondsToMemcache, useCache, forceUpdate);
    } // }}}
    private Object getCachedData(String backendURL, String bisqMarketsURI, String queryString, int secondsToMemcache, boolean useCache, boolean forceUpdate) // {{{
    {
        String response = null;
        Object responseData = null;
        boolean inMemcache = false;
        boolean inDatastore = false;

        // strip forceUpdate=1 if present
        String dataKey = bisqMarketsURI + "?" + queryString;

        // {{{ first check memcache, use backendURL as key
        if (useCache)
            response = (String)mc.get(dataKey);

        if (!forceUpdate && response != null)
        {
            responseData = parseJsonData(response);
            if (responseData == null)
                LOG.log(Level.WARNING, "Failed parsing memcache bisqMarkets response for "+backendURL);
            else
                inMemcache = true;
        }
        // }}}
        // {{{ if not in memcache, try request it from backend node
        if (responseData == null)
        {
            LOG.log(Level.WARNING, "Fetching data for "+bisqMarketsURI+" from "+backendURL);
            GraphQLQuery query = null;

            try
            {
                Map<String,String> queryMap = new HashMap();
                if (queryString != null)
                    queryMap = getQueryMap(queryString);
                query = GraphQLQuery.forRequest(bisqMarketsURI, queryMap);
            }
            catch (Exception e)
            {
                LOG.log(Level.WARNING, "GraphQLQuery failed for "+dataKey);
            }
            if (query != null)
            {
                try
                {
                    response = requestDataAsString(HTTPMethod.POST, new URL(backendURL), null, gson.toJson(query));
                }
                catch (Exception e)
                {
                    response = null;
                }
            }
            if (response != null)
            {
                try
                {
                    responseData = query.translateResponse(response);
                }
                catch (Exception e)
                {
                    responseData = null;
                    LOG.log(Level.WARNING, "Unable to translate response for "+dataKey+": "+e.toString());
                }
                if (responseData == null)
                    LOG.log(Level.WARNING, "Failed parsing requested bisqMarkets response for "+backendURL);
            }
        }
        // }}}
        // {{{ if successful, save response in memcache/datastore for next time
        if (response != null && responseData != null && (responseData instanceof GraphQLQuery.ErrorResponse == false))
        {
            if (useCache && !inMemcache)
            {
                LOG.log(Level.WARNING, "Adding memcache key for "+dataKey);
                try
                {
                    String responseJsonString = gson.toJson(responseData);
                    if (forceUpdate)
                        mc.put(dataKey, responseJsonString, Expiration.byDeltaSeconds(secondsToMemcache), SetPolicy.SET_ALWAYS);
                    else
                        mc.put(dataKey, responseJsonString, Expiration.byDeltaSeconds(secondsToMemcache), SetPolicy.ADD_ONLY_IF_NOT_PRESENT);
                }
                catch (Exception e)
                {
                    LOG.log(Level.WARNING, "Unable to add memcache for "+dataKey+": "+e.toString());
                }
            }
            if (useCache && !inDatastore)
            {
                LOG.log(Level.WARNING, "Adding datastore key for "+dataKey);
                Key cacheKey = KeyFactory.createKey(MarketsApiResponseCache.KIND, dataKey);
                Transaction tx = DS.beginTransaction();
                Entity cache = new Entity(cacheKey);
                try
                {
                    cache.setProperty(MarketsApiResponseCache.RISQNODE_URL, dataKey);
                    Text responseText = new Text(response);
                    cache.setProperty(MarketsApiResponseCache.RISQNODE_RESPONSE, responseText);
                    DS.put(tx, cache);
                    tx.commit();
                }
                catch (Exception e)
                {
                    LOG.log(Level.WARNING, e.toString(), e);
                }
                finally
                {
                    if (tx.isActive())
                    {
                        tx.rollback();
                    }
                }
            }
        }
        // }}}
        // {{{ if backend node not available, try querying the datastore
        if (useCache && !forceUpdate && responseData == null)
        {
            Key entityKey = KeyFactory.createKey(MarketsApiResponseCache.KIND, dataKey);
            Filter bisqMarketsURLFilter = new FilterPredicate(Entity.KEY_RESERVED_PROPERTY, FilterOperator.EQUAL, entityKey);
            response = null;
            Entity result = null;
            try
            {
                Query query = new Query(MarketsApiResponseCache.KIND)
                    .setFilter(bisqMarketsURLFilter);
                PreparedQuery pq = DS.prepare(query);
                result = pq.asSingleEntity();
            }
            catch (Exception e)
            {
                LOG.log(Level.WARNING, e.toString(), e);
                return null;
            }
            if (result != null)
            {
                Text responseText = (Text)result.getProperty(MarketsApiResponseCache.RISQNODE_RESPONSE);
                response = (String)responseText.getValue();
                responseData = parseJsonData(response);
                if (responseData == null)
                    LOG.log(Level.WARNING, "Failed parsing datastore bisqMarkets response for "+backendURL);
                else
                    inDatastore = true;
            }
        }
        //}}}

        return responseData;
    } // }}}

    private Object parseJsonData(String jsonRaw) // {{{
    {
        Object jsonData = null;
        try // {{{ parse as JSON object
        {
            jsonData = new Gson().fromJson(jsonRaw, Map.class);
        }
        catch (JsonSyntaxException e1)
        {
            try // parse as JSON array
            {
                jsonData = new Gson().fromJson(jsonRaw, ArrayList.class);
            }
            catch (JsonSyntaxException e2)
            {
                LOG.log(Level.WARNING, e2.toString(), e2);
                jsonData = null;
            }
        } // }}}
        return jsonData;
    } // }}}

    private String requestMarketsApiData(HttpServletRequest req, HTTPMethod requestMethod, String bisqMarketsURI, List<HTTPHeader> headers, String body) // {{{
    {
        URL bisqMarketsURL = buildRisqGraphURL(req, bisqMarketsURI);
        return requestDataAsString(requestMethod, bisqMarketsURL, headers, body);
    } // }}}

    private String requestDataAsString(HTTPMethod requestMethod, URL bisqMarketsURL, List<HTTPHeader> headers, String body) // {{{
    {
        String bisqMarketsResponse = null;
        HTTPResponse response = requestData(requestMethod, bisqMarketsURL, headers, body);
        if (response != null && (response.getResponseCode() == HttpURLConnection.HTTP_OK || response.getResponseCode() == HttpURLConnection.HTTP_CREATED))
            bisqMarketsResponse = getBodyFromResponse(response);
        return bisqMarketsResponse;
    } // }}}

    private HTTPResponse requestData(HTTPMethod requestMethod, URL bisqMarketsURL, List<HTTPHeader> headers, String body) // {{{
    {
        HTTPRequest request = new HTTPRequest(bisqMarketsURL, requestMethod, withDefaults().setDeadline(REQUEST_DEADLINE));
        HTTPResponse response = null;

        try
        {
            response = requestDataFetch(request, headers, body);
        }
        catch (IOException e)
        {
            // TODO log error requesting
            LOG.log(Level.WARNING, e.toString(), e);
            try
            {
                response = requestDataFetch(request, headers, body);
            }
            catch (Exception ee)
            {
                // TODO log error requesting
                response = null;
                LOG.log(Level.WARNING, ee.toString(), ee);
            }
        }
        catch (Exception e)
        {
            // TODO log error requesting
            response = null;
            LOG.log(Level.WARNING, e.toString(), e);
        }
        return response;
    } // }}}
    private HTTPResponse requestDataFetch(HTTPRequest request, List<HTTPHeader> headers, String body) // {{{
    throws IOException, UnsupportedEncodingException
    {
        URLFetchService urlFetchService = URLFetchServiceFactory.getURLFetchService();

        if (headers != null)
            for (HTTPHeader header : headers)
                request.setHeader(header);

        if (body != null)
        {
            request.setHeader(new HTTPHeader("Content-type", "application/json"));
            request.setPayload(body.getBytes("UTF-8"));
        }

        return urlFetchService.fetch(request);
    } // }}}
}

// vim: ts=4:expandtab:foldmethod=marker wrap
