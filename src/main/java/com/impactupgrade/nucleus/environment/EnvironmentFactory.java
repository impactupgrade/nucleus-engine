/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.environment;

import com.google.common.base.Strings;
import org.apache.http.client.utils.URLEncodedUtils;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MultivaluedMap;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

public class EnvironmentFactory {

  private String otherJsonFilename = null;

  public EnvironmentFactory() {
  }

  public EnvironmentFactory(String otherJsonFilename) {
    this.otherJsonFilename = otherJsonFilename;
  }

  public Environment init(HttpServletRequest request) {
    Environment env = newEnv();
    setRequest(env, request);

    env.getConfig().addOtherJsonFile(otherJsonFilename);

    return env;
  }

  public Environment init(MultivaluedMap<String, String> _otherContext) {
    Environment env = newEnv();

    for (String key : _otherContext.keySet()) {
      env.addOtherContext(key, _otherContext.getFirst(key));
    }

    env.getConfig().addOtherJsonFile(otherJsonFilename);

    return env;
  }

  public Environment init(String otherJsonFilename) {
    Environment env = newEnv();

    env.getConfig().addOtherJsonFile(this.otherJsonFilename);
    env.getConfig().addOtherJsonFile(otherJsonFilename);

    return env;
  }

  public Environment init(HttpServletRequest request, MultivaluedMap<String, String> _otherContext) {
    Environment env = newEnv();
    setRequest(env, request);

    for (String key : _otherContext.keySet()) {
      env.addOtherContext(key, _otherContext.getFirst(key));
    }

    env.getConfig().addOtherJsonFile(otherJsonFilename);

    return env;
  }

  public Environment init(HttpServletRequest request, String otherJsonFilename) {
    Environment env = newEnv();
    setRequest(env, request);

    env.getConfig().addOtherJsonFile(this.otherJsonFilename);
    env.getConfig().addOtherJsonFile(otherJsonFilename);

    return env;
  }

  public Environment init(HttpServletRequest request, String otherContextKey, String otherContextValue) {
    Environment env = newEnv();
    setRequest(env, request);

    if (!Strings.isNullOrEmpty(otherContextKey) && !Strings.isNullOrEmpty(otherContextValue)) {
      env.addOtherContext(otherContextKey, otherContextValue);
    }

    env.getConfig().addOtherJsonFile(otherJsonFilename);

    return env;
  }

  protected Environment newEnv() {
    return new Environment();
  }

  protected void setRequest(Environment env, HttpServletRequest request) {
    if (request != null) {
      Enumeration<String> headerNames = request.getHeaderNames();
      while (headerNames.hasMoreElements()) {
        String headerName = headerNames.nextElement();
        env.getOtherContext().put(headerName, request.getHeader(headerName));
      }

      URLEncodedUtils.parse(request.getQueryString(), StandardCharsets.UTF_8).forEach(
          pair -> env.getOtherContext().put(pair.getName(), pair.getValue()));

      env.getOtherContext().put("uri", request.getRequestURI());
    }
  }
}
