/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;
import com.paypal.base.rest.APIContext;

import java.util.Date;
import java.util.List;
import java.util.Map;

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
    public Payee payee;
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
    @JsonProperty("net_amount")
    public Amount netAmount;
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
}
