/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;
import com.neovisionaries.i18n.CountryCode;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.FormParam;
import java.text.DecimalFormat;
import java.util.Calendar;

public class DonationFormData {

  private static final Logger log = LogManager.getLogger(DonationFormData.class);

  // TODO: Business Donations coming soon.

  @FormParam("campaign_id") private String campaignId;
  @FormParam("frequency") private String frequency; // onetime, monthly, quarterly, yearly
  @FormParam("donation_amount") private Double amount;
//  @FormParam("giving_as") private String givingAs;
  @FormParam("first_name") private String firstName;
  @FormParam("last_name") private String lastName;
  @FormParam("email") private String email;
  @FormParam("phone") private String phone;
//  @FormParam("business_name") private String businessName;
//  @FormParam("business_email") private String businessEmail;
//  @FormParam("business_address") private String businessAddress;
//  @FormParam("business_address_2") private String businessAddress2;
//  @FormParam("business_city") private String businessCity;
//  @FormParam("business_state") private String businessState;
//  @FormParam("business_zip_code") private String businessZip;
//  @FormParam("business_country") private String businessCountry;
  @FormParam("billing_address") private String billingAddress;
  @FormParam("billing_address_2") private String billingAddress2;
  @FormParam("billing_city") private String billingCity;
  @FormParam("billing_state") private String billingState;
  @FormParam("billing_zip_code") private String billingZip;
  @FormParam("billing_country") private String billingCountry;
  // TODO: We should switch to a "payment gateway name" field and generic tokens.
  @FormParam("stripe_token") private String stripeCCToken;
  @FormParam("stripe_bank_account_token") private String stripeACHToken;
  @FormParam("donation_note") private String notes;
  // although we fall back to env.json defaults, allow explicit overrides
  @FormParam("currency") private String currency;

  @FormParam("g-recaptcha-response") private String recaptchaToken;

  private final Calendar timestamp;

  // Helpers (transient) set as we process the donation
  private String crmOrganizationAccountId = null;
  private String crmAccountId = null;
  private String crmContactId = null;

  // Switch, purely for integration tests :(
  private boolean integrationTest = false;

  public DonationFormData() {
    timestamp = Calendar.getInstance();
  }

  // TODO: Move to AntiFraudService?
  public boolean isFraudAttempt() {
    if (!isStripe() && !isIntegrationTest()) {
      // We're getting some bots sending the form with no payment details.
      log.info("blocking a bad request: no payment details");
      return true;
    } else if (Strings.isNullOrEmpty(firstName) || Strings.isNullOrEmpty(lastName)
        // Real creative.
        || firstName.contains("<first>") || firstName.contains("<First>")
        || lastName.contains("<last>") || lastName.contains("<Last>")) {
      log.info("blocking a bad request: name");
      return true;
    } else if (Strings.isNullOrEmpty(email) || !EmailValidator.getInstance().isValid(email) || email.contains("example.com")) {
      log.info("blocking a bad request: email");
      return true;
    // Note: City is not required since it's technically "Suburb" for AU/NZ, which is optional.
    } else if (Strings.isNullOrEmpty(billingAddress)
        || Strings.isNullOrEmpty(billingState) || Strings.isNullOrEmpty(billingZip)
        // Real creative.
        || billingAddress.contains("<street>") || billingAddress.contains("<Street>")) {
      log.info("blocking a bad request: address");
      return true;
    }

    return false;
  }

//  public boolean isBusiness() {
//    return "business".equalsIgnoreCase(givingAs);
//  }

  public boolean isRecurring() {
    return !"onetime".equalsIgnoreCase(frequency);
  }

  public String getAmountFormatted() {
    return new DecimalFormat("#.##").format(amount);
  }

  public long getAmountInCents() {
    return (long) (amount * 100);
  }

  public boolean isStripe() {
    return !Strings.isNullOrEmpty(getStripeToken());
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
    return /*isBusiness() ? businessName : */firstName + " " + lastName;
  }

  public String getCustomerEmail() {
    return /*isBusiness() ? businessEmail : */email;
  }

  public String getFullBillingAddress() {
    String fullBillingAddress = billingAddress;
    if (!Strings.isNullOrEmpty(billingAddress2)) {
      fullBillingAddress += ", " + billingAddress2;
    }
    return fullBillingAddress;
  }

//  public String getFullBusinessAddress() {
//    String fullBusinessAddress = businessAddress;
//    if (!Strings.isNullOrEmpty(businessAddress2)) {
//      fullBusinessAddress += ", " + businessAddress2;
//    }
//    return fullBusinessAddress;
//  }

  public String getBillingCountryFullName() {
    if (Strings.isNullOrEmpty(billingCountry) || billingCountry.length() > 3) {
      // empty or not a country code
      return billingCountry;
    }
    CountryCode countryCode = CountryCode.getByCode(billingCountry);
    return countryCode.getName();
  }

//  public String getBusinessCountryFullName() {
//    if (Strings.isNullOrEmpty(businessCountry) || businessCountry.length() > 3) {
//      // empty or not a country code
//      return businessCountry;
//    }
//    CountryCode countryCode = CountryCode.getByCode(businessCountry);
//    return countryCode.getName();
//  }

  // generate, but keep out Stripe tokens and bank numbers
  @Override
  public String toString() {
    return "DonationFormData{" +
        "campaignId='" + campaignId + '\'' +
        ", frequency='" + frequency + '\'' +
        ", amount=" + amount +
//        ", givingAs='" + givingAs + '\'' +
        ", firstName='" + firstName + '\'' +
        ", lastName='" + lastName + '\'' +
        ", email='" + email + '\'' +
        ", phone='" + phone + '\'' +
//        ", businessName='" + businessName + '\'' +
//        ", businessEmail='" + businessEmail + '\'' +
//        ", businessAddress='" + businessAddress + '\'' +
//        ", businessAddress2='" + businessAddress2 + '\'' +
//        ", businessCity='" + businessCity + '\'' +
//        ", businessState='" + businessState + '\'' +
//        ", businessZip='" + businessZip + '\'' +
//        ", businessCountry='" + businessCountry + '\'' +
        ", billingAddress='" + billingAddress + '\'' +
        ", billingAddress2='" + billingAddress2 + '\'' +
        ", billingCity='" + billingCity + '\'' +
        ", billingState='" + billingState + '\'' +
        ", billingZip='" + billingZip + '\'' +
        ", billingCountry='" + billingCountry + '\'' +
        ", notes='" + notes + '\'' +
        ", currency='" + currency + '\'' +
        ", timestamp=" + timestamp +
        '}';
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // GENERATED GETTERS/SETTERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public String getCampaignId() {
    return campaignId;
  }

  public void setCampaignId(String campaignId) {
    this.campaignId = campaignId;
  }

  public String getFrequency() {
    return frequency;
  }

  public void setFrequency(String frequency) {
    this.frequency = frequency;
  }

  public Double getAmount() {
    return amount;
  }

  public void setAmount(Double amount) {
    this.amount = amount;
  }

//  public String getGivingAs() {
//    return givingAs;
//  }
//
//  public void setGivingAs(String givingAs) {
//    this.givingAs = givingAs;
//  }

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

//  public String getBusinessName() {
//    return businessName;
//  }
//
//  public void setBusinessName(String businessName) {
//    this.businessName = businessName;
//  }
//
//  public String getBusinessEmail() {
//    return businessEmail;
//  }
//
//  public void setBusinessEmail(String businessEmail) {
//    this.businessEmail = businessEmail;
//  }
//
//  public String getBusinessAddress() {
//    return businessAddress;
//  }
//
//  public void setBusinessAddress(String businessAddress) {
//    this.businessAddress = businessAddress;
//  }
//
//  public String getBusinessAddress2() {
//    return businessAddress2;
//  }
//
//  public void setBusinessAddress2(String businessAddress2) {
//    this.businessAddress2 = businessAddress2;
//  }
//
//  public String getBusinessCity() {
//    return businessCity;
//  }
//
//  public void setBusinessCity(String businessCity) {
//    this.businessCity = businessCity;
//  }
//
//  public String getBusinessState() {
//    return businessState;
//  }
//
//  public void setBusinessState(String businessState) {
//    this.businessState = businessState;
//  }
//
//  public String getBusinessZip() {
//    return businessZip;
//  }
//
//  public void setBusinessZip(String businessZip) {
//    this.businessZip = businessZip;
//  }
//
//  public String getBusinessCountry() {
//    return businessCountry;
//  }
//
//  public void setBusinessCountry(String businessCountry) {
//    this.businessCountry = businessCountry;
//  }

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

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getRecaptchaToken() {
    return recaptchaToken;
  }

  public void setRecaptchaToken(String recaptchaToken) {
    this.recaptchaToken = recaptchaToken;
  }

  public Calendar getTimestamp() {
    return timestamp;
  }

  public String getCrmOrganizationAccountId() {
    return crmOrganizationAccountId;
  }

  public void setCrmOrganizationAccountId(String crmOrganizationAccountId) {
    this.crmOrganizationAccountId = crmOrganizationAccountId;
  }

  public String getCrmAccountId() {
    return crmAccountId;
  }

  public void setCrmAccountId(String crmAccountId) {
    this.crmAccountId = crmAccountId;
  }

  public String getCrmContactId() {
    return crmContactId;
  }

  public void setCrmContactId(String crmContactId) {
    this.crmContactId = crmContactId;
  }

  public boolean isIntegrationTest() {
    return integrationTest;
  }

  public void setIntegrationTest(boolean integrationTest) {
    this.integrationTest = integrationTest;
  }
}
