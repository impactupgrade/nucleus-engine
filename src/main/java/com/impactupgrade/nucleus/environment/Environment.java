/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.environment;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import javax.servlet.http.HttpServletRequest;

/**
 * Every action within Nucleus is kicked off by either an HTTP Request or a manual script. This class
 * is responsible for carrying the context of the flow throughout all processing steps, defining how
 * the organization's set of platforms, configurations, and customizations.
 *
 * We can't statically define this, one per organization. Instead, the whole concept is dynamic,
 * per-flow. This allows multitenancy (ex: DR having a single instance with multiple "Funding Nations",
 * each with their own unique characteristics and platform keys) and dynamic
 * logic based on additional context. We also determine the context at this level (rather than
 * at the application level) to queue us up for SaaS models with DB-driven configs.
 */
@Component
@RequestScope
public class Environment {

  private static final Logger log = LogManager.getLogger(Environment.class);

  protected final HttpServletRequest request;

  // TODO: use API key to pull from DB
  // Whenever possible, we focus on being configuration-driven using one, large JSON file.
  private final EnvironmentConfig config = EnvironmentConfig.init();

  public Environment(HttpServletRequest request) {
    this.request = request;
  }

  // Additional context, if available.
  // It seems odd to track URI and headers separately, rather than simply storing HttpServletRequest itself.
  // However, many (most?) frameworks don't allow aspects of request to be accessed over and over. Due to the mechanics,
  // some only allow it to be streamed once.
//  private String uri = null;
//  private final Map<String, String> headers = new HashMap<>();
//  private MultivaluedMap<String, String> otherContext = new MultivaluedHashMap<>();

  public EnvironmentConfig getConfig() {
    return config;
  }

//  public void setRequest(HttpServletRequest request) {
//    if (request != null) {
//      uri = request.getRequestURI();
//
//      Enumeration<String> headerNames = request.getHeaderNames();
//      while (headerNames.hasMoreElements()) {
//        String headerName = headerNames.nextElement();
//        headers.put(headerName, request.getHeader(headerName));
//      }
//    }
//  }
//
//  public String getUri() {
//    return uri;
//  }
//
  public String getHeader(String name) {
    return request.getHeader(name);
  }
//
//  public MultivaluedMap<String, String> getOtherContext() {
//    return otherContext;
//  }
//
//  public void setOtherContext(MultivaluedMap<String, String> otherContext) {
//    this.otherContext = otherContext;
//  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DYNAMIC CONFIRMATION
  // Most configuration is static and should be in environment.json. However, some pieces are dynamic and depend on
  // processing context. Allow organization to override these at the request level.
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public String getCurrency() {
    return "usd";
  }
}
