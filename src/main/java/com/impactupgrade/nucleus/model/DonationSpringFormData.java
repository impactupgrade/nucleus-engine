package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.util.Utils;
import com.neovisionaries.i18n.CountryCode;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Calendar;

// TODO: A ton of overlap between this and DR's internal form handler. And we have TER soon creating one. Define
// a core model and common logic?
public class DonationSpringFormData {

  private static final Logger log = LogManager.getLogger(DonationSpringFormData.class);

  protected static final NumberFormat CURRENCY_FORMATTER = NumberFormat.getCurrencyInstance();

  // TODO: not campaign, but fund selection
  @FormParam("campaign") private String campaign;
  @FormParam("donation_type") private String donationType; // onetime or monthly
  @FormParam("donation_amount") private Double amount;
  @FormParam("donate_as_business") private String donateAsBusiness;
  @FormParam("first_name") private String firstName;
  @FormParam("last_name") private String lastName;
  @FormParam("email") private String email;
  @FormParam("phone") private String phone;
  @FormParam("business_name") private String businessName;
  @FormParam("business_email") private String businessEmail;
  @FormParam("business_address") private String businessAddress;
  @FormParam("business_address_2") private String businessAddress2;
  @FormParam("business_city") private String businessCity;
  @FormParam("business_state") private String businessState;
  @FormParam("business_zip") private String businessZip;
  @FormParam("business_country") private String businessCountry;
  @FormParam("billing_address") private String billingAddress;
  @FormParam("billing_address_2") private String billingAddress2;
  @FormParam("billing_city") private String billingCity;
  @FormParam("billing_state") private String billingState;
  @FormParam("billing_zip") private String billingZip;
  @FormParam("billing_country") private String billingCountry;
  @FormParam("stripe_token") private String stripeCCToken;
  @FormParam("stripe_bank_account_token") private String stripeACHToken;
  @FormParam("donation_note") private String notes;

  @HeaderParam("Referer") private String referer;
  @HeaderParam("Origin") private String origin;
  @HeaderParam("Host") private String host;

  private final Calendar timestamp;

  public DonationSpringFormData() {
    timestamp = Calendar.getInstance();
  }

  public boolean isFraudAttempt() {
    // TODO: Once we're in iframe-only mode...
//    if (
//        Strings.isNullOrEmpty(referer) || (!referer.contains("donationspring.com"))
//            || Strings.isNullOrEmpty(origin) || (!origin.contains("donationspring.com"))
//    ) {
//      log.info("blocking a bad request: referer/origin");
//      return true;
      // TODO: Once we're in iframe-only mode...
//    } else if (Strings.isNullOrEmpty(host) || (
//        !host.contains("donationspring.com") && !host.contains("donationspring-staging") && !host.contains("localhost"))) {
//      // Ensure no requests going straight to Heroku -- must go through the subdomain in Cloudflare
//      log.info("blocking a bad request: host");
//      return true;
    /*} else */if (Strings.isNullOrEmpty(firstName) || Strings.isNullOrEmpty(lastName)
        // Real creative, bots.
        || firstName.contains("<first>") || firstName.contains("<First>")
        || lastName.contains("<last>") || lastName.contains("<Last>")) {
      log.info("blocking a bad request: name");
      return true;
    } else if (Strings.isNullOrEmpty(email) || !EmailValidator.getInstance().isValid(email) || email.contains("example.com")) {
      log.info("blocking a bad request: email");
      return true;
    } else if (Strings.isNullOrEmpty(billingAddress) || Strings.isNullOrEmpty(billingCity)
        || Strings.isNullOrEmpty(billingState) || Strings.isNullOrEmpty(billingZip)
        // Real creative, bots.
        || billingAddress.contains("<street>") || billingAddress.contains("<Street>")) {
      log.info("blocking a bad request: address");
      return true;
    }

    return false;
  }

  public boolean isBusiness() {
    return Utils.checkboxToBool(donateAsBusiness);
  }

  public boolean isRecurring() {
    return "monthly".equalsIgnoreCase(donationType);
  }

  public String getAmountFormatted() {
    return new DecimalFormat("#.##").format(amount);
  }

  public long getAmountInCents() {
    return (long) (amount * 100);
  }

  public String getStripeToken() {
    // If Stripe is used, may be a normal CC token or an ACH token from Plaid. Both are treated the same.
    if (!Strings.isNullOrEmpty(stripeACHToken)) {
      return stripeACHToken;
    } else {
      return stripeCCToken;
    }
  }

  public String getCustomerName() {
    return isBusiness() ? businessName : firstName + " " + lastName;
  }

  public String getCustomerEmail() {
    return isBusiness() ? businessEmail : email;
  }

  public String getFullBillingAddress() {
    String fullBillingAddress = billingAddress;
    if (!Strings.isNullOrEmpty(billingAddress2)) {
      fullBillingAddress += ", " + billingAddress2;
    }
    return fullBillingAddress + ", " + billingCity + ", " + billingState + " " + billingZip + ", " + getBillingCountryFullName();
  }

  public String getFullBusinessAddress() {
    String fullBusinessAddress = businessAddress;
    if (!Strings.isNullOrEmpty(businessAddress2)) {
      fullBusinessAddress += ", " + businessAddress2;
    }
    return fullBusinessAddress + ", " + businessCity + ", " + businessState + " " + businessZip + ", " + getBusinessCountryFullName();
  }

  public String getBillingCountryFullName() {
    if (Strings.isNullOrEmpty(billingCountry) || billingCountry.length() > 3) {
      // empty or not a country code
      return billingCountry;
    }
    CountryCode countryCode = CountryCode.getByCode(billingCountry);
    return countryCode.getName();
  }

  public String getBusinessCountryFullName() {
    if (Strings.isNullOrEmpty(businessCountry) || businessCountry.length() > 3) {
      // empty or not a country code
      return businessCountry;
    }
    CountryCode countryCode = CountryCode.getByCode(businessCountry);
    return countryCode.getName();
  }

  // Purposefully keep the tokens out of this!
  @Override
  public String toString() {
    return String.format("campaign=%s donation_type=%s donation_amount=%s donate_as_business=%s first_name=%s last_name=%s email=%s phone=%s business_name=%s business_email=%s business_address=%s business_address_2=%s business_city=%s business_state=%s business_zip_code=%s business_country=%s billing_address=%s billing_address_2=%s billing_city=%s billing_state=%s billing_zip=%s billing_country=%s referer=%s origin=%s", campaign, donationType, amount, donateAsBusiness, firstName, lastName, email, phone, businessName, businessEmail, businessAddress, businessAddress2, businessCity, businessState, businessZip, businessCountry, billingAddress, billingAddress2, billingCity, billingState, billingZip, billingCountry, referer, origin);
  }

  // TODO: REMOVE AFTER INITIAL LAUNCH TESTS
  public String toStringFull() {
    return String.format("campaign=%s donation_type=%s donation_amount=%s stripe_token=%s stripe_bank_account_token=%s donate_as_business=%s first_name=%s last_name=%s email=%s phone=%s business_name=%s business_email=%s business_address=%s business_address_2=%s business_city=%s business_state=%s business_zip_code=%s business_country=%s billing_address=%s billing_address_2=%s billing_city=%s billing_state=%s billing_zip=%s billing_country=%s referer=%s origin=%s", campaign, donationType, amount, stripeCCToken, stripeACHToken, donateAsBusiness, firstName, lastName, email, phone, businessName, businessEmail, businessAddress, businessAddress2, businessCity, businessState, businessZip, businessCountry, billingAddress, billingAddress2, billingCity, billingState, billingZip, billingCountry, referer, origin);
  }

  public String toStringEmail(Organization org) {
    StringBuilder sb = new StringBuilder();

    sb.append("Organization: " + org.getName() + "<br/>");
    sb.append("Amount: " + CURRENCY_FORMATTER.format(getAmount()) + "<br/>");
    if (!Strings.isNullOrEmpty(getCampaign())) {
      sb.append("Campaign: " + campaign + "<br/>");
    }
    if (isRecurring()) {
      sb.append("RECURRING: " + donationType + "<br/>");
    }
    sb.append("<br/>");

    sb.append("Name: " + firstName + " " + lastName + "<br/>");
    sb.append("Email: " + Strings.nullToEmpty(getEmail()) + "<br/>");
    sb.append("Phone: " + Strings.nullToEmpty(getPhone()) + "<br/>");
    sb.append("Billing Address: " + getFullBillingAddress() + "<br/>");
    sb.append("<br/>");

    if (isBusiness()) {
      sb.append("Business Name: " + businessName + "<br/>");
      sb.append("Business Email: " + Strings.nullToEmpty(getBusinessEmail()) + "<br/>");
      sb.append("Business Address: " + getFullBusinessAddress() + "<br/>");
    }

    sb.append("Notes: " + Strings.nullToEmpty(getNotes()));

    return sb.toString();
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // GENERATED GETTERS/SETTERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public String getCampaign() {
    return campaign;
  }

  public void setCampaign(String campaign) {
    this.campaign = campaign;
  }

  public String getDonationType() {
    return donationType;
  }

  public void setDonationType(String donationType) {
    this.donationType = donationType;
  }

  public Double getAmount() {
    return amount;
  }

  public void setAmount(Double amount) {
    this.amount = amount;
  }

  public String getDonateAsBusiness() {
    return donateAsBusiness;
  }

  public void setDonateAsBusiness(String donateAsBusiness) {
    this.donateAsBusiness = donateAsBusiness;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getBusinessName() {
    return businessName;
  }

  public void setBusinessName(String businessName) {
    this.businessName = businessName;
  }

  public String getBusinessEmail() {
    return businessEmail;
  }

  public void setBusinessEmail(String businessEmail) {
    this.businessEmail = businessEmail;
  }

  public String getBusinessAddress() {
    return businessAddress;
  }

  public void setBusinessAddress(String businessAddress) {
    this.businessAddress = businessAddress;
  }

  public String getBusinessAddress2() {
    return businessAddress2;
  }

  public void setBusinessAddress2(String businessAddress2) {
    this.businessAddress2 = businessAddress2;
  }

  public String getBusinessCity() {
    return businessCity;
  }

  public void setBusinessCity(String businessCity) {
    this.businessCity = businessCity;
  }

  public String getBusinessState() {
    return businessState;
  }

  public void setBusinessState(String businessState) {
    this.businessState = businessState;
  }

  public String getBusinessZip() {
    return businessZip;
  }

  public void setBusinessZip(String businessZip) {
    this.businessZip = businessZip;
  }

  public String getBusinessCountry() {
    return businessCountry;
  }

  public void setBusinessCountry(String businessCountry) {
    this.businessCountry = businessCountry;
  }

  public String getBillingAddress() {
    return billingAddress;
  }

  public void setBillingAddress(String billingAddress) {
    this.billingAddress = billingAddress;
  }

  public String getBillingAddress2() {
    return billingAddress2;
  }

  public void setBillingAddress2(String billingAddress2) {
    this.billingAddress2 = billingAddress2;
  }

  public String getBillingCity() {
    return billingCity;
  }

  public void setBillingCity(String billingCity) {
    this.billingCity = billingCity;
  }

  public String getBillingState() {
    return billingState;
  }

  public void setBillingState(String billingState) {
    this.billingState = billingState;
  }

  public String getBillingZip() {
    return billingZip;
  }

  public void setBillingZip(String billingZip) {
    this.billingZip = billingZip;
  }

  public String getBillingCountry() {
    return billingCountry;
  }

  public void setBillingCountry(String billingCountry) {
    this.billingCountry = billingCountry;
  }

  public String getStripeCCToken() {
    return stripeCCToken;
  }

  public void setStripeCCToken(String stripeCCToken) {
    this.stripeCCToken = stripeCCToken;
  }

  public String getStripeACHToken() {
    return stripeACHToken;
  }

  public void setStripeACHToken(String stripeACHToken) {
    this.stripeACHToken = stripeACHToken;
  }

  public String getReferer() {
    return referer;
  }

  public void setReferer(String referer) {
    this.referer = referer;
  }

  public String getOrigin() {
    return origin;
  }

  public void setOrigin(String origin) {
    this.origin = origin;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public Calendar getTimestamp() {
    return timestamp;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
