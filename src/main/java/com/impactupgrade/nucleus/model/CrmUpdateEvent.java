/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// TODO: Duplicates a lot of CrmImportEvent. If another instance comes up, create a superclass.
public class CrmUpdateEvent {

  private static final Logger log = LogManager.getLogger(CrmUpdateEvent.class.getName());

  protected final Environment env;

  private final Map<String, String> raw;

  private String accountId;
  private String contactId;
  private String opportunityId;

  private String email;
  private String firstName;
  private String homePhone;
  private String lastName;
  private String mobilePhone;
  private String ownerId;

  private String billingStreet;
  private String billingCity;
  private String billingState;
  private String billingZip;
  private String billingCountry;

  private String mailingStreet;
  private String mailingCity;
  private String mailingState;
  private String mailingZip;
  private String mailingCountry;

  private Boolean optInEmail;
  private Boolean optInSms;

  private BigDecimal opportunityAmount;
  private String opportunityCampaignExternalRef;
  private String opportunityCampaignId;
  private Calendar opportunityDate;
  private String opportunityDescription;
  private String opportunityName;
  private String opportunityOwnerId;
  private String opportunityRecordTypeId;
  private String opportunityRecordTypeName;
  private String opportunityStageName;

  public CrmUpdateEvent(Map<String, String> raw, Environment env) {
    this.raw = raw;
    this.env = env;
  }

  public static List<CrmUpdateEvent> fromGeneric(List<Map<String, String>> data, Environment env) {
    return data.stream().map(d -> fromGeneric(d, env)).collect(Collectors.toList());
  }

  public static CrmUpdateEvent fromGeneric(Map<String, String> data, Environment env) {
    CrmUpdateEvent updateEvent = new CrmUpdateEvent(data, env);

    updateEvent.accountId = data.get("Account ID");
    updateEvent.contactId = data.get("Contact ID");
    updateEvent.opportunityId = data.get("Opportunity ID");

    updateEvent.email = data.get("Email");
    updateEvent.firstName = data.get("First Name");
    updateEvent.homePhone = data.get("Home Phone");
    updateEvent.lastName = data.get("Last Name");
    updateEvent.mobilePhone = data.get("Mobile Phone");
    updateEvent.opportunityAmount = getAmount(data, "Opportunity Amount");
    updateEvent.ownerId = data.get("Owner ID");

    updateEvent.billingStreet = data.get("Billing Address");
    updateEvent.billingCity = data.get("Billing City");
    updateEvent.billingState = data.get("Billing State");
    updateEvent.billingZip = data.get("Billing PostCode");
    updateEvent.billingCountry = data.get("Billing Country");

    updateEvent.mailingStreet = data.get("Mailing Address");
    updateEvent.mailingCity = data.get("Mailing City");
    updateEvent.mailingState = data.get("Mailing State");
    updateEvent.mailingZip = data.get("Mailing PostCode");
    updateEvent.mailingCountry = data.get("Mailing Country");

    updateEvent.optInEmail = "yes".equalsIgnoreCase(data.get("Email Opt In")) || "true".equalsIgnoreCase(data.get("Email Opt In"));
    updateEvent.optInSms = "yes".equalsIgnoreCase(data.get("SMS Opt In")) || "true".equalsIgnoreCase(data.get("SMS Opt In"));

    updateEvent.opportunityDate = Calendar.getInstance();
    try {
      if (data.containsKey("Opportunity Date dd/mm/yyyy")) {
        updateEvent.opportunityDate.setTime(new SimpleDateFormat("dd/MM/yyyy").parse(data.get("Opportunity Date dd/mm/yyyy")));
      } else if (data.containsKey("Opportunity Date dd-mm-yyyy")) {
        updateEvent.opportunityDate.setTime(new SimpleDateFormat("dd-MM-yyyy").parse(data.get("Opportunity Date dd-mm-yyyy")));
      } else if (data.containsKey("Opportunity Date mm/dd/yyyy")) {
        updateEvent.opportunityDate.setTime(new SimpleDateFormat("MM/dd/yyyy").parse(data.get("Opportunity Date mm/dd/yyyy")));
      } else if (data.containsKey("Opportunity Date mm-dd-yyyy")) {
        updateEvent.opportunityDate.setTime(new SimpleDateFormat("MM-dd-yyyy").parse(data.get("Opportunity Date mm-dd-yyyy")));
      }
    } catch (ParseException e) {
      log.warn("failed to parse date", e);
    }

    updateEvent.opportunityCampaignExternalRef = data.get("External Campaign Ref");
    updateEvent.opportunityCampaignId = data.get("Campaign ID");
    updateEvent.opportunityDescription = data.get("Opportunity Description");
    updateEvent.opportunityName = data.get("Opportunity Name");
    updateEvent.opportunityOwnerId = data.get("Owner ID");
    updateEvent.opportunityRecordTypeId = data.get("Opportunity Record Type ID");
    updateEvent.opportunityRecordTypeName = data.get("Opportunity Record Type Name");
    updateEvent.opportunityStageName = data.get("Opportunity Stage Name");

    return updateEvent;
  }

  private static BigDecimal getAmount(Map<String, String> data, String columnName) {
    if (!data.containsKey(columnName)) {
      return null;
    }
    return new BigDecimal(data.get(columnName).replace("$", "").replace(",", "")
        .trim()).setScale(2, RoundingMode.CEILING);
  }

  public String getAccountId() {
    return accountId;
  }

  public String getContactId() {
    return contactId;
  }

  public String getOpportunityId() {
    return opportunityId;
  }

  public String getEmail() {
    return email;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getHomePhone() {
    return homePhone;
  }

  public String getLastName() {
    return lastName;
  }

  public String getMobilePhone() {
    return mobilePhone;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public String getBillingStreet() {
    return billingStreet;
  }

  public String getBillingCity() {
    return billingCity;
  }

  public String getBillingState() {
    return billingState;
  }

  public String getBillingZip() {
    return billingZip;
  }

  public String getBillingCountry() {
    return billingCountry;
  }

  public String getMailingStreet() {
    return mailingStreet;
  }

  public String getMailingCity() {
    return mailingCity;
  }

  public String getMailingState() {
    return mailingState;
  }

  public String getMailingZip() {
    return mailingZip;
  }

  public String getMailingCountry() {
    return mailingCountry;
  }

  public Boolean isOptInEmail() {
    return optInEmail;
  }

  public Boolean isOptInSms() {
    return optInSms;
  }

  public BigDecimal getOpportunityAmount() {
    return opportunityAmount;
  }

  public String getOpportunityCampaignExternalRef() {
    return opportunityCampaignExternalRef;
  }

  public String getOpportunityCampaignId() {
    return opportunityCampaignId;
  }

  public Calendar getOpportunityDate() {
    return opportunityDate;
  }

  public String getOpportunityDescription() {
    return opportunityDescription;
  }

  public String getOpportunityName() {
    return opportunityName;
  }

  public String getOpportunityOwnerId() {
    return opportunityOwnerId;
  }

  public String getOpportunityRecordTypeId() {
    return opportunityRecordTypeId;
  }

  public String getOpportunityRecordTypeName() {
    return opportunityRecordTypeName;
  }

  public String getOpportunityStageName() {
    return opportunityStageName;
  }

  public Map<String, String> getRaw() {
    return raw;
  }
}
