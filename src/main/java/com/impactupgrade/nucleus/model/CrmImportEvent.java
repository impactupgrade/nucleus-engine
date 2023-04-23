/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
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
import java.util.stream.Stream;

import static com.impactupgrade.nucleus.util.Utils.checkboxToBool;
import static com.impactupgrade.nucleus.util.Utils.fullNameToFirstLast;

public class CrmImportEvent {

  private static final Logger log = LogManager.getLogger(CrmImportEvent.class.getName());

  // Be case-insensitive, for sources that aren't always consistent.
  public CaseInsensitiveMap<String> raw = new CaseInsensitiveMap<>();

  // For updates only, used for retrieval.
  public String contactId;
  public String opportunityId;
  public String recurringDonationId;
  public String campaignId;

  // Can also be used for update retrieval, as well as inserts.
  public String contactEmail;

  // could be a contact's household, could be an organization itself
  public CrmAccount account = new CrmAccount();
  // organization affiliations
  public List<CrmAccount> contactOrganizations = new ArrayList<>();
  public List<String> contactOrganizationRoles = new ArrayList<>();

  // TODO: replace the rest with CrmContact, CrmOpportunity/CrmDonation, and CrmRecurringDonation

  public List<String> contactCampaignIds = new ArrayList<>();
  public List<String> contactCampaignNames = new ArrayList<>();
  public String contactDescription;
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

    importEvent.account.id = data.get("Account ID");
    importEvent.contactId = data.get("Contact ID");
    importEvent.opportunityId = data.get("Opportunity ID");
    importEvent.recurringDonationId = data.get("Recurring Donation ID");
    importEvent.campaignId = data.get("Campaign ID");

    importEvent.contactEmail = data.get("Contact Email");
    if (importEvent.contactEmail != null && importEvent.contactEmail.contains("@")) {
      // TODO: SFDC "where in ()" queries appear to be case sensitive, and SFDC lower cases all emails internally.
      //  For now, ensure we follow suit.
      importEvent.contactEmail = importEvent.contactEmail.toLowerCase(Locale.ROOT);
    }

    importEvent.account.billingAddress.street = data.get("Account Billing Street");
    if (!Strings.isNullOrEmpty(data.get("Account Billing Street 2"))) {
      importEvent.account.billingAddress.street += ", " + data.get("Account Billing Street 2");
    }
    importEvent.account.billingAddress.city = data.get("Account Billing City");
    importEvent.account.billingAddress.state = data.get("Account Billing State");
    importEvent.account.billingAddress.postalCode = data.get("Account Billing PostCode");
    importEvent.account.billingAddress.country = data.get("Account Billing Country");
    importEvent.account.description = data.get("Account Description");
    importEvent.account.name = data.get("Account Name");
    importEvent.account.ownerId = data.get("Account Owner ID");
    importEvent.account.typeId = data.get("Account Record Type ID");
    importEvent.account.typeName = data.get("Account Record Type Name");

    for (int i = 1; i <= 5; i++) {
      String columnPrefix = "Organization " + i;
      if (data.keySet().stream().anyMatch(k -> k.startsWith(columnPrefix)) && !Strings.isNullOrEmpty(data.get(columnPrefix + " Name"))) {
        CrmAccount organization = new CrmAccount();
        organization.billingAddress.street = data.get(columnPrefix + " Billing Street");
        if (!Strings.isNullOrEmpty(data.get(columnPrefix + " Billing Street 2"))) {
          organization.billingAddress.street += ", " + data.get(columnPrefix + " Billing Street 2");
        }
        organization.billingAddress.city = data.get(columnPrefix + " Billing City");
        organization.billingAddress.state = data.get(columnPrefix + " Billing State");
        organization.billingAddress.postalCode = data.get(columnPrefix + " Billing PostCode");
        organization.billingAddress.country = data.get(columnPrefix + " Billing Country");
        organization.description = data.get(columnPrefix + " Description");
        organization.name = data.get(columnPrefix + " Name");
        organization.ownerId = data.get(columnPrefix + " Owner ID");
        organization.type = EnvironmentConfig.AccountType.ORGANIZATION;
        organization.typeId = data.get(columnPrefix + " Record Type ID");
        organization.typeName = data.get(columnPrefix + " Record Type Name");
        importEvent.contactOrganizations.add(organization);

        importEvent.contactOrganizationRoles.add(data.get(columnPrefix + " Role"));
      }
    }

    importEvent.contactFirstName = data.get("Contact First Name");
    importEvent.contactLastName = data.get("Contact Last Name");
    importEvent.contactFullName = data.get("Contact Full Name");
    if (Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactFullName)) {
      String[] split = fullNameToFirstLast(importEvent.contactFullName);
      importEvent.contactFirstName = split[0];
      importEvent.contactLastName = split[1];
    }

    // 3 ways campaigns can be provided, using column headers:
    // 1: Contact Campaign n ID
    // 2: Contact Campaign n Name
    // 3: Contact Campaign [Some Name] -> boolean (true, yes, 1) values
    // #3 is helpful in many cases where we're migrating tags/fields with boolean values to campaign membership
    for (int i = 1; i <= 5; i++) {
      String columnPrefix = "Contact Campaign " + i;
      if (data.keySet().stream().anyMatch(k -> k.startsWith(columnPrefix))) {
        importEvent.contactCampaignIds.add(data.get(columnPrefix + " ID"));
        importEvent.contactCampaignNames.add(data.get(columnPrefix + " Name"));
      }
    }
    for (String columnName : importEvent.getContactCampaignColumnNames()) {
      if (columnName.startsWith("Contact Campaign Name ")) { // note the extra space at the end, different than the above
        String s = data.get(columnName);
        boolean value = Utils.checkboxToBool(s);
        if (value) {
          String campaignName = columnName.replace("Contact Campaign Name ", "");
          importEvent.contactCampaignNames.add(campaignName);
        }
      }
    }

    importEvent.contactDescription = data.get("Contact Description");
    importEvent.contactHomePhone = data.get("Contact Home Phone");
    importEvent.contactMobilePhone = data.get("Contact Mobile Phone");
    importEvent.contactWorkPhone = data.get("Contact Work Phone");
    importEvent.contactPreferredPhone = data.get("Contact Preferred Phone");
    importEvent.contactMailingStreet = data.get("Contact Mailing Street");
    if (!Strings.isNullOrEmpty(data.get("Contact Mailing Street 2"))) {
      importEvent.contactMailingStreet += ", " + data.get("Contact Mailing Street 2");
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

  public List<String> getAccountColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Account ")).toList();
  }
  public List<String> getAccountCustomFieldNames() {
    return getAccountColumnNames().stream().filter(k -> k.startsWith("Account Custom "))
        .map(k -> k.replace("Account Custom ", "").replace("Append ", "")).toList();
  }
  public List<String> getContactColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Contact ")).toList();
  }
  public List<String> getContactCustomFieldNames() {
    List<String> contactFields = getContactColumnNames().stream().filter(k -> k.startsWith("Contact Custom "))
        .map(k -> k.replace("Contact Custom ", "").replace("Append ", "")).toList();
    // We also need the account values!
    List<String> accountFields = getAccountCustomFieldNames().stream().map(f -> "Account." + f).toList();
    return Stream.concat(contactFields.stream(), accountFields.stream()).toList();
  }
  public List<String> getRecurringDonationColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Recurring Donation ")).toList();
  }
  public List<String> getRecurringDonationCustomFieldNames() {
    return getRecurringDonationColumnNames().stream().filter(k -> k.startsWith("Recurring Donation Custom "))
        .map(k -> k.replace("Recurring Donation Custom ", "").replace("Append ", "")).toList();
  }
  public List<String> getOpportunityColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Opportunity ")).toList();
  }
  public List<String> getOpportunityCustomFieldNames() {
    return getOpportunityColumnNames().stream().filter(k -> k.startsWith("Opportunity Custom "))
        .map(k -> k.replace("Opportunity Custom ", "").replace("Append ", "")).toList();
  }
  public List<String> getContactCampaignColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Contact Campaign ")).toList();
  }
  public List<String> getCampaignColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Campaign ")).toList();
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

      importEvent.account.name = data.get("Account1 Name");
      importEvent.account.billingAddress.street = data.get("Home Street");
      importEvent.account.billingAddress.city = data.get("Home City");
      importEvent.account.billingAddress.state = data.get("Home State/Province");
      importEvent.account.billingAddress.postalCode = data.get("Home Zip/Postal Code");
      importEvent.account.billingAddress.country = data.get("Home Country");

      importEvent.contactFirstName = data.get("Contact1 First Name");
      importEvent.contactLastName = data.get("Contact1 Last Name");
      importEvent.contactEmail = data.get("Contact1 Personal Email");
      importEvent.contactMobilePhone = data.get("Contact1 Mobile Phone");
      importEvent.contactHomePhone = data.get("Contact1 Home Phone");
      importEvent.contactWorkPhone = data.get("Contact1 Work Phone");
      importEvent.contactPreferredPhone = data.get("Contact1 Preferred Phone");
      if (!Strings.isNullOrEmpty(data.get("Campaign Name"))) {
        importEvent.contactCampaignNames.add(data.get("Campaign Name"));
      }

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
