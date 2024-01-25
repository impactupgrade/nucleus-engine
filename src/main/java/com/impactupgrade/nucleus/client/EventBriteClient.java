package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;

import java.util.List;

public class EventBriteClient {

  protected final Environment env;

  private final String apiKey;

  public EventBriteClient(Environment env) {
    this.env = env;
    this.apiKey = env.getConfig().eventBrite.secretKey;
  }

  public Event getEvent(String eventUrl) {
    return HttpClient.get(eventUrl, headers(), Event.class);
  }

  public Attendee getAttendee(String attendeeUrl) {
    return HttpClient.get(attendeeUrl, headers(), Attendee.class);
  }

  public Order getOrder(String eventUrl) {
    return HttpClient.get(eventUrl, headers(), Order.class);
  }

  private HttpClient.HeaderBuilder headers() {
    return HttpClient.HeaderBuilder.builder()
            .authBearerToken(apiKey)
            .header("Accept", "*/*");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Event {
    public String id;
    public Description name;
    public Description description;
    public String url;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Description {
    public String text;
    public String html;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Attendee {
    public String id;
    public Profile profile;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Profile {
    @JsonProperty("first_name")
    public String firstName;
    @JsonProperty("last_name")
    public String lastName;
    public String email;
    @JsonProperty("cell_phone")
    public String cellPhone;
    @JsonProperty("work_phone")
    public String workPhone;
    public Addresses addresses;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Addresses {
    public Address home;
    public Address ship;
    public Address work;
    public Address bill;

  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Address {
    @JsonProperty("address_1")
    public String address1;
    @JsonProperty("address_2")
    public String address2;
    public String city;
    public String region;
    @JsonProperty("postal_code")
    public String postalCode;
    public String country;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Order {
    public String id;
    public String created; // date format "2018-05-12T02:00:00Z",
    public String name;
    public String email;
    public String status;
    public Costs costs;
    public Event event;
    public List<Attendee> attendees;
    @JsonProperty("resource_uri")
    public String resourceUri;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Costs {
    @JsonProperty("base_price")
    public Cost basePrice;
    @JsonProperty("display_price")
    public Cost displayPrice;
    @JsonProperty("display_fee")
    public Cost displayFee;
    public Cost gross;
    @JsonProperty("eventbrite_fee")
    public Cost eventbriteFee;
    @JsonProperty("payment_fee")
    public Cost paymentFee;
    public Cost tax;
    @JsonProperty("display_tax")
    public Cost displayTax;
    @JsonProperty("price_before_discount")
    public Cost priceBeforeDiscount;
    @JsonProperty("discount_amount")
    public Cost discountAmount;
    @JsonProperty("discount_type")
    public String discountType;
    @JsonProperty("fee_components")
    public List<Component> feeComponents;
    @JsonProperty("tax_components")
    public List<Component> taxComponents;
    @JsonProperty("shipping_components")
    public List<Component> shippingComponents;
    @JsonProperty("has_gts_tax")
    public Boolean hasGtsTax;
    @JsonProperty("tax_name")
    public String taxtName;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Cost {
    public String currency;
    public Double value;
    @JsonProperty("major_value")
    public String majorValue;
    public String display;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Component {
    public Boolean intermediate;
    public String name;
    @JsonProperty("internal_name")
    public String internalName;
    @JsonProperty("group_name")
    public String group_name;
    public Double value;
    public Discount discount;
    public Rule rule;
    public String base;
    public String bucket;
    public String recipient;
    public String payer;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Discount {
    public Cost amount;
    public String reason; // TODO: enum?
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Rule {
    public String id;
  }

}
