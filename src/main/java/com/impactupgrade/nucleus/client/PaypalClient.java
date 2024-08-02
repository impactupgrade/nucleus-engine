/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;
import com.paypal.base.rest.APIContext;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class PaypalClient {
  
  private final static String PAYPAL_SANDBOX_API_URL = "https://api-m.sandbox.paypal.com";
  private final static String PAYPAL_API_URL = "https://api-m.paypal.com";

  protected final Environment env;

  protected final APIContext apiContext;
  protected final String apiUrl;

  public PaypalClient(Environment env) {
    this.env = env;
    this.apiContext = new APIContext(
        env.getConfig().paypal.clientId,
        env.getConfig().paypal.clientSecret, 
        env.getConfig().paypal.mode);
    this.apiUrl = "sandbox".equalsIgnoreCase(env.getConfig().paypal.mode) ? PAYPAL_SANDBOX_API_URL : PAYPAL_API_URL;
  }

  public boolean isValidWebhookData(String transmissionId, String transmissionTime, String certUrl, String authAlgo, String transmissionSig,  String webhookId, String webhookEvent) throws Exception {
    JSONObject jsonObject = new JSONObject();
    jsonObject.put("transmission_id", transmissionId);
    jsonObject.put("transmission_time", transmissionTime);
    jsonObject.put("cert_url", certUrl);
    jsonObject.put("auth_algo", authAlgo);
    jsonObject.put("transmission_sig", transmissionSig);
    jsonObject.put("webhook_id", webhookId);
    jsonObject.put("webhook_event", new JSONObject(webhookEvent));

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("transmission_id", transmissionId);
    params.put("transmission_time", transmissionTime);
    params.put("cert_url", certUrl);
    params.put("auth_algo", authAlgo);
    params.put("transmission_sig", transmissionSig);
    params.put("webhook_id", webhookId);
    //params.put("webhook_event", webhookEvent);

    String requestBody = new ObjectMapper().writeValueAsString(params);
    requestBody = requestBody.substring(0, requestBody.length()-1);
    requestBody += ",\"webhook_event\":" + webhookEvent + "}";

    System.out.println(requestBody);

    WebhookValidationResponse webhookValidationResponse = HttpClient.post(apiUrl + "/v1/notifications/verify-webhook-signature", requestBody, MediaType.APPLICATION_JSON, HttpClient.HeaderBuilder.builder().header("Authorization", apiContext.fetchAccessToken()), WebhookValidationResponse.class);
    return webhookValidationResponse != null && !"FAILURE".equalsIgnoreCase(webhookValidationResponse.verificationStatus);
  }

  public boolean isValidWebhookData2(HttpServletRequest request, String requestBody) throws Exception {
    URL url = new URL("https://api-m.sandbox.paypal.com/v1/notifications/verify-webhook-signature");
    HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
    httpConn.setRequestMethod("POST");

    httpConn.setRequestProperty("Content-Type", "application/json");
    httpConn.setRequestProperty("Authorization", apiContext.fetchAccessToken());

    httpConn.setDoOutput(true);
    OutputStreamWriter writer = new OutputStreamWriter(httpConn.getOutputStream());
    writer.write("{ " +
            "\"transmission_id\": \"" + request.getHeader("Paypal-Transmission-Id") + "\", " +
            "\"transmission_time\": \"" + request.getHeader("Paypal-Transmission-Time") + "\", " +
            "\"cert_url\": \"" + request.getHeader("Paypal-Cert-Url") + "\", " +
            "\"auth_algo\": \"" + request.getHeader("Paypal-Auth-Algo") + "\", " +
            "\"transmission_sig\": \"" + request.getHeader("Paypal-Transmission-Sig") + "\", " +
            "\"webhook_id\": \"" + env.getConfig().paypal.webhookId + "\", " +
            "\"webhook_event\":"  + requestBody +
            "}");
    writer.flush();
    writer.close();
    httpConn.getOutputStream().close();

    InputStream responseStream = httpConn.getResponseCode() / 100 == 2
            ? httpConn.getInputStream()
            : httpConn.getErrorStream();
    Scanner s = new Scanner(responseStream).useDelimiter("\\A");
    String response = s.hasNext() ? s.next() : "";
    System.out.println(response);

    return response.contains("SUCCESS");
  }

  public Subscription getSubscription(String id) throws Exception {
    return HttpClient.get(
        apiUrl + "/v1/billing/subscriptions/" + id + "?fields=plan",
        HttpClient.HeaderBuilder.builder().header("Authorization", apiContext.fetchAccessToken()),
        //HttpClient.HeaderBuilder.builder().authBearerToken(accessToken),
        Subscription.class);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Capture {
    public String id;
    public Amount amount;
    @JsonProperty("final_capture")
    public Boolean finalCapture;
    @JsonProperty("seller_protection")
    public SellerProtection sellerProtection;
    @JsonProperty("disbursement_mode")
    public String disbursementMode;
    @JsonProperty("seller_receivable_breakdown")
    public SellerReceivableBreakdown sellerReceivableBreakdown;
    public String status;
    @JsonProperty("supplementary_data")
    public SupplementaryData supplementaryData;
    @JsonProperty("create_time")
    public Date createTime;
    @JsonProperty("update_time")
    public Date updateTime;
    public List<Link> links;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Amount {
    @JsonProperty("currency_code")
    public String currencyCode;
    public Double value;

  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class SellerProtection {
    public String status;
    @JsonProperty("dispute_categories")
    public List<String> disputeCategories;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class SellerReceivableBreakdown {
    @JsonProperty("gross_amount")
    public Amount grossAmount;
    @JsonProperty("paypal_fee")
    public Amount paypalFee;
    @JsonProperty("platform_fees")
    public List<PlatformFee> platformFees;
    @JsonProperty("net_amount")
    public Amount netAmount;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class PlatformFee {
    public Amount amount;
    public Payee payee;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class SupplementaryData {
    @JsonProperty("related_ids")
    public Map<String, String> relatedIds;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Payee {
    @JsonProperty("email_address")
    public String emailAddress;
    @JsonProperty("merchant_id")
    public String merchantId;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Link {
    public String href;
    public String rel;
    public String method;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Subscription {
    public String id;
    @JsonProperty("shipping_amount")
    public Amount shippingAmount;
    @JsonProperty("plan_id")
    public String planId;
    @JsonProperty("plan_overridden")
    public Boolean planOverridden;
    @JsonProperty("start_time")
    public Date startTime;
    @JsonProperty("create_time")
    public Date createTime;
    @JsonProperty("update_time")
    public Date updateTime;
    public Integer quantity;
    public String status;
    public Plan plan;
    public Subscriber subscriber;
    public List<Link> links;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Plan {
    public Integer productId;
    public String name;
    public String description;
    public List<BillingCycle> billingCycles;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class BillingCycle {
    @JsonProperty("pricing_scheme")
    public PricingScheme pricingScheme;
    public Frequency frequency;
    @JsonProperty("tenure_type")
    public String tenureType;
    public Integer sequence;
    @JsonProperty("total_cycles")
    public Integer totalCycles;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class PricingScheme {
    @JsonProperty("fixed_price")
    public Currency fixedPrice;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Currency {
    @JsonProperty("currency_code")
    public String code;
    public Double value;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Frequency {
    @JsonProperty("interval_unit")
    public String intervalUnit;
    @JsonProperty("interval_count")
    public Integer intervalCount;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Subscriber {
    @JsonProperty("email_address")
    public String emailAddress;
    public Name name;
    @JsonProperty("shipping_address")
    public ShippingAddress shippingAddress;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Name {
    @JsonProperty("given_name")
    public String givenName;
    public String surname;
    @JsonProperty("full_name")
    public String fullName;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class ShippingAddress {
    public Name name;
    public Address address;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Address {
    @JsonProperty("address_line_1")
    public String addressLine1;
    @JsonProperty("address_line_2")
    public String addressLine2;
    @JsonProperty("admin_area_1")
    public String adminArea1;
    @JsonProperty("admin_area_2")
    public String adminArea2;
    @JsonProperty("postal_code")
    public String postalCode;
    @JsonProperty("country_code")
    public String countryCode;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class WebhookValidationResponse {
    @JsonProperty("verification_status")
    public String verificationStatus;
  }
}
