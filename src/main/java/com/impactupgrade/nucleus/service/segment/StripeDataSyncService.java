package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.PagedResults;
import com.stripe.model.Customer;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class StripeDataSyncService implements DataSyncService {

  protected Environment env;

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
      if (resultSet.getRecords().isEmpty()) continue;

      for (CrmContact crmContact : resultSet.getRecords()) {
        try {
          //TODO: consider handling retry since we do 1 by 1?
          updateOrCreateCustomer(crmContact);
        } catch (Exception e) {
          env.logJobError("{}/syncContacts failed: {}", this.name(), e);
        }
      }
    }
  }

  @Override
  public void syncTransactions(Calendar updatedAfter) throws Exception {
    List<CrmDonation> crmDonations = env.primaryCrmService().getDonations(updatedAfter);
    for (CrmDonation crmDonation : crmDonations) {
      //TODO: one by one update in Stripe
    }
  }

  protected Customer updateOrCreateCustomer(CrmContact crmContact) throws Exception {
    Optional<Customer> existingCustomer = getCustomer(crmContact);
    if (existingCustomer.isPresent()) {
      // Update
      CustomerUpdateParams customerUpdateParams = CustomerUpdateParams.builder()
          .setName(crmContact.getFullName())
          .setEmail(crmContact.email.toLowerCase(Locale.ROOT))
          .setAddress(toUpdateAddress(crmContact.mailingAddress))
          .build();
      return env.stripeClient().updateCustomer(existingCustomer.get(), customerUpdateParams);
    } else {
      // Create
      CustomerCreateParams.Builder customerCreateParamsBuilder = CustomerCreateParams.builder()
          .setName(crmContact.getFullName())
          .setEmail(crmContact.email.toLowerCase(Locale.ROOT))
          .setAddress(toCreateAddress(crmContact.mailingAddress));
      return env.stripeClient().createCustomer(customerCreateParamsBuilder);
    }
  }

  protected Optional<Customer> getCustomer(CrmContact crmContact) throws Exception {
    return env.stripeClient().getCustomerByEmail(crmContact.email);
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

  protected CustomerCreateParams.Address toCreateAddress(CrmAddress crmAddress) {
    return CustomerCreateParams.Address.builder()
        .setLine1(crmAddress.street)
        .setCity(crmAddress.city)
        .setState(crmAddress.state)
        .setPostalCode(crmAddress.postalCode)
        .setCountry(crmAddress.country)
        .build();
  }
}
