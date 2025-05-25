/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public class HttpClient {

  private static final Logger log = LogManager.getLogger(HttpClient.class);

  public static Response get(String url, HeaderBuilder headerBuilder) {
    Client client = client();
    WebTarget webTarget = client.target(url);
    MultivaluedMap<String, Object> headers = headerBuilder == null ? new MultivaluedHashMap<>() : headerBuilder.headers;
    return webTarget.request().headers(headers).get();
  }

  public static <T> T get(String url, HeaderBuilder headerBuilder, Class<T> clazz) {
    Response response = get(url, headerBuilder);
    if (isOk(response)) {
      if (clazz != null) {
        return response.readEntity(clazz);
      }
    } else if (response.getStatus() == 404 ){
      log.info("GET not found: url={}", url);
    } else {
      log.warn("GET failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
    }
    return null;
  }

  public static <T> T get(String url, HeaderBuilder headerBuilder, GenericType<T> genericType) {
    Response response = get(url, headerBuilder);
    if (isOk(response)) {
      if (genericType != null) {
        return response.readEntity(genericType);
      }
    } else if (response.getStatus() == 404 ){
      log.info("GET not found: url={}", url);
    } else {
      log.warn("GET failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
    }
    return null;
  }

  public static <S> Response post(String url, S entity, String mediaType, HeaderBuilder headerBuilder) {
    Client client = client();
    WebTarget webTarget = client.target(url);
    MultivaluedMap<String, Object> headers = headerBuilder == null ? new MultivaluedHashMap<>() : headerBuilder.headers;
    return webTarget.request().headers(headers).post(Entity.entity(entity, mediaType));
  }

  public static <S, T> T post(String url, S entity, String mediaType, HeaderBuilder headerBuilder, Class<T> clazz) {
    Response response = post(url, entity, mediaType, headerBuilder);
    if (isOk(response)) {
      if (clazz != null) {
        return response.readEntity(clazz);
      }
    } else {
      log.warn("POST failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
    }
    return null;
  }

  // special case to help DRY
  public static void postForm(String url, Map<String, String> data, HeaderBuilder headerBuilder) {
    Form form = new Form();
    data.forEach(form::param);
    post(url, form, MediaType.APPLICATION_FORM_URLENCODED, headerBuilder);
  }

  public static <S> void put(String url, S entity, String mediaType, HeaderBuilder headerBuilder) {
    put(url, entity, mediaType, headerBuilder, null);
  }

  public static <S, T> T put(String url, S entity, String mediaType, HeaderBuilder headerBuilder, Class<T> clazz) {
    Client client = client();
    WebTarget webTarget = client.target(url);
    MultivaluedMap<String, Object> headers = headerBuilder == null ? new MultivaluedHashMap<>() : headerBuilder.headers;
    Response response = webTarget.request().headers(headers).put(Entity.entity(entity, mediaType));
    if (isOk(response)) {
      if (clazz != null) {
        return response.readEntity(clazz);
      }
    } else {
      log.warn("PUT failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
    }
    return null;
  }

  // TODO: Switched to using JDK's HttpClient -- having issues with Jersey, PATCH fixes, and Java 16 now preventing reflection on private modules.
  //  Update this lib-wide, but isolating here for the moment.
  public static void patch(String url, Object entity, String mediaType, HeaderBuilder headerBuilder) throws IOException, InterruptedException {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    String json = objectMapper.writeValueAsString(entity);

    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", mediaType)
        .method("PATCH", HttpRequest.BodyPublishers.ofString(json));
    for (String key : headerBuilder.headers.keySet()) {
      builder.header(key, headerBuilder.headers.getFirst(key).toString());
    }
    HttpRequest request = builder.build();
    HttpResponse<String> response = java.net.http.HttpClient.newBuilder().build().send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 300) {
      log.warn("PATCH failed: url={} code={} message={}", url, response.statusCode(), response.body());
    }
  }

  public static void delete(String url, HeaderBuilder headerBuilder) {
    Client client = client();
    WebTarget webTarget = client.target(url);
    MultivaluedMap<String, Object> headers = headerBuilder == null ? new MultivaluedHashMap<>() : headerBuilder.headers;
    Response response = webTarget.request().headers(headers).delete();
    if (!isOk(response)) {
      log.warn("DELETE failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
    }
  }

  public static boolean isOk(Response response) {
    return response.getStatus() < 300;
  }

  private static Client client() {
    return ClientBuilder.newClient().register(new RedirectAuthFilter());
  }

  private static class RedirectAuthFilter implements ClientRequestFilter {
    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
      String url = requestContext.getUri().toString();

      // Specifically for S3 and other services that take the Authorization header for the initial request, but then
      // use a different auth setup on 30x redirect URLs (S3 includes a signature in the URL itself, and if the
      // auth header is still included, you'll get an error). If redirected and the URL contains a sig, strip
      // out the auth header.
      if (url.contains("X-Amz-Algorithm") || url.contains("Signature")) {
        requestContext.getHeaders().remove("Authorization");
      }
    }
  }

  public static class HeaderBuilder {
    private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    public static HeaderBuilder builder() {
      return new HeaderBuilder();
    }
    public HeaderBuilder header(String k, Object v) {
      headers.add(k, v);
      return this;
    }
    public HeaderBuilder authBearerToken(String v) {
      headers.add("Authorization", "Bearer " + v);
      return this;
    }
    public MultivaluedMap<String, Object> headersMap() {
      return headers;
    }
  }
}
