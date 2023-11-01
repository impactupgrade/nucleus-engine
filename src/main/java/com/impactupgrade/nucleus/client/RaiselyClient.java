package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.impactupgrade.nucleus.environment.Environment;

import java.util.List;
import java.util.Map;

import static com.impactupgrade.nucleus.util.HttpClient.get;

public class RaiselyClient extends OAuthClient {

  private static final String RAISELY_API_URL = "https://api.raisely.com/v3";
  private static final String AUTH_URL = RAISELY_API_URL + "/login";

  public RaiselyClient(Environment env) {
    super("raisely", env);
  }

  @Override
  protected OAuthContext oAuthContext() {
    return new UsernamePasswordOAuthContext(env.getConfig().raisely, AUTH_URL, false, Map.of("requestAdminToken", "true"));
  }

  //*Note this uses the donation ID from the Stripe metadata. Different from the donation UUID
  /*
   * From Raisely Support:
   * Yep it is possible to use the ID to find the donation UUID, it's a bit quirky,
   * you need to pass an LTE and GTE filter for the ID (idLTE=<donation_id>&idGTE=<donation_id>) in your request to get it to work,
   * like this: https://api.raisely.com/v3/donations?idGTE=14619704&idLTE=14619704&private=true
   */

  public RaiselyClient.Donation getDonation(String donationId) {
    DonationResponse response = get(
        RAISELY_API_URL + "/donations?" +
            "idGTE=" + donationId +
            "&idLTE=" + donationId +
            "&private=true",
        headers(),
        DonationResponse.class
    );

    if (response != null && !response.data.isEmpty()) {
      return response.data.get(0);
    }

    return null;
  }

  //Response Objects
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

