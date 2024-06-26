/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// TODO: Could someday become an open source lib...

public class DonorWranglerClient {

  private static final Logger log = LogManager.getLogger(DonorWranglerClient.class);

  private static final String CLIENT_NAME = "donationSpring";
  // Note: This is more of an "integration key" specifically for Donation Spring as a whole. Ok to be public.
  private static final String CLIENT_SECRET = "7UbODLnU/RGbN8hedFVBJuBhxJZtpOWC5oxjjoBeD8Hi6DnIgRf/yYVTxF6MarTC";

  private static final String SOLICITOR = "";

  protected final Environment env;

  public DonorWranglerClient(Environment env) {
    this.env = env;
  }

  public Optional<DwDonor> getContactByEmail(String email) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "getDonorByEmail"));
    params.add(new BasicNameValuePair("email", email));

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    env.logJobInfo("Donor Wrangler getDonorByEmail response: {}", responseString);
    JSONObject jsonObject = new JSONObject(responseString);

    if (!jsonObject.has("result")) {
      return Optional.empty();
    }

    JSONObject jsonDonor = (JSONObject) jsonObject.get("result");
    DwDonor donor = DwDonor.fromJson(jsonDonor);
    return Optional.of(donor);
  }

  public Optional<DwDonor> contactSearch(String searchKey, String searchValue) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "searchDonors"));
    params.add(new BasicNameValuePair("searchKey", searchKey));
    params.add(new BasicNameValuePair("searchValue", searchValue));
    params.add(new BasicNameValuePair("compMode", "="));

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    env.logJobInfo("Donor Wrangler contactSearch response: {}", responseString);
    JSONObject jsonObject = new JSONObject(responseString);
    JSONArray jsonArray = null;
    if (jsonObject.has("results")) {
      jsonArray = jsonObject.getJSONArray("results");
    }

    if (jsonArray == null || jsonArray.isEmpty()) {
      return Optional.empty();
    } else {
      if (jsonArray.length() > 1) {
        env.logJobWarn("multiple contacts found for {}={}; returning the first", searchKey, searchValue);
      }
      JSONObject jsonDonor = (JSONObject) jsonArray.get(0);
      DwDonor donor = DwDonor.fromJson(jsonDonor);
      return Optional.of(donor);
    }
  }

  // TODO: For now, cheating and using CrmContact. But if we pull this to a separate lib in the future, will need
  //  to expand DwDonor.
  public String upsertContact(CrmContact crmContact) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "addUpdateDonor"));
    params.add(new BasicNameValuePair("id", crmContact.id == null ? "-1" : crmContact.id));
    params.add(new BasicNameValuePair("donorInfo[address1]", Strings.nullToEmpty(crmContact.mailingAddress.street)));
    params.add(new BasicNameValuePair("donorInfo[city]", Strings.nullToEmpty(crmContact.mailingAddress.city)));
    params.add(new BasicNameValuePair("donorInfo[state]", Strings.nullToEmpty(crmContact.mailingAddress.state)));
    params.add(new BasicNameValuePair("donorInfo[zip]", Strings.nullToEmpty(crmContact.mailingAddress.postalCode)));
    params.add(new BasicNameValuePair("donorInfo[email]", Strings.nullToEmpty(crmContact.email)));
    params.add(new BasicNameValuePair("donorInfo[phone]", Strings.nullToEmpty(crmContact.mobilePhone)));
    params.add(new BasicNameValuePair("donorInfo[first]", crmContact.firstName));
    params.add(new BasicNameValuePair("donorInfo[last]", crmContact.lastName));
    params.add(new BasicNameValuePair("donorInfo[display]", crmContact.getFullName()));

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    env.logJobInfo("Donor Wrangler addUpdateDonor response: {}", responseString);

    JSONObject jsonObject = new JSONObject(responseString);

    if (!jsonObject.has("result")) {
      return null;
    }

    JSONObject jsonDonor = (JSONObject) jsonObject.get("result");
    int donorId = jsonDonor.getInt("id");
    return donorId + "";
  }

  public List<DwDonation> getDonationsByDonorId(String donorId) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "fetchDonorDonations"));
    params.add(new BasicNameValuePair("donorId", donorId));

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    env.logJobInfo("Donor Wrangler getDonationsByDonorId response: {}", responseString);
    JSONObject jsonObject = new JSONObject(responseString);
    JSONArray jsonArray = jsonObject.getJSONArray("results");

    List<DwDonation> donations = new ArrayList<>();
    for (int i = 0; i < jsonArray.length(); i++) {
      JSONObject jsonDonation = jsonArray.getJSONObject(i);
      // TODO: Josh, odd that this is a String while id is int everywhere else.
      donations.add(new DwDonation(
          jsonDonation.getString("id"),
          jsonDonation.getString("giftAmount"),
          jsonDonation.getString("fund"),
          jsonDonation.getString("directedPurpose"),
          jsonDonation.getString("source")
      ));
    }
    return donations;
  }

  // TODO: For now, cheating and using CrmDonation. But if we pull this to a separate lib in the future, will need
  //  to expand the generic model.
  public String insertDonation(CrmDonation crmDonation) throws Exception {
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("action", "addUpdateDonation"));
    params.add(new BasicNameValuePair("id", "-1"));
    params.add(new BasicNameValuePair("donationInfo[giftType]", "Gift"));
    params.add(new BasicNameValuePair("donationInfo[created]", DateTimeFormatter.ofPattern("yyyy-MM-dd").format(crmDonation.closeDate)));
    params.add(new BasicNameValuePair("donationInfo[donorId]", crmDonation.contact.id));
    params.add(new BasicNameValuePair("donationInfo[notes]", Strings.nullToEmpty(crmDonation.description)));
    params.add(new BasicNameValuePair("donationInfo[source]", crmDonation.gatewayName));
    params.add(new BasicNameValuePair("donationInfo[solicitor]", SOLICITOR));
//    params.add(new BasicNameValuePair("donationInfo[campaign]", Strings.nullToEmpty(campaign)));
    params.add(new BasicNameValuePair("donationInfo[giftAmount]", crmDonation.amount + ""));
    // TODO: need to update this on payout events
//    params.add(new BasicNameValuePair("donationInfo[feeAmount]", donation.getStripeFeeInDollars() + ""));
    params.add(new BasicNameValuePair("donationInfo[referenceNum]", crmDonation.transactionId));

    // if DS provided a configurable key/value, use them -- otherwise, default to General
    String subselectionValue = crmDonation.getMetadataValue("Subselection Value");
    if (!Strings.isNullOrEmpty(subselectionValue) && !"general".equalsIgnoreCase(subselectionValue)) {
//      params.add(new BasicNameValuePair("donationInfo[fund]", paymentGatewayEvent.getMetadataValue("Subselection Key")));
      params.add(new BasicNameValuePair("donationInfo[directedPurpose]", Strings.nullToEmpty(crmDonation.getMetadataValue("Subselection Value"))));
    } else if (crmDonation.getMetadataValue(env.getConfig().metadataKeys.fund) != null) {
//      params.add(new BasicNameValuePair("donationInfo[fund]", "Fund"));
      params.add(new BasicNameValuePair("donationInfo[directedPurpose]", crmDonation.getMetadataValue(env.getConfig().metadataKeys.fund)));
    } else {
//      params.add(new BasicNameValuePair("donationInfo[fund]", "General"));
    }

    HttpResponse response = executePost(params);
    String responseString = getResponseString(response);
    env.logJobInfo("Donor Wrangler addUpdateDonation response: {}", responseString);

    JSONObject jsonObject = new JSONObject(responseString);

    if (!jsonObject.has("result")) {
      return null;
    }

    JSONObject jsonDonor = (JSONObject) jsonObject.get("result");
    int donationId = jsonDonor.getInt("id");
    return donationId + "";
  }

  public record DwDonor(
    String id,
    String firstName,
    String lastName,
    String email,
    String phone,
    String address1,
    String address2,
    String city,
    String state,
    String zip
  ) {
    public static DwDonor fromJson(JSONObject jsonDonor) {
      return new DwDonor(
          jsonDonor.getInt("id") + "",
          jsonDonor.getString("first"),
          jsonDonor.getString("last"),
          jsonDonor.getString("email"),
          jsonDonor.getString("phone"),
          jsonDonor.getString("address1"),
          jsonDonor.getString("address2"),
          jsonDonor.getString("city"),
          jsonDonor.getString("state"),
          jsonDonor.getString("zip")
      );
    }
  }

  public record DwDonation(
      String id,
      String giftAmount,
      String fund,
      String directedPurpose,
      String source
  ){}

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
