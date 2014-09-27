package com.westudio.java.etcd;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Asynchronous Etcd Client
 */
public class AsyncEtcdClient {
    
    private static final CloseableHttpAsyncClient httpClient = buildHttpClient();
    private static final Gson gson = new GsonBuilder().create();

    private static final String URI_PREFIX = "v2/keys";
    private static final String DEFAULT_CHARSET = "UTF-8";

    private static final int DEFAULT_CONNECT_TIMEOUT = 10 * 1000;

    private final URI baseUri;

    private static CloseableHttpAsyncClient buildHttpClient() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .setSocketTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build();
        CloseableHttpAsyncClient httpAsyncClient;
        httpAsyncClient = HttpAsyncClients.custom()
                .setDefaultRequestConfig(config)
                .build();
        httpAsyncClient.start();

        return httpAsyncClient;
    }

    public AsyncEtcdClient(URI uri) {
        String url = uri.toString();
        if (!url.endsWith("/")) {
            url += "/";
            uri = URI.create(url);
        }
        this.baseUri = uri;
    }

    /**
     * Get the value of a key.
     * @param key the key
     * @return the corresponding value
     */
    public EtcdResponse get(String key) throws EtcdClientException {
        URI uri = buildUriWithKeyAndParams(key, null);
        HttpGet httpGet = new HttpGet(uri);

        EtcdResponse result = syncExecute(httpGet, new int[] {200, 404}, 100);
        if (result.isError()) {
            if (result.errorCode == 100) {
                return null;
            }
        }

        return result;
    }

    /**
     * Setting the value of a key
     * @param key the key
     * @param value the value
     * @return operation result
     */
    public EtcdResponse set(String key, String value) throws EtcdClientException {
        return set(key, value, null);
    }

    /**
     * Setting the value of a key with optional key TTL
     * @param key the key
     * @param value the value
     * @param ttl optional key TTL
     * @return operation result
     */
    public EtcdResponse set(String key, String value, Integer ttl) throws EtcdClientException {
        List<BasicNameValuePair> list = Lists.newArrayList();
        list.add(new BasicNameValuePair("value", value));
        if (ttl != null) {
            list.add(new BasicNameValuePair("ttl", String.valueOf(ttl)));
        }

        return put(key, list, null, new int[]{200, 201});
    }

    /**
     * Delete a key
     * @param key the key
     * @return operation result
     */
    public EtcdResponse delete(String key) throws EtcdClientException {
        URI uri = buildUriWithKeyAndParams(key, null);
        HttpDelete delete = new HttpDelete(uri);

        return syncExecute(delete, new int[]{200, 404});
    }

    /**
     * Creating directories
     * @param key the dir key
     * @return operation result
     * @throws EtcdClientException
     */
    public EtcdResponse createDir(String key) throws EtcdClientException {
        return createDir(key, null);
    }

    /**
     * Create directories with optional ttl
     * @param key the key
     * @param ttl the ttl
     * @return operation result
     * @throws EtcdClientException
     */
    public EtcdResponse createDir(String key, Integer ttl) throws EtcdClientException {
        List<BasicNameValuePair> data = Lists.newArrayList();
        data.add(new BasicNameValuePair("dir", String.valueOf(true)));
        if (ttl != null) {
            data.add(new BasicNameValuePair("ttl", String.valueOf(ttl)));
        }

        return put(key, data, null, new int[]{200, 201});
    }

    /**
     * Create directories with optional ttl and prevExist
     * @param key the key
     * @param ttl the ttl
     * @param prevExist exists before
     * @return the result
     * @throws EtcdClientException
     */
    public EtcdResponse createDir(String key, Integer ttl, Boolean prevExist) throws EtcdClientException {
        List<BasicNameValuePair> data = Lists.newArrayList();
        data.add(new BasicNameValuePair("dir", String.valueOf(true)));
        if (ttl != null) {
            data.add(new BasicNameValuePair("ttl", String.valueOf(ttl)));
        }
        if (prevExist != null) {
            data.add(new BasicNameValuePair("prevExist", String.valueOf(prevExist)));
        }

        return put(key, data, null, new int[]{200, 201});
    }

    /**
     * Listing a directory
     * @param key the dir key
     * @return a EtcdNode list
     * @throws EtcdClientException
     */
    public List<EtcdNode> listDir(String key) throws EtcdClientException {
        return listDir(key, false);
    }

    private List<EtcdNode> listDir(String key, Boolean recursive) throws EtcdClientException {
        EtcdResponse result = get(key + "/");
        if (result == null || result.node == null) {
            return null;
        }

        return result.node.nodes;
    }

    /**
     * Deleting a directory
     * @param key the dir key
     * @param recursive set recursive=true if the directory holds keys
     * @return operation result
     * @throws EtcdClientException
     */
    public EtcdResponse deleteDir(String key, Boolean recursive) throws EtcdClientException {
        Map<String, String> params = new HashMap<String, String>();
        if (recursive) {
            params.put("recursive", String.valueOf(true));
        } else {
            params.put("dir", String.valueOf(true));
        }

        URI uri = buildUriWithKeyAndParams(key, params);

        HttpDelete httpDelete = new HttpDelete(uri);
        return syncExecute(httpDelete, new int[] {202});
    }

    /**
     * Atomic Compare-and-Swap
     * @param key the key
     * @param value the new value
     * @param params comparable conditions
     * @return operation result
     * @throws EtcdClientException
     */
    public EtcdResponse cas(String key, String value, Map<String, String> params) throws EtcdClientException {
        List<BasicNameValuePair> data = Lists.newArrayList();
        data.add(new BasicNameValuePair("value", value));

        return put(key, data, params, new int[] {200, 412}, 101, 105);
    }

    /**
     * Atomic Compare-and-Delete
     * @param key the key
     * @param params comparable conditions
     * @return operation result
     * @throws EtcdClientException
     */
    public EtcdResponse cad(String key, Map<String, String> params) throws EtcdClientException {
        URI uri = buildUriWithKeyAndParams(key, params);
        HttpDelete httpDelete = new HttpDelete(uri);

        return syncExecute(httpDelete, new int[] {200, 412}, 101);
    }

    /**
     * Watch for a change on a key
     * @param key the key
     * @return a future result
     */
    public ListenableFuture<EtcdResponse> watch(String key) {
        return watch(key, null, false);
    }

    /**
     * Watch for a change on a key
     * @param key the key
     * @param index the wait index
     * @param recursive set recursive true if you want to watch for child keys
     * @return a future result
     */
    public ListenableFuture<EtcdResponse> watch(String key, Long index, boolean recursive) {
        Map<String, String> params = new HashMap<String, String>();
        params.put("wait", String.valueOf(true));
        if (index != null) {
            params.put("waitIndex", String.valueOf(index));
        }
        if (recursive) {
            params.put("recursive", String.valueOf(recursive));
        }

        URI uri = buildUriWithKeyAndParams(key, params);
        HttpGet httpGet = new HttpGet(uri);

        return asyncExecute(httpGet, new int[] {200});
    }

    /**
     * Getting the etcd version
     * @return the etcd version
     * @throws EtcdClientException
     */
    public String getVersion() throws EtcdClientException {
        URI uri = baseUri.resolve("version");

        HttpGet httpGet = new HttpGet(uri);

        JsonResponse jsonResponse = syncExecuteJson(httpGet, 200);
        if (jsonResponse.httpStatusCode != 200) {
            throw new EtcdClientException("Error while get etcd version", jsonResponse.httpStatusCode);
        }

        return jsonResponse.json;
    }

    /**
     * List children under a key
     * @param key the key
     * @return operation result
     * @throws EtcdClientException
     */
    public EtcdResponse listChildren(String key) throws EtcdClientException {
        URI uri = buildUriWithKeyAndParams(key + "/", null);

        HttpGet httpGet = new HttpGet(uri);

        return syncExecute(httpGet, new int[] {200});
    }

    /**
     * List children under a key
     * @param key the key
     * @param recursive should be recursive
     * @return the result
     * @throws EtcdClientException
     */
    public EtcdResponse listChildren(String key, Boolean recursive) throws EtcdClientException {
        Map<String, String> params = new HashMap<String, String>();
        if (recursive != null) {
            params.put("recursive", String.valueOf(recursive));
        }
        URI uri = buildUriWithKeyAndParams(key, params);
        HttpGet httpGet = new HttpGet(uri);

        return syncExecute(httpGet, new int[]{200});
    }

    // Build url with key and url params
    private URI buildUriWithKeyAndParams(String key, Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append(URI_PREFIX);
        if (key.startsWith("/")) {
            key = key.substring(1);
        }

        for (String token : Splitter.on("/").split(key)) {
            sb.append("/").append(urlEscape(token));
        }
        if (params != null) {
            sb.append("?");
            for (String str : params.keySet()) {
                sb.append(urlEscape(str)).append("=").append(urlEscape(params.get(str)));
                sb.append("&");
            }
        }

        String url = sb.toString();
        if (url.endsWith("&")) {
            url = url.substring(0, url.length() - 1);
        }
        return baseUri.resolve(url);
    }

    private static String urlEscape(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException();
        }
    }

    public static String format(Object obj) {
        try {
            return gson.toJson(obj);
        } catch (Exception e) {
            return "Error formatting response" + e.getMessage();
        }
    }

    // The basic put operation
    private EtcdResponse put(String key, List<BasicNameValuePair> data, Map<String, String> params, int[] httpErrorCodes,
                            int... expectedErrorCodes) throws EtcdClientException {
        URI uri = buildUriWithKeyAndParams(key, params);
        HttpPut httpPut = new HttpPut(uri);

        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(data, Charsets.UTF_8);
        httpPut.setEntity(entity);

        return syncExecute(httpPut, httpErrorCodes, expectedErrorCodes);
    }

    private EtcdResponse syncExecute(HttpUriRequest request, int[] expectedHttpStatusCodes, final int... exceptedErrorCodes) throws EtcdClientException {
        try {
            return asyncExecute(request, expectedHttpStatusCodes, exceptedErrorCodes).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new EtcdClientException("InterruptedException", e);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private JsonResponse syncExecuteJson(HttpUriRequest request, int... exceptedHttpStatusCodes) throws EtcdClientException {
        try {
            return asyncExecuteJson(request, exceptedHttpStatusCodes).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EtcdClientException("Interrupt during response", e);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }
    private ListenableFuture<EtcdResponse> asyncExecute(HttpUriRequest request, int[] expectedHttpStatusCodes, final int... excptedErrorCodes) {
        ListenableFuture<JsonResponse> response = asyncExecuteJson(request, expectedHttpStatusCodes);
        return Futures.transform(response, new AsyncFunction<JsonResponse, EtcdResponse>() {
            @Override
            public ListenableFuture<EtcdResponse> apply(JsonResponse jsonResponse) throws Exception {
                EtcdResponse result = jsonToEtcdResponse(jsonResponse, excptedErrorCodes);
                return Futures.immediateFuture(result);
            }
        });
    }

    private ListenableFuture<JsonResponse> asyncExecuteJson(final HttpUriRequest request, final int[] expectedHttpStatusCodes) {
        ListenableFuture<HttpResponse> response = asyncExecuteHttp(request);

        return Futures.transform(response, new AsyncFunction<HttpResponse, JsonResponse>() {
            @Override
            public ListenableFuture<JsonResponse> apply(final HttpResponse httpResponse) throws Exception {

                // Workaround for 307 response
                if (httpResponse.getStatusLine().getStatusCode() == 307) {
                    Header location = httpResponse.getFirstHeader("location");
                    HttpUriRequest redirectRequest;
                    if (request instanceof HttpPut) {
                        redirectRequest = new HttpPut(location.getValue());
                        ((HttpPut) redirectRequest).setEntity(((HttpPut) request).getEntity());
                    } else if (request instanceof HttpDelete) {
                        redirectRequest = new HttpDelete(location.getValue());
                    } else {
                        redirectRequest = new HttpGet(location.getValue());
                    }

                    return asyncExecuteJson(redirectRequest, expectedHttpStatusCodes);
                }

                JsonResponse jsonResponse = extractJsonResponse(httpResponse, expectedHttpStatusCodes);
                return Futures.immediateFuture(jsonResponse);
            }
        });
    }

    private ListenableFuture<HttpResponse> asyncExecuteHttp(HttpUriRequest request) {
        final SettableFuture<HttpResponse> future = SettableFuture.create();

        httpClient.execute(request, new FutureCallback<HttpResponse>() {
            @Override
            public void completed(HttpResponse httpResponse) {
                future.set(httpResponse);
            }

            @Override
            public void failed(Exception e) {
                future.setException(e);
            }

            @Override
            public void cancelled() {
                future.setException(new InterruptedException());
            }
        });

        return future;
    }

    private EtcdClientException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof EtcdClientException) {
            return (EtcdClientException)cause;
        } else {
            return new EtcdClientException("Failed to execute request", e);
        }
    }

    static class JsonResponse {
        final String json;
        final int httpStatusCode;

        public JsonResponse(String json, int httpStatusCode) {
            this.json = json;
            this.httpStatusCode = httpStatusCode;
        }
    }

    private JsonResponse extractJsonResponse(HttpResponse response, int[] expectedHttpStatusCode) throws EtcdClientException {
        try {
            StatusLine statusLine = response.getStatusLine();
            int statusCode = statusLine.getStatusCode();

            String json = null;

            if (response.getEntity() != null) {
                try {
                    json = EntityUtils.toString(response.getEntity(), DEFAULT_CHARSET);
                } catch (IOException e) {
                    throw new EtcdClientException("Error reading response", e);
                }
            }

            if (!contains(expectedHttpStatusCode, statusCode)) {
                if (statusCode == 404 && json != null) {
                    // More info in json
                } else {
                    throw new EtcdClientException("Error response from etcd: " + statusLine.getReasonPhrase(),
                            statusCode);
                }
            }

            return new JsonResponse(json, statusCode);
        } finally {
            close(response);
        }
    }

    private EtcdResponse jsonToEtcdResponse(JsonResponse jsonResponse, int... exceptedErrorCodes) throws EtcdClientException {
        if (jsonResponse == null || jsonResponse.json == null) {
            return null;
        }

        EtcdResponse result = parseEtcdResponse(jsonResponse.json);

        if (result.isError()) {
            if (!contains(exceptedErrorCodes, result.errorCode)) {
                throw new EtcdClientException(result.message, result);
            }
        }

        return result;
    }

    private EtcdResponse parseEtcdResponse(String json) throws EtcdClientException{
        EtcdResponse result;
        try {
            result = gson.fromJson(json, EtcdResponse.class);
        } catch (JsonParseException e) {
            throw new EtcdClientException("Error parsing response", e);
        }

        return result;
    }

    private static void close(HttpResponse response) {
        if (response == null) {
            return;
        }

        HttpEntity entity = response.getEntity();
        if (entity != null) {
            EntityUtils.consumeQuietly(entity);
        }
    }

    private static boolean contains(int[] exceptedCodes, int code) {
        for (int exceptedCode : exceptedCodes) {
            if (exceptedCode == code) {
                return true;
            }
        }

        return false;
    }
}
