/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
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
import java.util.Objects;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.Utils.checkboxToBool;
import static com.impactupgrade.nucleus.util.Utils.fullNameToFirstLast;

public class CrmImportEvent {

  private static final Logger log = LogManager.getLogger(CrmImportEvent.class.getName());

  // Be case-insensitive, for sources that aren't always consistent.
  public CaseInsensitiveMap<String> raw = new CaseInsensitiveMap<>();

  // For updates only, used for retrieval.
  public String accountId;
  public String contactId;
  public String opportunityId;
  public String recurringDonationId;
  public String campaignId;

  // Can also be used for update retrieval, as well as inserts.
  public String contactEmail;
  
  public String accountBillingStreet;
  public String accountBillingCity;
  public String accountBillingState;
  public String accountBillingZip;
  public String accountBillingCountry;
  public String accountName;
  public String accountOwnerId;
  public String accountRecordTypeId;
  public String accountRecordTypeName;

  public String contactCampaignId;
  public String contactCampaignName;
  public String contactFirstName;
  public String contactFullName;
  public String contactHomePhone;
  public String contactLastName;
  public String contactMobilePhone;
  public String contactMailingStreet;
  public String contactMailingCity;
  public String contactMailingState;
  public String contactMailingZip;
  public String contactMailingCountry;
  public Boolean contactOptInEmail;
  public Boolean contactOptOutEmail;
  public Boolean contactOptInSms;
  public Boolean contactOptOutSms;
  public String contactOwnerId;
  public String contactPreferredPhone;
  public String contactRecordTypeId;
  public String contactRecordTypeName;
  public String contactWorkPhone;

  public BigDecimal opportunityAmount;
  public String opportunityCampaignId;
  public String opportunityCampaignName;
  public Calendar opportunityDate;
  public String opportunityDescription;
  public String opportunityName;
  public String opportunityOwnerId;
  public String opportunityRecordTypeId;
  public String opportunityRecordTypeName;
  public String opportunitySource;
  public String opportunityStageName;
  public String opportunityTerminal;

  public BigDecimal recurringDonationAmount;
  public String recurringDonationCampaignId;
  public String recurringDonationCampaignName;
  public String recurringDonationInterval;
  public String recurringDonationName;
  public Calendar recurringDonationNextPaymentDate;
  public String recurringDonationOwnerId;
  public Calendar recurringDonationStartDate;
  public String recurringDonationStatus;

  public String campaignName;
  // TODO: In the future, could add OOTB support for dates, etc. but need to see this play out.

  // TODO: Add this to the Portal task. But for now, defaulting it to false out of caution.
  public Boolean opportunitySkipDuplicateCheck = false;

  public boolean secondPass = false;

  public String contactFullName() {
    if (!Strings.isNullOrEmpty(contactFullName)) {
      return contactFullName;
    }
    // trim in case one side or the other is blank
    return (contactFirstName + " " + contactLastName).trim();
  }

  public static List<CrmImportEvent> fromGeneric(List<Map<String, String>> data) {
    return data.stream().map(CrmImportEvent::fromGeneric).collect(Collectors.toList());
  }

  public static CrmImportEvent fromGeneric(Map<String, String> _data) {
    // Be case-insensitive, for sources that aren't always consistent.
    CaseInsensitiveMap<String> data = CaseInsensitiveMap.of(_data);

    CrmImportEvent importEvent = new CrmImportEvent();
    importEvent.raw = data;

    importEvent.accountId = data.get("Account ID");
    importEvent.contactId = data.get("Contact ID");
    importEvent.opportunityId = data.get("Opportunity ID");
    importEvent.recurringDonationId = data.get("Recurring Donation ID");
    importEvent.campaignId = data.get("Campaign ID");

    importEvent.contactEmail = data.get("Contact Email");
    if (importEvent.contactEmail != null) {
      // TODO: SFDC "where in ()" queries appear to be case sensitive, and SFDC lower cases all emails internally.
      //  For now, ensure we follow suit.
      importEvent.contactEmail = importEvent.contactEmail.toLowerCase(Locale.ROOT);
    }

    importEvent.accountBillingStreet = data.get("Account Billing Address");
    if (!Strings.isNullOrEmpty(data.get("Account Billing Address 2"))) {
      importEvent.accountBillingStreet += ", " + data.get("Account Billing Address 2");
    }
    importEvent.accountBillingCity = data.get("Account Billing City");
    importEvent.accountBillingState = data.get("Account Billing State");
    importEvent.accountBillingZip = data.get("Account Billing PostCode");
    importEvent.accountBillingCountry = data.get("Account Billing Country");
    importEvent.accountName = data.get("Account Name");
    importEvent.accountOwnerId = data.get("Account Owner ID");
    importEvent.accountRecordTypeId = data.get("Account Record Type ID");
    importEvent.accountRecordTypeName = data.get("Account Record Type Name");

    importEvent.contactFirstName = data.get("Contact First Name");
    importEvent.contactLastName = data.get("Contact Last Name");
    importEvent.contactFullName = data.get("Contact Full Name");
    if (Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactFullName)) {
      String[] split = fullNameToFirstLast(importEvent.contactFullName);
      importEvent.contactFirstName = split[0];
      importEvent.contactLastName = split[1];
    }

    importEvent.contactCampaignId = data.get("Contact Campaign ID");
    importEvent.contactCampaignName = data.get("Contact Campaign Name");
    importEvent.contactHomePhone = data.get("Contact Home Phone");
    importEvent.contactMobilePhone = data.get("Contact Mobile Phone");
    importEvent.contactWorkPhone = data.get("Contact Work Phone");
    importEvent.contactPreferredPhone = data.get("Contact Preferred Phone");
    importEvent.contactMailingStreet = data.get("Contact Mailing Address");
    if (!Strings.isNullOrEmpty(data.get("Contact Mailing Address 2"))) {
      importEvent.contactMailingStreet += ", " + data.get("Contact Mailing Address 2");
    }
    importEvent.contactMailingCity = data.get("Contact Mailing City");
    importEvent.contactMailingState = data.get("Contact Mailing State");
    importEvent.contactMailingZip = data.get("Contact Mailing PostCode");
    importEvent.contactMailingCountry = data.get("Contact Mailing Country");
    importEvent.contactOptInEmail = checkboxToBool(data.get("Contact Email Opt In"));
    importEvent.contactOptOutEmail = "no".equalsIgnoreCase(data.get("Contact Email Opt In")) || "false".equalsIgnoreCase(data.get("Contact Email Opt In")) || "0".equalsIgnoreCase(data.get("Contact Email Opt In"));
    importEvent.contactOptInSms = checkboxToBool(data.get("Contact SMS Opt In"));
    importEvent.contactOptOutSms = "no".equalsIgnoreCase(data.get("Contact SMS Opt In")) || "false".equalsIgnoreCase(data.get("Contact SMS Opt In")) || "0".equalsIgnoreCase(data.get("Contact SMS Opt In"));
    importEvent.contactOwnerId = data.get("Contact Owner ID");
    importEvent.contactRecordTypeId = data.get("Contact Record Type ID");
    importEvent.contactRecordTypeName = data.get("Contact Record Type Name");

    importEvent.opportunityAmount = getAmount(data, "Opportunity Amount");
    importEvent.opportunityCampaignId = data.get("Opportunity Campaign ID");
    importEvent.opportunityCampaignName = data.get("Opportunity Campaign Name");
    importEvent.opportunityDate = getDate(data, "Opportunity Date");
    importEvent.opportunityDescription = data.get("Opportunity Description");
    importEvent.opportunityName = data.get("Opportunity Name");
    importEvent.opportunityOwnerId = data.get("Opportunity Owner ID");
    importEvent.opportunityRecordTypeId = data.get("Opportunity Record Type ID");
    importEvent.opportunityRecordTypeName = data.get("Opportunity Record Type Name");
    importEvent.opportunityStageName = data.get("Opportunity Stage Name");

    importEvent.recurringDonationAmount = getAmount(data, "Recurring Donation Amount");
    importEvent.recurringDonationCampaignId = data.get("Recurring Donation Campaign ID");
    importEvent.recurringDonationCampaignName = data.get("Recurring Donation Campaign Name");
    importEvent.recurringDonationInterval = data.get("Recurring Donation Interval");
    importEvent.recurringDonationName = data.get("Recurring Donation Name");
    importEvent.recurringDonationNextPaymentDate = getDate(data, "Recurring Donation Next Payment Date");
    importEvent.recurringDonationOwnerId = data.get("Recurring Donation Owner Name");
    importEvent.recurringDonationStartDate = getDate(data, "Recurring Donation Start Date");
    importEvent.recurringDonationStatus = data.get("Recurring Donation Status");

    importEvent.campaignName = data.get("Campaign Name");

    return importEvent;
  }

  // TODO: Hate this code -- is there a lib that can handle it in a forgiving way?
  private static Calendar getDate(CaseInsensitiveMap<String> data, String columnName) {
    Calendar c = null;
    
    try {
      if (data.containsKey(columnName + " dd/mm/yyyy")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("dd/MM/yyyy").parse(data.get(columnName + " dd/mm/yyyy")));
      } else if (data.containsKey("Opportunity Date dd-mm-yyyy")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("dd-MM-yyyy").parse(data.get(columnName + " dd-mm-yyyy")));
      } else if (data.containsKey(columnName + " mm/dd/yyyy")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("MM/dd/yyyy").parse(data.get(columnName + " mm/dd/yyyy")));
      } else if (data.containsKey(columnName + " mm/dd/yy")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("MM/dd/yy").parse(data.get(columnName + " mm/dd/yy")));
      } else if (data.containsKey(columnName + " mm-dd-yyyy")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("MM-dd-yyyy").parse(data.get(columnName + " mm-dd-yyyy")));
      } else if (data.containsKey(columnName + " yyyy-mm-dd")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(data.get(columnName + " yyyy-mm-dd")));
      }
    } catch (ParseException e) {
      log.warn("failed to parse date", e);
    }
    
    return c;
  }

  public static List<CrmImportEvent> fromFBFundraiser(List<Map<String, String>> data) {
    return data.stream().map(CrmImportEvent::fromFBFundraiser).filter(Objects::nonNull).collect(Collectors.toList());
  }

  public static CrmImportEvent fromFBFundraiser(Map<String, String> _data) {
    // Be case-insensitive, for sources that aren't always consistent.
    CaseInsensitiveMap<String> data = CaseInsensitiveMap.of(_data);

//  TODO: 'S' means a standard charge, but will likely need to eventually support other types like refunds, etc.
    if (data.get("Charge Action Type").equalsIgnoreCase("S")) {
      CrmImportEvent importEvent = new CrmImportEvent();
      importEvent.raw = data;

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

  public static List<CrmImportEvent> fromGreaterGiving(List<Map<String, String>> data) {
    return data.stream().map(CrmImportEvent::fromGreaterGiving).filter(Objects::nonNull).collect(Collectors.toList());
  }

  public static CrmImportEvent fromGreaterGiving(Map<String, String> _data) {
    // TODO: Not mapped:
    // Household Phone
    // Account1 Phone
    // Account1 Street
    // Account1 City
    // Account1 State/Province
    // Account1 Zip/Postal Code
    // Account1 Country
    // Account1 Website
    // Payment Check/Reference Number
    // Payment Method
    // Contact1 Salutation
    // Contact1 Title
    // Contact1 Birthdate
    // Contact1 Work Email
    // Contact1 Alternate Email
    // Contact1 Preferred Email
    // Contact1 Other Phone
    // Contact2 Salutation
    // Contact2 First Name
    // Contact2 Last Name
    // Contact2 Birthdate
    // Contact2 Title
    // Contact2 Personal Email
    // Contact2 Work Email
    // Contact2 Alternate Email
    // Contact2 Preferred Email
    // Contact2 Home Phone
    // Contact2 Work Phone
    // Contact2 Mobile Phone
    // Contact2 Other Phone
    // Contact2 Preferred Phone
    // Donation Donor (IE, Contact1 or Contact2)
    // Donation Member Level
    // Donation Membership Start Date
    // Donation Membership End Date
    // Donation Membership Origin
    // Donation Record Type Name
    // Campaign Member Status

    // Be case-insensitive, for sources that aren't always consistent.
    CaseInsensitiveMap<String> data = CaseInsensitiveMap.of(_data);

    // TODO: Other types? Skipping gift-in-kind
    if (data.get("Donation Type").equalsIgnoreCase("Donation") || data.get("Donation Type").equalsIgnoreCase("Auction")) {
      CrmImportEvent importEvent = new CrmImportEvent();
      importEvent.raw = data;

      importEvent.accountName = data.get("Account1 Name");
      importEvent.accountBillingStreet = data.get("Home Street");
      importEvent.accountBillingCity = data.get("Home City");
      importEvent.accountBillingState = data.get("Home State/Province");
      importEvent.accountBillingZip = data.get("Home Zip/Postal Code");
      importEvent.accountBillingCountry = data.get("Home Country");

      importEvent.contactFirstName = data.get("Contact1 First Name");
      importEvent.contactLastName = data.get("Contact1 Last Name");
      importEvent.contactEmail = data.get("Contact1 Personal Email");
      importEvent.contactMobilePhone = data.get("Contact1 Mobile Phone");
      importEvent.contactHomePhone = data.get("Contact1 Home Phone");
      importEvent.contactWorkPhone = data.get("Contact1 Work Phone");
      importEvent.contactPreferredPhone = data.get("Contact1 Preferred Phone");
      importEvent.contactCampaignName = data.get("Campaign Name");

      importEvent.opportunityAmount = getAmount(data, "Donation Amount");
      if (!Strings.isNullOrEmpty(data.get("Donation Name"))) {
        importEvent.opportunityName = data.get("Donation Name");
      } else {
        importEvent.opportunityName = "Greater Giving: " + data.get("Donation Type");
      }
      try {
        importEvent.opportunityDate = Calendar.getInstance();
        importEvent.opportunityDate.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(data.get("Donation Date")));
      } catch (ParseException e) {
        log.warn("failed to parse date", e);
      }
      importEvent.opportunitySource = data.get("Donation Type");
      importEvent.opportunityStageName = data.get("Donation Stage");
      importEvent.opportunityDescription = data.get("Donation Description");
      importEvent.opportunityCampaignName = data.get("Campaign Name");

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
}
