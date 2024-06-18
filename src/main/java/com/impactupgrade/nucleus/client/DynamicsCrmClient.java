/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DynamicsCrmClient extends OAuthClient {

  private final static String TOKEN_URL = "https://login.microsoftonline.com";
  private final static String API_URL = "/api/data/v9.2";

  public DynamicsCrmClient(Environment env) {
    super("dynamics", env);
  }

  @Override
  protected OAuthContext oAuthContext() {
    return new ClientCredentialsOAuthContext(
        env.getConfig().dynamicsPlatform,
        TOKEN_URL + "/" + env.getConfig().dynamicsPlatform.tenantId + "/oauth2/token",
        true
    );
  }

  // Contact
  public Contact getContactById(String id) {
    return getContact("(" + id + ")");
  }

  public List<Contact> getContacts() {
    EntitiesResponse entitiesResponse =  HttpClient.get(env.getConfig().dynamicsPlatform.resourceUrl + API_URL + "/contacts", headers(), EntitiesResponse.class);
    return entitiesResponse.entities;
  }

  public Contact getContactByEmail(String email) {
    String emailFilter = "emailaddress1 eq '" + email + "'";
    return getContact("?$filter=" + encodeUrl(emailFilter));
  }

  public Contact getContactByPhoneNumber(String phoneNumber) {
    String mobilePhoneFilter = "mobilephone eq '" + phoneNumber + "'";
    String telephone1Filter = "telephone1 eq '" + phoneNumber + "'";
    return getContact("?$filter=" + encodeUrl(mobilePhoneFilter + " or " + telephone1Filter));
  }

  public Contact insertContact(Contact contact) throws JsonProcessingException {
    String url = env.getConfig().dynamicsPlatform.resourceUrl + API_URL + "/contacts";
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    String json = objectMapper.writeValueAsString(contact);

    HttpClient.HeaderBuilder headerBuilder = headers();
    // to return created entity in response
    headerBuilder.header("Prefer", "return=representation");
    return HttpClient.post(url, json, MediaType.APPLICATION_JSON, headerBuilder, Contact.class);

    // empty response with contact id in 'Location' header:
//    Response response = HttpClient.post(url, contact, MediaType.APPLICATION_JSON, headers());
//    if (response.getStatus() < 300) {
//      return response.getHeaderString("Location");
//    } else {
//      log.error("POST failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
//      return null;
//    }
  }

  public Contact updateContact(Contact contact) throws Exception {
    headers();
    String contactId = contact.id;
    String url = env.getConfig().dynamicsPlatform.resourceUrl + API_URL + "/contacts(" + contactId + ")";
    contact.id = null; // ID in payload results in Bad Request
    patch(url, headers().headersMap(), contact);
    contact.id = contactId;
    return contact;
  }

  private Contact getContact(String contactUrl) {
    return HttpClient.get(env.getConfig().dynamicsPlatform.resourceUrl + API_URL + "/contacts" + contactUrl, headers(), Contact.class);
  }

  // Opportunity
  public List<Opportunity> getOpportunities(List<String> transactionIds) {
    String transactionIdFilter = transactionIds.stream()
        .map(id -> env.getConfig().dynamicsPlatform.fieldDefinitions.paymentGatewayTransactionId + " eq '" + id + "'")
        .collect(Collectors.joining(" or "));
    return getOpportunities("?$filter=" + encodeUrl(transactionIdFilter));
  }

  public List<Opportunity> getOpportunities(String opportunitiesUrl) {
    EntitiesResponse entitiesResponse =  HttpClient.get(env.getConfig().dynamicsPlatform.resourceUrl + API_URL + "/opportunities" + opportunitiesUrl, headers(), EntitiesResponse.class);
    return entitiesResponse.entities;
  }

  public Opportunity insertOpportunity(Opportunity opportunity) throws JsonProcessingException {
    String url = env.getConfig().dynamicsPlatform.resourceUrl + API_URL + "/opportunities";
    //String json = new Gson().toJson(opportunity);
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    String json = objectMapper.writeValueAsString(opportunity);

    HttpClient.HeaderBuilder headerBuilder = headers();
    // to return created entity in response
    headerBuilder.header("Prefer", "return=representation");
    return HttpClient.post(url, json, MediaType.APPLICATION_JSON, headerBuilder, Opportunity.class);
  }

  public Opportunity updateOpportunity(Opportunity opportunity) throws Exception {
    headers();
    String oppId = opportunity.id;
    String url = env.getConfig().dynamicsPlatform.resourceUrl + API_URL + "/opportunities(" + oppId + ")";
    opportunity.id = null; // ID in payload results in Bad Request
    patch(url, headers().headersMap(), opportunity);
    opportunity.id = oppId;
    return opportunity;
  }

  // Account
  public Account getAccountById(String id) {
    return getAccount("(" + id + ")");
  }

  public List<Account> getAccountsByIds(Set<String> ids) {
    String idsFilters = ids.stream()
        .map(id -> "accountid eq '" + id + "'")
        .collect(Collectors.joining(" or "));
    return getAccounts("?$filter=" + encodeUrl(idsFilters));
  }

  public Account getAccountsByCustomerId(String customerId) {
    // Custom field search
    String customerIdFilter = env.getConfig().dynamicsPlatform.fieldDefinitions.paymentGatewayCustomerId + " eq '" + customerId + "'";
    return getAccount("?$filter=" + encodeUrl(customerIdFilter));
  }

  public Account insertAccount(Account account) {
    String url = env.getConfig().dynamicsPlatform.resourceUrl + API_URL + "/accounts";

    HttpClient.HeaderBuilder headerBuilder = headers();
    // to return created entity in response
    headerBuilder.header("Prefer", "return=representation");

    return HttpClient.post(url, account, MediaType.APPLICATION_JSON, headerBuilder, Account.class);
  }

  private Account getAccount(String accountUrl) {
    return HttpClient.get(env.getConfig().dynamicsPlatform.resourceUrl + API_URL + "/accounts" + accountUrl, headers(), Account.class);
  }

  private List<Account> getAccounts(String accountsUrl) {
    EntitiesResponse entitiesResponse =  HttpClient.get(env.getConfig().dynamicsPlatform.resourceUrl + API_URL + "/accounts" + accountsUrl, headers(), EntitiesResponse.class);
    return entitiesResponse.entities;
  }

  // Utils
  private void patch(String url, MultivaluedMap<String, Object> headers, Object entity) throws JsonProcessingException {
    HttpPatch httpPatch = new HttpPatch(url);

    headers.forEach((key, value) -> httpPatch.setHeader(key, value.toString()));

    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    String json = objectMapper.writeValueAsString(entity);
    
    httpPatch.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));

    try (CloseableHttpClient client = HttpClients.createDefault()) {
      CloseableHttpResponse response = client.execute(httpPatch);
      int statusCode = response.getStatusLine().getStatusCode();
      if (statusCode < 300) {
        String responseBody = response.getEntity() != null ? response.getEntity().toString() : null;
        if (Strings.isNullOrEmpty(responseBody)) {
          env.logJobInfo("PATCH OK: url={} code={} (empty response body)", url, statusCode);
        } else {
          env.logJobInfo("PATCH OK: url={} code={} response body={}", url, statusCode, responseBody);
        }
      } else {
        env.logJobError("PATCH failed: url={} code={} message={}", url, statusCode, response.getEntity().toString());
      }
    } catch (IOException e) {
      env.logJobError("PATCH failed: {}", e);
    }
  }

  private String encodeUrl(String url) {
    String encodedUrl = url;
    try {
      encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.toString());
    } catch (Exception e) {
      env.logJobWarn("Failed to encode url: {}! {}", e);
    }
    return encodedUrl;
  }
  
//  @JsonIgnoreProperties(ignoreUnknown = true)
//  public static class TokenResponse {
//    @JsonProperty("token_type")
//    public String tokenType; // "Bearer"
//    @JsonProperty("expires_in")
//    public String expiresIn; // "3599"
//    @JsonProperty("ext_expires_in")
//    public String extExpiresIn; // "3599"
//    @JsonProperty("expires_on")
//    public Long expiresOn; // "1684782043"
//    @JsonProperty("not_before")
//    public String notBefore; // "1684778143"
//    @JsonProperty("resource")
//    public String resource; // "https://org0e31d161.crm4.dynamics.com/"
//    @JsonProperty("access_token")
//    public String accessToken; // "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsIng1..."
//  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Contact {
    @JsonProperty("contactid")
    public String id; //"80ac35a0-01af-ea11-a812-000d3a8b3ec6"
    public String firstname;
    public String lastname;
    public String fullname;
    @JsonProperty("mobilephone")
    public String mobilePhone; //"619-555-0129"

    public String birthdate; //"1985-02-26"
    public Integer gendercode; //2
    public String emailaddress1; //"alex@treyresearch.net"
    public String modifiedon; //"2023-05-13T07:20:06Z"
    public String createdon; //"2023-05-13T07:16:53Z"
    public String _owninguser_value;  //"f985802e-0cf1-ed11-8849-000d3aabc131" account id?
    public Integer preferredcontactmethodcode; //1 phone email?
    @JsonProperty("_ownerid_value")
    public String ownerId; //"f985802e-0cf1-ed11-8849-000d3aabc131" account id?
    public String telephone1; //"619-555-0127"

    // Address 1
    @JsonProperty("address1_stateorprovince")
    public String address1StateOrProvince; //"California"
    @JsonProperty("address1_country")
    public String address1Country; //"United States"
    @JsonProperty("address1_line1")
    public String address1Street; //"789 3rd St"
    @JsonProperty("address1_city")
    public String address1City; //"San Francisco"
    @JsonProperty("address1_postalcode")
    public String address1Postalcode; //"94158"

    @Override
    public String toString() {
      return "Contact{" +
          "id='" + id + '\'' +
          ", firstname='" + firstname + '\'' +
          ", lastname='" + lastname + '\'' +
          ", mobilePhone='" + mobilePhone + '\'' +
          ", emailaddress1='" + emailaddress1 + '\'' +
          ", telephone1='" + telephone1 + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class EntitiesResponse<T> {
    @JsonProperty("@odata.context")
    public String oDataContext; // "https://org99ffc125.crm.dynamics.com/api/data/v9.2/$metadata#contacts"
    @JsonProperty("value")
    public List<T> entities; // [...]
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Account {
    @JsonProperty("accountid")
    public String accountId;
    public String description; //"Northwind Traders is headquartered in US and is the nation's leader in providing wholesalers with gift and novelty products. They began in 1925 and have continued to grow, expanding from mail catalogs to Internet e-commerce in the last two decades. "
    public String name;
    @JsonProperty("_ownerid_value")
    public String ownerId;
    public String telephone1;
    @JsonProperty("websiteurl")
    public String websiteUrl;

    // Address 1
    @JsonProperty("address1_addresstypecode")
    public Integer address1Type; // 1	Bill To, 2	Ship To, 3	Primary, 4	Other
    @JsonProperty("address1_line1")
    public String address1Street;
    @JsonProperty("address1_city")
    public String address1City;
    @JsonProperty("address1_stateorprovince")
    public String address1StateOrProvince;
    @JsonProperty("address1_postalcode")
    public String address1Postalcode;
    @JsonProperty("address1_country")
    public String address1Country;

    // Address 2
    @JsonProperty("address2_addresstypecode")
    public Integer address2Type; // 1	Bill To, 2	Ship To, 3	Primary, 4	Other
    @JsonProperty("address2_line1")
    public String address2Street;
    @JsonProperty("address2_city")
    public String address2City;
    @JsonProperty("address2_stateorprovince")
    public String address2StateOrProvince;
    @JsonProperty("address2_postalcode")
    public String address2Postalcode;
    @JsonProperty("address2_country")
    public String address2Country;

    @Override
    public String toString() {
      return "Account{" +
          "accountId='" + accountId + '\'' +
          ", name='" + name + '\'' +
          ", websiteUrl='" + websiteUrl + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Opportunity {
    //"@odata.context": "https://org99ffc125.crm.dynamics.com/api/data/v9.2/$metadata#opportunities/$entity",
    //	"@odata.etag": "W/\"3271456\"",
    //	"prioritycode": 1,
    //	"completeinternalreview": false,
    //	"stepname": "1-Qualify",
    //	"filedebrief": false,
    //	"estimatedclosedate": "2023-06-25",
    //	"_pricelevelid_value": "65029c08-f01f-eb11-a812-000d3a33e825",
    //	"totalamount": 4990.0000000000,
    @JsonProperty("totalamount")
    public Double totalAmount;
    //	"confirminterest": false,
    //	"captureproposalfeedback": false,
    //	"exchangerate": 1.000000000000,
    @JsonProperty("exchangerate")
    public Double exchangeRate;
    //	"opportunityid": "e90a0493-e8f0-ea11-a815-000d3a1b14a2",
    @JsonProperty("opportunityid")
    public String id;
    //	"_parentcontactid_value": "d1bf9a01-b056-e711-abaa-00155d701c02",
    @JsonProperty("_parentcontactid_value")
    public String contactId;
    //	"identifycompetitors": true,
    //	"_parentaccountid_value": "81883308-7ad5-ea11-a813-000d3a33f3b4",
    @JsonProperty("_parentaccountid_value")
    public String accountId;
    //	"name": "10 Airpot XL Coffee Makers for Alpine Ski House",
    public String name;
    //	"decisionmaker": true,
    //	"totallineitemamount": 4990.0000000000,
    //	"isrevenuesystemcalculated": true,
    //	"modifiedon": "2023-05-21T14:13:10Z",
    //	"_owninguser_value": "c47037b2-85f7-ed11-8847-000d3a358523",
    //	"skippricecalculation": 0,
    //	"presentproposal": false,
    //	"proposedsolution": "10 Airpot XL Coffee Makers should meet the customers requirements.",
    //	"totaldiscountamount": 0.0000000000,
    //	"_ownerid_value": "c47037b2-85f7-ed11-8847-000d3a358523",
    @JsonProperty("_ownerid_value")
    public String ownerId;
    //	"sendthankyounote": false,
    //	"identifycustomercontacts": true,
    //	"evaluatefit": false,
    //	"totalamountlessfreight": 4990.0000000000,
    //	"totallineitemdiscountamount": 0.0000000000,
    //	"totalamountlessfreight_base": 4990.0000000000,
    //	"msdyn_gdproptout": false,
    //	"statuscode": 1,
    //	"createdon": "2023-05-21T14:11:31Z",
    //	"versionnumber": 3271456,
    //	"emailaddress": "cacilia@alpineskihouse.com",
    //	"customerneed": "Need high volume coffee makers to meet demand.",
    //	"_msdyn_predictivescoreid_value": "45d7839a-e1f7-ed11-8847-6045bd0853c3",
    //	"totaltax_base": 0.0000000000,
    //	"totallineitemamount_base": 4990.0000000000,
    //	"estimatedvalue": 4990.0000000000,
    //	"totalamount_base": 4990.0000000000,
    //	"developproposal": false,
    //	"purchaseprocess": 2,
    //	"description": "Adding coffee machines to HQ",
    public String description;
    //	"_msdyn_opportunitykpiid_value": "e90a0493-e8f0-ea11-a815-000d3a1b14a2",
    //	"resolvefeedback": false,
    //	"totaltax": 0.0000000000,
    //	"totaldiscountamount_base": 0.0000000000,
    //	"_transactioncurrencyid_value": "d66f18f8-d9f7-ed11-8847-000d3a358523",
    //	"estimatedvalue_base": 4990.0000000000,
    //	"msdyn_forecastcategory": 100000001,
    //	"_modifiedby_value": "1d4fcf41-751c-4652-983b-2b1082f73591",
    //	"presentfinalproposal": false,
    //	"_createdby_value": "c47037b2-85f7-ed11-8847-000d3a358523",
    //	"timezoneruleversionnumber": 4,
    //	"currentsituation": "There is not enough coffee machines to supply the demand.",
    //	"pricingerrorcode": 0,
    //	"salesstagecode": 1,
    //	"totallineitemdiscountamount_base": 0.0000000000,
    //	"_modifiedonbehalfby_value": "c47037b2-85f7-ed11-8847-000d3a358523",
    //	"purchasetimeframe": 4,
    //	"identifypursuitteam": true,
    //	"closeprobability": 65,
    //	"participatesinworkflow": false,
    //	"statecode": 0,
    //	"_owningbusinessunit_value": "cc6937b2-85f7-ed11-8847-000d3a358523",
    //	"pursuitdecision": false,
    //	"opportunityratingcode": 3,
    //	"_customerid_value": "81883308-7ad5-ea11-a813-000d3a33f3b4",
    @JsonProperty("_customerid_value")
    public String customerId;
    //	"completefinalproposal": false,
    //	"msdyn_opportunityscore": null,
    //	"msdyn_scorehistory": null,
    //	"actualvalue_base": null,
    //	"msdyn_opportunityscoretrend": null,
    //	"_msdyn_segmentid_value": null,
    //	"budgetstatus": null,
    //	"_accountid_value": null,
    //	"finaldecisiondate": null,
    //	"_slainvokedid_value": null,
    //	"msdyn_similaropportunities": null,
    //	"msdyn_opportunitygrade": null,
    //	"quotecomments": null,
    //	"timeline": null,
    //	"qualificationcomments": null,
    //	"_contactid_value": null,
    //	"_campaignid_value": null,
    //	"stageid": null,
    //	"lastonholdtime": null,
    //	"need": null,
    //	"stepid": null,
    //	"freightamount_base": null,
    //	"processid": null,
    //	"discountamount_base": null,
    //	"onholdtime": null,
    //	"schedulefollowup_qualify": null,
    //	"importsequencenumber": null,
    //	"utcconversiontimezonecode": null,
    //	"discountamount": null,
    //	"salesstage": null,
    //	"traversedpath": null,
    //	"_createdonbehalfby_value": null,
    //	"_originatingleadid_value": null,
    //	"customerpainpoints": null,
    //	"budgetamount": null,
    //	"_owningteam_value": null,
    //	"scheduleproposalmeeting": null,
    //	"timespentbymeonemailandmeetings": null,
    //	"teamsfollowed": null,
    //	"overriddencreatedon": null,
    //	"initialcommunication": null,
    //	"schedulefollowup_prospect": null,
    //	"msdyn_scorereasons": null,
    //	"budgetamount_base": null,
    //	"freightamount": null,
    //	"actualclosedate": null,
    //	"actualvalue": null,
    //	"discountpercentage": null,
    //	"_slaid_value": null


    @Override
    public String toString() {
      return "Opportunity{" +
          "totalamount=" + totalAmount +
          ", exchangeRate=" + exchangeRate +
          ", id='" + id + '\'' +
          ", contactId='" + contactId + '\'' +
          ", accountId='" + accountId + '\'' +
          ", name='" + name + '\'' +
          ", ownerId='" + ownerId + '\'' +
          ", description='" + description + '\'' +
          ", customerId='" + customerId + '\'' +
          '}';
    }
  }

}
