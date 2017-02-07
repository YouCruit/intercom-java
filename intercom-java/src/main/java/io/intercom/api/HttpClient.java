package io.intercom.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

class HttpClient {

    private static final Logger logger = LoggerFactory.getLogger("intercom-java");

    private static final String CLIENT_AGENT_DETAILS = clientAgentDetails();

    private static final String USER_AGENT = Intercom.USER_AGENT;

    private static final String UTF_8 = "UTF-8";

    private static final String APPLICATION_JSON = "application/json";
    public static final MediaType JSON_MEDIA_TYPE = MediaType.parse(APPLICATION_JSON);

    private static String clientAgentDetails() {
        final HashMap<String, String> map = Maps.newHashMap();
        final ArrayList<String> propKeys = Lists.newArrayList(
            "os.arch", "os.name", "os.version",
            "user.language", "user.timezone",
            "java.class.version", "java.runtime.version", "java.version",
            "java.vm.name", "java.vm.vendor", "java.vm.version");
        for (String propKey : propKeys) {
            map.put(propKey, System.getProperty(propKey));
        }
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            MapperSupport.objectMapper().disable(SerializationFeature.INDENT_OUTPUT).writeValue(baos, map);
        } catch (IOException e) {
            logger.warn(String.format("could not serialize client agent details [%s]", e.getMessage()), e);
        }
        return baos.toString();
    }

    private final ObjectMapper objectMapper;

    private final URI uri;

    private final Map<String, String> headers;

    private final OkHttpClient okHttpClient;

    public HttpClient(URI uri) {
        this(uri, Maps.<String, String>newHashMap());
    }

    private HttpClient(URI uri, Map<String, String> headers) {
        this.uri = uri;
        this.headers = headers;
        this.objectMapper = MapperSupport.objectMapper();
        this.okHttpClient = Intercom.getHttpClient();
    }

    public <T> T get(Class<T> reqres) throws IntercomException {
        return get(getJavaType(reqres));
    }

    <T> T get(JavaType responseType) throws IntercomException {
        return executeHttpMethod("GET", null, responseType);
    }

    public <T> T delete(Class<T> reqres) {
        return executeHttpMethod("DELETE", null, getJavaType(reqres));
    }

    public <T, E> T put(Class<T> reqres, E entity) {
        return executeHttpMethod("PUT", (E) entity, getJavaType(reqres));
    }

    public <T, E> T post(Class<T> reqres, E entity) {
        return executeHttpMethod("POST", entity, getJavaType(reqres));
    }

    private <T, E> T executeHttpMethod(String method, E entity, JavaType responseType) {
	final Map<String, String> headers = createHeaders();
	headers.putAll(createAuthorizationHeaders());
	try {
	    if (logger.isDebugEnabled()) {
		logger.info(String.format("api server request --\n%s\n-- ", objectMapper.writeValueAsString(entity)));
	    }
	    final RequestBody requestBody;
	    if (entity != null) {
		byte[] value = objectMapper.writeValueAsBytes(entity);
		requestBody = RequestBody.create(JSON_MEDIA_TYPE, value);
	    } else {
		requestBody = null;
	    }
	    Request request = new Request.Builder().url(HttpUrl.get(uri))
						   .headers(Headers.of(headers))
						   .method(method, requestBody)
						   .build();
	    return runRequest(responseType, request);
	} catch (IOException e) {
	    return throwLocalException(e);
	}
    }

    private <T> JavaType getJavaType(Class<T> reqres) {
        return objectMapper.getTypeFactory().constructType(reqres);
    }

    // trick java with a dummy return
    private <T> T throwLocalException(IOException e) {
        throw new IntercomException(String.format("Local exception calling [%s]. Check connectivity and settings. [%s]", uri.toASCIIString(), e.getMessage()), e);
    }

    private <T> T runRequest(JavaType javaType, Request request) throws IOException {
	Response response = null;
        try {
	    response = okHttpClient.newCall(request).execute();
	    if (response.isSuccessful()) {
		return handleSuccess(javaType, response);
	    } else {
		return handleError(response);
	    }
	} finally {
	    if (response != null) {
	        response.body().close();
	    }
	}
    }

    private <T> T handleError(Response response) throws IOException {
        ErrorCollection errors;
        try {
	    final String content = response.body().string();
	    errors = objectMapper.readValue(content, ErrorCollection.class);
        } catch (IOException e) {
            errors = createUnprocessableErrorResponse(e);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("error json follows --\n{}\n-- ", objectMapper.writeValueAsString(errors));
        }
        return throwException(response.code(), errors);
    }

    private <T> T handleSuccess(JavaType javaType, Response response) throws IOException {
	try (ResponseBody responseBody = response.body()) {
	    if (shouldSkipResponseEntity(javaType, response)) {
		return null;
	    } else {
		if (logger.isDebugEnabled()) {
		    final String text = response.body().string();
		    logger.debug("api server response status[{}] --\n{}\n-- ", response.code(), text);
		    return objectMapper.readValue(text, javaType);
		} else {
		    return objectMapper.readValue(responseBody.byteStream(), javaType);
		}
	    }
	}
    }

    private boolean shouldSkipResponseEntity(JavaType javaType, Response response) {
	return response.code() == 204 || Void.class.equals(javaType.getRawClass()) || "DELETE".equals(response.request().method());
    }

    private <T> T throwException(int responseCode, ErrorCollection errors) {
        // bind some well known response codes to exceptions
        if (responseCode == 403 || responseCode == 401) {
            throw new AuthorizationException(errors);
        } else if (responseCode == 429) {
            throw new RateLimitException(errors);
        } else if (responseCode == 404) {
            throw new NotFoundException(errors);
        } else if (responseCode == 422) {
            throw new InvalidException(errors);
        } else if (responseCode == 400 || responseCode == 405 || responseCode == 406) {
            throw new ClientException(errors);
        } else if (responseCode == 500 || responseCode == 503) {
            throw new ServerException(errors);
        } else {
            throw new IntercomException(errors);
        }
    }

    private Map<String, String> createAuthorizationHeaders() {
        switch (Intercom.getAuthKeyType()) {
            case API_KEY:
                headers.put("Authorization", "Basic " + generateAuthString(Intercom.getAppID(),Intercom.getApiKey()));
                break;
            case TOKEN:
                headers.put("Authorization", "Basic " + generateAuthString(Intercom.getToken(),""));
                break;
        }
        return headers;
    }

    private String generateAuthString(String username, String password) {
        return Base64.encodeBase64String((username + ":" + password).getBytes());
    }

    private Map<String, String> createHeaders() {
        headers.put("User-Agent", USER_AGENT);
        headers.put("X-Client-Platform-Details", CLIENT_AGENT_DETAILS);
        headers.put("Accept-Charset", UTF_8);
        headers.put("Accept", APPLICATION_JSON);
        return headers;
    }

    private ErrorCollection createUnprocessableErrorResponse(IOException e) {
        ErrorCollection errors;
        final long grepCode = getGrepCode();
        final String msg = String.format("could not parse error response: [%s]", e.getLocalizedMessage());
        logger.error(String.format("[%016x] %s", grepCode, msg), e);
        Error err = new Error("unprocessable_entity", String.format("%s logged with code [%016x]", msg, grepCode));
        errors = new ErrorCollection(Lists.newArrayList(err));
        return errors;
    }

    private long getGrepCode() {
        return ThreadLocalRandom.current().nextLong();
    }

}
