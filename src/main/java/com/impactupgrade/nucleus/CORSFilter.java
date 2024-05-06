/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class CORSFilter implements ContainerResponseFilter {

  // TODO: I DO NOT UNDERSTAND WHY THIS IS NEEDED. App's CrossOriginFilter is supposed to work at the underlying
  //  Jetty level, but we hit CORS errors without this Jersey level in the mix as well.

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) throws IOException {
    response.getHeaders().add("Access-Control-Allow-Origin", "*");
    response.getHeaders().add("Access-Control-Allow-Headers", "Accept,Authorization,Content-Type,CSRF-Token,Nucleus-Api-Key,Origin,X-Requested-By,X-Requested-With");
    response.getHeaders().add("Access-Control-Allow-Credentials", "true");
    response.getHeaders().add("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,HEAD");
  }
}
