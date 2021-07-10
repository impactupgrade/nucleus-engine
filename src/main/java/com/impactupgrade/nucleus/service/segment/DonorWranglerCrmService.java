/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
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
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;

// TODO: Copies from the old Donation Spring code. Needs cleaned up and rethought...
// TODO: If needs expand, make this into an open source client lib

public class DonorWranglerCrmService implements CrmService {

  private static final Logger log = LogManager.getLogger(DonorWranglerCrmService.class);

  private static String CLIENT_NAME = "donationSpring";
  // Note: This is more of an "integration key" specifically for Donation Spring as a whole. Ok to be public.
  private static String CLIENT_SECRET = "7UbODLnU/RGbN8hedFVBJuBhxJZtpOWC5oxjjoBeD8Hi6DnIgRf/yYVTxF6MarTC";

  private static String SOLICITOR = "";

  private final String apiKey;
  protected final Environment env;
  private final ObjectMapper mapper;

  public DonorWranglerCrmService(Environment env) {
    this.apiKey = System.getenv("DONORWRANGLER_API_KEY");
    this.env = env;

    mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "getDonorByEmail"));
    params.add(new BasicNameValuePair("email", email));

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    log.info("Donor Wrangler getDonorByEmail response: {}", responseString);
    // TODO: test the new API response
    JSONObject jsonObject = new JSONObject(responseString);
    JSONObject jsonDonor = (JSONObject) jsonObject.get("donor");
    int donorId = jsonDonor.getInt("id");

    CrmContact crmContact = new CrmContact();
    crmContact.id = donorId + "";
    return Optional.of(crmContact);
  }

  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    // TODO: searchDonors
  }

  @Override
  public Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: ask Josh
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: ask Josh
  }

  @Override
  public String insertAccount(PaymentGatewayEvent PaymentGatewayEvent) throws Exception {
    // TODO: no accounts in Donor Wrangler (right?), so this is likely to mess with upstream
    return null;
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "addUpdateDonor"));
    // TODO: hit donationTemplate and see if any params changed
    params.add(new BasicNameValuePair("donor[address1]", Strings.nullToEmpty(crmContact.address.street)));
    params.add(new BasicNameValuePair("donor[city]", Strings.nullToEmpty(crmContact.address.city)));
    params.add(new BasicNameValuePair("donor[state]", Strings.nullToEmpty(crmContact.address.state)));
    params.add(new BasicNameValuePair("donor[zip]", Strings.nullToEmpty(crmContact.address.postalCode)));
    params.add(new BasicNameValuePair("donor[email]", Strings.nullToEmpty(crmContact.email)));
    // TODO: always mobile?
    params.add(new BasicNameValuePair("donor[phone]", Strings.nullToEmpty(crmContact.mobilePhone)));
    params.add(new BasicNameValuePair("donor[first]", Strings.nullToEmpty(crmContact.firstName)));
    params.add(new BasicNameValuePair("donor[last]", Strings.nullToEmpty(crmContact.lastName)));

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    log.info("Donor Wrangler addUpdateDonor response: {}", responseString);

    // TODO: test the new API response
    JSONObject jsonObject = new JSONObject(responseString);
    JSONObject jsonDonor = (JSONObject) jsonObject.get("donor");
    int donorId = jsonDonor.getInt("id");
    return donorId + "";
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "addUpdateDonation"));
    // TODO: hit donationTemplate and see if any params changed
    params.add(new BasicNameValuePair("donation[giftType]", "Gift"));
    params.add(new BasicNameValuePair("donation[donorId]", paymentGatewayEvent.getCrmContact().id));
    params.add(new BasicNameValuePair("donation[notes]", Strings.nullToEmpty(donation.getMemo())));
    params.add(new BasicNameValuePair("donation[source]", paymentGatewayEvent.getGatewayName()));
    params.add(new BasicNameValuePair("donation[solicitor]", SOLICITOR));
    params.add(new BasicNameValuePair("donation[fund]", Strings.nullToEmpty(fund)));
    params.add(new BasicNameValuePair("donation[directedPurpose]", Strings.nullToEmpty(directedPurpose)));
    params.add(new BasicNameValuePair("donation[campaign]", Strings.nullToEmpty(campaign)));
    params.add(new BasicNameValuePair("donation[giftAmount]", paymentGatewayEvent.getTransactionAmountInDollars().toString()));
    // TODO: need to update this on payout events
//    params.add(new BasicNameValuePair("donation[feeAmount]", donation.getStripeFeeInDollars() + ""));
    params.add(new BasicNameValuePair("donation[referenceNum]", paymentGatewayEvent.getTransactionId()));

    HttpResponse response = executePost(params);
    log.info("Donor Wrangler addUpdateDonation response: {}", getResponseString(response));
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: ask Josh
  }

  private HttpResponse executePost(List<NameValuePair> params) throws IOException {
    HttpPost post = new HttpPost("https://" + env.getDonorWranglerApi() + ".donorwrangler.com/api.php");
    params.add(new BasicNameValuePair("source", CLIENT_NAME));
    params.add(new BasicNameValuePair("secret", CLIENT_SECRET));
    params.add(new BasicNameValuePair("key", env.getDonorWranglerKey()));

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
