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
  private static final Logger log = LogManager.getLogger(RaiselyClient.class);
  private static final String RAISELY_API_URL = "https://api.raisely.com/v3";
  private static final String APPLICATION_JSON = "application/json";
  private  final String username;
  private final String password;
  private final String accessToken;

  protected final Environment env;

  public RaiselyClient(Environment env){
    this.env = env;
    this.username = env.getConfig().raisely.username;
    this.password = env.getConfig().raisely.password;
    if(env.getConfig().raisely.accessToken.isEmpty()){
      log.info("Getting token...");
      String token = getAccessToken().token;
      log.info("Token: {}", token);
      env.getConfig().raisely.accessToken = token;
      this.accessToken = token;
    }else{
      log.info("Access token already present");
      this.accessToken = env.getConfig().raisely.accessToken;
    }

  }

  //*Note this uses the donation ID from the Stripe metadata. Different from the donation UUID
  /*
   * From Raisely Support:
   * Yep it is possible to use the ID to find the donation UUID, it's a bit quirky,
   * you need to pass an LTE and GTE filter for the ID (idLTE=<donation_id>&idGTE=<donation_id>) in your request to get it to work,
   * like this: https://api.raisely.com/v3/donations?idGTE=14619704&idLTE=14619704&private=true
   */

  public RaiselyClient.DonationResponse getDonation(String donationId){
    return get(RAISELY_API_URL + "/donations?idGTE=" + donationId + "&idLTE=" + donationId + "&private=true", headers(), DonationResponse.class);
  }

  private RaiselyClient.TokenResponse getAccessToken(){
    HttpClient.HeaderBuilder headers = HttpClient.HeaderBuilder.builder();
    return post(RAISELY_API_URL + "/", Map.of("requestAdminToken", "true", "username", username, "password", password), APPLICATION_JSON, headers, TokenResponse.class);
  }

  private HttpClient.HeaderBuilder headers() {
    String token = env.getConfig().raisely.accessToken;
    return HttpClient.HeaderBuilder.builder().authBearerToken(token);
  }

  //Response Objects
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class TokenResponse {
    @JsonProperty("token")
    public String token;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DonationResponse{
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

