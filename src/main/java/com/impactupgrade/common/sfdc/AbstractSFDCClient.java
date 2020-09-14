package com.impactupgrade.common.sfdc;

import com.impactupgrade.integration.sfdc.SFDCPartnerAPIClient;

public abstract class AbstractSFDCClient extends SFDCPartnerAPIClient {

  private static String AUTH_URL;
  static {
    String profile = System.getenv("PROFILE");
    if ("production".equalsIgnoreCase(profile)) {
      AUTH_URL = "https://login.salesforce.com/services/Soap/u/47.0/";
    } else {
      AUTH_URL = "https://test.salesforce.com/services/Soap/u/47.0/";
    }
  }

  protected AbstractSFDCClient(String username, String password) {
    super(
        username,
        password,
        AUTH_URL,
        20 // LJI objects are massive, so toning down the batch sizes
    );
  }

  protected AbstractSFDCClient() {
    super(
        System.getenv("SFDC.USERNAME"),
        System.getenv("SFDC.PASSWORD"),
        AUTH_URL,
        20 // LJI objects are massive, so toning down the batch sizes
    );
  }

  // Exists to allow other clients to easily login.
  public static class LoginSFDCClient extends AbstractSFDCClient {

    public LoginSFDCClient(String username, String password) {
      super(username, password);
    }

    public LoginSFDCClient() {
      super();
    }
  }
}
