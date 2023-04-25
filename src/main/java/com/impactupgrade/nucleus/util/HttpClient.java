/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.util.Date;
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
      log.error("GET failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
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
      log.error("GET failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
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
      log.error("POST failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
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
      log.error("PUT failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
    }
    return null;
  }

  public static void delete(String url, HeaderBuilder headerBuilder) {
    Client client = client();
    WebTarget webTarget = client.target(url);
    MultivaluedMap<String, Object> headers = headerBuilder == null ? new MultivaluedHashMap<>() : headerBuilder.headers;
    Response response = webTarget.request().headers(headers).delete();
    if (!isOk(response)) {
      log.error("DELETE failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
    }
  }

  private static Client client() {
    return ClientBuilder.newClient();
  }

  private static boolean isOk(Response response) {
    return response.getStatus() < 300;
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
  }

  public static class TokenResponse {
    @JsonProperty("access_token")
    public String accessToken;
    @JsonProperty("token_type")
    public String tokenType;
    @JsonProperty("expires_in")
    public Integer expiresIn;
    @JsonProperty("refresh_token")
    public String refreshToken;
    @JsonProperty("userName")
    public String username;
    public Boolean twoFactorEnabled;
    @JsonProperty(".issued")
    public Date issuedAt;
    @JsonProperty(".expires")
    public Date expiresAt;
  }
}
