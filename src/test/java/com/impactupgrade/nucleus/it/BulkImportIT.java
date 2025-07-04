/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO: This test relies on some picklists existing in our dev edition of SFDC. Make it more resilient to auto create
//  those fields if they don't already exist!

// IMPORTANT: When debugging these tests, note that the Bulk Import endpoint spins off an async thread. So a breakpoint
// in the code processing the import does NOT pause the IT code!

public class BulkImportIT extends AbstractIT {

  protected BulkImportIT() {
    super(new App(envFactorySfdcStripe));
  }

  @Test
  public void existingRecords() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    String accountNameExisting = RandomStringUtils.randomAlphabetic(8);
    SObject account = new SObject("Account");
    account.setField("Name", accountNameExisting);
    String accountIdExisting = sfdcClient.insert(account).getId();

    String accountNameA = RandomStringUtils.randomAlphabetic(8);
    String firstnameA = RandomStringUtils.randomAlphabetic(8);
    String lastnameA = RandomStringUtils.randomAlphabetic(8);
    String emailA = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";

    String firstnameB = RandomStringUtils.randomAlphabetic(8);
    String lastnameB = RandomStringUtils.randomAlphabetic(8);
    String emailB = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";

    String firstnameC = RandomStringUtils.randomAlphabetic(8);
    String lastnameC = RandomStringUtils.randomAlphabetic(8);
    String emailC = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";

    SObject contactExistingD = randomContactSfdc();
    String contactIdExistingD = contactExistingD.getId();
    String emailExistingD = contactExistingD.getField("Email").toString();
    String accountNameD = RandomStringUtils.randomAlphabetic(8);
    String firstnameD = RandomStringUtils.randomAlphabetic(8);
    String lastnameD = RandomStringUtils.randomAlphabetic(8);

    SObject contactExistingE = randomContactSfdc();
    String contactIdExistingE = contactExistingE.getId();
    String firstNameExistingE = contactExistingE.getField("FirstName").toString();
    String lastNameExistingE = contactExistingE.getField("LastName").toString();
    String emailExistingE = contactExistingE.getField("Email").toString();
    String extRefE = RandomStringUtils.randomAlphabetic(8);

    SObject contactExistingF = randomContactSfdc();
    String contactIdExistingF = contactExistingF.getId();
    String firstNameExistingF = contactExistingF.getField("FirstName").toString();
    String lastNameExistingF = contactExistingF.getField("LastName").toString();
    String emailExistingF = contactExistingF.getField("Email").toString();
    String extRefExistingF = RandomStringUtils.randomAlphabetic(8);
    contactExistingF.setField("External_Reference__c", extRefExistingF);
    sfdcClient.update(contactExistingF);
    String extRefF = RandomStringUtils.randomAlphabetic(8);

    final List<Object> values = List.of(
        List.of("Account Name", "Contact First Name", "Contact Last Name", "Contact Personal Email", "Contact ExtRef External_Reference__c"),
        List.of(accountNameA, firstnameA, lastnameA, emailA, ""),
        // same account name that was inserted in the line above
        List.of(accountNameA, firstnameB, lastnameB, emailB, ""),
        // account name that already existed
        List.of(accountNameExisting, firstnameC, lastnameC, emailC, ""),
        // email that already existed
        List.of(accountNameD, firstnameD, lastnameD, emailExistingD, ""),
        // email that already existed without an extref, ignore the extref for fetching but the extref should be updated on the record
        List.of("", firstNameExistingE, lastNameExistingE, emailExistingE, extRefE),
        // email that already existed WITH an extref, which should create a new contact despite the email match
        List.of("", firstNameExistingF, lastNameExistingF, emailExistingF, extRefF)
    );
    postToBulkImport(values);

    // TODO: also test --> phone, name+street

    List<SObject> aAccounts = sfdcClient.getAccountsByName(accountNameA);
    assertEquals(1, aAccounts.size());
    String aAccountId = aAccounts.get(0).getId();
    List<SObject> emailAContacts = sfdcClient.getContactsByEmails(List.of(emailA));
    assertEquals(1, emailAContacts.size());
    List<SObject> emailBContacts = sfdcClient.getContactsByEmails(List.of(emailB));
    assertEquals(1, emailBContacts.size());
    List<SObject> emailCContacts = sfdcClient.getContactsByEmails(List.of(emailC));
    assertEquals(1, emailCContacts.size());
    List<SObject> emailDExistingContacts = sfdcClient.getContactsByEmails(List.of(emailExistingD));
    assertEquals(1, emailDExistingContacts.size());
    List<SObject> emailEExistingContacts = sfdcClient.getContactsByEmails(List.of(emailExistingE));
    assertEquals(1, emailEExistingContacts.size());
    // despite the email match, different extrefs so a duplicate was allowed
    List<SObject> emailFExistingContacts = sfdcClient.getContactsByEmails(List.of(emailExistingF));
    assertEquals(2, emailFExistingContacts.size());

    assertEquals(aAccountId, emailAContacts.get(0).getField("AccountId"));
    assertEquals(aAccountId, emailBContacts.get(0).getField("AccountId"));
    assertEquals(accountIdExisting, emailCContacts.get(0).getField("AccountId"));
    assertEquals(firstnameD + " " + lastnameD, sfdcClient.getContactById(contactIdExistingD).get().getField("Name")); // should have updated the name, using the existing email
    assertEquals(extRefE, sfdcClient.getContactById(contactIdExistingE).get().getField("External_Reference__c")); // should have updated the extref, using the existing email
    assertEquals(extRefExistingF, sfdcClient.getContactById(contactIdExistingF).get().getField("External_Reference__c")); // should NOT have updated the extref
    Optional<SObject> emailFNewContact = emailFExistingContacts.stream().filter(c -> !c.getId().equals(contactIdExistingF)).findFirst();
    assertEquals(extRefF, emailFNewContact.get().getField("External_Reference__c")); // second one by-email, but with the unique extref

    clearSfdc(accountNameExisting);
    clearSfdc(lastnameA);
    clearSfdc(lastnameB);
    clearSfdc(lastnameC);
    clearSfdc(lastnameD);
    clearSfdc(lastNameExistingE);
    clearSfdc(lastNameExistingF);
  }

  @Test
  public void multipleContactsSameEmail() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    String email = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";

    String firstnameA = RandomStringUtils.randomAlphabetic(8);
    String lastnameA = RandomStringUtils.randomAlphabetic(8);

    String firstnameB = RandomStringUtils.randomAlphabetic(8);
    String lastnameB = RandomStringUtils.randomAlphabetic(8);

    List<Object> values = List.of(
        List.of("Contact First Name", "Contact Last Name", "Contact Email"),
        List.of(firstnameA, lastnameA, email),
        // same email, different name
        List.of(firstnameB, lastnameB, email)
    );
    postToBulkImport(values);

    // only the first should have been kept and the second skipped
    List<SObject> emailContacts = sfdcClient.getContactsByEmails(List.of(email));
    assertEquals(1, emailContacts.size());
    assertEquals(firstnameA + " " + lastnameA, emailContacts.get(0).getField("Name"));

    // run it again -- A already exists in SFDC, so we're making sure that it's not overwritten by B
    postToBulkImport(values);
    emailContacts = sfdcClient.getContactsByEmails(List.of(email));
    assertEquals(1, emailContacts.size());
    assertEquals(firstnameA + " " + lastnameA, emailContacts.get(0).getField("Name"));

    // run it again -- A already exists in SFDC, but this time B is listed first and SHOULD overwrite
    values = List.of(
        List.of("Contact First Name", "Contact Last Name", "Contact Email"),
        List.of(firstnameB, lastnameB, email),
        List.of(firstnameA, lastnameA, email)
    );
    postToBulkImport(values);
    emailContacts = sfdcClient.getContactsByEmails(List.of(email));
    assertEquals(1, emailContacts.size());
    assertEquals(firstnameB + " " + lastnameB, emailContacts.get(0).getField("Name"));

    clearSfdc(lastnameA);
    clearSfdc(lastnameB);
  }

  @Test
  public void multipleMatchesByName() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    String firstname = RandomStringUtils.randomAlphabetic(8);
    String lastname = RandomStringUtils.randomAlphabetic(8);
    String street = "13022 Redding Drive";
    String city = "Fort Wayne";
    String state = "IN";
    String zip = "46814";

    SObject contact1 = new SObject("Contact");
    contact1.setField("FirstName", firstname);
    contact1.setField("LastName", lastname);
    contact1.setField("MailingStreet", street);
    contact1.setField("MailingCity", city);
    contact1.setField("MailingState", state);
    contact1.setField("MailingPostalCode", zip);
    contact1.setId(sfdcClient.insert(contact1).getId());

    SObject contact2 = new SObject("Contact");
    contact2.setField("FirstName", firstname);
    contact2.setField("LastName", lastname);
    contact2.setField("MailingStreet", street);
    contact2.setField("MailingCity", city);
    contact2.setField("MailingState", state);
    contact2.setField("MailingPostalCode", zip);
    contact2.setId(sfdcClient.insert(contact2).getId());

    final List<Object> values = List.of(
        List.of("Contact First Name", "Contact Last Name", "Contact Mailing Street", "Contact Mailing City", "Contact Mailing State", "Contact Mailing Postal Code", "Contact Description"),
        List.of(firstname, lastname, street, city, state, zip, "test")
    );
    postToBulkImport(values);

    List<SObject> contacts = sfdcClient.queryListAutoPaged("SELECT Id, Description FROM Contact WHERE Name='" + firstname + " " + lastname + "'");
    assertEquals(2, contacts.size());

    // ensure the oldest contact received the update
    assertEquals("test", contacts.get(0).getField("Description"));
    assertNull(contacts.get(1).getField("Description"));

    clearSfdc(lastname);
  }

  @Test
  public void extrefEdgeCases() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    String extRef1 = RandomStringUtils.randomAlphabetic(8);
    String extRef2 = RandomStringUtils.randomAlphabetic(8);
    String extRef3 = RandomStringUtils.randomAlphabetic(8);

    String nameA = RandomStringUtils.randomAlphabetic(8);
    String nameB = RandomStringUtils.randomAlphabetic(8);
    String firstnameA = RandomStringUtils.randomAlphabetic(8);
    String lastnameA = RandomStringUtils.randomAlphabetic(8);
    String firstnameB = RandomStringUtils.randomAlphabetic(8);
    String lastnameB = RandomStringUtils.randomAlphabetic(8);
    String emailA = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";
    String emailB = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";

    final List<Object> values = List.of(
        List.of("Account ExtRef External_Reference__c", "Contact ExtRef External_Reference__c", "Account Name", "Contact First Name", "Contact Last Name", "Contact Personal Email"),
        List.of(extRef1, extRef1, nameA, firstnameA, lastnameA, emailA),
        // same account name, but the "2" account extref should be used (import another account with the same name) and the account we imported above should be ignored
        List.of(extRef2, extRef2, nameA, firstnameB, lastnameB, emailB),
        // similarly, if the contact email was already imported, use the contact extref (import another with the same email) and ignore the existing contact
        List.of(extRef3, extRef3, nameB, firstnameA, lastnameA, emailA)
    );
    postToBulkImport(values);

    List<SObject> aAccounts = sfdcClient.getAccountsByName(nameA);
    assertEquals(2, aAccounts.size());
    List<SObject> bAccounts = sfdcClient.getAccountsByName(nameB);
    assertEquals(1, bAccounts.size());
    List<SObject> emailAContacts = sfdcClient.getContactsByEmails(List.of(emailA));
//    assertEquals(2, emailAContacts.size()); TODO, currently fails, logic needs reworked to store contacts by-email, by-phone, etc. in the secondPass
    List<SObject> emailBContacts = sfdcClient.getContactsByEmails(List.of(emailB));
    assertEquals(1, emailBContacts.size());

    Optional<SObject> aAccount = aAccounts.stream().filter(a -> a.getField("External_Reference__c").equals(extRef1)).findFirst();
    assertEquals(aAccount.get().getId(), emailAContacts.get(0).getField("AccountId"));
    // contact B should have landed in account B, despite having the same account name
    aAccount = aAccounts.stream().filter(a -> a.getField("External_Reference__c").equals(extRef2)).findFirst();
    assertEquals(aAccount.get().getId(), emailBContacts.get(0).getField("AccountId"));

    clearSfdc(lastnameA);
    clearSfdc(lastnameB);
  }

  @Test
  public void appendPicklist() throws Exception {
    SObject contact1 = randomContactSfdc();
    SObject contact2 = randomContactSfdc();
    SObject contact3 = randomContactSfdc();
    SObject contact4 = randomContactSfdc();
    SObject contact5 = randomContactSfdc();

    SfdcClient sfdcClient = env.sfdcClient();

    contact1.setField("Test_Multi_Select__c", "Value 1");
    contact2.setField("Test_Multi_Select__c", "Value 1;Value 3");
    contact3.setField("Test_Multi_Select__c", "Value 2");
    contact4.setField("Test_Multi_Select__c", "Value 2;Value 3");
    // nothing for contact 5
    sfdcClient.batchUpdate(contact1);
    sfdcClient.batchUpdate(contact2);
    sfdcClient.batchUpdate(contact3);
    sfdcClient.batchUpdate(contact4);
    sfdcClient.batchFlush();

    final List<Object> values = List.of(
        List.of("Contact ID", "Contact Custom Append Test_Multi_Select__c"),
        List.of(contact1.getId(), "Value 2"),
        List.of(contact2.getId(), "Value 2"),
        List.of(contact3.getId(), "Value 2"),
        List.of(contact4.getId(), "Value 2"),
        List.of(contact5.getId(), "Value 2")
    );
    postToBulkImport(values);

    contact1 = sfdcClient.getContactById(contact1.getId(), "Test_Multi_Select__c").get();
    contact2 = sfdcClient.getContactById(contact2.getId(), "Test_Multi_Select__c").get();
    contact3 = sfdcClient.getContactById(contact3.getId(), "Test_Multi_Select__c").get();
    contact4 = sfdcClient.getContactById(contact4.getId(), "Test_Multi_Select__c").get();
    contact5 = sfdcClient.getContactById(contact5.getId(), "Test_Multi_Select__c").get();

    assertEquals("Value 1;Value 2", contact1.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 1;Value 2;Value 3", contact2.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 2", contact3.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 2;Value 3", contact4.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 2", contact5.getField("Test_Multi_Select__c").toString());

    clearSfdc(contact1.getField("LastName").toString());
    clearSfdc(contact2.getField("LastName").toString());
    clearSfdc(contact3.getField("LastName").toString());
    clearSfdc(contact4.getField("LastName").toString());
    clearSfdc(contact5.getField("LastName").toString());
  }

  @Test
  public void appendText() throws Exception {
    SObject contact1 = randomContactSfdc();
    SObject contact2 = randomContactSfdc();

    SfdcClient sfdcClient = env.sfdcClient();

    contact2.setField("Description", "Existing Description");
    sfdcClient.update(contact2);

    final List<Object> values = List.of(
        List.of("Contact ID", "Contact Custom Append Description"),
        List.of(contact1.getId(), "New Description"),
        List.of(contact2.getId(), "New Description")
    );
    postToBulkImport(values);

    contact1 = sfdcClient.getContactById(contact1.getId(), "Description").get();
    contact2 = sfdcClient.getContactById(contact2.getId(), "Description").get();

    assertEquals("New Description", contact1.getField("Description").toString());
    assertEquals("Existing Description;New Description", contact2.getField("Description").toString());

    clearSfdc(contact1.getField("LastName").toString());
    clearSfdc(contact2.getField("LastName").toString());
  }

  @Test
  public void clearOutValues() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    SObject contact1 = randomContactSfdc();
    contact1.setField("Phone", "1234567890");
    sfdcClient.update(contact1);
    SObject contact2 = randomContactSfdc();
    contact2.setField("Phone", "0987654321");
    sfdcClient.update(contact2);

    // just in case, let's make sure email is actually set first
    contact1 = sfdcClient.getContactById(contact1.getId()).get();
    assertNotNull(contact1.getField("Email"));
    assertNotNull(contact1.getField("Phone"));
    contact2 = sfdcClient.getContactById(contact2.getId()).get();
    assertNotNull(contact2.getField("Email"));
    assertNotNull(contact2.getField("Phone"));

    String firstname3 = RandomStringUtils.randomAlphabetic(8);
    String lastname3 = RandomStringUtils.randomAlphabetic(8);
    String firstname4 = RandomStringUtils.randomAlphabetic(8);
    String lastname4 = RandomStringUtils.randomAlphabetic(8);

    final List<Object> values = List.of(
        List.of("Contact ID", "Contact First Name", "Contact Last Name", "Contact Email", "Contact Phone"),
        List.of(contact1.getId(), contact1.getField("FirstName").toString(), contact1.getField("LastName").toString(), "CLEAR IT", "CLEAR IT"),
        List.of(contact2.getId(), contact2.getField("FirstName").toString(), contact2.getField("LastName").toString(), "CLEAR IT", "CLEAR IT"),
        // bit of an edge case, but what happens if we insert with CLEAR IT in multiple rows? shouldn't be treated as an email in existing-contacts-by-email lists
        List.of("", firstname3, lastname3, "CLEAR IT", "CLEAR IT"),
        List.of("", firstname4, lastname4, "CLEAR IT", "CLEAR IT")
    );
    postToBulkImport(values);

    contact1 = sfdcClient.getContactById(contact1.getId()).get();
    assertNull(contact1.getField("Email"));
    assertNull(contact1.getField("Phone"));
    contact2 = sfdcClient.getContactById(contact2.getId()).get();
    assertNull(contact2.getField("Email"));
    assertNull(contact2.getField("Phone"));
    SObject contact3 = sfdcClient.getContactsByNames(List.of(firstname3 + " " + lastname3)).get(0);
    assertNull(contact3.getField("Email"));
    assertNull(contact3.getField("Phone"));
    SObject contact4 = sfdcClient.getContactsByNames(List.of(firstname4 + " " + lastname4)).get(0);
    assertNull(contact4.getField("Email"));
    assertNull(contact4.getField("Phone"));

    clearSfdc(contact1.getField("LastName").toString());
    clearSfdc(contact2.getField("LastName").toString());
    clearSfdc(lastname3);
    clearSfdc(lastname4);
  }

  @Test
  public void doNotOverwriteHouseholdName() throws Exception {
    SObject contact = randomContactSfdc();

    SfdcClient sfdcClient = env.sfdcClient();

    SObject accountOld = new SObject("Account");
    accountOld.setId((String) contact.getField("AccountId"));
    String accountOldExtRef = RandomStringUtils.randomAlphabetic(8);
    accountOld.setField("External_Reference__c", accountOldExtRef);
    sfdcClient.update(accountOld);

    SObject accountNew = new SObject("Account");
    accountNew.setField("Name", "FooBar");
    String accountNewExtRef = RandomStringUtils.randomAlphabetic(8);
    accountNew.setField("External_Reference__c", accountNewExtRef);
    sfdcClient.insert(accountNew);

    final List<Object> values = List.of(
        List.of("Contact ID", "Account ExtRef External_Reference__c"),
        List.of(contact.getId(), accountNewExtRef)
    );
    postToBulkImport(values);

    SObject updatedContact = sfdcClient.getContactById(contact.getId()).get();

    assertEquals(accountOld.getId(), updatedContact.getField("AccountId"));

    clearSfdc(contact.getField("LastName").toString());
  }

  /**
   * If a Contact belongs to an Account, and that Account is different than the Account identified by the import's
   * ExtRef, DO NOT move the Contact! Ex: SIS imports often have grandparents in the same household as the parents/student.
   * Staff will later move the grandparents into their own household. Since that new household is different than
   * what the SIS sync's import identifies as the Account ExtRef, ignore it so that their manual movements aren't nuked!
   */
  @Test
  public void doNotOverwriteHousehold() throws Exception {
    SObject contact = randomContactSfdc();

    SfdcClient sfdcClient = env.sfdcClient();

    final List<Object> values = List.of(
        List.of("Contact ID", "Account Name", "Contact Mobile Phone"),
        List.of(contact.getId(), "FooBar Household", "123-456-7890")
    );
    postToBulkImport(values);

    SObject account = sfdcClient.getAccountById(contact.getField("AccountId").toString()).get();

    // ensure the old household name (generated by NPSP) was kept
    assertEquals(contact.getField("LastName") + " Household", account.getField("Name").toString());

    clearSfdc(contact.getField("LastName").toString());
  }

  @Test
  public void doNotOverwriteEmailAndPhonePrefs() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    // uses the basic Email field, not the breakdown + pref
    SObject contact1 = randomContactSfdc();
    String contact1Id = contact1.getId();
    String contact1Email = contact1.getField("Email").toString();

    // duplicates randomContactSfdc, but we need control over the prefs
    String randomFirstName = RandomStringUtils.randomAlphabetic(8);
    String randomLastName = RandomStringUtils.randomAlphabetic(8);
    String randomPersonalEmail = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";
    String randomWorkEmail = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";
    SObject account2 = new SObject("Account");
    account2.setField("Name", randomLastName + " Household");
    String account2Id = sfdcClient.insert(account2).getId();
    SObject contact2 = new SObject("Contact");
    contact2.setField("AccountId", account2Id);
    contact2.setField("FirstName", randomFirstName);
    contact2.setField("LastName", randomLastName);
    contact2.setField("npe01__WorkEmail__c", randomWorkEmail);
    contact2.setField("npe01__Preferred_Email__c", "Work");
    String contact2Id = sfdcClient.insert(contact2).getId();

    final List<Object> values = List.of(
        List.of("Contact Personal Email", "Contact Work Email", "Contact Preferred Email"),
        List.of(contact1Email, "", "personal"),
        List.of(randomPersonalEmail, randomWorkEmail, "personal")
    );
    postToBulkImport(values);

    contact1 = sfdcClient.getContactById(contact1Id).get();
    contact2 = sfdcClient.getContactById(contact2Id).get();

    // contact1 should have Email the same, a value for Home Email, and a value for preferred
    assertEquals(contact1Email, contact1.getField("Email"));
    assertEquals(contact1Email, contact1.getField("npe01__HomeEmail__c"));
    assertEquals("Personal", contact1.getField("npe01__Preferred_Email__c"));

    // contact2 should have preserved its work email, received a new home email, and preserved its preferred
//    assertEquals(randomWorkEmail, contact2.getField("Email")); // TODO: Not sure why this isn't getting set by the preference. Expected?
    assertEquals(randomWorkEmail, contact2.getField("npe01__WorkEmail__c"));
    assertEquals("Work", contact2.getField("npe01__Preferred_Email__c"));
    assertEquals(randomPersonalEmail, contact2.getField("npe01__HomeEmail__c"));

    clearSfdc(contact1.getField("LastName").toString());
    clearSfdc(contact2.getField("LastName").toString());
  }

  @Test
  public void contactCampaignMembership() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    SObject campaign = new SObject("Campaign");
    campaign.setField("Name", RandomStringUtils.randomAlphabetic(8));
    String campaignId = sfdcClient.insert(campaign).getId();

    SObject newContact = new SObject("Contact");
    newContact.setField("FirstName", RandomStringUtils.randomAlphabetic(8));
    newContact.setField("LastName", RandomStringUtils.randomAlphabetic(8));
    newContact.setField("Email", RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com");
    String newContactId = sfdcClient.insert(newContact).getId();

    SObject existingContact = randomContactSfdc();

    final List<Object> values = List.of(
        List.of("Contact First Name", "Contact Last Name", "Contact Email", "Contact Campaign ID", "Contact Campaign Status"),
        List.of(newContact.getField("FirstName"), newContact.getField("LastName"), newContact.getField("Email"), campaignId, ""),
        List.of(existingContact.getField("FirstName"), existingContact.getField("LastName"), existingContact.getField("Email"), campaignId, "Responded")
    );
    postToBulkImport(values);

    Optional<SObject> newContactCampaignMember = sfdcClient.querySingle("SELECT Status FROM CampaignMember WHERE CampaignId = '" + campaignId + "' AND ContactId = '" + newContactId + "'");
    Optional<SObject> existingContactCampaignMember = sfdcClient.querySingle("SELECT Status FROM CampaignMember WHERE CampaignId = '" + campaignId + "' AND ContactId = '" + existingContact.getId() + "'");

    assertTrue(newContactCampaignMember.isPresent());
    assertEquals("Sent", newContactCampaignMember.get().getField("Status"));
    assertTrue(existingContactCampaignMember.isPresent());
    assertEquals("Responded", existingContactCampaignMember.get().getField("Status"));

    clearSfdc(newContact.getField("LastName").toString());
    clearSfdc(existingContact.getField("LastName").toString());
  }

  @Test
  public void multipleCampaignsWithSameName() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    // Two campaigns, both with the same name, inserted separately so one is oldest.
    String campaignName = RandomStringUtils.randomAlphabetic(8);
    SObject campaign1 = new SObject("Campaign");
    campaign1.setField("Name", campaignName);
    campaign1.setField("IsActive", true);
    String campaignId1 = sfdcClient.insert(campaign1).getId();
    Thread.sleep(1000); // ensure the CreatedDate has time to be different
    SObject campaign2 = new SObject("Campaign");
    campaign2.setField("Name", campaignName);
    campaign2.setField("IsActive", true);
    String campaignId2 = sfdcClient.insert(campaign2).getId();

    String firstName = RandomStringUtils.randomAlphabetic(8);
    String lastName = RandomStringUtils.randomAlphabetic(8);
    String email = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";

    final List<Object> values = List.of(
        List.of("Contact First Name", "Contact Last Name", "Contact Email", "Contact Campaign Name", "Opportunity Date yyyy-mm-dd", "Opportunity Stage Name", "Opportunity Amount", "Opportunity Campaign Name"),
        List.of(firstName, lastName, email, campaignName, "2025-05-29", "Closed Won", "1.0", campaignName)
    );
    postToBulkImport(values);

    // Oldest campaign should have been selected, despite the common name.
    List<SObject> contacts = sfdcClient.getContactsByEmails(List.of(email));
    assertEquals(1, contacts.size());
    String contactId = contacts.get(0).getId();
    Map<String, List<SObject>> campaignMemberships = sfdcClient.getCampaignsByContactIds(List.of(contactId), null);
    assertTrue(campaignMemberships.containsKey(contactId));
    assertEquals(campaignId1, campaignMemberships.get(contactId).get(0).getField("CampaignId"));
    List<SObject> opps = sfdcClient.getDonationsByAccountId(contacts.get(0).getField("AccountId").toString());
    assertEquals(1, opps.size());
    assertEquals(campaignId1, opps.get(0).getField("CampaignId"));
  }
}
