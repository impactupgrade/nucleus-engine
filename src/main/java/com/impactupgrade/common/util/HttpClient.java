package com.impactupgrade.common.util;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

// TODO: Didn't end up needing this, but leaving it here for future uses. Always rely on existing SDKs first
// (or create your own open source SDK, like we're doing with hubspot-java-client), but use this as a last-resort
// to directly call APIs with a raw HTTP client (Jersey)
public class HttpClient {

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
