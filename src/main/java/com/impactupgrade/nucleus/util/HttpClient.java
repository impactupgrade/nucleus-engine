/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import org.eclipse.jetty.http.HttpStatus;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Map;

public class HttpClient {

  public static String getAsString(String url) {
    return getAsString(url, null, null);
  }

  public static String getAsString(String url, String mediaType, String bearerToken) {
    return get(url, mediaType, bearerToken, String.class);
  }

  public static <T> T get(String url, String mediaType, String bearerToken, Class<T> clazz) {
    Response response = get(url, mediaType, bearerToken);
    return HttpStatus.OK_200 == response.getStatus() ? response.readEntity(clazz) : null;
  }

  public static <T> T get(String url, String mediaType, String bearerToken, GenericType<T> genericType) {
    Response response = get(url, mediaType, bearerToken);
    return HttpStatus.OK_200 == response.getStatus() ? response.readEntity(genericType) : null;
  }

  public static Response getJson(String url, String bearerToken) {
    return get(url, MediaType.APPLICATION_JSON, bearerToken);
  }

  protected static Response get(String url, String mediaType, String bearerToken) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    String defaultMediaType = !Strings.isNullOrEmpty(mediaType) ? mediaType : MediaType.TEXT_PLAIN;
    Invocation.Builder invocationBuilder = webTarget.request(defaultMediaType);
    if (!Strings.isNullOrEmpty(bearerToken)) {
      invocationBuilder.header("Authorization", "Bearer " + bearerToken);
    }
    return invocationBuilder.get();
  }

  public static <T> Response postJson(T entity, String bearerToken, String url, String... paths) {
    return post(MediaType.APPLICATION_JSON, entity, bearerToken, url, paths);
  }

  public static <T> Response postXml(T entity, String bearerToken, String url, String... paths) {
    return post(MediaType.APPLICATION_XML, entity, bearerToken, url, paths);
  }

  public static Response postForm(Map<String, String> data, String bearerToken, String url, String... paths) {
    Form form = new Form();
    data.forEach(form::param);
    return post(MediaType.APPLICATION_FORM_URLENCODED, form, bearerToken, url, paths);
  }

  protected static <T> Response post(String mediaType, T entity, String bearerToken, String url, String... paths) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    for (String path : paths) {
      webTarget = webTarget.path(path);
    }

    Invocation.Builder invocationBuilder = webTarget.request(mediaType);
    if (!Strings.isNullOrEmpty(bearerToken)) {
      invocationBuilder.header("Authorization", "Bearer " + bearerToken);
    }
    return invocationBuilder.post(Entity.entity(entity, mediaType));
  }

  public static <T> Response putJson(T entity, String bearerToken, String url, String... paths) {
    return put(MediaType.APPLICATION_JSON, entity, bearerToken, url, paths);
  }

  protected static <T> Response put(String mediaType, T entity, String bearerToken, String url, String... paths) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    for (String path : paths) {
      webTarget = webTarget.path(path);
    }

    Invocation.Builder invocationBuilder = webTarget.request(mediaType);
    if (!Strings.isNullOrEmpty(bearerToken)) {
      invocationBuilder.header("Authorization", "Bearer " + bearerToken);
    }
    return invocationBuilder.put(Entity.entity(entity, mediaType));
  }

  public static Response delete(String bearerToken, String url, String... paths) {
    return delete(MediaType.TEXT_PLAIN, bearerToken, url, paths);
  }

  protected static Response delete(String mediaType, String bearerToken, String url, String... paths) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    for (String path : paths) {
      webTarget = webTarget.path(path);
    }

    Invocation.Builder invocationBuilder = webTarget.request(mediaType);
    if (!Strings.isNullOrEmpty(bearerToken)) {
      invocationBuilder.header("Authorization", "Bearer " + bearerToken);
    }
    return invocationBuilder.delete();
  }

}
