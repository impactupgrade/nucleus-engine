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
  private String contactEmail;
  private String opportunityId;
  
  private String ownerId;

  private String accountBillingStreet;
  private String accountBillingCity;
  private String accountBillingState;
  private String accountBillingZip;
  private String accountBillingCountry;

  private String contactFirstName;
  private String contactHomePhone;
  private String contactLastName;
  private String contactMobilePhone;
  private String contactMailingStreet;
  private String contactMailingCity;
  private String contactMailingState;
  private String contactMailingZip;
  private String contactMailingCountry;
  private Boolean contactOptInEmail;
  private Boolean contactOptInSms;

  private BigDecimal opportunityAmount;
  private String opportunityCampaignExternalRef;
  private String opportunityCampaignId;
  private Calendar opportunityDate;
  private String opportunityDescription;
  private String opportunityName;
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
    updateEvent.contactEmail = data.get("Contact Email");
    updateEvent.opportunityId = data.get("Opportunity ID");
    
    updateEvent.ownerId = data.get("Owner ID");

    updateEvent.accountBillingStreet = data.get("Account Billing Address");
    updateEvent.accountBillingCity = data.get("Account Billing City");
    updateEvent.accountBillingState = data.get("Account Billing State");
    updateEvent.accountBillingZip = data.get("Account Billing PostCode");
    updateEvent.accountBillingCountry = data.get("Account Billing Country");

    updateEvent.contactFirstName = data.get("Contact First Name");
    updateEvent.contactHomePhone = data.get("Contact Home Phone");
    updateEvent.contactLastName = data.get("Contact Last Name");
    updateEvent.contactMobilePhone = data.get("Contact Mobile Phone");
    updateEvent.contactMailingStreet = data.get("Contact Mailing Address");
    updateEvent.contactMailingCity = data.get("Contact Mailing City");
    updateEvent.contactMailingState = data.get("Contact Mailing State");
    updateEvent.contactMailingZip = data.get("Contact Mailing PostCode");
    updateEvent.contactMailingCountry = data.get("Contact Mailing Country");

    updateEvent.contactOptInEmail = "yes".equalsIgnoreCase(data.get("Contact Email Opt In")) || "true".equalsIgnoreCase(data.get("Contact Email Opt In"));
    updateEvent.contactOptInSms = "yes".equalsIgnoreCase(data.get("Contact SMS Opt In")) || "true".equalsIgnoreCase(data.get("Contact SMS Opt In"));

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

    updateEvent.opportunityAmount = getAmount(data, "Opportunity Amount");
    updateEvent.opportunityCampaignExternalRef = data.get("Opportunity External Campaign Ref");
    updateEvent.opportunityCampaignId = data.get("Opportunity Campaign ID");
    updateEvent.opportunityDescription = data.get("Opportunity Description");
    updateEvent.opportunityName = data.get("Opportunity Name");
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

  public Map<String, String> getRaw() {
    return raw;
  }

  public String getAccountId() {
    return accountId;
  }

  public String getContactId() {
    return contactId;
  }

  public String getContactEmail() {
    return contactEmail;
  }

  public String getOpportunityId() {
    return opportunityId;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public String getAccountBillingStreet() {
    return accountBillingStreet;
  }

  public String getAccountBillingCity() {
    return accountBillingCity;
  }

  public String getAccountBillingState() {
    return accountBillingState;
  }

  public String getAccountBillingZip() {
    return accountBillingZip;
  }

  public String getAccountBillingCountry() {
    return accountBillingCountry;
  }

  public String getContactFirstName() {
    return contactFirstName;
  }

  public String getContactHomePhone() {
    return contactHomePhone;
  }

  public String getContactLastName() {
    return contactLastName;
  }

  public String getContactMobilePhone() {
    return contactMobilePhone;
  }

  public String getContactMailingStreet() {
    return contactMailingStreet;
  }

  public String getContactMailingCity() {
    return contactMailingCity;
  }

  public String getContactMailingState() {
    return contactMailingState;
  }

  public String getContactMailingZip() {
    return contactMailingZip;
  }

  public String getContactMailingCountry() {
    return contactMailingCountry;
  }

  public Boolean getContactOptInEmail() {
    return contactOptInEmail;
  }

  public Boolean getContactOptInSms() {
    return contactOptInSms;
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

  public String getOpportunityRecordTypeId() {
    return opportunityRecordTypeId;
  }

  public String getOpportunityRecordTypeName() {
    return opportunityRecordTypeName;
  }

  public String getOpportunityStageName() {
    return opportunityStageName;
  }
}
