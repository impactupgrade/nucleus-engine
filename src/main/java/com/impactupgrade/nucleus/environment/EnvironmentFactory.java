package com.impactupgrade.nucleus.environment;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MultivaluedMap;

public class EnvironmentFactory {

  public Environment init(HttpServletRequest request) {
    Environment env = newEnv();
    env.setRequest(request);
    return env;
  }

  public Environment init(MultivaluedMap<String, String> otherContext) {
    Environment env = newEnv();
    env.setOtherContext(otherContext);
    return env;
  }

  public Environment init(HttpServletRequest request, MultivaluedMap<String, String> otherContext) {
    Environment env = newEnv();
    env.setRequest(request);
    env.setOtherContext(otherContext);
    return env;
  }

  public Environment newEnv() {
    return new Environment();
  }
}