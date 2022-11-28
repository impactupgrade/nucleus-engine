package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FactsClient {

  private static final Logger log = LogManager.getLogger(FactsClient.class);

  private static final String FACTS_API_URL = "https://api.factsmgt.com";
  private static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

  private static final Integer DEFAULT_PAGE = 1;
  private static final Integer DEFAULT_PAGE_SIZE = 50;

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final String subscriptionKey;
  private final String apiKey;

  protected Environment env;

  public FactsClient(Environment env) {
    this.env = env;

    this.subscriptionKey = env.getConfig().facts.publicKey;
    this.apiKey = env.getConfig().facts.secretKey;
  }

  // Person/People

  public Person getPerson(Integer id) {
    return HttpClient.get(FACTS_API_URL + "/People/" + id, headers(), Person.class);
  }

  public List<Person> getPersons(List<Filter> filters) throws Exception {
    return get("/People", filters, Person.class);
  }

  public List<Person> getPersonsModifiedBetween(LocalDateTime fromDate, LocalDateTime toDate) throws Exception {
    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT);
    Filter fromFilter = new Filter("modifiedDate", Operator.GREATER_THAN_OR_EQUAL_TO, dateTimeFormatter.format(fromDate));
    Filter toFilter = new Filter("modifiedDate", Operator.LESS_THAN_OR_EQUAL_TO, dateTimeFormatter.format(toDate));
    return getPersons(List.of(fromFilter, toFilter));
  }

  //Student

  public List<Student> getStudents(List<Filter> filters) throws Exception {
    return get("/Students", filters, Student.class);
  }

  public List<Student> getStudentsEnrolledBetween(LocalDateTime fromDate, LocalDateTime toDate) throws Exception {
    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT);
    Filter statusFilter = new Filter("school.status", Operator.EQUALS_CASE_INSENSITIVE, "Enrolled");
    Filter fromFilter = new Filter("school.enrollDate", Operator.GREATER_THAN_OR_EQUAL_TO, dateTimeFormatter.format(fromDate));
    Filter toFilter = new Filter("school.enrollDate", Operator.LESS_THAN_OR_EQUAL_TO, dateTimeFormatter.format(toDate));
    return getStudents(List.of(statusFilter, fromFilter, toFilter));
  }

  public List<Student> getAdmissions() throws Exception {
    Filter statusFilter = new Filter("school.status", Operator.EQUALS_CASE_INSENSITIVE, "Admissions");
    return getStudents(List.of(statusFilter));
  }

  //Parent-Student

  public List<Person> getParents(Integer personId) throws Exception {
    Filter studentIdFilter = new Filter("studentID", Operator.EQUALS, personId + "");
    List<ParentStudent> parentStudents = get("/People/ParentStudent", List.of(studentIdFilter), ParentStudent.class);

    Set<Integer> parentsIds = parentStudents.stream()
        .map(parentStudent -> parentStudent.parentID)
        .collect(Collectors.toSet());
    String ids = parentsIds.stream().map(id -> id + "").collect(Collectors.joining("|"));

    Filter idsFilter = new Filter("personId", Operator.EQUALS, ids);
    return get("/People", List.of(idsFilter), Person.class);
  }

  // Person-Family // ?
  public PersonFamily getPersonFamily(Integer personId) throws Exception {
    Filter personIdFilter = new Filter("personId", Operator.EQUALS, personId + "");
    List<PersonFamily> personFamilies = get("/People/PersonFamily", List.of(personIdFilter), PersonFamily.class);
    if (personFamilies.isEmpty()) {
      return null;
    }
    if (personFamilies.size() > 1) {
      log.warn("Found more than 1 person-family records for person id {}!", personId);
    }
    return personFamilies.get(0);
  }

  // Address

  public Address getAddress(Integer id) {
    return HttpClient.get(FACTS_API_URL + "/people/Address/" + id, headers(), Address.class);
  }

  // Utils

  private <T> List<T> get(String url, List<Filter> filters, Class<T> clazz) throws Exception {
    return get(url, filters, null, DEFAULT_PAGE, DEFAULT_PAGE_SIZE, clazz);
  }

  private <T> List<T> get(String url, List<Filter> filters, String sortBy, Integer page, Integer pageSize, Class<T> clazz) throws Exception {
    FilteredResponse<T> filteredResponse = getFilteredResponse(url, filters, sortBy, page, pageSize, clazz);
    List<T> items = new LinkedList<>(filteredResponse.results);

    if (filteredResponse.pageCount > page) {
      int pageCount = filteredResponse.pageCount;
      for (int nextPage = page + 1; nextPage <= pageCount; nextPage++) {
        filteredResponse = getFilteredResponse(url, filters, sortBy, nextPage, pageSize, clazz);
        items.addAll(filteredResponse.results);
      }
    }
    return items;
  }

  private <T> FilteredResponse<T> getFilteredResponse(String url, List<Filter> filters, String sortBy, Integer page, Integer pageSize, Class<T> clazz) throws Exception {
    String fullUrl = FACTS_API_URL + url + toParametersUrl(filters, sortBy, page, pageSize);
    log.info("URL: {}", fullUrl);
    Response response = getWithAutoRetry(fullUrl);
    String responseString = response.readEntity(String.class);
    return objectMapper.readValue(responseString, objectMapper.getTypeFactory().constructParametricType(FilteredResponse.class, clazz));
  }

  private String toParametersUrl(List<Filter> filters, String sortBy, Integer page, Integer pageSize) throws Exception {
    List<String> parameters = new ArrayList<>();
    if (CollectionUtils.isNotEmpty(filters)) {
      parameters.add("filters=" + encodeFilters(filters));
    }
    if (!Strings.isNullOrEmpty(sortBy)) {
      parameters.add("sorts=" + sortBy);
    }
    if (page != null) {
      parameters.add("page=" + page);
    }
    if (pageSize != null) {
      parameters.add("pageSize=" + pageSize);
    }
    if (CollectionUtils.isNotEmpty(parameters)) {
      return "?" + String.join("&", parameters);
    } else {
      return null;
    }
  }

  private String encodeFilters(List<Filter> filters) throws UnsupportedEncodingException {
    if (CollectionUtils.isEmpty(filters)) {
      return "";
    }
    String parameters = filters.stream()
        .map(filter -> filter.name + filter.operator.value + filter.value)
        .collect(Collectors.joining(","));
    return URLEncoder.encode(parameters, "UTF-8");
  }

  private Response getWithAutoRetry(String url) {
    Response response = HttpClient.get(url, headers());
    if (response.getStatus() == 200) {
      return response;
    } else if (response.getStatus() == 429) {
      //TODO:
      log.warn("429/API limit! {}", response.readEntity(String.class));
      return HttpClient.get(url, headers());
    }
    log.error("Failed to get response! Status/Body {}/{}", response.getStatus(), response.readEntity(String.class));
    return null;
  }

  private HttpClient.HeaderBuilder headers() {
    return HttpClient.HeaderBuilder.builder()
        .header("Ocp-Apim-Subscription-Key", this.subscriptionKey)
        .header("Facts-Api-Key", this.apiKey);
  }

  public enum Operator {
    EQUALS("=="),
    NOT_EQUALS("!="),
    GREATER_THAN(">"),
    LESS_THAN("<"),
    GREATER_THAN_OR_EQUAL_TO(">="),
    LESS_THAN_OR_EQUAL_TO("<="),
    CONTAINS("@="),
    STARTS_WITH("_="),
    DOES_NOT_CONTAIN("!@="),
    DOES_NOT_START_WITH("!_="),
    CONTAINS_CASE_INSENSITIVE("@=*"),
    STARTS_WITH_CASE_INSENSITIVE("_=*"),
    EQUALS_CASE_INSENSITIVE("==*"),
    NOT_EQUALS_CASE_INSENSITIVE("!=*"),
    DOES_NOT_CONTAIN_CASE_INSENSITIVE("!@=*"),
    DOES_NOT_START_WITH_CASE_INSENSITIVE("!_=*");

    private final String value;

    Operator(String value) {
      this.value = value;
    }
  }

  public record Filter(String name, Operator operator, String value) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Person {
    public Integer personId;
    public String firstName;
    public String lastName;
    public String middleName;
    public String nickName;
    public String salutation;
    public String suffix;
    public String email;
    public String email2;
    public String username;
    public String homePhone;
    public String cellPhone;
    public Integer addressID;
    public Boolean deceased;
    public String modifiedDate; //TODO: decide which java date type to use

    @Override
    public String toString() {
      return "Person{" +
          "firstName='" + firstName + '\'' +
          ", lastName='" + lastName + '\'' +
          ", middleName='" + middleName + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Student {
    public Integer personStudentId;
    public Integer studentId;
    public Integer configSchoolId;
    public String schoolCode;
    public School school;
    public List<Locker> locker;

    @Override
    public String toString() {
      return "Student{" +
          "personStudentId=" + personStudentId +
          ", studentId=" + studentId +
          ", configSchoolId=" + configSchoolId +
          ", schoolCode='" + schoolCode + '\'' +
          ", school=" + school +
          ", locker=" + locker +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class School {
    public String status;
    public String enrollDate;
    public String gradeLevel;
    public String nextStatus;
    public String nextSchoolCode;
    public String nextGradeLevel;

    @Override
    public String toString() {
      return "School{" +
          "status='" + status + '\'' +
          ", enrollDate='" + enrollDate + '\'' +
          ", gradeLevel='" + gradeLevel + '\'' +
          ", nextStatus='" + nextStatus + '\'' +
          ", nextSchoolCode='" + nextSchoolCode + '\'' +
          ", nextGradeLevel='" + nextGradeLevel + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Locker {
    public Integer id;
    public String name;

    @Override
    public String toString() {
      return "Locker{" +
          "id=" + id +
          ", name='" + name + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ParentStudent {
    public Integer parentID;
    public Integer studentID;
    public Boolean custody;
    public Boolean correspondence;
    public String relationship;
    public Boolean grandparent;
    public Boolean emergencyContact;
    public Boolean reportCard;
    public Boolean pickUp;
    public Boolean parentsWeb;

    @Override
    public String toString() {
      return "ParentStudent{" +
          "parentID=" + parentID +
          ", studentID=" + studentID +
          ", relationship='" + relationship + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PersonFamily {
    public Integer personId;
    public Integer familyId;
    public Boolean parent;
    public Boolean student;
    public Boolean financialResponsibility;
    public Boolean factsCustomer;

    @Override
    public String toString() {
      return "PersonFamily{" +
          "personId=" + personId +
          ", familyId=" + familyId +
          ", parent=" + parent +
          ", student=" + student +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Address {
    public Integer addressID;
    public String address1;
    public String address2;
    public String city;
    public String state;
    public String zip;
    public String country;
    public Integer modifiedBy;
    public String modifiedDate;
    public String greeting1;
    public String greeting2;
    public String greeting3;
    public String greeting4;
    public String greeting5;

    @Override
    public String toString() {
      return "Address{" +
          "address1='" + address1 + '\'' +
          ", address2='" + address2 + '\'' +
          ", city='" + city + '\'' +
          ", state='" + state + '\'' +
          '}';
    }
  }

  public static class FilteredResponse<T> {
    public List<T> results;
    public Integer currentPage;
    public Integer pageCount;
    public Integer pageSize;
    public Integer rowCount;
    public String nextPage;
  }

  //TODO: remove once done with testing
  public static void main(String[] args) throws Exception {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.facts.publicKey = "5081164fcbb24d53a0e5be36e6256d38";
        envConfig.facts.secretKey = "McDIwBX3GiBoX41BDehJyX151h5w/i4609IiD6LaFvw31Yi81y/xdJhQgFqzPKKz/ITW+TzFSzHmw3mNJieTPwt0CkQQitX1o5PyCMHSYPg=";
        return envConfig;
      }
    };

    FactsClient factsClient = new FactsClient(env);

    LocalDateTime to = LocalDateTime.now();
    LocalDateTime from = to.minusMonths(1);

//    List<FactsClient.Student> students = factsClient.getStudentsEnrolledBetween(from, to);
    List<FactsClient.Student> students = factsClient.getAdmissions();
    log.info("students {}", students);

    for (FactsClient.Student student : students) {
      Integer personId = student.studentId; // (!) student id actually refers to person id

      FactsClient.Person person = factsClient.getPerson(personId);
      log.info("Person: {}", person);
      List<FactsClient.Person> parents = factsClient.getParents(personId);
      log.info("Parents: {}", parents);

      FactsClient.Address address = factsClient.getAddress(person.addressID);
      log.info("Address: {}", address);
    }
  }
}
