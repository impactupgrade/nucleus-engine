package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

public class HttpClient {

  public static String getAsString(String url) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    Invocation.Builder invocationBuilder = webTarget.request(MediaType.TEXT_PLAIN);
    Response response = invocationBuilder.get();
    return response.readEntity(String.class);
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

  private static <T> Response post(String mediaType, T entity, String bearerToken, String url, String... paths) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    for (String path : paths) {
      webTarget = webTarget.path(path);
    }

    Invocation.Builder builder = webTarget.request(mediaType);
    if (!Strings.isNullOrEmpty(bearerToken)) {
      builder.header("Authorization", "Bearer " + bearerToken);
    }
    return builder.post(Entity.entity(entity, mediaType));
  }
}
