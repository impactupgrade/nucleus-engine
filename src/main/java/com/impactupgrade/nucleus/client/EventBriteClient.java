package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

  public Order getOrder(String eventUrl, String... expansions) {
    String expand = Arrays.stream(expansions).collect(Collectors.joining(","));
    return HttpClient.get(eventUrl + "?expand=" + expand, headers(), Order.class);
  }

  private HttpClient.HeaderBuilder headers() {
    return HttpClient.HeaderBuilder.builder()
            .authBearerToken(apiKey)
            .header("Accept", "*/*");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Event {
    public String id;
    public TextHtml name;
    public String url;
    public DateTime start;
    public DateTime end;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class TextHtml {
    public String text;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DateTime {
    public String utc; // date format "2018-05-12T02:00:00Z",
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Attendee {
    public String id;
    public Profile profile;
    @JsonProperty("event_id")
    public String eventId;
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
    public String changed; // date format "2018-05-12T02:00:00Z",
    public String name;
    public String firstName; // The ticket buyer’s first name
    public String lastName;  // The ticket buyer’s last name
    public String email;
    public Costs costs;
    @JsonProperty("event_id")
    public String eventId;
    public List<Attendee> attendees;
    @JsonProperty("promo_code")
    public String promoCode;
    public String status;
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
    public String taxName;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Cost {
    public String currency;
    public Double value;
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
