package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PagedResults;
import com.stripe.exception.RateLimitException;
import com.stripe.model.Customer;
import com.stripe.param.CustomerUpdateParams;

import java.util.Calendar;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

public class StripeDataSyncService implements DataSyncService {

  protected Environment env;

  protected static final Integer RATE_LIMIT_EXCEPTION_TIMEOUT_SECONDS = 10; //?
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
    PagedResults<CrmContact> contactPagedResults = env.primaryCrmService().getDonorIndividualContacts(updatedAfter);
    for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
      for (CrmContact crmContact : resultSet.getRecords()) {
        try {
          updateCustomer(crmContact);
        } catch (Exception e) {
          env.logJobError("{}/syncContacts failed: {}", this.name(), e);
        }
      }
    }
  }

  @Override
  public void syncTransactions(Calendar updatedAfter) throws Exception {
    //TODO: pull our nightly batch job from PaymentGatewayController
    // and instead have this concept do it
  }

  protected Customer updateCustomer(CrmContact crmContact) throws Exception {
    Optional<Customer> existingCustomer = getCustomer(crmContact);
    if (existingCustomer.isPresent()) {
      // Update
      CustomerUpdateParams customerUpdateParams = CustomerUpdateParams.builder()
          .setName(crmContact.getFullName())
          .setEmail(crmContact.email.toLowerCase(Locale.ROOT))
          .setAddress(toUpdateAddress(crmContact.account.billingAddress))
          .setPhone(crmContact.mobilePhone)
          .build();
      return callWithRetries(() -> env.stripeClient().updateCustomer(existingCustomer.get(), customerUpdateParams));
    } else {
      return null;
    }
  }

  protected Optional<Customer> getCustomer(CrmContact crmContact) throws Exception {
    if (!Strings.isNullOrEmpty(crmContact.email)) {
      return env.stripeClient().getCustomerByEmail(crmContact.email);
    } else {
      env.logJobInfo("Expected contact {} to have an email defined.", crmContact.getFullName());
      return Optional.empty();
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
