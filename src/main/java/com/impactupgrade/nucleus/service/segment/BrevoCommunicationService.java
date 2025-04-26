package com.impactupgrade.nucleus.service.segment;

import brevoModel.GetContactDetails;
import com.ecwid.maleorang.MailchimpException;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.BrevoClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.impactupgrade.nucleus.client.MailchimpClient.FIRST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.LAST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.PHONE_NUMBER;

public class BrevoCommunicationService extends AbstractCommunicationService {

  @Override
  public String name() {
    return "brevo";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().brevo != null && !env.getConfig().brevo.isEmpty();
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.CommunicationPlatform brevoConfig : env.getConfig().brevo) {
      for (EnvironmentConfig.CommunicationList communicationList : brevoConfig.lists) {
        // clear the cache, since fields differ between audiences
        //mergeFieldsNameToTag.clear();

        BrevoClient brevoClient = env.brevoClient(brevoConfig);
        List<GetContactDetails> listContacts = brevoClient.getContactsFromList(communicationList.id);

        Set<String> brevoEmails = listContacts.stream().map(contact -> contact.getEmail().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

        PagedResults<CrmContact> contactPagedResults = env.primaryCrmService().getEmailContacts(lastSync, communicationList);
        for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
          do {
            syncContacts(resultSet, brevoConfig, communicationList, listContacts, brevoEmails, brevoClient);
            if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
              // next page
              resultSet = env.primaryCrmService().queryMoreContacts(resultSet.getNextPageToken());
            } else {
              resultSet = null;
            }
          } while (resultSet != null);
        }

        PagedResults<CrmAccount> accountPagedResults = env.primaryCrmService().getEmailAccounts(lastSync, communicationList);
        for (PagedResults.ResultSet<CrmAccount> resultSet : accountPagedResults.getResultSets()) {
          do {
            PagedResults.ResultSet<CrmContact> fauxContacts = new PagedResults.ResultSet<>();
            fauxContacts.getRecords().addAll(resultSet.getRecords().stream().map(this::asCrmContact).toList());
            syncContacts(fauxContacts, brevoConfig, communicationList, listContacts, brevoEmails, brevoClient);
            if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
              // next page
              resultSet = env.primaryCrmService().queryMoreAccounts(resultSet.getNextPageToken());
            } else {
              resultSet = null;
            }
          } while (resultSet != null);
        }
      }
    }
  }

  protected CrmContact asCrmContact(CrmAccount crmAccount) {
    CrmContact crmContact = new CrmContact();
    crmContact.account = crmAccount;
    crmContact.crmRawObject = crmAccount.crmRawObject;
    crmContact.email = crmAccount.email;
    crmContact.emailBounced = crmAccount.emailBounced;
    crmContact.emailOptIn = crmAccount.emailOptIn;
    crmContact.emailOptOut = crmAccount.emailOptOut;
    crmContact.firstName = crmAccount.name;
    crmContact.mailingAddress = crmAccount.mailingAddress;
    if (crmContact.mailingAddress == null || Strings.isNullOrEmpty(crmContact.mailingAddress.street)) {
      crmContact.mailingAddress = crmAccount.billingAddress;
    }
    // TODO
//    crmContact.firstDonationDate = ;
//    crmContact.lastDonationDate = ;
//    crmContact.largestDonationAmount = ;
//    crmContact.totalDonationAmount = ;
//    crmContact.numDonations = ;
//    crmContact.totalDonationAmountYtd = ;
//    crmContact.numDonationsYtd = ;
//    crmContact.ownerName = ;
    return crmContact;
  }

  protected void syncContacts(PagedResults.ResultSet<CrmContact> resultSet, EnvironmentConfig.CommunicationPlatform brevoConfig,
                              EnvironmentConfig.CommunicationList communicationList, List<GetContactDetails> listMembers, Set<String> brevoEmails,
                              BrevoClient brevoClient) {
    List<CrmContact> contactsToUpsert = new ArrayList<>();
    List<CrmContact> contactsToArchive = new ArrayList<>();

    List<CrmContact> crmContacts = resultSet.getRecords();

    // transactional is always subscribed
    if (communicationList.type == EnvironmentConfig.CommunicationListType.TRANSACTIONAL) {
      contactsToUpsert.addAll(crmContacts);
    } else {
      crmContacts.forEach(crmContact -> (crmContact.canReceiveEmail() ? contactsToUpsert : contactsToArchive).add(crmContact));
    }

    try {
      Map<String, Map<String, Object>> contactsCustomFields = new HashMap<>();
      for (CrmContact crmContact : contactsToUpsert) {
        //TODO:
        //Map<String, Object> customFieldMap = getCustomFields(communicationList.id, crmContact, mailchimpClient,
        //    mailchimpConfig, communicationList);
        //contactsCustomFields.put(crmContact.email, customFieldMap);
      }
      Map<String, List<String>> crmContactCampaignNames = env.primaryCrmService().getContactsCampaigns(crmContacts, communicationList);
      //TODO: tags?
//      Map<String, Set<String>> tags = mailchimpClient.getContactsTags(listMembers);
//      Map<String, Set<String>> activeTags = getActiveTags(contactsToUpsert, crmContactCampaignNames, mailchimpConfig,
//          communicationList);

      // run the actual contact upserts
      List<GetContactDetails> upsertMemberInfos = toGetContactDetails(communicationList, brevoConfig, contactsToUpsert, contactsCustomFields);
      //TODO:
      //String upsertBatchId = brevoClient.importContacts(null);

      //TODO: likely will be done in scope of import?
      // update all contacts' tags
//      List<MailchimpClient.EmailContact> emailContacts = contactsToUpsert.stream()
//          .map(crmContact -> new MailchimpClient.EmailContact(crmContact.email, activeTags.get(crmContact.email), tags.get(crmContact.email)))
//          .collect(Collectors.toList());
//      String tagsBatchId = updateTagsBatch(communicationList.id, emailContacts, mailchimpClient, mailchimpConfig);
//      mailchimpClient.runBatchOperations(mailchimpConfig, tagsBatchId, 0);

      // archive mc emails that are marked as unsubscribed in the CRM
      Set<String> emailsToArchive = contactsToArchive.stream().map(crmContact -> crmContact.email.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
      // but only if they actually exist in MC
      emailsToArchive.retainAll(brevoEmails);

      //TODO: likely will be done one by one?
      //TODO: move to a 'delete' list and then delete entire list?
      //emailsToArchive.forEach(email -> brevoClient.deleteContact());
    } catch (MailchimpException e) {
      env.logJobWarn("Brevo syncContacts failed: {}", e);
    } catch (Exception e) {
      env.logJobWarn("Brevo syncContacts failed", e);
    }
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    //TODO:
  }

  @Override
  public void upsertContact(String contactId) throws Exception {
    //TODO:
  }

  @Override
  public void massArchive() throws Exception {
    //TODO:
  }

  protected List<GetContactDetails> toGetContactDetails(EnvironmentConfig.CommunicationList communicationList, EnvironmentConfig.CommunicationPlatform brevoConfig, List<CrmContact> crmContacts,
                                                        Map<String, Map<String, Object>> customFieldsMap) {
    return crmContacts.stream()
        .map(crmContact -> toGetContactDetails(brevoConfig, crmContact, customFieldsMap.get(crmContact.email), communicationList.groups))
        .collect(Collectors.toList());
  }

  protected GetContactDetails toGetContactDetails(EnvironmentConfig.CommunicationPlatform brevoConfig, CrmContact crmContact, Map<String, Object> customFields, Map<String, String> groups) {
    if (crmContact == null) {
      return null;
    }

    GetContactDetails getContactDetails = new GetContactDetails();
    // TODO: This isn't correct, but we'll need a way to pull the existing MC contact ID? Or maybe it's never needed,
    //  since updates use the email hash...
//    mcContact.id = contact.id;
    getContactDetails.setEmail(crmContact.email);
    //TODO: attributes
    Properties attributes = new Properties();
    attributes.setProperty(FIRST_NAME, crmContact.firstName);
    attributes.setProperty(LAST_NAME, crmContact.lastName);
    attributes.setProperty(PHONE_NUMBER, crmContact.mobilePhone);
    getContactDetails.setAttributes(attributes);

    if (smsAllowed(brevoConfig, crmContact)) {
      //TODO: howto?
//      mcContact.consents_to_one_to_one_messaging = true;
//      mcContact.sms_subscription_status = SUBSCRIBED;
//      String phoneNumber = crmContact.phoneNumberForSMS();
//      mcContact.sms_phone_number = phoneNumber;
//      mcContact.merge_fields.mapping.put(SMS_PHONE_NUMBER, phoneNumber);
    }

//    mcContact.merge_fields.mapping.putAll(customFields);
//    mcContact.status = SUBSCRIBED;

//    List<String> groupIds = crmContact.emailGroups.stream().map(groupName -> getGroupIdFromName(groupName, groups)).collect(Collectors.toList());
//    // TODO: Does this deselect what's no longer subscribed to in MC?
//    MailchimpObject groupMap = new MailchimpObject();
//    groupIds.forEach(id -> groupMap.mapping.put(id, true));
//    mcContact.interests = groupMap;

    return getContactDetails;
  }

  private boolean smsAllowed(EnvironmentConfig.CommunicationPlatform brevoConfig, CrmContact crmContact) {
    if (!brevoConfig.enableSms) {
      return false;
    }
    boolean smsOptIn = Boolean.TRUE == crmContact.smsOptIn && Boolean.TRUE != crmContact.smsOptOut;
    if (!smsOptIn) {
      return false;
    }
    String phoneNumber = crmContact.phoneNumberForSMS();
    if (Strings.isNullOrEmpty(phoneNumber)) {
      return false;
    }

    boolean smsAllowed = false;
    if (!Strings.isNullOrEmpty(brevoConfig.countryCode) && phoneNumber.startsWith(brevoConfig.countryCode)) {
      smsAllowed = true;
    } else if (!Strings.isNullOrEmpty(brevoConfig.country) && !phoneNumber.startsWith("+")) {
      smsAllowed = Stream.of(crmContact.account.billingAddress, crmContact.account.mailingAddress, crmContact.mailingAddress)
          .filter(Objects::nonNull)
          .anyMatch(crmAddress -> brevoConfig.country.equalsIgnoreCase(crmAddress.country));
    }
    return smsAllowed;
  }

  //TODO: remove once done with testing
  public static void main(String[] args) throws Exception {
    EnvironmentConfig.CommunicationPlatform communicationPlatform = new EnvironmentConfig.CommunicationPlatform();
    communicationPlatform.secretKey = "...";
    BrevoClient brevoClient = new BrevoClient(communicationPlatform, null);
    brevoClient.getContactsFromList("1");

  }
}
