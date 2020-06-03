package com.impactupgrade.common.util;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

public class HttpClient {

  public static String getAsString(String url) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    Invocation.Builder invocationBuilder = webTarget.request(MediaType.TEXT_PLAIN);
    Response response = invocationBuilder.get();
    return response.readEntity(String.class);
  }

  public static <T> Response postXml(T entity, String url, String... paths) {
    return post(MediaType.APPLICATION_XML, entity, url, paths);
  }

  private static <T> Response post(String mediaType, T entity, String url, String... paths) {
    Client client = ClientBuilder.newClient();
    WebTarget webTarget = client.target(url);
    for (String path : paths) {
      webTarget = webTarget.path(path);
    }

    Invocation.Builder invocationBuilder =  webTarget.request(mediaType);
    return invocationBuilder.put(Entity.entity(entity, mediaType));
  }
}
