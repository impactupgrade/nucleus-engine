package com.impactupgrade.nucleus.environment;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

public class EnvironmentFactory {

  private final String otherJsonFilename;

  public EnvironmentFactory() {
    otherJsonFilename = null;
  }

  public EnvironmentFactory(String otherJsonFilename) {
    this.otherJsonFilename = otherJsonFilename;
  }

  public Environment init(HttpServletRequest request) {
    Environment env = newEnv();
    env.setRequest(request);

    env.getConfig().addOtherJson(otherJsonFilename);

    return env;
  }

  public Environment init(MultivaluedMap<String, String> otherContext) {
    Environment env = newEnv();
    env.setOtherContext(otherContext);

    env.getConfig().addOtherJson(otherJsonFilename);

    return env;
  }

  public Environment init(HttpServletRequest request, MultivaluedMap<String, String> otherContext) {
    Environment env = newEnv();
    env.setRequest(request);
    env.setOtherContext(otherContext);

    env.getConfig().addOtherJson(otherJsonFilename);

    return env;
  }

  public Environment init(HttpServletRequest request, String otherContextKey, String otherContextValue) {
    Environment env = newEnv();
    env.setRequest(request);

    MultivaluedMap<String, String> otherContext = new MultivaluedHashMap<>();
    otherContext.add(otherContextKey, otherContextValue);
    env.setOtherContext(otherContext);

    env.getConfig().addOtherJson(otherJsonFilename);

    return env;
  }

  protected Environment newEnv() {
    return new Environment();
  }
}
