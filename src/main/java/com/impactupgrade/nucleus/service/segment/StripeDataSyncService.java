package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.PagedResults;
import com.stripe.model.Customer;
import com.stripe.param.CustomerUpdateParams;

import java.util.Calendar;
import java.util.List;

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
          //TODO: consider handling retry since e do 1 by 1?
          updateCustomer(crmContact);
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
      //TODO: bulk update in Stripe
    }
  }

  protected Customer updateCustomer(CrmContact crmContact) throws Exception {
    String customerId = getCustomerId(crmContact);
    Customer customer = env.stripeClient().getCustomer(customerId);
    CustomerUpdateParams customerUpdateParams = CustomerUpdateParams.builder()
        .setName(crmContact.getFullName())
        .setEmail(crmContact.email)
        .setAddress(toAddress(crmContact.mailingAddress))
        .build();
    return env.stripeClient().updateCustomer(customer, customerUpdateParams);
  }

  protected String getCustomerId(CrmContact crmContact) {
    return crmContact.id; //TODO:
  }

  protected CustomerUpdateParams.Address toAddress(CrmAddress crmAddress) {
    return CustomerUpdateParams.Address.builder()
        .setLine1(crmAddress.street)
        .setCity(crmAddress.city)
        .setState(crmAddress.state)
        .setPostalCode(crmAddress.postalCode)
        .setCountry(crmAddress.country)
        .build();
  }
}
