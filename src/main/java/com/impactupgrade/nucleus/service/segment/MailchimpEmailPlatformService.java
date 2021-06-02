package com.impactupgrade.nucleus.service.segment;

import com.ecwid.maleorang.MailchimpException;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.Opt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;

public class MailchimpEmailPlatformService implements EmailPlatformService {

  private static final Logger log = LogManager.getLogger(MailchimpEmailPlatformService.class);

  protected final Environment env;
  protected final MailchimpClient mailchimpClient;

  public MailchimpEmailPlatformService(Environment env){
    this.env = env;
    mailchimpClient = new MailchimpClient(this.env);
  }

  @Override
  public List<String> getEmailsInList(String listName) throws IOException, MailchimpException, InterruptedException, ConnectionException {
    return mailchimpClient.getContactEmails(listName);
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String listName, String email) throws Exception {
    if (env.crmService().getContactByEmail(email).isPresent()){
      return env.crmService().getContactByEmail(email);
    }else{
      return Optional.empty();
    }
  }

  @Override
  public List<CrmContact> getListOfContacts(String listName) throws Exception {
    List<CrmContact> contacts = new ArrayList<CrmContact>();
    for(MemberInfo mcContact : mailchimpClient.getContactList(listName)) {
      contacts.add(mailchimpClient.toCrmContact(mcContact));
    }
    return contacts;
  }

  @Override
  public void addContactToList(CrmContact crmContact, String listName) throws Exception {
    mailchimpClient.addContactToList(crmContact,listName);
  }

  @Override
  public void updateContact(CrmContact contact, String listName) throws Exception{
    mailchimpClient.updateContact(listName,contact);
  }

  @Override
  public void unsubscribeContact(String email, String listName) throws Exception {
    Optional<CrmContact> contact = env.crmService().getContactByEmail(email);
    if (contact.isPresent()) {
      contact.get().emailOptIn = false;
      //env.crmService().updateContact(contact.get()); TODO brett fixing this
      log.info(email + "unsubscribed from list " + listName);
    }

  }


  @Override
  public List<String> getContactGroups(CrmContact crmContact, String listName) throws Exception {
    return mailchimpClient.getContactGroupIDs(listName,crmContact.email);
  }

  @Override
  public void addContactToGroup(CrmContact crmContact, String listName, String groupName) throws Exception {
    mailchimpClient.addContactToGroup(listName, crmContact.email, groupName);
  }

  @Override
  public List<String> getContactTags(String listName, CrmContact crmContact) throws Exception {
    return mailchimpClient.getContactTags(mailchimpClient.getListIdFromName(listName), crmContact.email);
  }

  @Override
  public void addTagToContact(String listName, CrmContact crmContact, String tag) throws Exception {
    mailchimpClient.addTag(listName,crmContact.email,tag);
  }


  @Override
  public void syncNewContacts(Calendar calendar) throws Exception {
    //env.crmService().getContactsSince(calendar).forEach(c -> updateContact(c,"listname")); //todo figure out list name & exceptions
    log.info("New/Updated contacts retrieved from CRM");
  }

  @Override
  public void syncNewDonors(Calendar calendar) throws Exception {
    //env.crmService().getDonorsSince(calendar).forEach(c -> updateContact(c,"listname")); //todo figure out list name & exceptions
    log.info("Donors retrieved from CRM");
  }


}
