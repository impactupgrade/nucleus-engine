/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.ecwid.maleorang.MailchimpException;
import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.lists.members.EditMemberMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.GetMemberMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.GetMembersMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.sforce.ws.ConnectionException;

import java.io.IOException;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class MailchimpClient {

  private String apiKey;
  private static final com.ecwid.maleorang.MailchimpClient CLIENT = new com.ecwid.maleorang.MailchimpClient(System.getenv("MAILCHIMP_KEY"));
  private static final String testListKey = System.getenv("TEST_LIST_KEY");//TODO: Get these elsewhere
  private final Environment env;

  //<editor-fold desc="API name variables">
  private static final String CONTACT_SUBSCRIBED = "subscribed";
  private static final String CONTACT_UNSUBSCRIBED = "unsubscribed";
  private static final String FIRST_NAME = "FNAME";
  private static final String LAST_NAME = "LNAME";
  private static final String TAGS = "tags";
  private static final String PHONE_NUMBER = "PHONE";
  private static final String TAG_COUNT = "tags_count";
  private static final String TAG_NAME = "name";
  private static final String TAG_STATUS = "status";
  private static final String TAG_ACTIVE = "active";
  private static final String TAG_INACTIVE = "inactive";
  private static final String ADDRESS = "ADDRESS";
  //</editor-fold>

  public MailchimpClient(Environment env){
    this.env = env;
  }

  //////////////////////////////////////////////Utility
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

//TODO: Test new Conversions
  public CrmContact toCrmContact(MemberInfo member){
    CrmContact contact = new CrmContact();
    contact.id = member.id;
    contact.email = member.email_address;
    contact.firstName = (String) member.merge_fields.mapping.get(FIRST_NAME);
    contact.lastName = (String) member.merge_fields.mapping.get(LAST_NAME);
    contact.phone = (String) member.merge_fields.mapping.get(PHONE_NUMBER);
    if(member.status == CONTACT_SUBSCRIBED){
      contact.emailOptIn = true;
    }else{
      contact.emailOptIn = false;
    }
    contact.address = toCrmAddress((Map<String, Object>) member.merge_fields.mapping.get(ADDRESS));
//    contact.groups = getContactGroupIDs(contact.listName, contact.email); //TODO: Figure out listName?
    return contact;
  }

  public MemberInfo toMemberInfo(CrmContact contact){
    MemberInfo McContact = new MemberInfo();
    McContact.id = contact.id;
    McContact.email_address = contact.email;
    McContact.merge_fields.mapping.put(FIRST_NAME,contact.firstName);
    McContact.merge_fields.mapping.put(LAST_NAME,contact.lastName);
    McContact.merge_fields.mapping.put(PHONE_NUMBER,contact.phone);
    McContact.mapping.put(ADDRESS,toMcAddress(contact.address));
    if(contact.emailOptIn){
      McContact.status = CONTACT_SUBSCRIBED;
    }else{
      McContact.status = CONTACT_UNSUBSCRIBED;
    }
    List<String> groupIds = contact.groups.stream().map(g -> getGroupIdFromName(g)).collect(Collectors.toList());
    McContact.interests = createGroupMap(groupIds);

    return McContact;
  }

  protected CrmAddress toCrmAddress(Map<String,Object> address){
    CrmAddress crmAddress = new CrmAddress();

    crmAddress.country = (String) address.get("country");
    crmAddress.street = (String) address.get("state");
    crmAddress.city = (String) address.get("city");
    crmAddress.street = address.get("addr1") + "\n" + address.get("addr2");
    crmAddress.postalCode = (String) address.get("zip");

    return crmAddress;
  }

  protected MailchimpObject toMcAddress(CrmAddress address){
    MailchimpObject mcAddress = new MailchimpObject();

    mcAddress.mapping.put("country", address.country);
    mcAddress.mapping.put("state", address.state);
    mcAddress.mapping.put("city", address.city);
    mcAddress.mapping.put("addr1", address.street); //TODO does not handle multiple address lines eg. addr1 & addr2
    mcAddress.mapping.put("zip", address.postalCode);

    return mcAddress;
  }

  /**
   * Returns the ID of the list from the config map
   * @param listName the key, name of the list to get ID from
   * @return
   */
  public String getListIdFromName(String listName){
    return env.getConfig().mailchimp.lists.get(listName);
  }

  /**
   * Returns the ID of the group from the config map
   * @param groupName the key, name of the group to get ID from
   * @return
   */
  public String getGroupIdFromName(String groupName){
    return env.getConfig().mailchimp.groups.get(groupName);
  }

  /**
   *  Get a specific contact's information (as an object)
   * @param listName The Name of the list being accessed
   * @param contactEmail The email of the contact being accessed
   * @return The contact's data represented as a MemberInfo object
   * @throws IOException
   * @throws MailchimpException
   * @throws ConnectionException
   * @throws InterruptedException
   */
  public MemberInfo getContactInfo(String listName, String contactEmail) throws IOException, MailchimpException, ConnectionException, InterruptedException {
    GetMemberMethod getMemberMethod = new GetMemberMethod(getListIdFromName(listName), contactEmail);
    return CLIENT.execute(getMemberMethod);
  }

  /**
   * Given a list of group IDs will create a MailchimpObject to assign to a contact
   * @param groupIDs IDs of the groups to assign
   * @return MailchimpObject with group data
   */
  public MailchimpObject createGroupMap(List<String> groupIDs){ //TODO: <-Could change this to group names if we figure groups out more w/ the CRM
    MailchimpObject groupMap = new MailchimpObject();
    groupIDs.forEach(id -> groupMap.mapping.put(id,true));
    return groupMap;
  }

  /**
   * Creates a MailChimp object to add a contact's name
   * @param fName Contact's first name
   * @param lName Contact's last name
   * @return
   */
  public MailchimpObject createNameFieldMap(String fName, String lName){
    MailchimpObject nameMap = new MailchimpObject();
    nameMap.mapping.put(FIRST_NAME,fName);
    nameMap.mapping.put(LAST_NAME,lName);
    return nameMap;
  }

  //TODO: TEST THIS
  public void updateContact(String listName, CrmContact contact) throws IOException, MailchimpException {
    EditMemberMethod editMemberMethod = new EditMemberMethod.Update(getListIdFromName(listName),contact.email);
    MemberInfo updatedContact = toMemberInfo(contact);
    editMemberMethod.mapping.putAll(updatedContact.mapping);
    CLIENT.execute(editMemberMethod);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////Lists
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Get a list of contacts (as objects)
   * @param listName The name of the list being accessed
   * @return A List of each contact represented as a MemberInfo object
   * @throws IOException
   * @throws MailchimpException
   * @throws ConnectionException
   * @throws InterruptedException
   */
  public List<MemberInfo> getContactList(String listName) throws IOException,MailchimpException, ConnectionException, InterruptedException{
    GetMembersMethod getMembersMethod = new GetMembersMethod(getListIdFromName(listName));
    GetMembersMethod.Response getMemberResponse = CLIENT.execute(getMembersMethod);
    return getMemberResponse.members;
  }

  /**
   * Get a list of contact's emails from a  list
   * @param listName The Name of the list being accessed
   * @return A list of contact emails
   * @throws IOException
   * @throws MailchimpException
   * @throws ConnectionException
   * @throws InterruptedException
   */
  public List<String> getContactEmails(String listName ) throws IOException, MailchimpException, ConnectionException, InterruptedException {
//    List<MemberInfo> contacts = getContactList(listName);
//    List<String> contactEmails = new ArrayList<String>();
//    contacts.forEach(c-> contactEmails.add(c.email_address));
//    return contactEmails;
    //TODO stream is not tested
   return getContactList(listName).stream().map(c -> c.email_address).collect(Collectors.toList());
  }

  /**
   * Add a contact to a list
   * Will only add them as an email address in the list
   * @param listName name of the list to add to
   * @param contact contact in the Crm
   * @throws IOException
   * @throws MailchimpException
   * @throws ConnectionException
   * @throws InterruptedException
   */
  public void addContactToList(CrmContact contact, String listName) throws IOException, MailchimpException, ConnectionException, InterruptedException{
    EditMemberMethod editMemberMethod = new EditMemberMethod.Create(getListIdFromName(listName), contact.email);
    editMemberMethod.status = CONTACT_SUBSCRIBED;
    CLIENT.execute(editMemberMethod);
  }

  /**
   * Unsubscribes a contact from a list
   * @param email email of the contact to be unsubscribed
   * @param listID ID of the list to be unsubscribed from
   * @throws IOException
   * @throws MailchimpException
   * @throws ConnectionException
   * @throws InterruptedException
   */
  public void unsubscribeContact(String email, String listID) throws IOException, MailchimpException, ConnectionException, InterruptedException{
    EditMemberMethod editMemberMethod = new EditMemberMethod.Update(listID, email);
    editMemberMethod.status = CONTACT_UNSUBSCRIBED;
    CLIENT.execute(editMemberMethod);
  }

  /**
   * Add a contact to a list with more info
   * @param listName name of the list to assign to
   * @param contactEmail The contact's email
   * @param groupIDs A list of groups (IDs) to assign to a contact
   * @param fName Contact's first name
   * @param lName Contact's Last name
   * @throws IOException
   * @throws MailchimpException
   * @throws ConnectionException
   * @throws InterruptedException
   */
  //TODO: TEST this method
  public void addContactToList(String listName, String contactEmail, List<String> groupIDs, String fName, String lName, CrmAddress address, String phone)throws IOException, MailchimpException, ConnectionException, InterruptedException{
    EditMemberMethod editMemberMethod = new EditMemberMethod.Create(listName, contactEmail);
    editMemberMethod.status = CONTACT_SUBSCRIBED;
    editMemberMethod.interests = createGroupMap(groupIDs) ;
    editMemberMethod.merge_fields = createNameFieldMap(fName, lName);
    editMemberMethod.merge_fields.mapping.put(PHONE_NUMBER,phone);
    editMemberMethod.merge_fields.mapping.put(ADDRESS,toMcAddress(address));
    CLIENT.execute(editMemberMethod);
    addContactToGroups(listName,contactEmail,groupIDs);
  }


  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////Groups
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Add a contact to a group
   * @param listName Name of the list being accessed
   * @param contactEmail The email of the contact being added
   * @param groupName The name of the group the contact will be added to
   * @throws IOException
   * @throws MailchimpException
   * @throws ConnectionException
   * @throws InterruptedException
   */
  public void addContactToGroup(String listName, String contactEmail, String groupName) throws IOException, MailchimpException, ConnectionException, InterruptedException {
    EditMemberMethod editMemberMethod = new EditMemberMethod.Update(getListIdFromName(listName), contactEmail);
    editMemberMethod.interests = getContactInfo(listName, contactEmail).interests;
    editMemberMethod.interests.mapping.replace(getGroupIdFromName(groupName), true);
    CLIENT.execute(editMemberMethod);
  }

  /**
   * Add a contact to multiple groups
   * @param listName name of the list being accessed
   * @param contactEmail Email of the contact being added
   * @param groupNames Names of the groups the contact is added to
   * @throws IOException
   * @throws MailchimpException
   * @throws ConnectionException
   * @throws InterruptedException
   */
  public void addContactToGroups(String listName, String contactEmail, List<String> groupNames) throws IOException, MailchimpException, ConnectionException, InterruptedException{
    for (String name : groupNames) {
      addContactToGroup(listName, contactEmail, name);
    }
  }

  /**
   * Gets a list of group IDs for a contact
   * @param listName  name of the list being accessed
   * @param contactEmail Email of the contact being accessed
   * @return A list of group IDs
   * @throws IOException
   * @throws MailchimpException
   * @throws ConnectionException
   * @throws InterruptedException
   */
  public List<String> getContactGroupIDs(String listName, String contactEmail) throws IOException, MailchimpException, ConnectionException, InterruptedException{
    MemberInfo contact = getContactInfo(listName,contactEmail);
//    List<String> groups = new ArrayList<>();
//    contact.interests.mapping.keySet().forEach(value -> groups.add((String) value));
//    return groups;
    //TODO test stream
    return contact.interests.mapping.keySet().stream().collect(Collectors.toList());
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////Tags
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


  /**
   * Returns a list of a contact's tags
   * @param listName name of the audience being accessed
   * @param contactEmail Email of the member who's being accessed
   * @return A list of a contact's tags
   * @throws IOException
   * @throws MailchimpException
   * @throws ConnectionException
   * @throws InterruptedException
   */
  //TODO Test this
  public List<String> getContactTags(String listName, String contactEmail) throws IOException, MailchimpException, ConnectionException, InterruptedException{
    MemberInfo member = getContactInfo(listName, contactEmail);
    List<MailchimpObject> tags = (List<MailchimpObject>) member.mapping.get(TAGS);
    return tags.stream().map(t -> t.mapping.get(TAG_NAME).toString()).collect(Collectors.toList());
  }


  //TODO: TEST

  public void addTag(String listName, String contactEmail, String tagName) throws IOException, MailchimpException, ConnectionException, InterruptedException {
    MailchimpObject newTag = new MailchimpObject();
    newTag.mapping.put(TAG_STATUS, TAG_ACTIVE);
    newTag.mapping.put(TAG_NAME, tagName);
    EditMemberMethod.AddorRemoveTag editMemberMethod = new EditMemberMethod.AddorRemoveTag(getListIdFromName(listName), contactEmail);
    editMemberMethod.tags = getContactInfo(listName, contactEmail).tags;
    editMemberMethod.tags.add(newTag);
    CLIENT.execute(editMemberMethod);
  }

  //TODO: TEST
  public void removeTag(String listName, String contactEmail, String tagName) throws IOException, MailchimpException, ConnectionException, InterruptedException {
    MailchimpObject tag = new MailchimpObject();
    tag.mapping.put(TAG_STATUS, TAG_INACTIVE);
    tag.mapping.put(TAG_NAME, tagName);
    EditMemberMethod.AddorRemoveTag editMemberMethod = new EditMemberMethod.AddorRemoveTag(getListIdFromName(listName), contactEmail);
    editMemberMethod.tags = getContactInfo(listName, contactEmail).tags;
    editMemberMethod.tags.add(tag);
    editMemberMethod.tags.remove(tag);
    CLIENT.execute(editMemberMethod);
  }



  //   //TODO
  public void findSpamContacts(Date sinceDate) throws IOException, MailchimpException, ConnectionException, InterruptedException {
//    // TODO: 39dcb0514a == US Marketing
//    GetMembersMethod getMembersMethod = new GetMembersMethod("39dcb0514a");
//    // TODO: Max is 1000, but that timed out
//    getMembersMethod.count = 600;
//    getMembersMethod.since_timestamp_opt = sinceDate;
//    GetMembersMethod.Response getMembersResponse = CLIENT.execute(getMembersMethod);
//    int count = 0;
//    int size = getMembersResponse.members.size();
//    for (MemberInfo member : getMembersResponse.members) {
//      count++;
//      Optional<SObject> contact = sfdcClient.getContactByEmail(member.email_address);
//      Optional<Account> account = sfdcClient.getAccountByEmail(member.email_address);
//      if (contact.isEmpty() && account.isEmpty()) {
//        System.out.println("[" + count + " of " + size + "] missing : " + member.email_address);
//        // TODO: currently requires a branch build (https://github.com/brmeyer/maleorang/tree/permanently-delete)
//        // Have a PR in place, but the project may be abandoned. Time to fork it?
//        DeletePermanentMemberMethod deleteMemberMethod = new DeletePermanentMemberMethod("39dcb0514a", member.email_address);
//        CLIENT.execute(deleteMemberMethod);
//      } else {
//        System.out.println("[" + count + " of " + size + "]");
//      }
//    }
  }
}
