/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

public class HttpClient {

  private static final Logger log = LogManager.getLogger(HttpClient.class);

  public static void get(String url, HeaderBuilder headerBuilder) {
    get(url, headerBuilder, null);
  }

  public static <T> T get(String url, HeaderBuilder headerBuilder, Class<T> clazz) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    Response response = webTarget.request().headers(headerBuilder.headers).get();
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

  public static <S> void post(String url, S entity, String mediaType, HeaderBuilder headerBuilder) {
    post(url, entity, mediaType, headerBuilder, null);
  }

  public static <S, T> T post(String url, S entity, String mediaType, HeaderBuilder headerBuilder, Class<T> clazz) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    Response response = webTarget.request().headers(headerBuilder.headers).post(Entity.entity(entity, mediaType));
    if (isOk(response)) {
      if (clazz != null) {
        return response.readEntity(clazz);
      }
    } else {
      log.error("POST failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
    }
    return null;
  }

  public static <S> void put(String url, S entity, String mediaType, HeaderBuilder headerBuilder) {
    put(url, entity, mediaType, headerBuilder, null);
  }

  public static <S, T> T put(String url, S entity, String mediaType, HeaderBuilder headerBuilder, Class<T> clazz) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    Response response = webTarget.request().headers(headerBuilder.headers).put(Entity.entity(entity, mediaType));
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
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    Response response = webTarget.request().headers(headerBuilder.headers).delete();
    if (!isOk(response)) {
      log.error("DELETE failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
    }
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
}
