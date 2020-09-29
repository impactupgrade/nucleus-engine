package com.impactupgrade.common.sfdc;

import com.impactupgrade.common.util.LoggingUtil;
import com.impactupgrade.integration.sfdc.SFDCPartnerAPIClient;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;

public class SFDCClient extends SFDCPartnerAPIClient {

  private static final Logger log = LogManager.getLogger(SFDCClient.class.getName());

  private static final String AUTH_URL;
  static {
    String profile = System.getenv("PROFILE");
    if ("production".equalsIgnoreCase(profile)) {
      AUTH_URL = "https://login.salesforce.com/services/Soap/u/47.0/";
    } else {
      AUTH_URL = "https://test.salesforce.com/services/Soap/u/47.0/";
    }
  }

  public SFDCClient(String username, String password) {
    super(
        username,
        password,
        AUTH_URL,
        20 // objects are massive, so toning down the batch sizes
    );
  }

  public SFDCClient() {
    super(
        System.getenv("SFDC_USERNAME"),
        System.getenv("SFDC_PASSWORD"),
        AUTH_URL,
        20 // objects are massive, so toning down the batch sizes
    );
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // ACCOUNTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Optional<SObject> getAccountById(String accountId) throws ConnectionException, InterruptedException {
    String query = "select id, OwnerId from account where id = '" + accountId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CONTACTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private static final String CONTACT_FIELDS = "Id, AccountId, OwnerId, FirstName, LastName";

  public Optional<SObject> getContactById(String contactId) throws ConnectionException, InterruptedException {
    String query = "select " + CONTACT_FIELDS + " from contact where id = '" + contactId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getContactsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + CONTACT_FIELDS + " from contact where accountId = '" + accountId + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getContactsByEmail(String email) throws ConnectionException, InterruptedException {
    String query = "select " + CONTACT_FIELDS + " from contact where email = '" + email + "' OR npe01__HomeEmail__c = '" + email + "' OR npe01__WorkEmail__c = '" + email + "' OR npe01__AlternateEmail__c = '" + email + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getContactsByName(String firstName, String lastName) throws ConnectionException, InterruptedException {
    String query = "select " + CONTACT_FIELDS + " from contact where firstName = '" + firstName + "' and lastName = '" + lastName + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // META
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Optional<SObject> getRecordTypeByName(String recordTypeName) throws ConnectionException, InterruptedException {
    String query = "select id from recordtype where name = '" + recordTypeName + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // USERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Use with caution, it retrieves ALL active users. Unsuitable for orgs with many users.
   */
  public List<SObject> getActiveUsers() throws ConnectionException, InterruptedException {
    String query = "select id, firstName, lastName from user where isActive = true";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DRY HELPERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public SaveResult insert(SObject sObject) throws InterruptedException {
    SaveResult saveResult = super.insert(sObject);

    // for convenience, set the ID back on the sObject so it can be directly reused for further processing
    sObject.setId(saveResult.getId());

    return saveResult;
  }
}
