/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

// TODO: Copies from the old Donation Spring code. Needs cleaned up and rethought...
// TODO: If needs expand, make this into an open source client lib

public class DonorWranglerCrmService implements BasicCrmService {

  private static final Logger log = LogManager.getLogger(DonorWranglerCrmService.class);

  private static String CLIENT_NAME = "donationSpring";
  // Note: This is more of an "integration key" specifically for Donation Spring as a whole. Ok to be public.
  private static String CLIENT_SECRET = "7UbODLnU/RGbN8hedFVBJuBhxJZtpOWC5oxjjoBeD8Hi6DnIgRf/yYVTxF6MarTC";

  private static String SOLICITOR = "";

  protected Environment env;
  private ObjectMapper mapper;

  @Override
  public String name() { return "bloomerang"; }

  @Override
  public void init(Environment env) {
    this.env = env;

    mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    // TODO: no accounts in Donor Wrangler (right?), so this is likely to mess with upstream
    return Optional.empty();
  }

  @Override
  public Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception {
    // TODO: no accounts in Donor Wrangler (right?), so this is likely to mess with upstream
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    return contactSearch("id", id);
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "getDonorByEmail"));
    params.add(new BasicNameValuePair("email", email));

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    log.info("Donor Wrangler getDonorByEmail response: {}", responseString);
    JSONObject jsonObject = new JSONObject(responseString);
    JSONObject jsonDonor = (JSONObject) jsonObject.get("result");
    int donorId = jsonDonor.getInt("id");

    CrmContact crmContact = new CrmContact();
    crmContact.id = donorId + "";
    return Optional.of(crmContact);
  }

  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    return contactSearch("phone", phone);
  }

  public Optional<CrmContact> contactSearch(String searchKey, String searchValue) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "searchDonors"));
    params.add(new BasicNameValuePair("searchKey", searchKey));
    params.add(new BasicNameValuePair("searchValue", searchValue));
    params.add(new BasicNameValuePair("compMode", "="));

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    log.info("Donor Wrangler v response: {}", responseString);
    JSONObject jsonObject = new JSONObject(responseString);
    JSONArray jsonArray = jsonObject.getJSONArray("results");

    if (jsonArray.isEmpty()) {
      return Optional.empty();
    } else {
      if (jsonArray.length() > 1) {
        log.warn("multiple contacts found for {}={}; returning the first", searchKey, searchValue);
      }
      JSONObject jsonDonor = (JSONObject) jsonArray.get(0);
      int donorId = jsonDonor.getInt("id");

      CrmContact crmContact = new CrmContact();
      crmContact.id = donorId + "";
      return Optional.of(crmContact);
    }
  }

  @Override
  public String insertAccount(CrmAccount crmAccount) throws Exception {
    // TODO: no accounts in Donor Wrangler (right?), so this is likely to mess with upstream
    return null;
  }

  @Override
  public void updateAccount(CrmAccount crmAccount) throws Exception {
    // TODO: no accounts in Donor Wrangler (right?), so this is likely to mess with upstream
  }

  @Override
  public String insertAccount(PaymentGatewayEvent PaymentGatewayEvent) throws Exception {
    // TODO: no accounts in Donor Wrangler (right?), so this is likely to mess with upstream
    return null;
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    return upsertContact(crmContact);
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {
    upsertContact(crmContact);
  }

  private String upsertContact(CrmContact crmContact) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "addUpdateDonor"));
    params.add(new BasicNameValuePair("id", crmContact.id == null ? "-1" : crmContact.id));
    params.add(new BasicNameValuePair("donorInfo[address1]", Strings.nullToEmpty(crmContact.address.street)));
    params.add(new BasicNameValuePair("donorInfo[city]", Strings.nullToEmpty(crmContact.address.city)));
    params.add(new BasicNameValuePair("donorInfo[state]", Strings.nullToEmpty(crmContact.address.state)));
    params.add(new BasicNameValuePair("donorInfo[zip]", Strings.nullToEmpty(crmContact.address.postalCode)));
    params.add(new BasicNameValuePair("donorInfo[email]", Strings.nullToEmpty(crmContact.email)));
    params.add(new BasicNameValuePair("donorInfo[phone]", Strings.nullToEmpty(crmContact.mobilePhone)));
    params.add(new BasicNameValuePair("donorInfo[first]", Strings.nullToEmpty(crmContact.firstName)));
    params.add(new BasicNameValuePair("donorInfo[last]", Strings.nullToEmpty(crmContact.lastName)));

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    log.info("Donor Wrangler addUpdateDonor response: {}", responseString);

    JSONObject jsonObject = new JSONObject(responseString);
    JSONObject jsonDonor = (JSONObject) jsonObject.get("result");
    int donorId = jsonDonor.getInt("id");
    return donorId + "";
  }

  @Override
  public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
    // TODO: Josh?
    return Optional.empty();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    // TODO: Josh?
    return Optional.empty();
  }

  @Override
  public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    // TODO: API technically has fetchDonorDonations, but this is really only used to power the Donor Portal.
    return Collections.emptyList();
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "addUpdateDonation"));
    params.add(new BasicNameValuePair("id", "-1"));
    params.add(new BasicNameValuePair("donationInfo[giftType]", "Gift"));
    params.add(new BasicNameValuePair("donationInfo[donorId]", paymentGatewayEvent.getCrmContact().id));
    params.add(new BasicNameValuePair("donationInfo[notes]", Strings.nullToEmpty(paymentGatewayEvent.getTransactionDescription())));
    params.add(new BasicNameValuePair("donationInfo[source]", paymentGatewayEvent.getGatewayName()));
    params.add(new BasicNameValuePair("donationInfo[solicitor]", SOLICITOR));
//    params.add(new BasicNameValuePair("donationInfo[campaign]", Strings.nullToEmpty(campaign)));
    params.add(new BasicNameValuePair("donationInfo[giftAmount]", paymentGatewayEvent.getTransactionAmountInDollars().toString()));
    // TODO: need to update this on payout events
//    params.add(new BasicNameValuePair("donationInfo[feeAmount]", donation.getStripeFeeInDollars() + ""));
    params.add(new BasicNameValuePair("donationInfo[referenceNum]", paymentGatewayEvent.getTransactionId()));

    // if DS provided a configurable key/value, use them -- otherwise, default to Fund
    if (paymentGatewayEvent.getMetadataValue("Subselection Key") != null) {
      params.add(new BasicNameValuePair("donationInfo[fund]", paymentGatewayEvent.getMetadataValue("Subselection Key")));
      params.add(new BasicNameValuePair("donationInfo[directedPurpose]", Strings.nullToEmpty(paymentGatewayEvent.getMetadataValue("Subselection Value"))));
    } else if (paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund) != null) {
      params.add(new BasicNameValuePair("donationInfo[fund]", "Fund"));
      params.add(new BasicNameValuePair("donationInfo[directedPurpose]", paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund)));
    } else {
      params.add(new BasicNameValuePair("donationInfo[fund]", "General"));
    }

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    log.info("Donor Wrangler addUpdateDonation response: {}", responseString);

    JSONObject jsonObject = new JSONObject(responseString);
    JSONObject jsonDonor = (JSONObject) jsonObject.get("result");
    int donationId = jsonDonor.getInt("id");
    return donationId + "";
  }

  @Override
  public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO
  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: Josh
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: Josh
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: Josh
    return Optional.empty();
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: Josh
    return null;
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: Josh
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // TODO: Josh
    return Optional.empty();
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // TODO: Josh
  }

  @Override
  public void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // TODO: Josh
  }

  @Override
  public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    // TODO: API technically has fetchDonorDonations, but this is really only used to power the Donor Portal.
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getContactsUpdatedSince(Calendar calendar) throws Exception {
    // TODO: Josh
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getDonorContactsSince(Calendar calendar) throws Exception {
    // TODO: Josh
    return Collections.emptyList();
  }

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    // TODO
  }

  @Override
  public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {
    // TODO
  }

  private HttpResponse executePost(List<NameValuePair> params) throws IOException {
    HttpPost post = new HttpPost("https://" + env.getConfig().donorwrangler.subdomain + ".donorwrangler.com/api.php");
    params.add(new BasicNameValuePair("source", CLIENT_NAME));
    params.add(new BasicNameValuePair("secret", CLIENT_SECRET));
    params.add(new BasicNameValuePair("key", env.getConfig().donorwrangler.secretKey));

    post.setEntity(new UrlEncodedFormEntity(params));

    HttpClient httpClient = HttpClientBuilder.create().build();
    return httpClient.execute(post);
  }

  private String getResponseString(HttpResponse response) throws IOException {
    try (InputStream stream = response.getEntity().getContent()) {
      return IOUtils.toString(stream, "UTF-8");
    }
  }
}
