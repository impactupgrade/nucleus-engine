package com.impactupgrade.nucleus.service.segment;

import brevo.ApiException;
import brevoModel.CreateAttribute;
import brevoModel.GetAttributesAttributes;
import brevoModel.GetContactDetails;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.BrevoClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PagedResults;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.impactupgrade.nucleus.client.BrevoClient.FIRSTNAME;
import static com.impactupgrade.nucleus.client.BrevoClient.LASTNAME;
import static com.impactupgrade.nucleus.client.BrevoClient.SMS;

public class BrevoCommunicationService extends AbstractCommunicationService {

  private final Set<String> attributeNames = new HashSet<>();

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
        Map<String, Object> customFieldMap = getCustomFields(crmContact, brevoClient, brevoConfig, communicationList);
        contactsCustomFields.put(crmContact.email, customFieldMap);
      }
      // run the actual contact upserts
      List<GetContactDetails> upsertMemberInfos = toGetContactDetails(communicationList, brevoConfig, contactsToUpsert, contactsCustomFields);
      brevoClient.importContacts(communicationList.id, upsertMemberInfos);

      // archive mc emails that are marked as unsubscribed in the CRM
      Set<String> emailsToArchive = contactsToArchive.stream().map(crmContact -> crmContact.email.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
      // but only if they actually exist in MC
      emailsToArchive.retainAll(brevoEmails);

      brevoClient.deleteContacts(communicationList.id, emailsToArchive);
    } catch (ApiException e) {
      env.logJobWarn("Brevo syncContacts failed: {}", e);
    } catch (Exception e) {
      env.logJobWarn("Brevo syncContacts failed", e);
    }
  }

  @Override
  public void massArchive() throws Exception {
    for (EnvironmentConfig.CommunicationPlatform brevoConfig : env.getConfig().brevo) {
      for (EnvironmentConfig.CommunicationList communicationList : brevoConfig.lists) {
        BrevoClient brevoClient = env.brevoClient(brevoConfig);
        List<GetContactDetails> listMembers = brevoClient.getContactsFromList(communicationList.id);
        // get all mc email addresses in the entire audience
        Set<String> emailsToArchive = listMembers.stream().map(memberInfo -> memberInfo.getEmail().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

        // remove CRM contacts
        PagedResults<CrmContact> contactPagedResults = env.primaryCrmService().getEmailContacts(null, communicationList);
        for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
          do {
            for (CrmContact crmContact : resultSet.getRecords()) {
              if (crmContact.canReceiveEmail()) {
                emailsToArchive.remove(crmContact.email.toLowerCase(Locale.ROOT));
              }
            }
            if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
              resultSet = env.primaryCrmService().queryMoreContacts(resultSet.getNextPageToken());
            } else {
              resultSet = null;
            }
          } while (resultSet != null);
        }
        // remove CRM accounts
        PagedResults<CrmAccount> accountPagedResults = env.primaryCrmService().getEmailAccounts(null, communicationList);
        for (PagedResults.ResultSet<CrmAccount> resultSet : accountPagedResults.getResultSets()) {
          do {
            for (CrmAccount crmAccount : resultSet.getRecords()) {
              if (crmAccount.canReceiveEmail()) {
                emailsToArchive.remove(crmAccount.email.toLowerCase(Locale.ROOT));
              }
            }
            if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
              // next page
              resultSet = env.primaryCrmService().queryMoreAccounts(resultSet.getNextPageToken());
            } else {
              resultSet = null;
            }
          } while (resultSet != null);
        }

        env.logJobInfo("massArchiving {} listMembers in Brevo: {}", emailsToArchive.size(), String.join(", ", emailsToArchive));
        brevoClient.deleteContacts(communicationList.id, emailsToArchive);
      }
    }
  }


  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.CommunicationPlatform brevoConfig : env.getConfig().brevo) {
      BrevoClient brevoClient = env.brevoClient(brevoConfig);
      for (EnvironmentConfig.CommunicationList communicationList : brevoConfig.lists) {
        List<GetContactDetails> listMembers = brevoClient.getContacts(lastSync, null);
        //TODO: how to filter?
//        List<GetContactDetails> unsubscribed = listMembers.stream()
//            .filter(c -> c.getListUnsubscribed() != null && c.getListUnsubscribed().contains(communicationList.id))
//            .toList();
//        syncUnsubscribed(getEmails(unsubscribed));
      }
    }
  }

  protected List<String> getEmails(List<GetContactDetails> contacts) {
    return contacts.stream().map(c -> c.getEmail().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
  }

  protected void syncUnsubscribed(List<String> unsubscribedEmails) throws Exception {
    updateContactsByEmails(unsubscribedEmails, c -> c.emailOptOut = true);
    updateAccountsByEmails(unsubscribedEmails, a -> a.emailOptOut = true);
  }

  protected void updateContactsByEmails(List<String> emails, Consumer<CrmContact> contactConsumer) throws Exception {
    // VITAL: In order for batching to work, must be operating under a single instance of the CrmService!
    CrmService crmService = env.primaryCrmService();
    List<CrmContact> contacts = crmService.getContactsByEmails(emails);
    int count = 0;
    int total = contacts.size();
    for (CrmContact crmContact : contacts) {
      env.logJobInfo("updating unsubscribed contact in CRM: {} ({} of {})", crmContact.email, count++, total);
      CrmContact updateContact = new CrmContact();
      updateContact.id = crmContact.id;
      contactConsumer.accept(updateContact);
      crmService.batchUpdateContact(updateContact);
    }
    crmService.batchFlush();
  }

  protected void updateAccountsByEmails(List<String> emails, Consumer<CrmAccount> accountConsumer) throws Exception {
    // VITAL: In order for batching to work, must be operating under a single instance of the CrmService!
    CrmService crmService = env.primaryCrmService();
    List<CrmAccount> accounts = crmService.getAccountsByEmails(emails);
    int count = 0;
    int total = accounts.size();
    for (CrmAccount account : accounts) {
      env.logJobInfo("updating unsubscribed account in CRM: {} ({} of {})", account.email, count++, total);
      CrmAccount updateAccount = new CrmAccount();
      updateAccount.id = account.id;
      accountConsumer.accept(updateAccount);
      crmService.batchUpdateAccount(updateAccount);
    }
    crmService.batchFlush();
  }

  @Override
  public void upsertContact(String contactId) throws Exception {
    CrmService crmService = env.primaryCrmService();

    for (EnvironmentConfig.CommunicationPlatform brevoconfig : env.getConfig().brevo) {
      for (EnvironmentConfig.CommunicationList communicationList : brevoconfig.lists) {
        Optional<CrmContact> crmContact = crmService.getFilteredContactById(contactId, communicationList.crmFilter);
        if (crmContact.isPresent() && !Strings.isNullOrEmpty(crmContact.get().email)) {
          upsertContact(brevoconfig, communicationList, crmContact.get());
        }
      }
    }
  }

  protected void upsertContact(EnvironmentConfig.CommunicationPlatform brevoConfig,
                               EnvironmentConfig.CommunicationList communicationList, CrmContact crmContact) throws Exception {
    BrevoClient brevoClient = env.brevoClient(brevoConfig);

    // transactional is always subscribed
    if (communicationList.type != EnvironmentConfig.CommunicationListType.TRANSACTIONAL && !crmContact.canReceiveEmail()) {
      return;
    }

    try {
      Map<String, Object> customFields = getCustomFields(crmContact, brevoClient, brevoConfig, communicationList);
      // run the actual contact upsert
      GetContactDetails getContactDetails = toGetContactDetails(brevoConfig, crmContact, customFields, communicationList.groups);
      brevoClient.createContact(communicationList.id, getContactDetails);
    } catch (ApiException e) {
      env.logJobWarn("Brevo upsertContact failed: {}", e);
    } catch (Exception e) {
      env.logJobWarn("Brevo upsertContact failed", e);
    }
  }

  protected Map<String, Object> getCustomFields(CrmContact crmContact, BrevoClient brevoClient,
                                                EnvironmentConfig.CommunicationPlatform brevoConfig, EnvironmentConfig.CommunicationList communicationList)
      throws Exception {
    Map<String, Object> customFieldMap = new HashMap<>();

    List<CustomField> customFields = buildContactCustomFields(crmContact, brevoConfig, communicationList);
    if (attributeNames.isEmpty()) {
      List<GetAttributesAttributes> attributes = brevoClient.getAttributes();
      for (GetAttributesAttributes attribute : attributes) {
        attributeNames.add(attribute.getName());
      }
    }
    for (CustomField customField : customFields) {
      if (customField.value == null) {
        continue;
      }

      if (!attributeNames.contains(customField.name.toUpperCase(Locale.ROOT))) {
        //  TEXT("text"), DATE("date"), FLOAT("float"), BOOLEAN("boolean"), ID("id"), CATEGORY("category");
        CreateAttribute.TypeEnum type = switch (customField.type) {
          case DATE -> CreateAttribute.TypeEnum.DATE;
          case BOOLEAN -> CreateAttribute.TypeEnum.BOOLEAN;
          case NUMBER -> CreateAttribute.TypeEnum.FLOAT;
          default -> CreateAttribute.TypeEnum.TEXT;
        };
        brevoClient.createAttribute(customField.name, type);
        attributeNames.add(customField.name.toUpperCase(Locale.ROOT));
      }

      Object value = customField.value;
      if (customField.type == CustomFieldType.BOOLEAN) {
        if (((Boolean) value)) {
          value = 1;
        } else {
          value = 0;
        }
      } else if (customField.type == CustomFieldType.DATE) {
        Calendar c = (Calendar) value;
        value = new SimpleDateFormat("MM/dd/yyyy").format(c.getTime());
      }

      customFieldMap.put(customField.name, value);
    }

    return customFieldMap;
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
    // TODO: This isn't correct, but we'll need a way to pull the existing brevo contact ID? Or maybe it's never needed,
    //  since updates use the email hash...
//    getContactDetails.id = contact.id;
    getContactDetails.setEmail(crmContact.email);
    Properties attributes = new Properties();
    attributes.setProperty(FIRSTNAME, crmContact.firstName);
    attributes.setProperty(LASTNAME, crmContact.lastName);

    getContactDetails.setAttributes(attributes);

    if (smsAllowed(brevoConfig, crmContact)) {
      attributes.setProperty(SMS, crmContact.phoneNumberForSMS());
    }

//    List<String> groupIds = crmContact.emailGroups.stream().map(groupName -> getGroupIdFromName(groupName, groups)).collect(Collectors.toList());
//    // TODO: Does this deselect what's no longer subscribed to in MC?
//    MailchimpObject groupMap = new MailchimpObject();
//    groupIds.forEach(id -> groupMap.mapping.put(id, true));
//    mcContact.interests = groupMap;

    return getContactDetails;
  }

  //TODO: remove?
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
    Environment env = new Environment() {
      @Override
      public BrevoClient brevoClient(EnvironmentConfig.CommunicationPlatform brevoConfig) {
        return new BrevoClient(brevoConfig, this);
      }
    };

    CrmContact crmContact = new CrmContact();
    crmContact.firstName = "John";
    crmContact.lastName = "Smith";
    crmContact.email = "john.smith@gmail.com";
    crmContact.mobilePhone = "+380979797999";
    // Custom field
    crmContact.title = "test title";

    PagedResults.ResultSet<CrmContact> resultSet = new PagedResults.ResultSet<>(List.of(crmContact), "");

    EnvironmentConfig.CommunicationPlatform brevoConfig = new EnvironmentConfig.CommunicationPlatform();
    brevoConfig.secretKey = "xkeysib-...";
    EnvironmentConfig.CommunicationList communicationList = new EnvironmentConfig.CommunicationList();
    communicationList.id = "3";


    BrevoClient brevoClient = env.brevoClient(brevoConfig);
    List<GetContactDetails> allContacts = brevoClient.getContacts(null, null);
    List<GetContactDetails> listContacts = brevoClient.getContactsFromList(communicationList.id);
    Set<String> brevoEmails = listContacts.stream().map(contact -> contact.getEmail().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

    BrevoCommunicationService brevoCommunicationService = new BrevoCommunicationService();
    brevoCommunicationService.init(env);

    //brevoCommunicationService.syncContacts(resultSet, brevoConfig, communicationList, listContacts, brevoEmails, brevoClient);

    //brevoCommunicationService.upsertContact(brevoConfig, communicationList, crmContact);
  }
}
