package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.util.HttpClient;
import com.impactupgrade.nucleus.util.OAuth2;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

import static com.impactupgrade.nucleus.util.HttpClient.get;

public class RaiselyClient extends OrgConfiguredClient {

  private static final String RAISELY_API_URL = "https://api.raisely.com/v3";
  private static final String AUTH_URL = RAISELY_API_URL + "/login";

  private final OAuth2.Context oAuth2Context;

  public RaiselyClient(Environment env) {
    super(env);

    JSONObject raiselyJson = getEnvJson().getJSONObject("raisely");

    this.oAuth2Context = new OAuth2.UsernamePasswordContext(
      env.getConfig().raisely.username, env.getConfig().raisely.password, Map.of("requestAdminToken", "true"),
      raiselyJson.getString("accessToken"), raiselyJson.getLong("expiresAt"), raiselyJson.getString("refreshToken"),  AUTH_URL);
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

  protected HttpClient.HeaderBuilder headers() {
    String accessToken = oAuth2Context.accessToken();
    if (oAuth2Context.refresh().accessToken() != accessToken)  {
      // tokens updated - need to update config in db
      updateEnvJson("raisely", oAuth2Context);
    }
    return HttpClient.HeaderBuilder.builder().authBearerToken(oAuth2Context.accessToken());
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

  //TODO: remove once done with testing
  public static void main(String[] args) {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.raisely.username = "brett@impactupgrade.com";
        envConfig.raisely.password = "6.tw*gghr.fyDDjkjaZj";
        return envConfig;
      }
    };
    
    RaiselyClient raiselyClient = new RaiselyClient(env);
    
    raiselyClient.getDonation("123");
    raiselyClient.getDonation("123");
  }
}

