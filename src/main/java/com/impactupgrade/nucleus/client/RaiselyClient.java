package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.post;

public class RaiselyClient{
  private static final String RAISELY_API_URL = "https://api.raisely.com/v3/";
  private static final String APPLICATION_JSON = "application/json";

  protected final Environment env;

  private String _accessToken = null;

  public RaiselyClient(Environment env){
    this.env = env;
  }

  //*Note this uses the donation ID from the Stripe metadata. Different from the donation UUID
  /*
   * From Raisely Support:
   * Yep it is possible to use the ID to find the donation UUID, it's a bit quirky,
   * you need to pass an LTE and GTE filter for the ID (idLTE=<donation_id>&idGTE=<donation_id>) in your request to get it to work,
   * like this: https://api.raisely.com/v3/donations?idGTE=14619704&idLTE=14619704&private=true
   */

  public RaiselyClient.Donation getDonation(String donationId){
    DonationResponse response = get(
        RAISELY_API_URL + "donations?idGTE=" + donationId + "&idLTE=" + donationId + "&private=true",
        HttpClient.HeaderBuilder.builder().authBearerToken(getAccessToken()),
        DonationResponse.class
    );

    if (response != null && !response.data.isEmpty()) {
      return response.data.get(0);
    }

    return null;
  }

  protected String getAccessToken() {
    if (_accessToken == null) {
      String username = env.getConfig().raisely.username;
      String password = env.getConfig().raisely.password;

      env.logJobInfo("Getting token...");
      HttpClient.HeaderBuilder headers = HttpClient.HeaderBuilder.builder();
      TokenResponse response = post(RAISELY_API_URL + "login", Map.of("requestAdminToken", "true", "username", username, "password", password), APPLICATION_JSON, headers, TokenResponse.class);
      env.logJobInfo("Token: {}", response.token);
      this._accessToken = response.token;
    }

    return this._accessToken;
  }

  //Response Objects
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class TokenResponse {
    @JsonProperty("token")
    public String token;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DonationResponse {
    public List<Donation> data;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Donation {
    @JsonProperty("amount")
    public Integer amount;

    @JsonProperty("fee")
    public Integer fee;

    @JsonProperty("feeOptIn")
    public boolean feeCovered;
    @JsonProperty("total")
    public Integer total;

    @JsonProperty("items")
    public List<DonationItem> items;

    @Override
    public String toString() {
      return "Donation{" +
              "amount=" + amount +
              ", fee=" + fee +
              ", feeOptIn = " + feeCovered +
              ", total='" + total + '\'' +
              ", items='" + items + '\'' +
              '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DonationItem {
    public Integer amount;
    public Integer amountRefunded;
    public String type;
    public Integer quantity;

    @Override
    public String toString() {
      return "Item{" +
              "amount=" + amount +
              ", amountRefunded=" + amountRefunded +
              ", type='" + type + '\'' +
              ", quantity='" + quantity + '\'' +
              '}';
    }
  }

}

