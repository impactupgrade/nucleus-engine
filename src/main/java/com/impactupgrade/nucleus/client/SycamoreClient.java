package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.stream.Collectors;

public class SycamoreClient {

  private static final Logger log = LogManager.getLogger(SycamoreClient.class);

  private static final String SYCAMORE_API_URL = "https://app.sycamoreschool.com/api/v1";

  private final String apiKey;
  private final Integer schoolId;

  protected Environment env;

  public SycamoreClient(Environment env) {
    this.env = env;

    this.apiKey = env.getConfig().sycamore.secretKey; // 5e13b5cb6c2ac3643faf5f849684d1eb
    this.schoolId = env.getConfig().sycamore.schoolId; // 3188
  }

  public List<SchoolStudent> getSchoolStudents() {
    return get("/School/" + schoolId + "/Students", new GenericType<>() {
    });
  }

  public Student getStudent(Integer studentId) {
    return get("/Student/" + studentId, new GenericType<>() {
    });
  }

  public Family getFamily(Integer familyId) {
    return get("/Family/" + familyId, new GenericType<>() {});
  }

  public List<FamilyContact> getFamilyContacts(Integer familyId) {
    return get("/Family/" + familyId + "/Contacts", new GenericType<>() {});
  }

  private <T> T get(String url, GenericType<T> genericType) {
    return get(SYCAMORE_API_URL + url, headers(), genericType);
  }

  private <T> T get(String url, HttpClient.HeaderBuilder headerBuilder, GenericType<T> genericType) {
    Response response = HttpClient.get(url, headerBuilder);
    if (response.getStatus() < 300) { // OK response
      return response.readEntity(genericType);
    } else {
      log.error("GET failed: url={} code={} message={}", url, response.getStatus(), response.readEntity(String.class));
      return null;
    }
  }

  private HttpClient.HeaderBuilder headers() {
    return HttpClient.HeaderBuilder.builder()
        .authBearerToken(this.apiKey);
  }

  public static final class SchoolStudent {
    @JsonProperty("ID")
    public Integer id;
    @JsonProperty("FamilyID")
    public Integer familyId;
    @JsonProperty("Family2ID")
    public Integer family2Id;
    @JsonProperty("UserID")
    public Integer userId;
    @JsonProperty("StudentCode")
    public String studentCode;
    @JsonProperty("FirstName")
    public String firstName;
    @JsonProperty("LastName")
    public String lastName;
    @JsonProperty("Grade")
    public Integer grade;
    @JsonProperty("GradYear")
    public Integer gradeYear;
    @JsonProperty("Graduated")
    public boolean graduated;
    @JsonProperty("ExternalID")
    public String externalId;
  }

  public static final class Student {
    @JsonProperty("Code")
    public String code;
    @JsonProperty("FirstName")
    public String firstName;
    @JsonProperty("LastName")
    public String lastName;
    @JsonProperty("Ethnicity")
    public String ethnicity;
    @JsonProperty("NickName")
    public String nickName;
    @JsonProperty("DOB")
    public String dateOfBirth;
    @JsonProperty("Picture")
    public String picture;
    @JsonProperty("Email")
    public String email;
    @JsonProperty("Cell")
    public String cellPhone;
    @JsonProperty("AdvisorID")
    public Integer advisorId;
    @JsonProperty("Advisor")
    public String advisor;
    @JsonProperty("HomeroomTeacherID")
    public Integer homeroomTeacherId;
    @JsonProperty("HomeroomTeacher")
    public String homeroomTeacher;
    @JsonProperty("LockerNum")
    public String lockerNum;
    @JsonProperty("ComboNum")
    public String comboNum;
    @JsonProperty("StateID")
    public String stateId;
    @JsonProperty("ExtID")
    public String extId;
    @JsonProperty("Gender")
    public String gender;
    @JsonProperty("Grade")
    public String grade;
    @JsonProperty("Location")
    public String location;
  }

  public static final class Family {
    @JsonProperty("ExtID")
    public String extId;
    @JsonProperty("Code")
    public String code;
    @JsonProperty("Name")
    public String name;
    @JsonProperty("FormalName")
    public String formalName;
    @JsonProperty("MessagerID")
    public Integer messagerId;
    @JsonProperty("Memo")
    public String memo;
    @JsonProperty("SecretWord")
    public String secretWord;
    @JsonProperty("MailingAddress1")
    public String mailingAddress1;
    @JsonProperty("MailingAddress2")
    public String mailingAddress2;
    @JsonProperty("MailingCity")
    public String mailingCity;
    @JsonProperty("MailingState")
    public String mailingState;
    @JsonProperty("MailingZIP")
    public String mailingZip;
    @JsonProperty("MailingCountry")
    public String mailingCountry;
    @JsonProperty("HomePhone")
    public String homePhone;
    @JsonProperty("BillingName")
    public String billingName;
    @JsonProperty("BillingAddress1")
    public String billingAddress1;
    @JsonProperty("BillingAddress2")
    public String billingAddress2;
    @JsonProperty("BillingCity")
    public String billingCity;
    @JsonProperty("BillingState")
    public String billingState;
    @JsonProperty("BillingZIP")
    public String billingZip;
    @JsonProperty("BillingCountry")
    public String billingCountry;
    @JsonProperty("BillingPhone")
    public String billingPhone;
  }

  public static final class FamilyContact {
    @JsonProperty("ID")
    public Integer id;
    @JsonProperty("FirstName")
    public String firstName;
    @JsonProperty("LastName")
    public String lastName;
    @JsonProperty("WorkPhone")
    public String workPhone;
    @JsonProperty("HomePhone")
    public String homePhone;
    @JsonProperty("CellPhone")
    public String cellPhone;
    @JsonProperty("Email")
    public String email;
    @JsonProperty("Relation")
    public String relation;
    @JsonProperty("PrimaryParent")
    public boolean primaryParent;
    @JsonProperty("Pickup")
    public boolean pickup;
    @JsonProperty("Emergency")
    public boolean emergency;
  }

  // TODO: Mapstruct?
  private CrmContact toCrmContact(Student student) {
    CrmContact crmContact = new CrmContact();
    crmContact.firstName = student.firstName;
    crmContact.lastName = student.lastName;
    crmContact.fullName = crmContact.firstName + " " + crmContact.lastName;
    crmContact.email = student.email;
    crmContact.mobilePhone = student.cellPhone;
    crmContact.preferredPhone = CrmContact.PreferredPhone.MOBILE; // ?
    return crmContact;
  }

  private CrmAddress getCrmAddress(Family family) {
    if (family == null) {
      return null;
    }
    CrmAddress crmAddress = new CrmAddress();
    crmAddress.street = family.mailingAddress1;
    crmAddress.city = family.mailingCity;
    crmAddress.state = family.mailingState;
    crmAddress.postalCode = family.mailingZip;
    crmAddress.country = family.mailingCountry;
    return crmAddress;
  }

  private CrmContact toCrmContact(FamilyContact familyContact) {
    CrmContact crmContact = new CrmContact();
    crmContact.firstName = familyContact.firstName;
    crmContact.lastName = familyContact.lastName;
    crmContact.fullName = familyContact.firstName + " " + crmContact.lastName;
    crmContact.email = familyContact.email;
    crmContact.homePhone = familyContact.homePhone;
    crmContact.mobilePhone = familyContact.cellPhone;
    crmContact.workPhone = familyContact.workPhone;
    crmContact.preferredPhone = CrmContact.PreferredPhone.MOBILE; // ?
    return crmContact;
  }

  public void upsertStudent(Student student, Family family, List<FamilyContact> familyContacts, CrmService crmService) throws Exception {
    CrmContact contact = toCrmContact(student);
    CrmAddress address = getCrmAddress(family);
    List<CrmContact> parents = familyContacts.stream()
        .map(familyContent -> toCrmContact(familyContent))
        .collect(Collectors.toList());
    contact.address = address;
    parents.forEach(parent -> parent.address = address);
    log.info("Upserting student contact for student code: {}...", student.code);
    upsertCrmContact(contact, null, crmService);
    for (CrmContact parent : parents) {
      log.info("Upserting parent contact for student code: {}...", student.code);
      upsertCrmContact(parent, contact.accountId, crmService);
    }
  }

  private CrmContact upsertCrmContact(CrmContact crmContact, String crmAccountId, CrmService crmService) throws Exception {
    String name = crmContact.firstName + " " + crmContact.lastName;
    CrmContact existing = getCrmContactForFullNameAndEmail(name, crmContact.email, crmService);
    if (existing == null) {
      // Create new contact/new household account
      if (Strings.isNullOrEmpty(crmAccountId)) {
        CrmAccount crmAccount = createHouseholdAccount(crmContact);
        String accountId = crmService.insertAccount(crmAccount);
        crmContact.accountId = accountId;
      } else {
        crmContact.accountId = crmAccountId;
      }
      log.info("Inserting contact...");
      crmService.insertContact(crmContact);
    } else {
      // Update existing contact
      updateCrmContact(existing, crmContact);
      log.info("Updating contact...");
      crmService.updateContact(existing);
    }
    return crmContact;
  }

  private CrmContact getCrmContactForFullNameAndEmail(String fullName, String email, CrmService crmService) throws Exception {
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.keywords = fullName;
    contactSearch.email = email;
    PagedResults<CrmContact> pagedResults = crmService.searchContacts(contactSearch);
    List<CrmContact> foundContacts = pagedResults.getResults();
    return CollectionUtils.isNotEmpty(foundContacts) ? foundContacts.stream().findFirst().get() : null;
  }

  private CrmAccount createHouseholdAccount(CrmContact crmContact) {
    CrmAccount crmAccount = new CrmAccount();
    crmAccount.name = crmContact.lastName + " Family";
    crmAccount.type = EnvironmentConfig.AccountType.HOUSEHOLD;
    return crmAccount;
  }

  private void updateCrmContact(CrmContact existing, CrmContact update) {
    existing.homePhone = update.homePhone;
    existing.workPhone = update.workPhone;
    existing.preferredPhone = update.preferredPhone;
    updateCrmAddress(existing.address, update.address);
  }

  private void updateCrmAddress(CrmAddress existing, CrmAddress update) {
    if (update == null) {
      return;
    }
    if (update.street != null) {
      existing.street = update.street;
    }
    if (update.city != null) {
      existing.city = update.city;
    }
    if (update.state != null) {
      existing.state = update.state;
    }
    if (update.postalCode != null) {
      existing.postalCode = update.postalCode;
    }
    if (update.country != null) {
      existing.country = update.country;
    }
  }
}
