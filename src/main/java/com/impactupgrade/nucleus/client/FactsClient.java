package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.HttpClient.get;

public class FactsClient {

  private static final Logger log = LogManager.getLogger(FactsClient.class);

  private static final String FACTS_API_URL = "https://api.factsmgt.com";

  private String subscriptionKey;
  private String apiKey;

  protected Environment env;

  public FactsClient(Environment env) {
    this.env = env;

    this.subscriptionKey = env.getConfig().facts.publicKey;
    this.apiKey = env.getConfig().facts.secretKey;
  }

  // People/Person
  public List<Person> getPeople() {
    return findPeople(null);
  }

  public List<Person> findPeople(List<Filter> filters) {
    PeopleResponse peopleResponse = get(FACTS_API_URL + "/People" + toFiltersUrl(filters), headers(), PeopleResponse.class);
    return peopleResponse.results;
  }

  public Person getPersonById(Integer id) {
    return getPerson(FACTS_API_URL + "/People/" + id);
  }

  private Person getPerson(String personUrl) {
    return get(personUrl, headers(), Person.class);
  }

  private HttpClient.HeaderBuilder headers() {
    return HttpClient.HeaderBuilder.builder()
        .header("Ocp-Apim-Subscription-Key", this.subscriptionKey)
        .header("Facts-Api-Key", this.apiKey);
  }

  private String toFiltersUrl(List<Filter> filters) {
    if (CollectionUtils.isEmpty(filters)) {
      return "";
    }
    return "?filters=" + filters.stream()
        .map(filter -> filter.name + filter.operator.value + filter.value)
        .collect(Collectors.joining(","));
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
}
