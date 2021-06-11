/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Joiner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CrmImportEvent {

  private static final Logger log = LogManager.getLogger(CrmImportEvent.class.getName());

  private final Map<String, String> raw;

  private String city;
  private String country;
  private String email;
  private String firstName;
  private String homePhone;
  private String lastName;
  private String mobilePhone;
  private String ownerId;
  private String state;
  private String street;
  private String zip;

  private boolean optInEmail;
  private boolean optInSms;

  private BigDecimal opportunityAmount;
  private String opportunityCampaignExternalRef;
  private String opportunityCampaignId;
  private Calendar opportunityDate;
  private String opportunityDescription;
  private String opportunityName;
  private String opportunityOwnerId;
  private String opportunityRecordTypeId;
  private String opportunityRecordTypeName;
  private String opportunitySource;
  private String opportunityStageName;
  private String opportunityTerminal;

  public CrmImportEvent(Map<String, String> raw) {
    this.raw = raw;
  }

  public static List<CrmImportEvent> fromGeneric(List<Map<String, String>> data) {
    return data.stream().map(CrmImportEvent::fromGeneric).collect(Collectors.toList());
  }

  public static CrmImportEvent fromGeneric(Map<String, String> data) {
    CrmImportEvent importEvent = new CrmImportEvent(data);

    importEvent.city = data.get("City");
    importEvent.country = data.get("Country");
    importEvent.email = data.get("Email");
    importEvent.firstName = data.get("First Name");
    importEvent.homePhone = data.get("Home Phone");
    importEvent.lastName = data.get("Last Name");
    importEvent.mobilePhone = data.get("Mobile Phone");
    importEvent.opportunityAmount = getAmount(data, "Opportunity Amount");
    importEvent.ownerId = data.get("Owner ID");
    importEvent.state = data.get("State");
    importEvent.street = data.get("Address");
    importEvent.zip = data.get("PostCode");

    importEvent.optInEmail = "yes".equalsIgnoreCase(data.get("Email Opt In")) || "true".equalsIgnoreCase(data.get("Email Opt In"));
    importEvent.optInSms = "yes".equalsIgnoreCase(data.get("SMS Opt In")) || "true".equalsIgnoreCase(data.get("SMS Opt In"));

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
      }
    } catch (ParseException e) {
      log.warn("failed to parse date", e);
    }

    importEvent.opportunityCampaignExternalRef = data.get("External Campaign Ref");
    importEvent.opportunityCampaignId = data.get("Campaign ID");
    importEvent.opportunityDescription = data.get("Opportunity Description");
    importEvent.opportunityName = data.get("Opportunity Name");
    importEvent.opportunityOwnerId = data.get("Owner ID");
    importEvent.opportunityRecordTypeId = data.get("Opportunity Record Type ID");
    importEvent.opportunityRecordTypeName = data.get("Opportunity Record Type Name");
    importEvent.opportunityStageName = data.get("Opportunity Stage Name");

    return importEvent;
  }

  public static List<CrmImportEvent> fromFBFundraiser(List<Map<String, String>> data) {
    return data.stream().map(CrmImportEvent::fromFBFundraiser).collect(Collectors.toList());
  }

  // Taken from a sample CSV export from STS's Jan-Feb 2021 FB fundraisers.
  public static CrmImportEvent fromFBFundraiser(Map<String, String> data) {
    CrmImportEvent importEvent = new CrmImportEvent(data);

    // Note: commented-out fields were deemed not needed, for now
//    importEvent. = data.get("Charge Time");
//    importEvent. = data.get("Donation Amount");
//    importEvent. = data.get("FB Fee");
    importEvent.opportunityAmount = getAmount(data, "Net Payout Amount");
//    importEvent. = data.get("Payout Currency");
//    importEvent. = data.get("Sender Currency");
//    importEvent. = data.get("Tax Amount");
//    importEvent. = data.get("Tax USD Amount");
//    importEvent. = data.get("Charge Action Type");
    try {
      importEvent.opportunityDate.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(data.get("Charge Date")));
    } catch (ParseException e) {
      log.warn("failed to parse date", e);
    }
    importEvent.firstName = data.get("First Name");
    importEvent.lastName = data.get("Last Name");
    importEvent.email = data.get("Email Address");
//    importEvent. = data.get("Charity ID");
    importEvent.opportunitySource = data.get("Fundraiser Title");
    importEvent.opportunityTerminal = data.get("Payment Processor");
//    importEvent. = data.get("Matching Donation");
//    importEvent. = data.get("Charge Time PT");

    List<String> description = new ArrayList<>();
    description.add("Fundraiser Title: " + data.get("Fundraiser Title"));
    description.add("Fundraiser Type: " + data.get("Fundraiser Type"));
    description.add("Campaign Owner Name: " + data.get("Campaign Owner Name"));
    description.add("Campaign ID: " + data.get("Campaign ID"));
    description.add("Permalink: " + data.get("Permalink"));
    description.add("Payment ID: " + data.get("Payment ID"));
    description.add("Source Name: " + data.get("Source Name"));
    importEvent.opportunityDescription = Joiner.on("\n").join(description);

    return importEvent;
  }

  private static BigDecimal getAmount(Map<String, String> data, String columnName) {
    if (!data.containsKey(columnName)) {
      return null;
    }
    return new BigDecimal(data.get(columnName).replace("$", "").replace(",", "")
        .trim()).setScale(2, RoundingMode.CEILING);
  }

  public String getCity() {
    return city;
  }

  public String getCountry() {
    return country;
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

  public String getState() {
    return state;
  }

  public String getStreet() {
    return street;
  }

  public String getZip() {
    return zip;
  }

  public boolean isOptInEmail() {
    return optInEmail;
  }

  public boolean isOptInSms() {
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

  public String getOpportunitySource() {
    return opportunitySource;
  }

  public String getOpportunityStageName() {
    return opportunityStageName;
  }

  public String getOpportunityTerminal() {
    return opportunityTerminal;
  }

  public Map<String, String> getRaw() {
    return raw;
  }
}
