/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.FactsClient;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Path("/facts")
public class FactsController {

  private static final Logger log = LogManager.getLogger(FactsController.class.getName());

  protected final EnvironmentFactory envFactory;

  public FactsController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @Path("/sync-students/all")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response syncAllStudents(
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    EnvironmentConfig.CRMFieldDefinitions crmFieldDefinitions = env.primaryCrmService().getFieldDefinitions();
    EnvironmentConfig.Facts factsConfig = env.getConfig().facts;

    FactsClient factsClient = env.factsClient();

    // TODO: FACTS appears to have parent employer data, but trying to find where to pull that from.

    Runnable runnable = () -> {
      try {
        // NOTE: FACTS has absurdly restricted API limits, so fetching details as-needed within loops isn't
        // possible. We instead fetch the whole database and hold it in memory...

        List<FactsClient.Student> students = factsClient.getStudents();
        Map<Integer, FactsClient.Person> persons = factsClient.getPersons().stream().collect(Collectors.toMap(p -> p.personId, p -> p));

        Map<Integer, List<FactsClient.ParentStudent>> studentToParents = new HashMap<>();
        List<FactsClient.ParentStudent> parentStudents = factsClient.getParentStudents();
        for (FactsClient.ParentStudent parentStudent : parentStudents) {
          if (!studentToParents.containsKey(parentStudent.studentID)) {
            studentToParents.put(parentStudent.studentID, new ArrayList<>());
          }
          studentToParents.get(parentStudent.studentID).add(parentStudent);
        }

        Map<Integer, List<FactsClient.EmergencyContact>> studentToEmergencyContacts = new HashMap<>();
        if (factsConfig.syncEmergency) {
          List<FactsClient.EmergencyContact> emergencyContacts = factsClient.getEmergencyContacts();
          for (FactsClient.EmergencyContact emergencyContact : emergencyContacts) {
            if (!studentToEmergencyContacts.containsKey(emergencyContact.studentID)) {
              studentToEmergencyContacts.put(emergencyContact.studentID, new ArrayList<>());
            }
            studentToEmergencyContacts.get(emergencyContact.studentID).add(emergencyContact);
          }
        }

        Map<Integer, List<FactsClient.PersonFamily>> personToFamilies = new HashMap<>();
        List<FactsClient.PersonFamily> personFamilies = factsClient.getPersonFamilies();
        for (FactsClient.PersonFamily personFamily : personFamilies) {
          if (!personToFamilies.containsKey(personFamily.personId)) {
            personToFamilies.put(personFamily.personId, new ArrayList<>());
          }
          personToFamilies.get(personFamily.personId).add(personFamily);
        }

        Map<Integer, FactsClient.Address> addresses = factsClient.getAddresses().stream().collect(Collectors.toMap(a -> a.addressID, a -> a));

        List<Map<String, String>> parentImports = new ArrayList<>();
        List<Map<String, String>> studentImports = new ArrayList<>();
        List<Map<String, String>> emergencyContactImports = new ArrayList<>();

        Set<Integer> inactiveStudentIds = students.stream().map(s -> s.studentId).collect(Collectors.toSet());

        for (FactsClient.Student student : students) {
          // Inactive == applied but never enrolled. For now, keeping this out of SFDC.
          if ("Inactive".equalsIgnoreCase(student.school.status)) {
            continue;
          }
          // TODO: Needs to be in SFDC eventually, but waiting for confirmation on HubSpot sync expectations.
          if ("Admissions".equalsIgnoreCase(student.school.status) || "Pre-enrolled".equalsIgnoreCase(student.school.status)) {
            continue;
          }
          // TODO: We likely need a one time import, but there's so much stale/incorrect data in this.
          if ("Graduate".equalsIgnoreCase(student.school.status)) {
            continue;
          }
          // TODO: Needed if they're withdrawn from the current year?.
          if ("Withdrawn".equalsIgnoreCase(student.school.status)) {
            continue;
          }
          // TODO: Seeing this for NSU's first school. True for CLHS too? Could be the source of lots of graduates
          //  accidentally being imported? Although, how is this different than graduate/withdrawn?
          if (Strings.isNullOrEmpty(student.school.status)) {
            continue;
          }

          inactiveStudentIds.remove(student.studentId);

          Set<String> seenNames = new HashSet<>();

          // TODO: Do not enable any of the above without adding status as a custom field to all families/contacts
          //  and switches to turn each of these on/off.
          //  NSU only needs current students, so it at least needs to be configurable.

          // TODO: We'll likely also need a way to delete/archive previously current families that are no longer current in SIS.

          // confusingly, studentId appears to be the person, not personStudentId
          FactsClient.Person personStudent = persons.get(student.studentId);
          List<FactsClient.PersonFamily> studentFamilies = personToFamilies.get(personStudent.personId);

          Map<Integer, String> familyIdToAddressStreet = new HashMap<>();

          // some graduates appear to have their parents removed
          if (studentToParents.containsKey(student.studentId) && studentFamilies != null) {
            List<Integer> studentFamilyIds = studentFamilies.stream().map(f -> f.familyId).toList();

            for (FactsClient.ParentStudent parent : studentToParents.get(student.studentId)) {
              FactsClient.Person personParent = persons.get(parent.parentID);

              // It seems that FACTS rarely checks emergencyContact on parents, more often centering on a separate table.
              // Do that first and check the box directly on parent when applicable.
              if (factsConfig.syncEmergency && studentToEmergencyContacts.containsKey(student.studentId)) {
                boolean found = studentToEmergencyContacts.get(student.studentId).stream()
                    .anyMatch(c -> c.firstName.equalsIgnoreCase(personParent.firstName) && c.lastName.equalsIgnoreCase(personParent.lastName));
                if (found) {
                  parent.emergencyContact = true;
                  seenNames.add(personParent.firstName.toLowerCase(Locale.ROOT) + " " + personParent.lastName.toLowerCase(Locale.ROOT));
                }
              }

              if (!isParent(parent) && !isGrandparent(parent) && !(factsConfig.syncEmergency && isEmergency(parent))) {
                continue;
              }

              FactsClient.Address address = addresses.get(personParent.addressID);

              // parents sometimes have multiple (duplicate) households, so we need to pick the one
              // actually used by the student
              List<FactsClient.PersonFamily> parentFamilies = personToFamilies.get(personParent.personId);
              Optional<Integer> _householdId = parentFamilies.stream()
                  .filter(f -> studentFamilyIds.contains(f.familyId))
                  .map(f -> f.familyId)
                  .findFirst();
              if (_householdId.isEmpty()) {
                // TODO: There appear to be some persons that were originally listed as a parent,
                //  then the household was removed from the student, but the parent remains
                //  tied to them behind the scenes? Seemed to be mostly step parents?
                //  Ex: https://renweb1.renweb.com/renweb1/#/peoplemanagement/student/10126/student_dashboard?personFilterType=0&date=03-22-2023
                //  Family members with Bush as the last name, but Emily Bush
                //  https://renweb1.renweb.com/renweb1/#/peoplemanagement/parent/11134/parent_dashboard
                //  is returned in the getParentStudents call, but her family is no longer listed
                //  as a family of the students.
                //  Only 7 of these, so it's not a pervasive issue.
                log.info("skipping parent with a family not attached to the student");
                continue;
              }
              Integer householdId = _householdId.get();

              if (address != null && isParent(parent)) {
                familyIdToAddressStreet.put(householdId, address.address1);
              }

              Map<String, String> accountData = toAccountData(student, parent, personParent, householdId, address, crmFieldDefinitions);
              Map<String, String> parentContactData = toParentContactData(student, parent, personParent, address, crmFieldDefinitions);
              parentContactData.putAll(accountData);
              parentImports.add(parentContactData);
            }
          }

          FactsClient.Address address = addresses.get(personStudent.addressID);

          Integer householdId = null;
          if (studentFamilies != null) {
            if (address != null && studentFamilies.size() > 1) {
              // look for the household that matches the student's address
              householdId = studentFamilies.stream()
                  .filter(f -> familyIdToAddressStreet.containsKey(f.familyId) && address.address1.equalsIgnoreCase(familyIdToAddressStreet.get(f.familyId)))
                  .map(f -> f.familyId)
                  .findFirst()
                  .orElse(studentFamilies.get(0).familyId);
            } else {
              householdId = studentFamilies.get(0).familyId;
            }
          }

          // let the student override the address of their primary household -- seeing situations where
          // it's missing on family member profiles
          Map<String, String> accountData = toAccountData(student, null, personStudent, householdId, address, crmFieldDefinitions);
          Map<String, String> studentContactData = toStudentContactData(student, personStudent, address, crmFieldDefinitions);
          studentContactData.putAll(accountData);
          studentImports.add(studentContactData);

          if (factsConfig.syncEmergency && studentToEmergencyContacts.containsKey(student.studentId)) {
            for (FactsClient.EmergencyContact emergencyContact : studentToEmergencyContacts.get(student.studentId)) {
              if (!seenNames.contains(emergencyContact.firstName.toLowerCase(Locale.ROOT) + " " + emergencyContact.lastName.toLowerCase(Locale.ROOT))) {
                seenNames.add(emergencyContact.firstName.toLowerCase(Locale.ROOT) + " " + emergencyContact.lastName.toLowerCase(Locale.ROOT));
                Map<String, String> emergencyContactData = toEmergencyContactData(student, emergencyContact, crmFieldDefinitions);
                emergencyContactImports.add(emergencyContactData);
              }
            }
          }
        }

        // We make two passes: 1) Import the parent data, since the parents are more likely to exist in the CRM already.
        // Helps keep the Households cleaner. 2) Then import the students.
        // But that can be overriden! See note on EnvironmentConfig#Facts
        List<Map<String, String>> firstPass, secondPass;
        if (factsConfig.syncStudentsBeforeParents) {
          firstPass = studentImports;
          secondPass = parentImports;
        } else {
          firstPass = parentImports;
          secondPass = studentImports;
        }
        List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(firstPass, env);
        env.primaryCrmService().processBulkImport(importEvents);
        importEvents = CrmImportEvent.fromGeneric(secondPass, env);
        env.primaryCrmService().processBulkImport(importEvents);

        if (factsConfig.syncEmergency) {
          importEvents = CrmImportEvent.fromGeneric(emergencyContactImports, env);
          env.primaryCrmService().processBulkImport(importEvents);
        }

        // TODO: need genericized for non-SFDC environments
//        insertRelationships(studentToParents, env, crmFieldDefinitions);

        // handle non-current students
        processInactiveStudents(inactiveStudentIds, env);

        log.info("DONE");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    };
    // Away from the main thread
    new Thread(runnable).start();

    return Response.ok().build();
  }

  // Allow the subclasses to decide how to implement this. Some may tag differently, others may archive/delete.
  protected void processInactiveStudents(Set<Integer> inactiveStudentIds, Environment env) {

  }

  protected Map<String, String> toContactData(FactsClient.Person person, FactsClient.Address address,
      EnvironmentConfig.CRMFieldDefinitions crmFieldDefinitions) {
    Map<String, String> contactData = new HashMap<>();
    contactData.put("Contact ExtRef " + crmFieldDefinitions.sisContactId, person.personId + "");
    contactData.put("Contact First Name", person.firstName);
    contactData.put("Contact Last Name", person.lastName);
    contactData.putAll(toMailingAddressData(address));

    if (person.email != null && person.email.contains("@")) {
      contactData.put("Contact Personal Email", person.email);
    }
    if (person.email2 != null && person.email2.contains("@")) {
      contactData.put("Contact Other Email", person.email2);
    }
    contactData.put("Contact Preferred Email", CrmImportEvent.ContactEmailPreference.PERSONAL.name());

    contactData.put("Contact Mobile Phone", person.cellPhone);
    contactData.put("Contact Home Phone", person.homePhone);
    if (Strings.isNullOrEmpty(person.cellPhone) && !Strings.isNullOrEmpty(person.homePhone)) {
      contactData.put("Contact Preferred Phone", CrmImportEvent.ContactPhonePreference.HOME.name());
    } else {
      contactData.put("Contact Preferred Phone", CrmImportEvent.ContactPhonePreference.MOBILE.name());
    }

    return contactData;
  }

  protected Map<String, String> toAccountData(FactsClient.Student student, FactsClient.ParentStudent parent,
      FactsClient.Person person, Integer _householdId, FactsClient.Address address,
      EnvironmentConfig.CRMFieldDefinitions crmFieldDefinitions) {
    Map<String, String> accountData = new HashMap<>();
    String householdId = _householdId == null ? null : _householdId + "";
    accountData.put("Account ExtRef " + crmFieldDefinitions.sisHouseholdId, householdId + "");
    accountData.put("Account Record Type Name", "Household Account");
    accountData.put("Account Name", person.lastName + " Household");
    if (isParent(parent)) {
      accountData.putAll(toBillingAddressData(address));
      accountData.putAll(toShippingAddressData(address));
    }

    // additional fields added by client subclasses

    return accountData;
  }

  protected Map<String, String> toStudentContactData(FactsClient.Student student, FactsClient.Person personStudent,
      FactsClient.Address address, EnvironmentConfig.CRMFieldDefinitions crmFieldDefinitions) {
    Map<String, String> contactData = toContactData(personStudent, address, crmFieldDefinitions);

    // additional fields added by client subclasses

    return contactData;
  }

  protected Map<String, String> toParentContactData(FactsClient.Student student, FactsClient.ParentStudent parent,
      FactsClient.Person personParent, FactsClient.Address address,
      EnvironmentConfig.CRMFieldDefinitions crmFieldDefinitions) {
    Map<String, String> contactData = toContactData(personParent, address, crmFieldDefinitions);

    // additional fields added by client subclasses

    return contactData;
  }

  protected Map<String, String> toEmergencyContactData(FactsClient.Student student, FactsClient.EmergencyContact emergencyContact,
      EnvironmentConfig.CRMFieldDefinitions crmFieldDefinitions) {
    Map<String, String> contactData = new HashMap<>();
    contactData.put("Contact ExtRef " + crmFieldDefinitions.sisContactId, emergencyContact.emergencyContactID + "");
    contactData.put("Contact First Name", emergencyContact.firstName);
    contactData.put("Contact Last Name", emergencyContact.lastName);
    if (emergencyContact.email != null && emergencyContact.email.contains("@")) {
      contactData.put("Contact Email", emergencyContact.email);
    }
    contactData.put("Contact Mobile Phone", emergencyContact.cellPhone);
    contactData.put("Contact Home Phone", emergencyContact.homePhone);

    if (emergencyContact.email != null && emergencyContact.email.contains("@")) {
      contactData.put("Contact Personal Email", emergencyContact.email);
    }
    contactData.put("Contact Preferred Email", CrmImportEvent.ContactEmailPreference.PERSONAL.name());

    contactData.put("Contact Mobile Phone", emergencyContact.cellPhone);
    contactData.put("Contact Home Phone", emergencyContact.homePhone);
    if (Strings.isNullOrEmpty(emergencyContact.cellPhone) && !Strings.isNullOrEmpty(emergencyContact.homePhone)) {
      contactData.put("Contact Preferred Phone", CrmImportEvent.ContactPhonePreference.HOME.name());
    } else {
      contactData.put("Contact Preferred Phone", CrmImportEvent.ContactPhonePreference.MOBILE.name());
    }

    // additional fields added by client subclasses

    return contactData;
  }

  protected Map<String, String> toMailingAddressData(FactsClient.Address address) {
    if (address == null) {
      return Collections.emptyMap();
    }

    Map<String, String> addressData = new HashMap<>();
    addressData.put("Contact Mailing Street", address.address1);
    addressData.put("Contact Mailing Street 2", address.address2);
    addressData.put("Contact Mailing City", address.city);
    addressData.put("Contact Mailing State", address.state);
    addressData.put("Contact Mailing Postal Code", address.zip);
    addressData.put("Contact Mailing Country", address.state);
    return addressData;
  }

  protected Map<String, String> toBillingAddressData(FactsClient.Address address) {
    if (address == null) {
      return Collections.emptyMap();
    }

    Map<String, String> addressData = new HashMap<>();
    addressData.put("Account Billing Street", address.address1);
    addressData.put("Account Billing Street 2", address.address2);
    addressData.put("Account Billing City", address.city);
    addressData.put("Account Billing State", address.state);
    addressData.put("Account Billing Postal Code", address.zip);
    addressData.put("Account Billing Country", address.state);
    return addressData;
  }

  protected Map<String, String> toShippingAddressData(FactsClient.Address address) {
    if (address == null) {
      return Collections.emptyMap();
    }

    Map<String, String> addressData = new HashMap<>();
    String street = address.address1;
    if (!Strings.isNullOrEmpty(address.address2)) {
      street += ", " + address.address2;
    }
    addressData.put("Account Shipping Street", street);
    addressData.put("Account Shipping City", address.city);
    addressData.put("Account Shipping State", address.state);
    addressData.put("Account Shipping Postal Code", address.zip);
    addressData.put("Account Shipping Country", address.country);
    return addressData;
  }

  // TODO: Duplicating parts of RaisersEdgeToSalesforce. Think through how to model this in the sheet data.
  protected void insertRelationships(Map<Integer, List<FactsClient.ParentStudent>> studentToParents, Environment env,
      EnvironmentConfig.CRMFieldDefinitions crmFieldDefinitions) throws InterruptedException, ConnectionException {
    log.info("inserting relationships");

    SfdcClient sfdcClient = env.sfdcClient();

    Map<String, SObject> factsIdToContact = new HashMap<>();
    List<SObject> contacts = sfdcClient.queryListAutoPaged("SELECT Id, AccountId, " + crmFieldDefinitions.sisContactId + " FROM Contact WHERE " + crmFieldDefinitions.sisContactId + "!=''");
    for (SObject contact : contacts) {
      factsIdToContact.put((String) contact.getField(crmFieldDefinitions.sisContactId), contact);
    }

    // prevent duplicates
    Set<String> seenRelationships = new HashSet<>();
    List<SObject> relationships = sfdcClient.queryListAutoPaged("SELECT npe4__Contact__c, npe4__RelatedContact__c FROM npe4__Relationship__c WHERE npe4__Contact__c!='' AND npe4__RelatedContact__c!=''");
    for (SObject relationship : relationships) {
      String from = (String) relationship.getField("npe4__Contact__c");
      String to = (String) relationship.getField("npe4__RelatedContact__c");
      seenRelationships.add(from + "::" + to);
      seenRelationships.add(to + "::" + from);
    }

    for (Map.Entry<Integer, List<FactsClient.ParentStudent>> e : studentToParents.entrySet()) {
      for (FactsClient.ParentStudent parent : e.getValue()) {
        SObject relationship = generateRelationship(parent, factsIdToContact, seenRelationships);
        if (relationship != null) {
          sfdcClient.batchInsert(relationship);
        }
      }
    }

    sfdcClient.batchFlush();

    log.info("DONE inserting relationships");
  }

  protected SObject generateRelationship(FactsClient.ParentStudent parent, Map<String, SObject> factsIdToContact,
      Set<String> seenRelationships) {

    if (isParent(parent) || isGrandparent(parent)) {
      String fromFactsId = parent.studentID.toString();
      SObject fromSObject = factsIdToContact.get(fromFactsId);
      String toFactsId = parent.parentID.toString();
      SObject toSObject = factsIdToContact.get(toFactsId);
      if (fromSObject == null || toSObject == null) {
        return null;
      }
      String from = fromSObject.getId();
      String to = toSObject.getId();

      if (seenRelationships.contains(from + "::" + to) || seenRelationships.contains(to + "::" + from)) {
        return null;
      }

      SObject relationship = new SObject("npe4__Relationship__c");
      relationship.setField("npe4__Contact__c", from);
      relationship.setField("npe4__RelatedContact__c", to);
      relationship.setField("npe4__Status__c", "Current");
      if (isGrandparent(parent)) {
        relationship.setField("npe4__Type__c", "Grandparent");
      } else {
        relationship.setField("npe4__Type__c", "Parent");
      }

      seenRelationships.add(from + "::" + to);
      seenRelationships.add(to + "::" + from);

      return relationship;
    }

    return null;
  }

  protected boolean isParent(FactsClient.ParentStudent parentStudent) {
    return parentStudent != null && ("Mother".equalsIgnoreCase(parentStudent.relationship) || "Father".equalsIgnoreCase(parentStudent.relationship)
        || "Stepmother".equalsIgnoreCase(parentStudent.relationship) || "Stepfather".equalsIgnoreCase(parentStudent.relationship)
        || "Parent".equalsIgnoreCase(parentStudent.relationship));
  }

  protected boolean isGrandparent(FactsClient.ParentStudent parentStudent) {
    return parentStudent != null && parentStudent.grandparent != null && parentStudent.grandparent;
  }

  protected boolean isEmergency(FactsClient.ParentStudent parentStudent) {
    return parentStudent != null && parentStudent.emergencyContact != null && parentStudent.emergencyContact;
  }

  @Path("/sync-staff")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response syncStaff(
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    EnvironmentConfig.CRMFieldDefinitions crmFieldDefinitions = env.primaryCrmService().getFieldDefinitions();
    EnvironmentConfig.Facts factsConfig = env.getConfig().facts;

    FactsClient factsClient = env.factsClient();

    Runnable runnable = () -> {
      try {
        // NOTE: FACTS has absurdly restricted API limits, so fetching details as-needed within loops isn't
        // possible. We instead fetch the whole database and hold it in memory...

        List<FactsClient.Staff> allStaff = factsClient.getStaff(new FactsClient.Filter("active", FactsClient.Operator.EQUALS, "true"));
        List<Map<String, String>> staffImports = new ArrayList<>();

        for (FactsClient.Staff staff : allStaff) {
          Map<String, String> staffData = toStaffData(staff, crmFieldDefinitions);
          staffImports.add(staffData);
        }

        List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(staffImports, env);
        env.primaryCrmService().processBulkImport(importEvents);

        log.info("DONE");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    };
    // Away from the main thread
    new Thread(runnable).start();

    return Response.ok().build();
  }

  protected Map<String, String> toStaffData(FactsClient.Staff staff,
      EnvironmentConfig.CRMFieldDefinitions crmFieldDefinitions) {
    Map<String, String> contactData = new HashMap<>();
    contactData.put("Contact ExtRef " + crmFieldDefinitions.sisContactId, staff.staffId + "");
    contactData.put("Contact Name", staff.name);
    return contactData;
  }
}