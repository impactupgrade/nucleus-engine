/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.util.Utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.impactupgrade.nucleus.util.Utils.checkboxToBool;
import static com.impactupgrade.nucleus.util.Utils.fullNameToFirstLast;
import static com.impactupgrade.nucleus.util.Utils.getZonedDateFromDateString;

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
        return null;
      }

      if (PERSONAL.name.equals(name.toLowerCase(Locale.ROOT))) {
        return PERSONAL;
      } else if (WORK.name.equals(name.toLowerCase(Locale.ROOT))) {
        return WORK;
      } else if (OTHER.name.equals(name.toLowerCase(Locale.ROOT))) {
        return OTHER;
      } else {
        return null;
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
        return null;
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
        return null;
      }
    }
  }

  public static class CampaignMembership {
    public String campaignId;
    public String campaignName;
    public String status;
  }

  // TODO: It originally made sense to use CaseInsensitiveMap here. But, we run into issues since most
  //  impls of CaseInsensitiveMap automatically lowercase all keys and values. That wrecks havoc for CRMs like SFDC,
  //  where the API is unfortunately case sensitive. For now, keep the originals and require column heads to be
  //  case sensitive
  public Map<String, String> raw = new HashMap<>();

  // For updates only, used for retrieval.
  public String contactId;
  public String opportunityId;
  public String recurringDonationId;
  public String campaignId;

  // Can also be used for update retrieval, as well as inserts.
  public String contactEmail;
  public String contactPersonalEmail;
  public String contactWorkEmail;
  public String contactOtherEmail;
  public ContactEmailPreference contactEmailPreference;

  // could be a contact's household, could be an organization itself -- both are assumed to be the primary account
  public CrmAccount account = new CrmAccount();
  public List<CampaignMembership> accountCampaigns = new ArrayList<>();
  public String accountNote;
  // organization affiliations
  public List<CrmAccount> contactOrganizations = new ArrayList<>();
  public List<String> contactOrganizationRoles = new ArrayList<>();

  // TODO: replace the rest with CrmContact, CrmOpportunity/CrmDonation, and CrmRecurringDonation

  public List<CampaignMembership> contactCampaigns = new ArrayList<>();
  public String contactDescription;
  public String contactFirstName;
  public String contactLastName;

  public String contactPhone;
  public String contactHomePhone;
  public String contactMobilePhone;
  public String contactWorkPhone;
  public String contactOtherPhone;
  public ContactPhonePreference contactPhonePreference;

  public String contactMailingStreet;
  public String contactMailingCity;
  public String contactMailingState;
  public String contactMailingZip;
  public String contactMailingCountry;
  public String contactNote;
  public Boolean contactOptInEmail;
  public Boolean contactOptOutEmail;
  public Boolean contactOptInSms;
  public Boolean contactOptOutSms;
  public String contactOwnerId;
  public String contactRecordTypeId;
  public String contactRecordTypeName;
  public String contactSalutation;

  public BigDecimal opportunityAmount;
  public String opportunityCampaignId;
  public String opportunityCampaignName;
  public ZonedDateTime opportunityDate;
  public String opportunityDescription;
  public String opportunityName;
  public String opportunityOwnerId;
  public String opportunityRecordTypeId;
  public String opportunityRecordTypeName;
  public String opportunitySource;
  public String opportunityStageName;
  public String opportunityTerminal;
  public String opportunityTransactionId;

  public BigDecimal recurringDonationAmount;
  public String recurringDonationCampaignId;
  public String recurringDonationCampaignName;
  public String recurringDonationInterval;
  public String recurringDonationName;
  public ZonedDateTime recurringDonationNextPaymentDate;
  public String recurringDonationOwnerId;
  public ZonedDateTime recurringDonationStartDate;
  public String recurringDonationStatus;

  // If the sheet is updating addresses, but all we have is names and no ids/extrefs/emails, we still need
  // the original addresses for existing matches. But all we really use for that is the street.
  public String originalStreet;

  public String campaignName;
  public String campaignRecordTypeId;
  public String campaignRecordTypeName;
  // TODO: In the future, could add OOTB support for dates, etc. but need to see this play out.

  // used during processing
  public boolean secondPass = false;

  public String contactFullName() {
    // trim in case one side or the other is blank
    return (contactFirstName + " " + contactLastName).trim();
  }

  public static List<CrmImportEvent> fromGeneric(List<Map<String, String>> data, Environment env) {
    return data.stream()
        // Some spreadsheets oddly give us empty rows at the end before the file terminates. Skip them!
        .filter(d -> d.values().stream().anyMatch(v -> !Strings.isNullOrEmpty(v)))
        .map(d -> fromGeneric(d, env))
        .collect(Collectors.toList());
  }

  public static CrmImportEvent fromGeneric(Map<String, String> _data, Environment env) {
    // TODO: It originally made sense to use CaseInsensitiveMap here. But, we run into issues since most
    //  impls of CaseInsensitiveMap automatically lowercase all keys and values. That wrecks havoc for CRMs like SFDC,
    //  where the API is unfortunately case sensitive. For now, keep the originals and require column heads to be
    //  case sensitive
//    CaseInsensitiveMap<String, String> data = new CaseInsensitiveMap<>(_data);
    Map<String, String> data = _data;

    CrmImportEvent importEvent = new CrmImportEvent();
    importEvent.raw = data;

    importEvent.account.id = data.get("Account ID");
    importEvent.contactId = data.get("Contact ID");
    importEvent.opportunityId = data.get("Opportunity ID");
    importEvent.recurringDonationId = data.get("Recurring Donation ID");
    importEvent.campaignId = data.get("Campaign ID");

    if (data.get("Contact Email") != null) {
      importEvent.contactEmail = data.get("Contact Email");
      // SFDC "where in ()" queries appear to be case-sensitive, and SFDC lower cases all emails internally.
      // For now, ensure we follow suit.
      importEvent.contactEmail = importEvent.contactEmail.toLowerCase(Locale.ROOT);
      importEvent.contactEmail = Utils.noWhitespace(importEvent.contactEmail);
    }

    if (data.get("Contact Personal Email") != null) {
      importEvent.contactPersonalEmail = data.get("Contact Personal Email");
      // SFDC "where in ()" queries appear to be case-sensitive, and SFDC lower cases all emails internally.
      // For now, ensure we follow suit.
      importEvent.contactPersonalEmail = importEvent.contactPersonalEmail.toLowerCase(Locale.ROOT);
      importEvent.contactPersonalEmail = Utils.noWhitespace(importEvent.contactPersonalEmail);
    }

    if (data.get("Contact Work Email") != null) {
      importEvent.contactWorkEmail = data.get("Contact Work Email");
      // SFDC "where in ()" queries appear to be case-sensitive, and SFDC lower cases all emails internally.
      // For now, ensure we follow suit.
      importEvent.contactWorkEmail = importEvent.contactWorkEmail.toLowerCase(Locale.ROOT);
      importEvent.contactWorkEmail = Utils.noWhitespace(importEvent.contactWorkEmail);
    }

    if (data.get("Contact Other Email") != null) {
      importEvent.contactOtherEmail = data.get("Contact Other Email");
      // SFDC "where in ()" queries appear to be case-sensitive, and SFDC lower cases all emails internally.
      // For now, ensure we follow suit.
      importEvent.contactOtherEmail = importEvent.contactOtherEmail.toLowerCase(Locale.ROOT);
      importEvent.contactOtherEmail = Utils.noWhitespace(importEvent.contactOtherEmail);
    }

    if (data.get("Contact Preferred Email") != null) {
      importEvent.contactEmailPreference = ContactEmailPreference.fromName(data.get("Contact Preferred Email"));
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
    // 1: Account Campaign n ID (or a single Account Campaign ID)
    // 2: Account Campaign n Name (or a single Account Campaign Name)
    // 3: Account Campaign [Some Name] -> boolean (true, yes, 1) values
    // #3 is helpful in many cases where we're migrating tags/fields with boolean values to campaign membership
    if (!Strings.isNullOrEmpty(data.get("Account Campaign ID"))) {
      CampaignMembership campaignMembership = new CampaignMembership();
      campaignMembership.campaignId = data.get("Account Campaign ID");
      campaignMembership.status = data.get("Account Campaign Status");
      importEvent.accountCampaigns.add(campaignMembership);
    }
    if (!Strings.isNullOrEmpty(data.get("Account Campaign Name"))) {
      CampaignMembership campaignMembership = new CampaignMembership();
      campaignMembership.campaignName = data.get("Account Campaign Name");
      campaignMembership.status = data.get("Account Campaign Status");
      importEvent.accountCampaigns.add(campaignMembership);
    }
    for (int i = 1; i <= 5; i++) {
      String columnPrefix = "Account Campaign " + i;
      if (data.keySet().stream().anyMatch(k -> k.startsWith(columnPrefix))) {
        CampaignMembership campaignMembership = new CampaignMembership();
        campaignMembership.campaignId = data.get(columnPrefix + " ID");
        campaignMembership.campaignName = data.get(columnPrefix + " Name");
        campaignMembership.status = data.get(columnPrefix + " Status");
        importEvent.accountCampaigns.add(campaignMembership);
      }
    }
    for (String columnName : importEvent.getAccountCampaignColumnNames()) {
      if (columnName.startsWith("Account Campaign Name ")) { // note the extra space at the end, different than the above
        String s = data.get(columnName);
        boolean value = Utils.checkboxToBool(s);
        if (value) {
          CampaignMembership campaignMembership = new CampaignMembership();
          campaignMembership.campaignName = columnName.replace("Account Campaign Name ", "");
          // TODO: we assume boolean values, but we could technically support using campaign member statuses as well
          importEvent.accountCampaigns.add(campaignMembership);
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

    importEvent.contactSalutation = data.get("Contact Salutation");
    importEvent.contactFirstName = data.get("Contact First Name");
    importEvent.contactLastName = data.get("Contact Last Name");
    if (Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(data.get("Contact Full Name"))) {
      String[] split = fullNameToFirstLast(data.get("Contact Full Name"));
      importEvent.contactFirstName = split[0];
      importEvent.contactLastName = split[1];
    }

    // 3 ways campaigns can be provided, using column headers:
    // 1: Contact Campaign n ID (or a single Contact Campaign ID)
    // 2: Contact Campaign n Name (or a single Contact Campaign Name)
    // 3: Contact Campaign [Some Name] -> boolean (true, yes, 1) values
    // #3 is helpful in many cases where we're migrating tags/fields with boolean values to campaign membership
    if (!Strings.isNullOrEmpty(data.get("Contact Campaign ID"))) {
      CampaignMembership campaignMembership = new CampaignMembership();
      campaignMembership.campaignId = data.get("Contact Campaign ID");
      campaignMembership.status = data.get("Contact Campaign Status");
      importEvent.contactCampaigns.add(campaignMembership);
    }
    if (!Strings.isNullOrEmpty(data.get("Contact Campaign Name"))) {
      CampaignMembership campaignMembership = new CampaignMembership();
      campaignMembership.campaignName = data.get("Contact Campaign Name");
      campaignMembership.status = data.get("Contact Campaign Status");
      importEvent.contactCampaigns.add(campaignMembership);
    }
    for (int i = 1; i <= 5; i++) {
      String columnPrefix = "Contact Campaign " + i;
      if (data.keySet().stream().anyMatch(k -> k.startsWith(columnPrefix))) {
        CampaignMembership campaignMembership = new CampaignMembership();
        campaignMembership.campaignId = data.get(columnPrefix + " ID");
        campaignMembership.campaignName = data.get(columnPrefix + " Name");
        campaignMembership.status = data.get(columnPrefix + " Status");
        importEvent.contactCampaigns.add(campaignMembership);
      }
    }
    for (String columnName : importEvent.getContactCampaignColumnNames()) {
      if (columnName.startsWith("Contact Campaign Name ")) { // note the extra space at the end, different than the above
        String s = data.get(columnName);
        boolean value = Utils.checkboxToBool(s);
        if (value) {
          CampaignMembership campaignMembership = new CampaignMembership();
          campaignMembership.campaignName = columnName.replace("Contact Campaign Name ", "");
          // TODO: we assume boolean values, but we could technically support using campaign member statuses as well
          importEvent.contactCampaigns.add(campaignMembership);
        }
      }
    }

    importEvent.contactDescription = data.get("Contact Description");
    importEvent.contactPhone = data.get("Contact Phone");
    importEvent.contactHomePhone = data.get("Contact Home Phone");
    importEvent.contactMobilePhone = data.get("Contact Mobile Phone");
    importEvent.contactWorkPhone = data.get("Contact Work Phone");
    importEvent.contactPhonePreference = ContactPhonePreference.fromName(data.get("Contact Preferred Phone"));
    importEvent.contactMailingStreet = data.get("Contact Mailing Street");
    if (!Strings.isNullOrEmpty(data.get("Contact Mailing Street 2"))) {
      importEvent.contactMailingStreet += ", " + data.get("Contact Mailing Street 2");
    }
    // Also check Address (vs Street) -- happens often.
    if (Strings.isNullOrEmpty(importEvent.contactMailingStreet)) {
      importEvent.contactMailingStreet = data.get("Contact Mailing Address");
      if (!Strings.isNullOrEmpty(data.get("Contact Mailing Address 2"))) {
        importEvent.contactMailingStreet += ", " + data.get("Contact Mailing Address 2");
      }
    }
    importEvent.contactMailingCity = data.get("Contact Mailing City");
    importEvent.contactMailingState = data.get("Contact Mailing State");
    importEvent.contactMailingZip = data.get("Contact Mailing Postal Code");
    importEvent.contactMailingCountry = data.get("Contact Mailing Country");
    importEvent.contactNote = data.get("Contact Note");
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
    importEvent.opportunityDate = getDate(data, "Opportunity Date", env);
    importEvent.opportunityDescription = data.get("Opportunity Description");
    importEvent.opportunityName = data.get("Opportunity Name");
    importEvent.opportunityOwnerId = data.get("Opportunity Owner ID");
    importEvent.opportunityRecordTypeId = data.get("Opportunity Record Type ID");
    importEvent.opportunityRecordTypeName = data.get("Opportunity Record Type Name");
    importEvent.opportunityStageName = data.get("Opportunity Stage Name");
    if (Strings.isNullOrEmpty(importEvent.opportunityStageName)) {
      importEvent.opportunityStageName = data.get("Opportunity Stage");
    }

    importEvent.recurringDonationAmount = getAmount(data, "Recurring Donation Amount");
    importEvent.recurringDonationCampaignId = data.get("Recurring Donation Campaign ID");
    importEvent.recurringDonationCampaignName = data.get("Recurring Donation Campaign Name");
    importEvent.recurringDonationInterval = data.get("Recurring Donation Interval");
    importEvent.recurringDonationName = data.get("Recurring Donation Name");
    importEvent.recurringDonationNextPaymentDate = getDate(data, "Recurring Donation Next Payment Date", env);
    importEvent.recurringDonationOwnerId = data.get("Recurring Donation Owner ID");
    importEvent.recurringDonationStartDate = getDate(data, "Recurring Donation Start Date", env);
    importEvent.recurringDonationStatus = data.get("Recurring Donation Status");

    importEvent.originalStreet = data.get("Original Street");

    importEvent.campaignName = data.get("Campaign Name");
    importEvent.campaignRecordTypeId = data.get("Campaign Record Type ID");
    importEvent.campaignRecordTypeName = data.get("Campaign Record Type Name");

    return importEvent;
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
    return Stream.of(contactEmail, contactPersonalEmail, contactWorkEmail, contactOtherEmail)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
  }
  public boolean hasEmail() {
    return !Strings.isNullOrEmpty(contactEmail)
            || !Strings.isNullOrEmpty(contactPersonalEmail)
            || !Strings.isNullOrEmpty(contactWorkEmail)
            || !Strings.isNullOrEmpty(contactOtherEmail);
  }

  public static BigDecimal getAmount(Map<String, String> data, String columnName) {
    if (!data.containsKey(columnName)) {
      return null;
    }
    String n = data.get(columnName);
    if (Strings.isNullOrEmpty(n)) {
      return null;
    }
    return new BigDecimal(n.replace("$", "").replace(",", "").trim()).setScale(2, RoundingMode.CEILING);
  }

  // TODO: Hate this code -- is there a lib that can handle it in a forgiving way?
  private static ZonedDateTime getDate(Map<String, String> data, String columnName, Environment env) {
    if (data.containsKey(columnName + " dd/mm/yyyy")) {
      return getZonedDateFromDateString(data.get(columnName + " dd/mm/yyyy"), env.getConfig().timezoneId, "d/M/yyyy");
    } else if (data.containsKey(columnName + " dd-mm-yyyy")) {
      return getZonedDateFromDateString(data.get(columnName + " dd-mm-yyyy"), env.getConfig().timezoneId, "d-M-yyyy");
    } else if (data.containsKey(columnName + " mm/dd/yyyy")) {
      return getZonedDateFromDateString(data.get(columnName + " mm/dd/yyyy"), env.getConfig().timezoneId, "M/d/yyyy");
    } else if (data.containsKey(columnName + " mm/dd/yy")) {
      return getZonedDateFromDateString(data.get(columnName + " mm/dd/yy"), env.getConfig().timezoneId, "M/d/yy");
    } else if (data.containsKey(columnName + " mm-dd-yyyy")) {
      return getZonedDateFromDateString(data.get(columnName + " mm-dd-yyyy"), env.getConfig().timezoneId, "M-d-yyyy");
    } else if (data.containsKey(columnName + " yyyy-mm-dd")) {
      return getZonedDateFromDateString(data.get(columnName + " yyyy-mm-dd"), env.getConfig().timezoneId, "yyyy-M-d");
    }

    return null;
  }

  private String removeDateSelectors(String s) {
    return s.replace("dd/mm/yyyy", "").replace("dd-mm-yyyy", "").replace("mm/dd/yyyy", "").replace("mm/dd/yy", "")
        .replace("mm-dd-yyyy", "").replace("yyyy-mm-dd", "").trim();
  }
}
