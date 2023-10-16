package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.impactupgrade.nucleus.environment.Environment;
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
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class FactsClient {

  private static final Logger log = LogManager.getLogger(FactsClient.class);

  private static final String FACTS_API_URL = "https://api.factsmgt.com";
  private static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

  private static final Integer DEFAULT_PAGE = 1;
  private static final Integer DEFAULT_PAGE_SIZE = 10000;
  private static final Integer BATCH_REQUEST_IDS_LIMIT = 1000;
  private static final Integer AUTO_RETRY_TIMEOUT_SECONDS = 30;

  private static final ObjectMapper objectMapper = new ObjectMapper();

  protected Environment env;

  public FactsClient(Environment env) {
    this.env = env;
  }

  // Person/People
//    public Person getPerson(Integer id) {
//        return HttpClient.get(FACTS_API_URL + "/People/" + id, headers(), Person.class);
//    }

  public List<Person> getPersons(List<Integer> ids) throws Exception {
    return getByIds("/People", "personId", ids, Person.class);
  }

  public List<Person> getPersons(Filter... filters) throws Exception {
    return get("/People", Arrays.asList(filters), Person.class);
  }

  //Student
  public List<Student> getStudents(Filter... filters) throws Exception {
    return get("/Students", Arrays.asList(filters), Student.class);
  }

  public List<Student> getStudentsEnrolledBetween(LocalDateTime fromDate, LocalDateTime toDate) throws Exception {
    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT);
    Filter statusFilter = new Filter("school.status", Operator.EQUALS_CASE_INSENSITIVE, "Enrolled");
    Filter fromFilter = new Filter("school.enrollDate", Operator.GREATER_THAN_OR_EQUAL_TO, dateTimeFormatter.format(fromDate));
    Filter toFilter = new Filter("school.enrollDate", Operator.LESS_THAN_OR_EQUAL_TO, dateTimeFormatter.format(toDate));
    return getStudents(statusFilter, fromFilter, toFilter);
  }

  public List<Student> getAdmissions() throws Exception {
    Filter statusFilter = new Filter("school.status", Operator.EQUALS_CASE_INSENSITIVE, "Admissions");
    return getStudents(statusFilter);
  }

  //Parent-Student
  public List<ParentStudent> getParentStudents(List<Integer> personIds) throws Exception {
    return getByIds("/people/ParentStudent", "studentID", personIds, ParentStudent.class);
  }

  public List<ParentStudent> getParentStudents(Filter... filters) throws Exception {
    return get("/people/ParentStudent", Arrays.asList(filters), ParentStudent.class);
  }

//    public List<Person> getParents(Integer personId) throws Exception {
//        Filter studentIdFilter = new Filter("studentID", Operator.EQUALS, personId + "");
//        List<ParentStudent> parentStudents = get("/People/ParentStudent", List.of(studentIdFilter), ParentStudent.class);
//
//        Set<Integer> parentsIds = parentStudents.stream()
//                .map(parentStudent -> parentStudent.parentID)
//                .collect(Collectors.toSet());
//        String ids = parentsIds.stream().map(id -> id + "").collect(Collectors.joining("|"));
//
//        Filter idsFilter = new Filter("personId", Operator.EQUALS, ids);
//        return get("/People", List.of(idsFilter), Person.class);
//    }

  // Address
//    public Address getAddress(Integer id) {
//        return HttpClient.get(FACTS_API_URL + "/people/Address/" + id, headers(), Address.class);
//    }

  public List<PersonFamily> getPersonFamilies(Filter... filters) throws Exception {
    return get("/people/PersonFamily", Arrays.asList(filters), PersonFamily.class);
  }

  public List<Address> getAddresses(List<Integer> ids) throws Exception {
    return getByIds("/people/Address", "addressID", ids, Address.class);
  }

  public List<Address> getAddresses(Filter... filters) throws Exception {
    return get("/people/Address", Arrays.asList(filters), Address.class);
  }

  // Utils
  private <T> List<T> getByIds(String path, String idFieldName, List<Integer> ids, Class<T> clazz) throws Exception {
    List<List<Integer>> subLists = Lists.partition(ids, BATCH_REQUEST_IDS_LIMIT);
    List<T> items = new ArrayList<>();
    for (List<Integer> partitionIds : subLists) {
      Filter idsFilter = toIdsFilter(idFieldName, partitionIds);
      items.addAll(get(path, List.of(idsFilter), clazz));
    }
    return items;
  }

  private Filter toIdsFilter(String idFieldName, List<Integer> ids) {
    String joinedIds = ids.stream().map(id -> id + "").collect(Collectors.joining("|"));
    return new Filter(idFieldName, Operator.EQUALS, joinedIds);
  }

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

  private Response getWithAutoRetry(String url) throws Exception {
    Response response = HttpClient.get(url, headers());
    if (response.getStatus() == 200) {
      return response;
    } else if (response.getStatus() == 429) {
      log.warn("API limit response! Status/Body {}/{}", response.getStatus(), response.readEntity(String.class));
      Thread.sleep(AUTO_RETRY_TIMEOUT_SECONDS * 1000);
      return getWithAutoRetry(url);
    }
    log.error("Failed to get response! Status/Body {}/{}", response.getStatus(), response.readEntity(String.class));
    return null;
  }

  private HttpClient.HeaderBuilder headers() {
    return HttpClient.HeaderBuilder.builder()
        .header("Ocp-Apim-Subscription-Key", env.getConfig().facts.publicKey)
        .header("Facts-Api-Key", env.getConfig().facts.secretKey);
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
          "personId=" + personId +
          ", firstName='" + firstName + '\'' +
          ", lastName='" + lastName + '\'' +
          ", middleName='" + middleName + '\'' +
          ", nickName='" + nickName + '\'' +
          ", salutation='" + salutation + '\'' +
          ", suffix='" + suffix + '\'' +
          ", email='" + email + '\'' +
          ", email2='" + email2 + '\'' +
          ", username='" + username + '\'' +
          ", homePhone='" + homePhone + '\'' +
          ", cellPhone='" + cellPhone + '\'' +
          ", addressID=" + addressID +
          ", deceased=" + deceased +
          ", modifiedDate='" + modifiedDate + '\'' +
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
    public String withdrawReason;

    @Override
    public String toString() {
      return "School{" +
          "status='" + status + '\'' +
          ", enrollDate='" + enrollDate + '\'' +
          ", gradeLevel='" + gradeLevel + '\'' +
          ", nextStatus='" + nextStatus + '\'' +
          ", nextSchoolCode='" + nextSchoolCode + '\'' +
          ", nextGradeLevel='" + nextGradeLevel + '\'' +
          ", withdrawReason='" + withdrawReason + '\'' +
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
    //        public Boolean custody;
//        public Boolean correspondence;
    public String relationship;
    public Boolean grandparent;
//        public Boolean emergencyContact;
//        public Boolean reportCard;
//        public Boolean pickUp;
//        public Boolean parentsWeb;

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

    @Override
    public String toString() {
      return "PersonFamily{" +
          "personId=" + personId +
          ", familyId=" + familyId +
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
//        public Integer modifiedBy;
//        public String modifiedDate;
//        public String greeting1;
//        public String greeting2;
//        public String greeting3;
//        public String greeting4;
//        public String greeting5;

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

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class FilteredResponse<T> {
    public List<T> results;
    //        public Integer currentPage;
    public Integer pageCount;
//        public Integer pageSize;
//        public Integer rowCount;
//        public String nextPage;
  }
}
