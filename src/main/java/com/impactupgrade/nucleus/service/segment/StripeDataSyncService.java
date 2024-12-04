package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PagedResults;
import com.stripe.exception.RateLimitException;
import com.stripe.model.Customer;
import com.stripe.param.CustomerUpdateParams;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

public class StripeDataSyncService implements DataSyncService {

  protected Environment env;

  protected static final Integer RATE_LIMIT_EXCEPTION_TIMEOUT_SECONDS = 5;
  protected static final Integer RATE_LIMIT_MAX_RETRIES = 3;

  @Override
  public String name() {
    return "stripeDataSync";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return true;
  }

  @Override
  public void init(Environment env) {
    this.env = env;
  }

  @Override
  public void syncContacts(Calendar updatedAfter) throws Exception {
    if (!env.getConfig().stripe.enableContactSync) {
      return;
    }

    PagedResults<CrmContact> contactPagedResults = env.primaryCrmService().getDonorIndividualContacts(updatedAfter);
    for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
      try {
        do {
          syncContacts(resultSet.getRecords());
          if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
            // next page
            resultSet = env.primaryCrmService().queryMoreContacts(resultSet.getNextPageToken());
          } else {
            resultSet = null;
          }
        } while (resultSet != null);
      } catch (Exception e) {
        env.logJobError("{}/syncContacts failed: {}", this.name(), e);
      }
    }

    PagedResults<CrmAccount> accountPagedResults = env.primaryCrmService().getDonorOrganizationAccounts(updatedAfter);
    for (PagedResults.ResultSet<CrmAccount> resultSet : accountPagedResults.getResultSets()) {
      try {
        do {
          List<CrmAccount> crmAccounts = resultSet.getRecords();
          List<CrmContact> fauxContacts = crmAccounts.stream().map(this::toFauxContact).toList();
          syncContacts(fauxContacts);
          if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
            // next page
            resultSet = env.primaryCrmService().queryMoreAccounts(resultSet.getNextPageToken());
          } else {
            resultSet = null;
          }
        } while (resultSet != null);
      } catch (Exception e) {
        env.logJobError("{}/syncContacts failed: {}", this.name(), e);
      }
    }
  }

  protected void syncContacts(List<CrmContact> contacts) {
    for (CrmContact crmContact : contacts) {
      try {
        if (Strings.isNullOrEmpty(crmContact.email)) {
          env.logJobInfo("skipping {}; no email address", crmContact.id);
          continue;
        }
        updateCustomer(crmContact);
      } catch (Exception e) {
        env.logJobError("{}/syncContacts failed: {}", this.name(), e);
      }
    }
  }

  protected CrmContact toFauxContact(CrmAccount crmAccount) {
    CrmContact crmContact = new CrmContact();
    crmContact.account = crmAccount;
    crmContact.setFullNameOverride(crmAccount.name);
    crmContact.email = crmAccount.email;
    crmContact.mobilePhone = crmAccount.phone;
    return crmContact;
  }

  @Override
  public void syncTransactions(Calendar updatedAfter) throws Exception {
    //TODO: pull our nightly batch job from PaymentGatewayController
    // and instead have this concept do it
  }

  protected void updateCustomer(CrmContact crmContact) throws Exception {
    List<Customer> existingCustomers = getCustomers(crmContact);
    for (Customer existingCustomer : existingCustomers) {
      env.logJobInfo("updating contact {}, customer {}", crmContact.id, existingCustomer.getId());
      CustomerUpdateParams customerUpdateParams = CustomerUpdateParams.builder()
          .setName(crmContact.getFullName())
          .setEmail(crmContact.email.toLowerCase(Locale.ROOT))
          .setAddress(toUpdateAddress(crmContact.account.billingAddress))
          .setPhone(crmContact.mobilePhone)
          .build();
      callWithRetries(() -> env.stripeClient().updateCustomer(existingCustomer, customerUpdateParams));
    }
  }

  protected List<Customer> getCustomers(CrmContact crmContact) throws Exception {
    if (!Strings.isNullOrEmpty(crmContact.email)) {
      return callWithRetries(() -> env.stripeClient().getCustomersByEmail(crmContact.email));
    } else {
      env.logJobInfo("Expected contact {} to have an email defined.", crmContact.getFullName());
      return Collections.emptyList();
    }
  }

  protected CustomerUpdateParams.Address toUpdateAddress(CrmAddress crmAddress) {
    return CustomerUpdateParams.Address.builder()
        .setLine1(crmAddress.street)
        .setCity(crmAddress.city)
        .setState(crmAddress.state)
        .setPostalCode(crmAddress.postalCode)
        .setCountry(crmAddress.country)
        .build();
  }

  protected <T> T callWithRetries(Callable<T> callable) throws Exception {
    for (int i = 0; i <= RATE_LIMIT_MAX_RETRIES; i++) {
      try {
        return callable.call();
      } catch (RateLimitException e) {
        env.logJobWarn("API rate limit exceeded. Trying again after " + RATE_LIMIT_EXCEPTION_TIMEOUT_SECONDS + " seconds...");
        Thread.sleep(RATE_LIMIT_EXCEPTION_TIMEOUT_SECONDS * 1000);
      }
    }
    // Should be unreachable
    env.logJobError("Failed to get API response after {} tries!", RATE_LIMIT_MAX_RETRIES);
    return null;
  }
}
