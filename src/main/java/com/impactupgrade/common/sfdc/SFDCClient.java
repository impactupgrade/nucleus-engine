package com.impactupgrade.common.sfdc;

import com.impactupgrade.integration.sfdc.SFDCPartnerAPIClient;

public class SFDCClient extends SFDCPartnerAPIClient {

  private static String AUTH_URL;
  static {
    String profile = System.getenv("PROFILE");
    if ("production".equalsIgnoreCase(profile)) {
      AUTH_URL = "https://login.salesforce.com/services/Soap/u/47.0/";
    } else {
      AUTH_URL = "https://test.salesforce.com/services/Soap/u/47.0/";
    }
  }

  public SFDCClient(String username, String password) {
    super(
        username,
        password,
        AUTH_URL,
        20 // objects are massive, so toning down the batch sizes
    );
  }

  public SFDCClient() {
    super(
        System.getenv("SFDC_USERNAME"),
        System.getenv("SFDC_PASSWORD"),
        AUTH_URL,
        20 // objects are massive, so toning down the batch sizes
    );
  }
}
