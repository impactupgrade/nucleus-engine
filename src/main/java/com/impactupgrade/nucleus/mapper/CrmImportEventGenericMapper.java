package com.impactupgrade.nucleus.mapper;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.util.Utils;
import org.apache.commons.collections.MapUtils;

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

import static com.impactupgrade.nucleus.util.Utils.checkboxToBool;
import static com.impactupgrade.nucleus.util.Utils.fullNameToFirstLast;

public class CrmImportEventGenericMapper {

  public CrmImportEvent toCrmImportEvent(Map<String, String> _data) {
    // TODO: It originally made sense to use CaseInsensitiveMap here. But, we run into issues since most
    //  impls of CaseInsensitiveMap automatically lowercase all keys and values. That wrecks havoc for CRMs like SFDC,
    //  where the API is unfortunately case sensitive. For now, keep the originals and require column heads to be
    //  case sensitive
//    CaseInsensitiveMap<String, String> data = new CaseInsensitiveMap<>(_data);
    Map<String, String> data = _data;

    CrmImportEvent importEvent = new CrmImportEvent();
    importEvent.raw = data;

    Map<String, String> accountData = filterByKeyPrefix(data, "Account ");
    importEvent.account = toCrmAccount(accountData);
    importEvent.accountNote = data.get("Account Note");

    Map<String, String> accountBillingAddressData = filterByKeyPrefix(data, "Account Billing ");
    importEvent.account.billingAddress = toCrmAddress(accountBillingAddressData);

    Map<String, String> accountMailingAddressData = filterByKeyPrefix(data, "Account Shipping ");
    importEvent.account.mailingAddress = toCrmAddress(accountMailingAddressData);

    setAccountCampaignData(importEvent, data);

    // Contact
    Map<String, String> contactData = filterByKeyPrefix(data, "Contact ");
    setContactData(importEvent, contactData);
    Map<String, String> contactMailingAddressData = filterByKeyPrefix(data, "Contact Mailing ");
    setContactAddressData(importEvent, contactMailingAddressData);

    setContactOrganizationsData(importEvent, contactData);
    setContactCampaignData(importEvent, contactData);

    // Opp
    Map<String, String> opportunityData = filterByKeyPrefix(data, "Opportunity ");
    setOpportunityData(importEvent, opportunityData);

    Map<String, String> recurringDonationData = filterByKeyPrefix(data, "Recurring Donation ");
    setRecurringDonationData(importEvent, recurringDonationData);

    // Campaign
    Map<String, String> campaignData = filterByKeyPrefix(data, "Campaign ");
    setCampaignData(importEvent, campaignData);

    // Other
    importEvent.originalStreet = data.get("Original Street");

    return importEvent;
  }

  public CrmImportEvent toCrmImportEvent(Map<String, String> _data, Map<String, String> mappings) {
    if (MapUtils.isEmpty(mappings)) {
      return toCrmImportEvent(_data);
    }

    Map<String, String> data = new HashMap<>();
    _data.entrySet().forEach(e -> {
      if (mappings.containsKey(e.getKey())) {
        // Replacing key if specified in 'mappings'
        data.put(mappings.get(e.getKey()), e.getValue());
      } else {
        // using as-is
        data.put(e.getKey(), e.getValue());
      }
    });

    CrmImportEvent crmImportEvent = toCrmImportEvent(data);
    crmImportEvent.raw = _data;
    return crmImportEvent;
  }

  public CrmImportEvent fromFBFundraiser(Map<String, String> _data) {
    if (!_data.get("Charge Action Type").equalsIgnoreCase("S")) {
      return null;
    }

    Map<String, String> data = new HashMap<>(_data);
    String opportunityName;
    if (data.get("Fundraiser Type").contains("Fundraiser")) {
      opportunityName = "Facebook Fundraiser: " + data.get("Fundraiser Title");
    } else if (!Strings.isNullOrEmpty(data.get("Fundraiser Title"))) {
      opportunityName = "Facebook Fundraiser: " + data.get("Fundraiser Title") + " (" + data.get("Fundraiser Type") + ")";
    } else {
      opportunityName = "Facebook Fundraiser: " + data.get("Fundraiser Type");
    }
    data.put("Opportunity Name", opportunityName);

    Calendar opportunityDate;
    try {
      opportunityDate = Calendar.getInstance();
      opportunityDate.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(data.get("Charge Date")));
    } catch (ParseException e) {
      throw new RuntimeException("failed to parse date", e);
    }
    data.put("Opportunity Date " + "yyyy-MM-dd", opportunityDate.toString());

    String opportunitySource = (!Strings.isNullOrEmpty(data.get("Fundraiser Title"))) ? data.get("Fundraiser Title") : data.get("Fundraiser Type");
    data.put("Opportunity Source", opportunitySource);

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
    data.put("Opportunity Description", Joiner.on("\n").join(description));


    Map<String, String> mappings = new HashMap<>();
    mappings.put("Donation Amount", "Opportunity Amount");
    mappings.put("First Name", "Contact First Name");
    mappings.put("Last Name", "Contact Last Name");
    mappings.put("Email Address", "Contact Personal Email");
    mappings.put("Payment Processor", "Opportunity Terminal");
    mappings.put("Posted", "Opportunity Stage Name");
    mappings.put("CRM Campaign ID", "Opportunity Campaign ID");

    CrmImportEvent crmImportEvent = toCrmImportEvent(data, mappings);
    crmImportEvent.raw = _data;

    return crmImportEvent;
  }

  public CrmImportEvent fromMyCustomForm(Map<String, String> _data) {
    Map<String, String> mappings = new HashMap<>();

    mappings.put("Account private name", "Account Name");

    mappings.put("first name here", "Contact First Name");
    mappings.put("last name here", "Contact Last Name");
    mappings.put("form field email", "Contact Personal Email");

    mappings.put("Donation Total field", "Opportunity Amount");
    mappings.put("Payment Processor header", "Opportunity Terminal");
    mappings.put("Closed/Lost/Won", "Opportunity Stage Name");

    return toCrmImportEvent(_data, mappings);
  }

  private CrmAccount toCrmAccount(Map<String, String> data) {
    CrmAccount crmAccount = new CrmAccount();
    crmAccount.id = data.get("ID");
    crmAccount.description = data.get("Description");
    crmAccount.name = data.get("Name");
    crmAccount.ownerId = data.get("Owner ID");
    crmAccount.recordTypeId = data.get("Record Type ID");
    crmAccount.recordTypeName = data.get("Record Type Name");
    crmAccount.website = data.get("Website");
    return crmAccount;
  }

  private CrmAddress toCrmAddress(Map<String, String> data) {
    CrmAddress crmAddress = new CrmAddress();
    crmAddress.street = data.get("Street");
    if (!Strings.isNullOrEmpty(data.get("Street 2"))) {
      crmAddress.street += ", " + data.get("Street 2");
    }
    // Also check Address (vs Street) -- happens often.
    if (Strings.isNullOrEmpty(crmAddress.street)) {
      crmAddress.street = data.get("Address");
      if (!Strings.isNullOrEmpty(data.get("Address 2"))) {
        crmAddress.street += ", " + data.get("Address 2");
      }
    }
    crmAddress.city = data.get("City");
    crmAddress.state = data.get("State");
    crmAddress.postalCode = data.get("Postal Code");
    crmAddress.country = data.get("Country");

    return crmAddress;
  }

  private void setAccountCampaignData(CrmImportEvent importEvent, Map<String, String> data) {
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
  }

  private void setContactData(CrmImportEvent importEvent, Map<String, String> data) {
    importEvent.contactId = data.get("ID");

    importEvent.contactEmail = getEmail(data, "Email");
    importEvent.contactPersonalEmail = getEmail(data, "Personal Email");
    importEvent.contactWorkEmail = getEmail(data, "Work Email");
    importEvent.contactOtherEmail = getEmail(data, "Other Email");

    if (data.get("Preferred Email") != null) {
      importEvent.contactEmailPreference = CrmImportEvent.ContactEmailPreference.fromName(data.get("Preferred Email"));
    }

    importEvent.contactSalutation = data.get("Salutation");
    importEvent.contactFirstName = Utils.nameToTitleCase(data.get("First Name"));
    importEvent.contactLastName = Utils.nameToTitleCase(data.get("Last Name"));
    if (Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(data.get("Full Name"))) {
      String[] split = fullNameToFirstLast(Utils.nameToTitleCase(data.get("Full Name")));
      importEvent.contactFirstName = split[0];
      importEvent.contactLastName = split[1];
    }

    importEvent.contactDescription = data.get("Description");
    importEvent.contactPhone = data.get("Phone");
    importEvent.contactHomePhone = data.get("Home Phone");
    importEvent.contactMobilePhone = data.get("Mobile Phone");
    importEvent.contactWorkPhone = data.get("Work Phone");
    importEvent.contactPhonePreference = CrmImportEvent.ContactPhonePreference.fromName(data.get("Preferred Phone"));

    importEvent.contactNote = data.get("Note");
    importEvent.contactOptInEmail = checkboxToBool(data.get("Email Opt In"));
    importEvent.contactOptOutEmail = "no".equalsIgnoreCase(data.get("Email Opt In")) || "false".equalsIgnoreCase(data.get("Email Opt In")) || "0".equalsIgnoreCase(data.get("Email Opt In"));
    importEvent.contactOptInSms = checkboxToBool(data.get(" SMS Opt In"));
    importEvent.contactOptOutSms = "no".equalsIgnoreCase(data.get("SMS Opt In")) || "false".equalsIgnoreCase(data.get("SMS Opt In")) || "0".equalsIgnoreCase(data.get("SMS Opt In"));
    importEvent.contactOwnerId = data.get("Owner ID");
    importEvent.contactRecordTypeId = data.get("Record Type ID");
    importEvent.contactRecordTypeName = data.get("Record Type Name");
  }

  private void setContactAddressData(CrmImportEvent importEvent, Map<String, String> data) {
    CrmAddress crmAddress = toCrmAddress(data);
    importEvent.contactMailingStreet = crmAddress.street;
    importEvent.contactMailingCity = crmAddress.city;
    importEvent.contactMailingState = crmAddress.state;
    importEvent.contactMailingZip = crmAddress.postalCode;
    importEvent.contactMailingCountry = crmAddress.country;
  }

  private void setContactOrganizationsData(CrmImportEvent importEvent, Map<String, String> data) {
    for (int i = 1; i <= 5; i++) {
      String columnPrefix = "Organization " + i;
      if (data.keySet().stream().anyMatch(k -> k.startsWith(columnPrefix)) && !Strings.isNullOrEmpty(data.get(columnPrefix + " Name"))) {
        Map<String, String> orgData = filterByKeyPrefix(data, columnPrefix);
        CrmAccount organization = toCrmAccount(orgData);
        organization.recordType = EnvironmentConfig.AccountType.ORGANIZATION;

        Map<String, String> orgBillingAddressData = filterByKeyPrefix(data, columnPrefix + " Billing");
        organization.billingAddress = toCrmAddress(orgBillingAddressData);

        Map<String, String> orgMailingAddressData = filterByKeyPrefix(data, columnPrefix + " Shipping");
        organization.mailingAddress = toCrmAddress(orgMailingAddressData);

        importEvent.contactOrganizations.add(organization);
        importEvent.contactOrganizationRoles.add(data.get(columnPrefix + " Role"));
      }
    }
  }

  private void setContactCampaignData(CrmImportEvent importEvent, Map<String, String> data) {
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
  }

  private void setOpportunityData(CrmImportEvent importEvent, Map<String, String> data) {
    importEvent.opportunityId = data.get("ID");
    importEvent.opportunityAmount = getAmount(data, "Amount");
    importEvent.opportunityCampaignId = data.get("Campaign ID");
    importEvent.opportunityCampaignName = data.get("Campaign Name");
    importEvent.opportunityDate = getDate(data, "Date");
    importEvent.opportunityDescription = data.get("Description");
    importEvent.opportunityName = data.get("Name");
    importEvent.opportunityOwnerId = data.get("Owner ID");
    importEvent.opportunityRecordTypeId = data.get("Record Type ID");
    importEvent.opportunityRecordTypeName = data.get("Record Type Name");
    importEvent.opportunitySource = data.get("Source");
    importEvent.opportunityStageName = data.get("Stage Name");
    if (Strings.isNullOrEmpty(importEvent.opportunityStageName)) {
      importEvent.opportunityStageName = data.get("Stage");
    }
    importEvent.opportunityTerminal = data.get("Terminal");
  }

  private void setRecurringDonationData(CrmImportEvent importEvent, Map<String, String> data) {
    importEvent.recurringDonationId = data.get("ID");
    importEvent.recurringDonationAmount = getAmount(data, "Amount");
    importEvent.recurringDonationCampaignId = data.get("Campaign ID");
    importEvent.recurringDonationCampaignName = data.get("Campaign Name");
    importEvent.recurringDonationInterval = data.get("Interval");
    importEvent.recurringDonationName = data.get("Name");
    importEvent.recurringDonationNextPaymentDate = getDate(data, "Next Payment Date");
    importEvent.recurringDonationOwnerId = data.get("Owner Name");
    importEvent.recurringDonationStartDate = getDate(data, "Start Date");
    importEvent.recurringDonationStatus = data.get("Status");
  }

  private void setCampaignData(CrmImportEvent importEvent, Map<String, String> data) {
    importEvent.campaignId = data.get("ID");
    importEvent.campaignName = data.get("Name");
    importEvent.campaignRecordTypeId = data.get("Record Type ID");
    importEvent.campaignRecordTypeName = data.get("Record Type Name");
  }

  // Utils
  private Map<String, String> filterByKeyPrefix(Map<String, String> map, String keyPrefix) {
    if (MapUtils.isEmpty(map) || Strings.isNullOrEmpty(keyPrefix)) {
      return map;
    }

    Map<String, String> filtered = new HashMap<>();
    map.entrySet().forEach(e -> {
      if (e.getKey().startsWith(keyPrefix)) {
        String key = e.getKey().replaceFirst(keyPrefix, "");
        filtered.put(key, e.getValue());
      }
    });

    return filtered;
  }

  private String getEmail(Map<String, String> data, String emailKey) {
    String email = data.get(emailKey);
    if (email == null) return null;
    return Utils.noWhitespace(email.toLowerCase(Locale.ROOT));
  }

  private BigDecimal getAmount(Map<String, String> data, String columnName) {
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

}
