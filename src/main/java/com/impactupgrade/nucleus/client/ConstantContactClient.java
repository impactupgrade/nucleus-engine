/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.util.HttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class ConstantContactClient extends OAuthClient {

  private static final String API_BASE_URL = "https://api.cc.email";

  protected static Integer BATCH_STATUS_RETRY_WAIT_IN_SECONDS = 10;
  protected static Integer BATCH_STATUS_MAX_RETRIES = 30;

  protected final EnvironmentConfig.CommunicationPlatform constantContactConfig;
  protected final Environment env;

  public ConstantContactClient(EnvironmentConfig.CommunicationPlatform constantContactConfig, Environment env) {
    super("constantContact", env);
    this.constantContactConfig = constantContactConfig;
    this.env = env;
  }

  @Override
  protected OAuthContext oAuthContext() {
    OAuthContext oAuthContext = new ClientCredentialsOAuthContext(
        constantContactConfig, "https://authz.constantcontact.com/oauth2/default/v1/token", true);

    // TODO: Is this typical? If so, move it to super.
    String basicToken = constantContactConfig.clientId + ":" + constantContactConfig.clientSecret;
    basicToken = Base64.getEncoder().encodeToString(basicToken.getBytes(StandardCharsets.UTF_8));
    oAuthContext.refreshTokensAdditionalHeaders = Map.of("Authorization", "Basic " + basicToken);

    return oAuthContext;
  }

  @Override
  protected JSONObject getClientConfigJson(JSONObject envJson) {
    JSONArray ccArray = envJson.getJSONArray(name);
    // TODO: Should we implement a name field on Platform so we can look these up?
    return ccArray.getJSONObject(0);
//    for (int i = 0; i < ccArray.length(); i++) {
//      JSONObject cc = ccArray.getJSONObject(i);
//      if (constantContactConfig.name.equalsIgnoreCase(cc.getString("name"))) {
//        return cc;
//      }
//    }
//    return null;
  }

  public String upsertContactsBatch(String listId, List<ContactImport> contacts) {
    if (contacts.isEmpty()) {
      return null;
    }

    ContactsImportRequest contactsImportRequest  = new ContactsImportRequest();
    contactsImportRequest.listIds = List.of(listId);
    contactsImportRequest.contacts = contacts;

    String url = API_BASE_URL + "/v3/activities/contacts_json_import";
    BatchOperation batchOperation = HttpClient.post(
        url, contactsImportRequest, MediaType.APPLICATION_JSON, headers(), BatchOperation.class);
    return batchOperation.activityId;
  }

  public List<Contact> getListMembers(String listId) {
    return getListMembers(listId, "all", null);
  }

  public List<Contact> getListMembers(String listId, String status, Calendar sinceLastChanged) {
    return getListMembers(listId, status,
        "custom_fields,taggings", // HUGE performance improvement -- limit to only what we need, in addition to the basic fields
        sinceLastChanged);
  }

  // status: "all" "active" "deleted" "not_set" "pending_confirmation" "temp_hold" "unsubscribed"
  public List<Contact> getListMembers(String listId, String status, String include, Calendar sinceLastChanged) {
    String url = API_BASE_URL + "/v3/contacts?lists=" + listId + "&limit=500";
    if (!Strings.isNullOrEmpty(status)) {
      url += "&status=" + status;
    }
    if (!Strings.isNullOrEmpty(include)) {
      url += "&include=" + include;
    }
    if (sinceLastChanged != null) {
      String formattedDate = new SimpleDateFormat("yyyy-MM-dd").format(sinceLastChanged.getTime()) + "T00:00:00.000Z";
      url += "&updated_after=" + formattedDate;
    }

    List<Contact> contacts = new ArrayList<>();

    while (url != null) {
      ContactsResponse contactsResponse = HttpClient.get(url, headers(), ContactsResponse.class);
      contacts.addAll(contactsResponse.contacts);

      url = null;
      if (contactsResponse.links != null && contactsResponse.links.next != null
          && !Strings.isNullOrEmpty(contactsResponse.links.next.href)) {
        url = API_BASE_URL + contactsResponse.links.next.href;
      }
    }

    return contacts;
  }

  public String archiveContactsBatch(List<String> contactIds) {
    if (contactIds.isEmpty()) {
      return null;
    }

    DeleteContactsRequest deleteContactsRequest  = new DeleteContactsRequest();
    deleteContactsRequest.contactIds = contactIds;

    String url = API_BASE_URL + "/v3/activities/contact_delete";
    BatchOperation batchOperation = HttpClient.post(
        url, deleteContactsRequest, MediaType.APPLICATION_JSON, headers(), BatchOperation.class);
    return batchOperation.activityId;
  }

  public String addContactTagsBatch(List<String> contactIds, String tagId) {
    if (contactIds == null || contactIds.isEmpty()) {
      return null;
    }

    ContactTagsRequest contactTagsRequest  = new ContactTagsRequest();
    contactTagsRequest.source.contactIds = contactIds;
    contactTagsRequest.tagIds = List.of(tagId);

    String url = API_BASE_URL + "/v3/activities/contacts_taggings_add";
    BatchOperation batchOperation = HttpClient.post(
        url, contactTagsRequest, MediaType.APPLICATION_JSON, headers(), BatchOperation.class);
    return batchOperation.activityId;
  }

  public String removeContactTagsBatch(List<String> contactIds, String tagId) {
    if (contactIds.isEmpty()) {
      return null;
    }

    ContactTagsRequest contactTagsRequest  = new ContactTagsRequest();
    contactTagsRequest.source.contactIds = contactIds;
    contactTagsRequest.tagIds = List.of(tagId);

    String url = API_BASE_URL + "/v3/activities/contacts_taggings_remove";
    BatchOperation batchOperation = HttpClient.post(
        url, contactTagsRequest, MediaType.APPLICATION_JSON, headers(), BatchOperation.class);
    return batchOperation.activityId;
  }

  public void runBatchOperations(EnvironmentConfig.CommunicationPlatform config, String batchStatusId, int attemptCount) {
    if (Strings.isNullOrEmpty(batchStatusId)) {
      return;
    }

    if (attemptCount == BATCH_STATUS_MAX_RETRIES) {
      env.logJobError("exhausted retries; returning...");
    } else {
      try {
        String url = API_BASE_URL + "/v3/activities/" + batchStatusId;
        BatchOperation batchOperation = HttpClient.get(url, headers(), BatchOperation.class);
        BatchOperationStatus batchStatus = batchOperation.status;

        if (!"completed".equalsIgnoreCase(batchOperation.state)) {
          env.logJobInfo("Batch '{}' is not finished: {}/{} Retrying in {} seconds...",
              batchStatusId, batchStatus.itemsCompletedCount, batchStatus.itemsTotalCount, BATCH_STATUS_RETRY_WAIT_IN_SECONDS);
          Thread.sleep(BATCH_STATUS_RETRY_WAIT_IN_SECONDS * 1000);
          int newAttemptCount = attemptCount + 1;
          runBatchOperations(config, batchStatusId, newAttemptCount);
        } else {
          env.logJobInfo("Batch '{}' finished! (finished/total) {}/{}",
              batchStatusId, batchStatus.itemsCompletedCount, batchStatus.itemsTotalCount);
          if (batchStatus.errorCount != null && batchStatus.errorCount > 0) {
            env.logJobWarn("Errored operations count: {}", batchStatus.errorCount);
          } else {
            env.logJobInfo("All operations processed OK!");
          }

          batchOperation.activityErrors.forEach(error -> env.logJobWarn("Batch {} operation error: {}", batchStatusId, error));
        }
      } catch (Exception e) {
        env.logJobError("Batch {} failed: {}", batchStatusId, e.getMessage());
      }
    }
  }

  public List<CustomField> getAllCustomFields() {
    String url = API_BASE_URL + "/v3/contact_custom_fields?limit=100";

    List<CustomField> allCustomFields = new ArrayList<>();

    while (url != null) {
      CustomFieldResponse customFieldResponse = HttpClient.get(url, headers(), CustomFieldResponse.class);
      allCustomFields.addAll(customFieldResponse.customFields);

      url = null;
      if (customFieldResponse.links != null && customFieldResponse.links.next != null
          && !Strings.isNullOrEmpty(customFieldResponse.links.next.href)) {
        url = API_BASE_URL + customFieldResponse.links.next.href;
      }
    }

    return allCustomFields;
  }

  public CustomField createCustomField(String name, String type) {
    String url = API_BASE_URL + "/v3/contact_custom_fields";

    CustomField customField = new CustomField();
    // the POST endpoint only takes a label, oddly not the name too
    customField.label = name;
    customField.type = type;

    if ("currency".equalsIgnoreCase(type)) {
      // TODO: configurable
      customField.metadata.put("currency_code", "USD");
    }

    return HttpClient.post(url, customField, MediaType.APPLICATION_JSON, headers(), CustomField.class);
  }

  public List<Tag> getAllTags() {
    String url = API_BASE_URL + "/v3/contact_tags?limit=500";

    List<Tag> allTags = new ArrayList<>();

    while (url != null) {
      TagsResponse tagsResponse = HttpClient.get(url, headers(), TagsResponse.class);
      allTags.addAll(tagsResponse.tags);

      url = null;
      if (tagsResponse.links != null && tagsResponse.links.next != null
          && !Strings.isNullOrEmpty(tagsResponse.links.next.href)) {
        url = API_BASE_URL + tagsResponse.links.next.href;
      }
    }

    return allTags;
  }

  public Tag createTag(String name) {
    String url = API_BASE_URL + "/v3/contact_tags";

    Tag tag = new Tag();
    tag.name = name;

    return HttpClient.post(url, tag, MediaType.APPLICATION_JSON, headers(), Tag.class);
  }

  public static final class Links {
    public HasHref next;
  }

  public static final class HasHref {
    public String href;
  }

  // TODO: update uses of List to Set where it makes sense

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class BatchOperation {
    @JsonProperty("activity_id")
    public String activityId;
    public String state;
    @JsonProperty("percent_done")
    public Integer percentDone;
    @JsonProperty("activity_errors")
    public List<String> activityErrors = new ArrayList<>();
    public BatchOperationStatus status = new BatchOperationStatus();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class BatchOperationStatus {
    @JsonProperty("items_total_count")
    public Integer itemsTotalCount;
    @JsonProperty("items_completed_count")
    public Integer itemsCompletedCount;
    @JsonProperty("error_count")
    public Integer errorCount;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ContactsImportRequest {
    @JsonProperty("import_data")
    public List<ContactImport> contacts = new ArrayList<>();
    @JsonProperty("list_ids")
    public List<String> listIds = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactImport {
    @JsonProperty("first_name")
    public String firstname;
    @JsonProperty("last_name")
    public String lastname;
    @JsonProperty("email")
    public String emailAddress;
    /*
    TODO: Before we can do sms, need to include more info:
    "To include a contact's sms_number, if the contact provided explicit permission to receive SMS messages, you must set the sms_permission_to_send property to explicit and specify the date of consent using the sms_consent_date column header. If explicit permission was not provided, set sms_permission_to_send to not_set (the sms_consent_date is not required). If the sms_consent_date is not set, SMS messages cannot be sent to contacts and sms_permission_to_send defaults to not_set. Valid value formats for sms_consent_date include MM/DD/YYYY, M/D/YYYY, YYYY/MM/DD, YYYY/M/D, YYYY-MM-DD, YYYY-M-D,M-D-YYYY, or M-DD-YYYY."
     */
//    @JsonProperty("sms_number")
//    @JsonInclude(JsonInclude.Include.NON_NULL)
//    public String smsNumber;
    // On imports, custom fields are in the root of the contact payload, prefixed with cf.
    // Ex: "cf:custom_field_name": "the value"
    @JsonAnyGetter
    public Map<String, Object> customFields = new HashMap<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactsResponse {
    public List<Contact> contacts = new ArrayList<>();
    @JsonProperty("contacts_count")
    public Integer contactsCount;
    @JsonProperty("_links")
    public Links links = new Links();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Contact {
    @JsonProperty("contact_id")
    public String id;
    @JsonProperty("first_name")
    public String firstname;
    @JsonProperty("last_name")
    public String lastname;
    @JsonProperty("email_address")
    public EmailAddress emailAddress = new EmailAddress();
//    @JsonProperty("sms_number")
//    public String smsNumber;
    @JsonProperty("custom_fields")
    public List<ContactCustomField> customFields = new ArrayList<>();
    @JsonProperty("taggings")
    public List<String> tagIds = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class EmailAddress {
    @JsonProperty("address")
    public String emailAddress;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactCustomField {
    @JsonProperty("custom_field_id")
    public String id;
    public Object value;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CustomFieldResponse {
    @JsonProperty("custom_fields")
    public List<CustomField> customFields = new ArrayList<>();
    @JsonProperty("_links")
    public Links links = new Links();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CustomField {
    @JsonProperty("custom_field_id")
    public String id;
    public String label;
    public String name;
    public String type;
    public Map<String, String> metadata = new HashMap<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class TagsResponse {
    public List<Tag> tags = new ArrayList<>();
    @JsonProperty("_links")
    public Links links = new Links();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Tag {
    @JsonProperty("tag_id")
    public String id;
    public String name;
  }

  public record TaggedContact(String email, Set<String> activeTagNames, Set<String> inactiveTagNames) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactTagsRequest {
    public ContactTagsSource source = new ContactTagsSource();
    @JsonProperty("tag_ids")
    public List<String> tagIds = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactTagsSource {
    @JsonProperty("contact_ids")
    public List<String> contactIds = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DeleteContactsRequest {
    @JsonProperty("contact_ids")
    public List<String> contactIds = new ArrayList<>();
  }
}
