package com.impactupgrade.nucleus.it;

import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmContact;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MailchimpCommunicationServiceIT extends AbstractIT {

  protected MailchimpCommunicationServiceIT() {
    super(new App(new EnvironmentFactory("environment-it-sfdc-mailchimp.json") {
      @Override
      public Environment newEnv() {
        return new Environment() {
          @Override
          public MailchimpClient mailchimpClient(EnvironmentConfig.CommunicationPlatform mailchimpConfig) {
            String decoded = new String(Base64.getDecoder().decode("YWU1ZTVhYjU3NmEwMzNhMjM1OTJkMjFhNGE0YjkxY2MtdXMyMg=="));
            mailchimpConfig.secretKey = decoded;
            return new MailchimpClient(mailchimpConfig,this);
          }
        };
      }
    }));
  }

  @Test
  public void syncContacts() throws Exception {
    List<String> emails = new ArrayList<>();
    List<Object> values = new LinkedList<>();
    values.add(List.of("Contact First Name", "Contact Last Name", "Contact Email", "Contact Email Opt In"));

    for (int i = 1; i <= 5; i++) {
      String firstname = RandomStringUtils.randomAlphabetic(8);
      String lastname = RandomStringUtils.randomAlphabetic(8);
      String email = (firstname + "." + lastname + "@gmail.com").toLowerCase(Locale.ROOT);

      emails.add(email);
      values.add(List.of(firstname, lastname, email, "true"));
    }

    // Bulk import to SF
    Instant instant = LocalDate.now().atStartOfDay(ZoneId.of("UTC")).toInstant();
    Calendar beforeBulkImport = Calendar.getInstance();
    beforeBulkImport.setTimeInMillis(instant.toEpochMilli());

    postToBulkImport(values);

    List<CrmContact> crmContacts = env.primaryCrmService().getContactsByEmails(emails);
    assertNotNull(crmContacts);

    List<String> crmContactsEmails = crmContacts.stream().map(crmContact -> crmContact.email).collect(Collectors.toList());
    assertEquals(emails.size(), crmContactsEmails.size());
    assertTrue(crmContactsEmails.containsAll(emails));

    // Sync contacts to MC
    env.communicationService("mailchimp").syncContacts(beforeBulkImport);

    EnvironmentConfig.Mailchimp mailchimp = env.getConfig().mailchimp.get(0);
    String listId = mailchimp.lists.get(0).id;
    MailchimpClient mailchimpClient = env.mailchimpClient(mailchimp);

    assertEmailsStatus(emails, "subscribed", listId, mailchimpClient);
  }

  @Test
  public void syncUnsubscribes() throws Exception {
    List<String> unsubscribeEmails = new ArrayList<>();
    List<String> cleanEmails = new ArrayList<>();
    List<Object> values = new LinkedList<>();
    values.add(List.of("Contact First Name", "Contact Last Name", "Contact Email", "Contact Email Opt In"));

    for (int i = 1; i <= 5; i++) {
      String firstname = RandomStringUtils.randomAlphabetic(8);
      String lastname = RandomStringUtils.randomAlphabetic(8);
      String email = (firstname + "." + lastname + "@gmail.com").toLowerCase(Locale.ROOT);

      ((i % 2 == 0) ? unsubscribeEmails : cleanEmails).add(email);
      values.add(List.of(firstname, lastname, email, "true"));
    }

    List<String> emails = new ArrayList<>();
    emails.addAll(unsubscribeEmails);
    emails.addAll(cleanEmails);

    // Bulk import to SF
    Instant instant = LocalDate.now().atStartOfDay(ZoneId.of("UTC")).toInstant();
    Calendar beforeBulkImport = Calendar.getInstance();
    beforeBulkImport.setTimeInMillis(instant.toEpochMilli());

    postToBulkImport(values);

    List<CrmContact> crmContacts = env.primaryCrmService().getContactsByEmails(emails);
    assertNotNull(crmContacts);

    List<String> crmContactsEmails = crmContacts.stream().map(crmContact -> crmContact.email).collect(Collectors.toList());
    assertEquals(emails.size(), crmContactsEmails.size());
    assertTrue(crmContactsEmails.containsAll(emails));

    // Subscribe emails to MC list
    EnvironmentConfig.Mailchimp mailchimp = env.getConfig().mailchimp.get(0);
    String listId = mailchimp.lists.get(0).id;
    MailchimpClient mailchimpClient = env.mailchimpClient(mailchimp);

    addEmailsToList(emails, "subscribed", listId, mailchimpClient);
    Thread.sleep(10000);

    // Update statuses to unsubscribed / cleaned
    updateEmailsStatus(unsubscribeEmails, "unsubscribed", listId, mailchimpClient);
    assertEmailsStatus(unsubscribeEmails, "unsubscribed", listId, mailchimpClient);

    updateEmailsStatus(cleanEmails, "cleaned", listId, mailchimpClient);
    assertEmailsStatus(cleanEmails, "cleaned", listId, mailchimpClient);

    // Sync unsubscribes MC >> SF
    env.communicationService("mailchimp").syncUnsubscribes(beforeBulkImport);

    crmContacts = env.primaryCrmService().getContactsByEmails(unsubscribeEmails);
    assertFalse(crmContacts.isEmpty());

    for (CrmContact crmContact : crmContacts) {
      assertTrue(Boolean.TRUE == crmContact.emailOptOut);
    }

    crmContacts = env.primaryCrmService().getContactsByEmails(cleanEmails);
    assertFalse(crmContacts.isEmpty());

    for (CrmContact crmContact : crmContacts) {
      assertTrue(Boolean.TRUE == crmContact.emailBounced);
    }
  }

  // Utils
  private void addEmailsToList(List<String> emails, String status, String listId, MailchimpClient mailchimpClient) throws Exception {
    List<MemberInfo> memberInfos = emails.stream().map(email -> toMemberInfo(email, status)).collect(Collectors.toList());
    mailchimpClient.upsertContactsBatch(listId, memberInfos);
  }

  private MemberInfo toMemberInfo(String email, String status) {
    MemberInfo memberInfo = new MemberInfo();
    memberInfo.email_address = email;
    memberInfo.status = status;
    memberInfo.merge_fields = new MailchimpObject();
    memberInfo.interests = new MailchimpObject();
    return memberInfo;
  }

  private void updateEmailsStatus(List<String> emails, String status, String listId, MailchimpClient mailchimpClient) throws Exception {
    List<MemberInfo> memberInfos = mailchimpClient.getListMembers(listId, null, "members.email_address,members.status,members.merge_fields,members.interests,total_items", null);
    Map<String, MemberInfo> memberInfoMap = memberInfos.stream()
            .collect(Collectors.toMap(
                    m -> m.email_address, m -> m
            ));
    for (String email : emails) {
      MemberInfo memberInfo = memberInfoMap.get(email);
      if (memberInfo == null) {
        throw new IllegalStateException("Failed to find email '" + email + "' in list '" + listId + "'! Skipping update.");
      } else {
        memberInfo.status = status;
        memberInfo.interests = new MailchimpObject(); // API does not return this field for some reason but is required for update
        mailchimpClient.upsertContact(listId, memberInfo);
      }
    }
  }

  private void assertEmailsStatus(List<String> emails, String status, String listId, MailchimpClient mailchimpClient) throws Exception {
    List<MemberInfo> listMembers = mailchimpClient.getListMembers(listId, status, "members.email_address,members.status,total_items", null);
    assertNotNull(listMembers);
    List<String> membersEmails = listMembers.stream().map(memberInfo -> memberInfo.email_address).collect(Collectors.toList());
    assertTrue(membersEmails.containsAll(emails));
    for (MemberInfo memberInfo : listMembers) {
      if (emails.contains(memberInfo.email_address)) {
        assertEquals(status, memberInfo.status);
      }
    }
  }
}
