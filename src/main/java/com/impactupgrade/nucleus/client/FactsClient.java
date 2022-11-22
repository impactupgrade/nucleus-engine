package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.HttpClient.get;

public class FactsClient {

  private static final Logger log = LogManager.getLogger(FactsClient.class);

  private static final String FACTS_API_URL = "https://api.factsmgt.com";
  private static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";
  private static final Integer DEFAULT_PAGE_SIZE = 50;

  private String subscriptionKey;
  private String apiKey;

  protected Environment env;

  public FactsClient(Environment env) {
    this.env = env;

    this.subscriptionKey = env.getConfig().facts.publicKey;
    this.apiKey = env.getConfig().facts.secretKey;
  }

  // Person/People
  public Person getPerson(Integer id) {
    return get(FACTS_API_URL + "/People/" + id, headers(), Person.class);
  }

  public List<Person> getPeople() throws Exception {
    return findPeople(null);
  }

  public List<Person> findPeople(List<Filter> filters) throws Exception {
    return findPeople(filters, null, null, null);
  }

  public List<Person> findPeople(List<Filter> filters, String sortBy, Integer page, Integer pageSize) throws Exception {
    PeopleResponse peopleResponse = get(FACTS_API_URL + "/People" + toParametersUrl(filters, sortBy, page, pageSize), headers(), PeopleResponse.class);
    // TODO: handle 429
    //{ "statusCode": 429, "message": "Rate limit is exceeded. Try again in 40 seconds." }

    List<Person> people = new ArrayList<>();
    people.addAll(peopleResponse.results);
    // TODO: extract generic method to find with paging
//    if (peopleResponse.pageCount > 1) {
//      for (int p = 1; p <= peopleResponse.pageCount; p++) {
//        peopleResponse = get(FACTS_API_URL + "/People" + toParametersUrl(filters, sortBy, p, pageSize), headers(), PeopleResponse.class);
//        people.addAll(peopleResponse.results);
//      }
//    }
    return people;
  }

  public List<Person> findPeopleModifiedBetween(LocalDateTime fromDate, LocalDateTime toDate) throws Exception {
    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT);
    Filter fromFilter = new Filter("modifiedDate", FactsClient.Operator.GREATER_THAN_OR_EQUAL_TO, dateTimeFormatter.format(fromDate));
    Filter toFilter = new Filter("modifiedDate", Operator.LESS_THAN_OR_EQUAL_TO, dateTimeFormatter.format(toDate));
    return findPeople(List.of(fromFilter, toFilter));
  }

  // Person-Family
  public List<Person> getParents(Integer personId) throws Exception {
    PersonFamily personFamily = getPersonFamily(personId);
    if (personFamily == null) {
      return null;
    }
    Filter familyIdFilter = new Filter("familyId", Operator.EQUALS, personFamily.familyId + "");
    Filter isParentFilter = new Filter("parent", Operator.EQUALS, Boolean.TRUE + "");
    List<PersonFamily> personFamilies = findPersonFamilies(List.of(familyIdFilter, isParentFilter));

    List<Person> parents = personFamilies.stream()
        .map(pf -> getPerson(pf.personId)).collect(Collectors.toList());

    return parents;
  }

  public PersonFamily getPersonFamily(Integer personId) throws Exception {
    List<PersonFamily> personFamilies = findPersonFamilies(List.of(new Filter("personId", Operator.EQUALS, personId + "")));
    if (personFamilies.isEmpty()) {
      return null;
    }
    if (personFamilies.size() > 1) {
      log.warn("Found more than 1 person family records for person id {}!" + personId);
    }
    return personFamilies.get(0);
  }

  public List<PersonFamily> getPersonFamilies(Integer familyId) throws Exception {
    Filter familyIdFilter = new Filter("familyId", Operator.EQUALS, familyId + "");
    return findPersonFamilies(List.of(familyIdFilter));
  }

  public List<PersonFamily> findPersonFamilies(List<Filter> filters) throws Exception {
    return findPersonFamilies(filters, null, null, null);
  }

  public List<PersonFamily> findPersonFamilies(List<Filter> filters, String sortBy, Integer page, Integer pageSize) throws Exception {
    PersonFamilyResponse personFamilyResponse = get(FACTS_API_URL + "/People/PersonFamily" + toParametersUrl(filters, sortBy, page, pageSize), headers(), PersonFamilyResponse.class);
    return personFamilyResponse.results;
  }

  // Address
  public Address getAddress(Integer id) {
    return get(FACTS_API_URL + "/people/Address/" + id, headers(), Address.class);
  }

  // Utils
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
      return "?" + parameters.stream().collect(Collectors.joining("&"));
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
    String encodedParameters = URLEncoder.encode(parameters, "UTF-8");
    return encodedParameters;
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
  public static class PeopleResponse {
    public List<Person> results;
    public Integer currentPage;
    public Integer pageCount;
    public Integer pageSize;
    public Integer rowCount;
    public String nextPage;
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
  public static class PersonFamilyResponse {
    public List<PersonFamily> results;
    public Integer currentPage;
    public Integer pageCount;
    public Integer pageSize;
    public Integer rowCount;
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

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class AddressResponse {
    public List<Address> results;
    public Integer currentPage;
    public Integer pageCount;
    public Integer pageSize;
    public Integer rowCount;
  }
}
