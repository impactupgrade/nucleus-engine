/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.util.Utils;
import org.apache.commons.collections4.map.CaseInsensitiveMap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.impactupgrade.nucleus.util.Utils.checkboxToBool;
import static com.impactupgrade.nucleus.util.Utils.fullNameToFirstLast;

public class CrmImportEvent {

  public enum ContactEmailPreference {
    PERSONAL("personal"),
    WORK("work"),
    OTHER("other");

    private final String name;

    ContactEmailPreference(String name) {
      this.name = name;
    }

    public static ContactEmailPreference fromName(String name) {
      if (Strings.isNullOrEmpty(name)) {
        return PERSONAL;
      }

      if (PERSONAL.name.equals(name.toLowerCase(Locale.ROOT))) {
        return PERSONAL;
      } else if (WORK.name.equals(name.toLowerCase(Locale.ROOT))) {
        return WORK;
      } else if (OTHER.name.equals(name.toLowerCase(Locale.ROOT))) {
        return OTHER;
      } else {
        // default to personal
        return PERSONAL;
      }
    }
  }

  public enum ContactPhonePreference {
    HOME("home"),
    MOBILE("mobile"),
    WORK("work"),
    OTHER("other");

    private final String name;

    ContactPhonePreference(String name) {
      this.name = name;
    }

    public static ContactPhonePreference fromName(String name) {
      if (Strings.isNullOrEmpty(name)) {
        // default to personal
        return MOBILE;
      }

      if (HOME.name.equals(name.toLowerCase(Locale.ROOT))) {
        return HOME;
      } else if (MOBILE.name.equals(name.toLowerCase(Locale.ROOT))) {
        return MOBILE;
      } else if (WORK.name.equals(name.toLowerCase(Locale.ROOT))) {
        return WORK;
      } else if (OTHER.name.equals(name.toLowerCase(Locale.ROOT))) {
        return OTHER;
      } else {
        // default to personal
        return MOBILE;
      }
    }
  }

  // TODO: It originally made sense to use CaseInsensitiveMap here. But, we run into issues since most
  //  impls of CaseInsensitiveMap automatically lowercase all keys and values. That wrecks havoc for CRMs like SFDC,
  //  where the API is unfortunately case sensitive. For now, keep the originals and require column heads to be
  //  case sensitive
  public Map<String, String> raw = new HashMap<>();

  // could be a contact's household, could be an organization itself -- both are assumed to be the primary account
  public CrmAccount account = new CrmAccount();
  public List<String> accountCampaignIds = new ArrayList<>();
  public List<String> accountCampaignNames = new ArrayList<>();
  public String accountNote;
  // organization affiliations
  public List<CrmAccount> contactOrganizations = new ArrayList<>();
  public List<String> contactOrganizationRoles = new ArrayList<>();

  public CrmContact contact = new CrmContact();
  public List<String> contactCampaignIds = new ArrayList<>();
  public List<String> contactCampaignNames = new ArrayList<>();
  public String contactNote;

  // TODO: To make things easier, we assume CrmDonation throughout, even though some may simply be CrmOpportunity.
  //  Since CrmDonation extends CrmOpportunity, we can get away with it, but that model feels like it needs a refactor.
  public CrmDonation opportunity;
  public String opportunityCampaignId;
  public String opportunityCampaignName;

  public CrmRecurringDonation recurringDonation;
  public String recurringDonationCampaignId;
  public String recurringDonationCampaignName;

  // If the sheet is updating addresses, but all we have is names and no ids/extrefs/emails, we still need
  // the original addresses for existing matches. But all we really use for that is the street.
  public String originalStreet;

  public String campaignName;
  public String campaignRecordTypeId;
  public String campaignRecordTypeName;
  // TODO: In the future, could add OOTB support for dates, etc. but need to see this play out.

  // used during processing
  public boolean secondPass = false;

  public static List<CrmImportEvent> fromGeneric(List<Map<String, String>> data) {
    return data.stream()
        // Some spreadsheets oddly give us empty rows at the end before the file terminates. Skip them!
        .filter(d -> d.values().stream().anyMatch(v -> !Strings.isNullOrEmpty(v)))
        .map(CrmImportEvent::fromGeneric)
        .collect(Collectors.toList());
  }

  public static CrmImportEvent fromGeneric(Map<String, String> _data) {
    // TODO: It originally made sense to use CaseInsensitiveMap here. But, we run into issues since most
    //  impls of CaseInsensitiveMap automatically lowercase all keys and values. That wrecks havoc for CRMs like SFDC,
    //  where the API is unfortunately case sensitive. For now, keep the originals and require column heads to be
    //  case sensitive
//    CaseInsensitiveMap<String, String> data = new CaseInsensitiveMap<>(_data);
    Map<String, String> data = _data;

    CrmImportEvent importEvent = new CrmImportEvent();
    importEvent.raw = data;

    importEvent.account.id = data.get("Account ID");
    importEvent.contact.id = data.get("Contact ID");
    importEvent.opportunity.id = data.get("Opportunity ID");
    importEvent.recurringDonation.id = data.get("Recurring Donation ID");
    importEvent.campaign.id = data.get("Campaign ID");

    if (data.get("Contact Email") != null) {
      importEvent.contact.homeEmail = data.get("Contact Email");
      // SFDC "where in ()" queries appear to be case-sensitive, and SFDC lower cases all emails internally.
      // For now, ensure we follow suit.
      importEvent.contact.homeEmail = importEvent.contact.homeEmail.toLowerCase(Locale.ROOT);
      importEvent.contact.homeEmail = Utils.noWhitespace(importEvent.contact.homeEmail);
    }

    if (data.get("Contact Personal Email") != null) {
      importEvent.contact.homeEmail = data.get("Contact Personal Email");
      // SFDC "where in ()" queries appear to be case-sensitive, and SFDC lower cases all emails internally.
      // For now, ensure we follow suit.
      importEvent.contact.homeEmail = importEvent.contact.homeEmail.toLowerCase(Locale.ROOT);
      importEvent.contact.homeEmail = Utils.noWhitespace(importEvent.contact.homeEmail);
    }

    if (data.get("Contact Work Email") != null) {
      importEvent.contact.workEmail = data.get("Contact Work Email");
      // SFDC "where in ()" queries appear to be case-sensitive, and SFDC lower cases all emails internally.
      // For now, ensure we follow suit.
      importEvent.contact.workEmail = importEvent.contact.workEmail.toLowerCase(Locale.ROOT);
      importEvent.contact.workEmail = Utils.noWhitespace(importEvent.contact.workEmail);
    }

    if (data.get("Contact Other Email") != null) {
      importEvent.contact.otherEmail = data.get("Contact Other Email");
      // SFDC "where in ()" queries appear to be case-sensitive, and SFDC lower cases all emails internally.
      // For now, ensure we follow suit.
      importEvent.contact.otherEmail = importEvent.contact.otherEmail.toLowerCase(Locale.ROOT);
      importEvent.contact.otherEmail = Utils.noWhitespace(importEvent.contact.otherEmail);
    }

    if (data.get("Contact Preferred Email") != null) {
      importEvent.contact.preferredEmail = ContactEmailPreference.fromName(data.get("Contact Preferred Email"));
    }

    importEvent.account.billingAddress.street = data.get("Account Billing Street");
    if (!Strings.isNullOrEmpty(data.get("Account Billing Street 2"))) {
      importEvent.account.billingAddress.street += ", " + data.get("Account Billing Street 2");
    }
    // Also check Address (vs Street) -- happens often.
    if (Strings.isNullOrEmpty(importEvent.account.billingAddress.street)) {
      importEvent.account.billingAddress.street = data.get("Account Billing Address");
      if (!Strings.isNullOrEmpty(data.get("Account Billing Address 2"))) {
        importEvent.account.billingAddress.street += ", " + data.get("Account Billing Address 2");
      }
    }
    importEvent.account.billingAddress.city = data.get("Account Billing City");
    importEvent.account.billingAddress.state = data.get("Account Billing State");
    importEvent.account.billingAddress.postalCode = data.get("Account Billing Postal Code");
    importEvent.account.billingAddress.country = data.get("Account Billing Country");
    importEvent.account.mailingAddress.street = data.get("Account Shipping Street");
    if (!Strings.isNullOrEmpty(data.get("Account Shipping Street 2"))) {
      importEvent.account.mailingAddress.street += ", " + data.get("Account Shipping Street 2");
    }
    // Also check Address (vs Street) -- happens often.
    if (Strings.isNullOrEmpty(importEvent.account.mailingAddress.street)) {
      importEvent.account.mailingAddress.street = data.get("Account Shipping Address");
      if (!Strings.isNullOrEmpty(data.get("Account Shipping Address 2"))) {
        importEvent.account.mailingAddress.street += ", " + data.get("Account Shipping Address 2");
      }
    }
    importEvent.account.mailingAddress.city = data.get("Account Shipping City");
    importEvent.account.mailingAddress.state = data.get("Account Shipping State");
    importEvent.account.mailingAddress.postalCode = data.get("Account Shipping Postal Code");
    importEvent.account.mailingAddress.country = data.get("Account Shipping Country");
    importEvent.account.description = data.get("Account Description");
    importEvent.account.name = data.get("Account Name");
    importEvent.accountNote = data.get("Account Note");
    importEvent.account.ownerId = data.get("Account Owner ID");
    importEvent.account.recordTypeId = data.get("Account Record Type ID");
    importEvent.account.recordTypeName = data.get("Account Record Type Name");
    importEvent.account.website = data.get("Account Website");

    // 3 ways campaigns can be provided, using column headers:
    // 1: Account Campaign n ID
    // 2: Account Campaign n Name
    // 3: Account Campaign [Some Name] -> boolean (true, yes, 1) values
    // #3 is helpful in many cases where we're migrating tags/fields with boolean values to campaign membership
    for (int i = 1; i <= 5; i++) {
      String columnPrefix = "Account Campaign " + i;
      if (data.keySet().stream().anyMatch(k -> k.startsWith(columnPrefix))) {
        importEvent.accountCampaignIds.add(data.get(columnPrefix + " ID"));
        importEvent.accountCampaignNames.add(data.get(columnPrefix + " Name"));
      }
    }
    for (String columnName : importEvent.getAccountCampaignColumnNames()) {
      if (columnName.startsWith("Account Campaign Name ")) { // note the extra space at the end, different than the above
        String s = data.get(columnName);
        boolean value = Utils.checkboxToBool(s);
        if (value) {
          String campaignName = columnName.replace("Account Campaign Name ", "");
          importEvent.accountCampaignNames.add(campaignName);
        }
      }
    }

    for (int i = 1; i <= 5; i++) {
      String columnPrefix = "Organization " + i;
      if (data.keySet().stream().anyMatch(k -> k.startsWith(columnPrefix)) && !Strings.isNullOrEmpty(data.get(columnPrefix + " Name"))) {
        CrmAccount organization = new CrmAccount();
        organization.billingAddress.street = data.get(columnPrefix + " Billing Street");
        if (!Strings.isNullOrEmpty(data.get(columnPrefix + " Billing Street 2"))) {
          organization.billingAddress.street += ", " + data.get(columnPrefix + " Billing Street 2");
        }
        // Also check Address (vs Street) -- happens often.
        if (Strings.isNullOrEmpty(organization.billingAddress.street)) {
          organization.billingAddress.street = data.get(columnPrefix + " Billing Address");
          if (!Strings.isNullOrEmpty(data.get(columnPrefix + " Billing Address 2"))) {
            organization.billingAddress.street += ", " + data.get(columnPrefix + " Billing Address 2");
          }
        }
        organization.billingAddress.city = data.get(columnPrefix + " Billing City");
        organization.billingAddress.state = data.get(columnPrefix + " Billing State");
        organization.billingAddress.postalCode = data.get(columnPrefix + " Billing Postal Code");
        organization.billingAddress.country = data.get(columnPrefix + " Billing Country");
        organization.mailingAddress.street = data.get(columnPrefix + " Shipping Street");
        if (!Strings.isNullOrEmpty(data.get(columnPrefix + " Shipping Street 2"))) {
          organization.mailingAddress.street += ", " + data.get(columnPrefix + " Shipping Street 2");
        }
        // Also check Address (vs Street) -- happens often.
        if (Strings.isNullOrEmpty(organization.mailingAddress.street)) {
          organization.mailingAddress.street = data.get(columnPrefix + " Shipping Address");
          if (!Strings.isNullOrEmpty(data.get(columnPrefix + " Shipping Address 2"))) {
            organization.mailingAddress.street += ", " + data.get(columnPrefix + " Shipping Address 2");
          }
        }
        organization.mailingAddress.city = data.get(columnPrefix + " Shipping City");
        organization.mailingAddress.state = data.get(columnPrefix + " Shipping State");
        organization.mailingAddress.postalCode = data.get(columnPrefix + " Shipping Postal Code");
        organization.mailingAddress.country = data.get(columnPrefix + " Shipping Country");
        organization.description = data.get(columnPrefix + " Description");
        organization.name = data.get(columnPrefix + " Name");
        organization.ownerId = data.get(columnPrefix + " Owner ID");
        organization.recordType = EnvironmentConfig.AccountType.ORGANIZATION;
        organization.recordTypeId = data.get(columnPrefix + " Record Type ID");
        organization.recordTypeName = data.get(columnPrefix + " Record Type Name");
        organization.website = data.get(columnPrefix + " Website");

        importEvent.contactOrganizations.add(organization);
        importEvent.contactOrganizationRoles.add(data.get(columnPrefix + " Role"));
      }
    }

    importEvent.contact.salutation = data.get("Contact Salutation"); // TODO: add to CrmServices' handling
    importEvent.contact.firstName = data.get("Contact First Name");
    importEvent.contact.lastName = data.get("Contact Last Name");
    if (Strings.isNullOrEmpty(importEvent.contact.firstName) && !Strings.isNullOrEmpty(data.get("Contact Full Name"))) {
      String[] split = fullNameToFirstLast(data.get("Contact Full Name"));
      importEvent.contact.firstName = split[0];
      importEvent.contact.lastName = split[1];
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

    importEvent.contact.description = data.get("Contact Description");
    importEvent.contact.mobilePhone = data.get("Contact Mobile Phone");
    if (Strings.isNullOrEmpty(importEvent.contact.mobilePhone) && data.containsKey("Contact Phone")) {
      importEvent.contact.mobilePhone = data.get("Contact Phone");
    }
    importEvent.contact.homePhone = data.get("Contact Home Phone");
    importEvent.contact.workPhone = data.get("Contact Work Phone");
    importEvent.contact.preferredPhone = ContactPhonePreference.fromName(data.get("Contact Preferred Phone"));
    importEvent.contact.mailingAddress.street = data.get("Contact Mailing Street");
    if (!Strings.isNullOrEmpty(data.get("Contact Mailing Street 2"))) {
      importEvent.contact.mailingAddress.street += ", " + data.get("Contact Mailing Street 2");
    }
    // Also check Address (vs Street) -- happens often.
    if (Strings.isNullOrEmpty(importEvent.contact.mailingAddress.street)) {
      importEvent.contact.mailingAddress.street = data.get("Contact Mailing Address");
      if (!Strings.isNullOrEmpty(data.get("Contact Mailing Address 2"))) {
        importEvent.contact.mailingAddress.street += ", " + data.get("Contact Mailing Address 2");
      }
    }
    importEvent.contact.mailingAddress.city = data.get("Contact Mailing City");
    importEvent.contact.mailingAddress.state = data.get("Contact Mailing State");
    importEvent.contact.mailingAddress.postalCode = data.get("Contact Mailing Postal Code");
    importEvent.contact.mailingAddress.country = data.get("Contact Mailing Country");
    importEvent.contactNote = data.get("Contact Note");
    importEvent.contact.emailOptIn = checkboxToBool(data.get("Contact Email Opt In"));
    importEvent.contact.emailOptOut = "no".equalsIgnoreCase(data.get("Contact Email Opt In")) || "false".equalsIgnoreCase(data.get("Contact Email Opt In")) || "0".equalsIgnoreCase(data.get("Contact Email Opt In"));
    importEvent.contact.smsOptIn = checkboxToBool(data.get("Contact SMS Opt In"));
    importEvent.contact.smsOptOut = "no".equalsIgnoreCase(data.get("Contact SMS Opt In")) || "false".equalsIgnoreCase(data.get("Contact SMS Opt In")) || "0".equalsIgnoreCase(data.get("Contact SMS Opt In"));
    importEvent.contact.ownerId = data.get("Contact Owner ID");
    importEvent.contact.recordTypeId = data.get("Contact Record Type ID");
    importEvent.contact.recordTypeName = data.get("Contact Record Type Name");

    importEvent.opportunity.amount = getAmount(data, "Opportunity Amount");
    importEvent.opportunityCampaignId = data.get("Opportunity Campaign ID");
    importEvent.opportunityCampaignName = data.get("Opportunity Campaign Name");
    importEvent.opportunity.closeDate = getDate(data, "Opportunity Date");
    importEvent.opportunity.description = data.get("Opportunity Description");
    importEvent.opportunity.name = data.get("Opportunity Name");
    importEvent.opportunity.ownerId = data.get("Opportunity Owner ID");
    importEvent.opportunity.recordTypeId = data.get("Opportunity Record Type ID");
    importEvent.opportunity.recordTypeName = data.get("Opportunity Record Type Name");
    importEvent.opportunity.status = data.get("Opportunity Stage Name");
    if (Strings.isNullOrEmpty(importEvent.opportunity.status)) {
      importEvent.opportunity.status = data.get("Opportunity Stage");
    }

    importEvent.recurringDonation.amount = getAmount(data, "Recurring Donation Amount");
    importEvent.recurringDonationCampaignId = data.get("Recurring Donation Campaign ID");
    importEvent.recurringDonationCampaignName = data.get("Recurring Donation Campaign Name");
    importEvent.recurringDonation.frequency = data.get("Recurring Donation Interval");
    importEvent.recurringDonation.name = data.get("Recurring Donation Name");
    importEvent.recurringDonation.subscriptionNextDate = getDate(data, "Recurring Donation Next Payment Date");
    importEvent.recurringDonation.ownerId = data.get("Recurring Donation Owner Name");
    importEvent.recurringDonation.subscriptionStartDate = getDate(data, "Recurring Donation Start Date");
    importEvent.recurringDonation.status = data.get("Recurring Donation Status");

    importEvent.originalStreet = data.get("Original Street");

    importEvent.campaignName = data.get("Campaign Name");
    importEvent.campaignRecordTypeId = data.get("Campaign Record Type ID");
    importEvent.campaignRecordTypeName = data.get("Campaign Record Type Name");

    return importEvent;
  }

  public static List<CrmImportEvent> fromFBFundraiser(List<Map<String, String>> data) {
    return data.stream().map(CrmImportEvent::fromFBFundraiser).filter(Objects::nonNull).collect(Collectors.toList());
  }

  public static CrmImportEvent fromFBFundraiser(Map<String, String> _data) {
    // Be case-insensitive, for sources that aren't always consistent.
    CaseInsensitiveMap<String, String> data = new CaseInsensitiveMap<>(_data);

//  TODO: 'S' means a standard charge, but will likely need to eventually support other types like refunds, etc.
    if (data.get("Charge Action Type").equalsIgnoreCase("S")) {
      CrmImportEvent importEvent = new CrmImportEvent();
      importEvent.raw = data;

//    TODO: support for initial amount, any fees, and net amount
//    importEvent. = data.get("Donation Amount");
//    importEvent. = data.get("FB Fee");
//    importEvent. = getAmount(data, "Net Payout Amount");
      importEvent.opportunity.amount = getAmount(data, "Donation Amount");

//      TODO: support for different currencies will likely be needed in the future
//      importEvent. = data.get("Payout Currency");
//      importEvent. = data.get("Sender Currency");
      if (data.get("Fundraiser Type").contains("Fundraiser")) {
        importEvent.opportunity.name = "Facebook Fundraiser: " + data.get("Fundraiser Title");
      } else if (!Strings.isNullOrEmpty(data.get("Fundraiser Title"))) {
        importEvent.opportunity.name = "Facebook Fundraiser: " + data.get("Fundraiser Title") + " (" + data.get("Fundraiser Type") + ")";
      } else {
        importEvent.opportunity.name = "Facebook Fundraiser: " + data.get("Fundraiser Type");
      }
      try {
        importEvent.opportunity.closeDate = Calendar.getInstance();
        importEvent.opportunity.closeDate.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(data.get("Charge Date")));
      } catch (ParseException e) {
        throw new RuntimeException("failed to parse date", e);
      }

      importEvent.contact.firstName = Utils.nameToTitleCase(data.get("First Name"));
      importEvent.contact.lastName = Utils.nameToTitleCase(data.get("Last Name"));
      importEvent.contact.homeEmail = data.get("Email Address");
      importEvent.opportunity.source = (!Strings.isNullOrEmpty(data.get("Fundraiser Title"))) ? data.get("Fundraiser Title") : data.get("Fundraiser Type");
      importEvent.opportunity.terminal = data.get("Payment Processor");
      importEvent.opportunity.status = "Posted";

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
      importEvent.opportunity.description = Joiner.on("\n").join(description);

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
    CaseInsensitiveMap<String, String> data = new CaseInsensitiveMap<>(_data);

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

      importEvent.contact.firstName = data.get("Contact1 First Name");
      importEvent.contact.lastName = data.get("Contact1 Last Name");
      importEvent.contact.homeEmail = data.get("Contact1 Personal Email");
      importEvent.contact.workEmail = data.get("Contact1 Work Email");
      importEvent.contact.otherEmail = data.get("Contact1 Other Email");
      importEvent.contact.mobilePhone = data.get("Contact1 Mobile Phone");
      importEvent.contact.homePhone = data.get("Contact1 Home Phone");
      importEvent.contact.workPhone = data.get("Contact1 Work Phone");
      importEvent.contact.preferredPhone = ContactPhonePreference.fromName(data.get("Contact Preferred Phone"));
      if (!Strings.isNullOrEmpty(data.get("Campaign Name"))) {
        importEvent.contactCampaignNames.add(data.get("Campaign Name"));
      }

      importEvent.opportunity.amount = getAmount(data, "Donation Amount");
      if (!Strings.isNullOrEmpty(data.get("Donation Name"))) {
        importEvent.opportunity.name = data.get("Donation Name");
      } else {
        importEvent.opportunity.name = "Greater Giving: " + data.get("Donation Type");
      }
      try {
        importEvent.opportunity.closeDate = Calendar.getInstance();
        importEvent.opportunity.closeDate.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(data.get("Donation Date")));
      } catch (ParseException e) {
        throw new RuntimeException("failed to parse date", e);
      }
      importEvent.opportunity.source = data.get("Donation Type");
      importEvent.opportunity.status = data.get("Donation Stage");
      importEvent.opportunity.description = data.get("Donation Description");
      importEvent.opportunityCampaignName = data.get("Campaign Name");

      return importEvent;
    } else {
      return null;
    }
  }

  public List<String> getAccountColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Account")).toList();
  }
  public List<String> getAccountCustomFieldNames() {
    return getAccountColumnNames().stream().filter(k -> k.startsWith("Account Custom"))
        .map(k -> k.replace("Account Custom", "").replace("Append", "").trim()).map(this::removeDateSelectors).toList();
  }
  public List<String> getAccountCampaignColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Account Campaign")).toList();
  }
  public List<String> getContactColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Contact")).toList();
  }
  public List<String> getContactCustomFieldNames() {
    List<String> contactFields = getContactColumnNames().stream().filter(k -> k.startsWith("Contact Custom"))
        .map(k -> k.replace("Contact Custom", "").replace("Append", "").trim()).map(this::removeDateSelectors).toList();
    // We also need the account values!
    List<String> accountFields = getAccountCustomFieldNames().stream().map(f -> "Account." + f).toList();
    return Stream.concat(contactFields.stream(), accountFields.stream()).toList();
  }
  public List<String> getContactCampaignColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Contact Campaign")).toList();
  }
  public List<String> getRecurringDonationColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Recurring Donation")).toList();
  }
  public List<String> getRecurringDonationCustomFieldNames() {
    return getRecurringDonationColumnNames().stream().filter(k -> k.startsWith("Recurring Donation Custom"))
        .map(k -> k.replace("Recurring Donation Custom", "").replace("Append", "").trim()).map(this::removeDateSelectors).toList();
  }
  public List<String> getOpportunityColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Opportunity")).toList();
  }
  public List<String> getOpportunityCustomFieldNames() {
    return getOpportunityColumnNames().stream().filter(k -> k.startsWith("Opportunity Custom"))
        .map(k -> k.replace("Opportunity Custom", "").replace("Append", "").trim()).map(this::removeDateSelectors).toList();
  }
  public List<String> getCampaignColumnNames() {
    return raw.keySet().stream().filter(k -> k.startsWith("Campaign")).toList();
  }
  public List<String> getCampaignCustomFieldNames() {
    return getOpportunityColumnNames().stream().filter(k -> k.startsWith("Campaign Custom"))
        .map(k -> k.replace("Campaign Custom", "").replace("Append", "").trim()).map(this::removeDateSelectors).toList();
  }
  public List<String> getAllContactEmails() {
    return Stream.of(contact.homeEmail, contact.workEmail, contact.otherEmail)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
  }
  public boolean hasEmail() {
    return !Strings.isNullOrEmpty(contact.email());
  }


  // TODO: Hate this code -- is there a lib that can handle it in a forgiving way?
  private static Calendar getDate(Map<String, String> data, String columnName) {
    String key = null;

    if (data.containsKey(columnName + " dd/mm/yyyy")) {
      key = columnName + " dd/mm/yyyy";
    } else if (data.containsKey(columnName + " dd-mm-yyyy")) {
      key = columnName + " dd-mm-yyyy";
    } else if (data.containsKey(columnName + " mm/dd/yyyy")) {
      key = columnName + " mm/dd/yyyy";
    } else if (data.containsKey(columnName + " mm/dd/yy")) {
      key = columnName + " mm/dd/yy";
    } else if (data.containsKey(columnName + " mm-dd-yyyy")) {
      key = columnName + " mm-dd-yyyy";
    } else if (data.containsKey(columnName + " yyyy-mm-dd")) {
      key = columnName + " yyyy-mm-dd";
    }

    if (Strings.isNullOrEmpty(key) || Strings.isNullOrEmpty(data.get(key))) {
      return null;
    }

    try {
      Calendar c = Calendar.getInstance();
      c.setTime(new SimpleDateFormat("dd/MM/yyyy").parse(data.get(key)));
      return c;
    } catch (ParseException e) {
      throw new RuntimeException("failed to parse date", e);
    }
  }
  private String removeDateSelectors(String s) {
    return s.replace("dd/mm/yyyy", "").replace("dd-mm-yyyy", "").replace("mm/dd/yyyy", "").replace("mm/dd/yy", "")
        .replace("mm-dd-yyyy", "").replace("yyyy-mm-dd", "").trim();
  }

  private static BigDecimal getAmount(Map<String, String> data, String columnName) {
    if (!data.containsKey(columnName)) {
      return null;
    }
    String n = data.get(columnName);
    if (Strings.isNullOrEmpty(n)) {
      return null;
    }
    return new BigDecimal(n.replace("$", "").replace(",", "").trim()).setScale(2, RoundingMode.CEILING);
  }
}
