/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.Utils;
import com.stripe.util.CaseInsensitiveMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class CrmImportEvent {

  private static final Logger log = LogManager.getLogger(CrmImportEvent.class.getName());

  protected final Environment env;

  // Be case-insensitive, for sources that aren't always consistent.
  private final CaseInsensitiveMap<String> raw;

  // For updates only, used for retrieval.
  private String accountId;
  private String contactId;
  private String opportunityId;

  // Can also be used for update retrieval, as well as inserts.
  private String contactEmail;
  
  private String ownerId;

  private String accountBillingStreet;
  private String accountBillingCity;
  private String accountBillingState;
  private String accountBillingZip;
  private String accountBillingCountry;
  private String accountRecordTypeId;
  private String accountRecordTypeName;

  private String contactCampaignId;
  private String contactCampaignName;
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
  private String contactRecordTypeId;
  private String contactRecordTypeName;

  private BigDecimal opportunityAmount;
  private String opportunityCampaignId;
  private String opportunityCampaignName;
  private Calendar opportunityDate;
  private String opportunityDescription;
  private String opportunityName;
  private String opportunityRecordTypeId;
  private String opportunityRecordTypeName;
  private String opportunitySource;
  private String opportunityStageName;
  private String opportunityTerminal;

  public CrmImportEvent(CaseInsensitiveMap<String> raw, Environment env) {
    this.raw = raw;
    this.env = env;
  }

  public static List<CrmImportEvent> fromGeneric(List<Map<String, String>> data, Environment env) {
    return data.stream().map(d -> fromGeneric(d, env)).collect(Collectors.toList());
  }

  public static CrmImportEvent fromGeneric(Map<String, String> _data, Environment env) {
    // Be case-insensitive, for sources that aren't always consistent.
    CaseInsensitiveMap<String> data = CaseInsensitiveMap.of(_data);

    CrmImportEvent importEvent = new CrmImportEvent(data, env);

    importEvent.accountId = data.get("Account ID");
    importEvent.contactId = data.get("Contact ID");
    importEvent.opportunityId = data.get("Opportunity ID");

    importEvent.contactEmail = data.get("Contact Email");
    if (importEvent.contactEmail != null) {
      // TODO: SFDC "where in ()" queries appear to be case sensitive, and SFDC lower cases all emails internally.
      //  For now, ensure we follow suit.
      importEvent.contactEmail = importEvent.contactEmail.toLowerCase(Locale.ROOT);
    }

    importEvent.ownerId = data.get("Owner ID");

    importEvent.accountBillingStreet = data.get("Account Billing Address");
    if (!Strings.isNullOrEmpty(data.get("Account Billing Address 2"))) {
      importEvent.accountBillingStreet += ", " + data.get("Account Billing Address 2");
    }
    importEvent.accountBillingCity = data.get("Account Billing City");
    importEvent.accountBillingState = data.get("Account Billing State");
    importEvent.accountBillingZip = data.get("Account Billing PostCode");
    importEvent.accountBillingCountry = data.get("Account Billing Country");
    importEvent.accountRecordTypeId = data.get("Account Record Type ID");
    importEvent.accountRecordTypeName = data.get("Account Record Type Name");

    importEvent.contactCampaignId = data.get("Contact Campaign ID");
    importEvent.contactCampaignName = data.get("Contact Campaign Name");
    importEvent.contactFirstName = data.get("Contact First Name");
    importEvent.contactHomePhone = data.get("Contact Home Phone");
    importEvent.contactLastName = data.get("Contact Last Name");
    importEvent.contactMobilePhone = data.get("Contact Mobile Phone");
    importEvent.contactMailingStreet = data.get("Contact Mailing Address");
    if (!Strings.isNullOrEmpty(data.get("Contact Mailing Address 2"))) {
      importEvent.contactMailingStreet += ", " + data.get("Contact Mailing Address 2");
    }
    importEvent.contactMailingCity = data.get("Contact Mailing City");
    importEvent.contactMailingState = data.get("Contact Mailing State");
    importEvent.contactMailingZip = data.get("Contact Mailing PostCode");
    importEvent.contactMailingCountry = data.get("Contact Mailing Country");
    importEvent.contactOptInEmail = "yes".equalsIgnoreCase(data.get("Contact Email Opt In")) || "true".equalsIgnoreCase(data.get("Contact Email Opt In")) || "1".equalsIgnoreCase(data.get("Contact Email Opt In"));
    importEvent.contactOptInSms = "yes".equalsIgnoreCase(data.get("Contact SMS Opt In")) || "true".equalsIgnoreCase(data.get("Contact SMS Opt In")) || "1".equalsIgnoreCase(data.get("Contact SMS Opt In"));
    importEvent.contactRecordTypeId = data.get("Contact Record Type ID");
    importEvent.contactRecordTypeName = data.get("Contact Record Type Name");

    // TODO: Hate this code -- is there a lib that can handle it in a forgiving way?
    importEvent.opportunityDate = Calendar.getInstance();
    try {
      if (data.containsKey("Opportunity Date dd/mm/yyyy")) {
        importEvent.opportunityDate.setTime(new SimpleDateFormat("dd/MM/yyyy").parse(data.get("Opportunity Date dd/mm/yyyy")));
      } else if (data.containsKey("Opportunity Date dd-mm-yyyy")) {
        importEvent.opportunityDate.setTime(new SimpleDateFormat("dd-MM-yyyy").parse(data.get("Opportunity Date dd-mm-yyyy")));
      } else if (data.containsKey("Opportunity Date mm/dd/yyyy")) {
        importEvent.opportunityDate.setTime(new SimpleDateFormat("MM/dd/yyyy").parse(data.get("Opportunity Date mm/dd/yyyy")));
      } else if (data.containsKey("Opportunity Date mm-dd-yyyy")) {
        importEvent.opportunityDate.setTime(new SimpleDateFormat("MM-dd-yyyy").parse(data.get("Opportunity Date mm-dd-yyyy")));
      } else if (data.containsKey("Opportunity Date yyyy-mm-dd")) {
        importEvent.opportunityDate.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(data.get("Opportunity Date yyyy-mm-dd")));
      }
    } catch (ParseException e) {
      log.warn("failed to parse date", e);
    }

    importEvent.opportunityAmount = getAmount(data, "Opportunity Amount");
    importEvent.opportunityCampaignId = data.get("Opportunity Campaign ID");
    importEvent.opportunityCampaignName = data.get("Opportunity Campaign Name");
    importEvent.opportunityDescription = data.get("Opportunity Description");
    importEvent.opportunityName = data.get("Opportunity Name");
    importEvent.opportunityRecordTypeId = data.get("Opportunity Record Type ID");
    importEvent.opportunityRecordTypeName = data.get("Opportunity Record Type Name");
    importEvent.opportunityStageName = data.get("Opportunity Stage Name");


    return importEvent;
  }

  public static List<CrmImportEvent> fromFBFundraiser(List<Map<String, String>> data, Environment env) {
    return data.stream().map(d -> fromFBFundraiser(d, env)).collect(Collectors.toList());
  }

  public static CrmImportEvent fromFBFundraiser(Map<String, String> _data, Environment env) {
    // Be case-insensitive, for sources that aren't always consistent.
    CaseInsensitiveMap<String> data = CaseInsensitiveMap.of(_data);

//  TODO: 'S' means a standard charge, but will likely need to eventually support other types like refunds, etc.
    if (data.get("Charge Action Type").equalsIgnoreCase("S")) {
      CrmImportEvent importEvent = new CrmImportEvent(data, env);

//    TODO: support for initial amount, any fees, and net amount
//    importEvent. = data.get("Donation Amount");
//    importEvent. = data.get("FB Fee");
//    importEvent. = getAmount(data, "Net Payout Amount");
      importEvent.opportunityAmount = getAmount(data, "Donation Amount");

//      TODO: support for different currencies will likely be needed in the future
//      importEvent. = data.get("Payout Currency");
//      importEvent. = data.get("Sender Currency");
      if (data.get("Fundraiser Type").contains("Fundraiser")) {
        importEvent.opportunityName = "Facebook Fundraiser: " + data.get("Fundraiser Title");
      } else if (!Strings.isNullOrEmpty(data.get("Fundraiser Title"))) {
        importEvent.opportunityName = "Facebook Fundraiser: " + data.get("Fundraiser Title") + " (" + data.get("Fundraiser Type") + ")";
      } else {
        importEvent.opportunityName = "Facebook Fundraiser: " + data.get("Fundraiser Type");
      }
      try {
        importEvent.opportunityDate = Calendar.getInstance();
        importEvent.opportunityDate.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(data.get("Charge Date")));
      } catch (ParseException e) {
        log.warn("failed to parse date", e);
      }

      importEvent.contactFirstName = Utils.nameToTitleCase(data.get("First Name"));
      importEvent.contactLastName = Utils.nameToTitleCase(data.get("Last Name"));
      importEvent.contactEmail = data.get("Email Address");
      importEvent.opportunitySource = (!Strings.isNullOrEmpty(data.get("Fundraiser Title"))) ? data.get("Fundraiser Title") : data.get("Fundraiser Type");
      importEvent.opportunityTerminal = data.get("Payment Processor");
      importEvent.opportunityStageName = "Posted";

      if (data.containsKey("CRM Campaign ID")) {
        importEvent.opportunityCampaignId = data.get("CRM Campaign ID");
      }

      List<String> description = new ArrayList<>();
      description.add("Fundraiser Title: " + data.get("Fundraiser Title"));
      description.add("Fundraiser Type: " + data.get("Fundraiser Type"));
      description.add("Campaign Owner Name: " + data.get("Campaign Owner Name"));
      // Depending on the context, Campaign ID might be the CRM, but it might be vendor-specific (ie, Facebook)
      description.add("Campaign ID: " + data.get("Campaign ID"));
      description.add("CRM Campaign ID: " + data.get("CRM Campaign ID"));
      description.add("Permalink: " + data.get("Permalink"));
      description.add("Payment ID: " + data.get("Payment ID"));
      description.add("Source Name: " + data.get("Source Name"));
      importEvent.opportunityDescription = Joiner.on("\n").join(description);

      return importEvent;
    } else {
      return null;
    }
  }

  private static BigDecimal getAmount(CaseInsensitiveMap<String> data, String columnName) {
    if (!data.containsKey(columnName)) {
      return null;
    }
    return new BigDecimal(data.get(columnName).replace("$", "").replace(",", "")
        .trim()).setScale(2, RoundingMode.CEILING);
  }

  public CaseInsensitiveMap<String> getRaw() {
    return raw;
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

  public String getContactEmail() {
    return contactEmail;
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

  public String getAccountRecordTypeId() {
    return accountRecordTypeId;
  }

  public String getAccountRecordTypeName() {
    return accountRecordTypeName;
  }

  public String getContactCampaignId() {
    return contactCampaignId;
  }

  public String getContactCampaignName() {
    return contactCampaignName;
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

  public String getContactRecordTypeId() {
    return contactRecordTypeId;
  }

  public String getContactRecordTypeName() {
    return contactRecordTypeName;
  }

  public BigDecimal getOpportunityAmount() {
    return opportunityAmount;
  }

  public String getOpportunityCampaignId() {
    return opportunityCampaignId;
  }

  public String getOpportunityCampaignName() {
    return opportunityCampaignName;
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

  public String getOpportunitySource() {
    return opportunitySource;
  }

  public String getOpportunityStageName() {
    return opportunityStageName;
  }

  public String getOpportunityTerminal() {
    return opportunityTerminal;
  }
}
