package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.DonationFormData;
import com.impactupgrade.nucleus.service.logic.AntiFraudService;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.Utils;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.PaymentSource;
import com.stripe.model.Subscription;
import com.stripe.param.ChargeCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PlanCreateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.impactupgrade.nucleus.util.Utils.emptyStringToNull;


// TODO: Square this up with DS?
@Path("/donation")
public class DonationFormController {

  private static final Logger log = LogManager.getLogger(DonationFormController.class);

  private final EnvironmentFactory envFactory;

  public DonationFormController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  /**
   * IMPORTANT: Although this method does a lot, it cannot be spun off into a new thread! The donation form
   * needs to receive any error messages from Stripe (invalid CVC, etc), so this must currently be sync!
   */
  @Path("/donation-form")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response donationForm(
      @BeanParam DonationFormData formData,
      @Context HttpServletRequest request
  ) {
    log.info(formData);
    Environment env = envFactory.init(request);
    AntiFraudService antiFraudService = new AntiFraudService(env);

    formData.setFirstName(Utils.cleanUnicode(formData.getFirstName()));
    formData.setLastName(Utils.cleanUnicode(formData.getLastName()));
    formData.setNotes(Utils.cleanUnicode(formData.getNotes()));

    try {
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // Sanity checks to block bots.
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

      if (
          formData.isFraudAttempt()
          || !antiFraudService.isRecaptchaTokenValid(formData.getRecaptchaToken())
      ) {
        // Return something meaningful, just in case it's actually a human, but not detailed to the point where
        // it tips off the nefarious person/bot to the specific issue we detected.
        return Response.status(400).entity("Donation blocked as possible spam. If this is in error, please try again or contact us!").build();
      }

      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // CRM
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

      // TODO: Needs refactored to use the generic CRM concepts.
      // TODO: Refactor for PaymentGatewayService instead of StripeClient?
      // TODO: Business Donations coming soon, but not all CRMs support email at the company/account level.

      CrmService crmService = env.donationsCrmService();
      StripeClient stripeClient = env.stripeClient();

      // If we're in Stripe mode, it's VITAL that we create/update the Customer with the payment source before inserting
      // anything new in SF! For fraud attempts, adding a source to a Customer is when Stripe validates the card.
      // Fail as early as possible!
      Customer stripeCustomer = null;
      PaymentSource stripePaymentSource = null;

//      if (formData.isBusiness()) {
//        Optional<CrmAccount> existingAccount = crmService.getAccountByEmail(formData.getBusinessEmail())
//            .filter(a -> a.type == CrmAccount.Type.ORGANIZATION);
//
//        String bizAccountId;
//
//        // Retrieve or create the biz Account.
//        if (existingAccount.isEmpty()) {
//          log.info("unable to find CRM account using email {}; creating a new account", formData.getEmail());
//
//          if (formData.isStripe()) {
//            // Attempt/validate *before* creating the new records!
//            stripeCustomer = createStripeCustomer(formData, env);
//            stripePaymentSource = stripeCustomer.getSources().getData().get(0);
//          }
//
//          bizAccountId = createBusinessAccount(formData, env);
//          formData.setCrmOrganizationAccountId(bizAccountId);
//        } else {
//          // Existing account -- first set the accountId so that we can look for an existing customer.
//          bizAccountId = existingAccount.get().id;
//          formData.setCrmOrganizationAccountId(bizAccountId);
//
//          log.info("found CRM account {} using email {}; updating it...", formData.getCrmOrganizationAccountId(), formData.getBusinessEmail());
//
//          if (formData.isStripe()) {
//            stripeCustomer = createStripeCustomer(formData, env);
//            stripePaymentSource = stripeCustomer.getSources().getData().get(0);
//            log.info("created Stripe Customer {}", stripeCustomer.getId());
//          }
//
//          updateBusinessAccount(formData, env);
//        }
//
//        // Additionally, create a Household Account and Contact for the individual giving on behalf of the biz.
//        // But only if they don't exist already!
//        Optional<CrmContact> existingContact = crmService.getContactByEmail(formData.getEmail());
//        if (existingContact.isEmpty()) {
//          log.info("unable to find CRM contact using email {}; creating a new account/contact", formData.getEmail());
//
//          String accountId = createHouseholdAccount(formData, env);
//          formData.setCrmAccountId(accountId);
//          String contactId = createHouseholdContact(formData, env);
//          formData.setCrmContactId(contactId);
//
//          // Attach the contact as an affiliate to the business account.
//          crmService.insertSecondaryAffiliation(bizAccountId, contactId);
//        } else {
//          formData.setCrmContactId(existingContact.get().id);
//          formData.setCrmAccountId(existingContact.get().accountId);
//          log.info("found CRM contact {} and account {} using email {}",
//              formData.getCrmContactId(), formData.getCrmAccountId(), formData.getEmail());
//
//          if (!crmService.hasSecondaryAffiliation(bizAccountId, formData.getCrmContactId())) {
//            log.info("unable to find CRM affiliation between contact {} and biz {}; creating it...",
//                formData.getCrmContactId(), bizAccountId);
//            crmService.insertSecondaryAffiliation(bizAccountId, formData.getCrmContactId());
//          }
//        }
//      }
//      // Retrieve or create the household Account/Contact.
//      else {
        Optional<CrmContact> existingContact = crmService.getContactByEmail(formData.getEmail());
        if (existingContact.isEmpty()) {
          log.info("unable to find CRM contact using email {}; creating a new account/contact", formData.getEmail());

          if (formData.isStripe()) {
            // Attempt/validate *before* creating the new records!
            stripeCustomer = createStripeCustomer(formData, env);
            stripePaymentSource = stripeCustomer.getSources().getData().get(0);
            log.info("created Stripe Customer {}", stripeCustomer.getId());
          }

          String accountId = createHouseholdAccount(formData, env);
          formData.setCrmAccountId(accountId);
          String contactId = createHouseholdContact(formData, env);
          formData.setCrmContactId(contactId);
        } else {
          formData.setCrmContactId(existingContact.get().id);
          // Existing account -- first set the accountId so that we can look for an existing customer.
          formData.setCrmAccountId(existingContact.get().accountId);

          log.info("found CRM contact {} and account {} using email {}; updating them...",
              formData.getCrmContactId(), formData.getCrmAccountId(), formData.getEmail());

          if (formData.isStripe()) {
            stripeCustomer = createStripeCustomer(formData, env);
            stripePaymentSource = stripeCustomer.getSources().getData().get(0);
            log.info("created Stripe Customer {}", stripeCustomer.getId());
          }

          updateHouseholdAccount(formData, env);
          updateHouseholdContact(formData, env);
        }
//      }

      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // Payment gateways.
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

      // STRIPE (CREDIT/DEBIT CARD)
      if (formData.isStripe()) {
        processStripe(stripeCustomer, stripePaymentSource, formData, stripeClient, env);
      }
      // INTEGRATION TEST
      else if (formData.isIntegrationTest()) {
        log.info("integration test; skipping payment gateway");
      }
      // UNKNOWN
      else {
        log.error("unknown payment gateway handling");
        return Response.status(500).build();
      }
    } catch (StripeException e) {
      log.warn("Stripe failed to process the donation: {}", e.getMessage());
      return Response.status(400).entity(e.getMessage()).build();
    } catch (Exception e) {
      log.error("failed to process the donation", e);
      return Response.status(500).build();
    }

    return Response.status(200).build();
  }

  private String createHouseholdAccount(DonationFormData formData, Environment env) throws Exception {
    CrmAccount account = new CrmAccount();

    account.name = formData.getLastName() + " Household";

    // If it's a biz donation, this will all be the biz billing info and we shouldn't set it on the individual.
//    if (!formData.isBusiness()) {
      account.address = new CrmAddress();
      account.address.street = formData.getFullBillingAddress();
      account.address.city = formData.getBillingCity();
      account.address.state = formData.getBillingState();
      account.address.postalCode = formData.getBillingZip();
      account.address.country = formData.getBillingCountryFullName();
//    }

    return env.donationsCrmService().insertAccount(account);  }

  private void updateHouseholdAccount(DonationFormData formData, Environment env) throws Exception {
    CrmAccount account = new CrmAccount();
    account.id = formData.getCrmAccountId();
    account.address = new CrmAddress();
    account.address.street = emptyStringToNull(formData.getFullBillingAddress());
    account.address.city = emptyStringToNull(formData.getBillingCity());
    account.address.state = emptyStringToNull(formData.getBillingState());
    account.address.postalCode = emptyStringToNull(formData.getBillingZip());
    account.address.country = emptyStringToNull(formData.getBillingCountryFullName());

    env.donationsCrmService().updateAccount(account);
  }

//  private String createBusinessAccount(DonationFormData formData, Environment env) throws Exception {
//    CrmAccount account = new CrmAccount();
//
//    account.name = formData.getBusinessName();
//    account.address = new CrmAddress();
//    account.address.street = formData.getFullBillingAddress();
//    account.address.city = formData.getBillingCity();
//    account.address.state = formData.getBillingState();
//    account.address.postalCode = formData.getBillingZip();
//    account.address.country = formData.getBillingCountryFullName();
//
//    return env.donationsCrmService().insertAccount(account);
//  }
//
//  private void updateBusinessAccount(DonationFormData formData, Environment env) throws Exception {
//    CrmAccount account = new CrmAccount();
//    account.id = formData.getCrmAccountId();
//
//    account.address = new CrmAddress();
//    account.address.street = emptyStringToNull(formData.getFullBillingAddress());
//    account.address.city = emptyStringToNull(formData.getBillingCity());
//    account.address.state = emptyStringToNull(formData.getBillingState());
//    account.address.postalCode = emptyStringToNull(formData.getBillingZip());
//    account.address.country = emptyStringToNull(formData.getBillingCountryFullName());
//
//    env.donationsCrmService().updateAccount(account);
//  }

  private String createHouseholdContact(DonationFormData formData, Environment env) throws Exception {
    CrmContact contact = new CrmContact();
    contact.accountId = formData.getCrmAccountId();

    contact.firstName = formData.getFirstName();
    contact.lastName = formData.getLastName();
    contact.email = formData.getEmail();
    contact.mobilePhone = formData.getPhone();

    // If it's a biz donation, this will all be the biz billing info and we shouldn't set it on the individual.
    contact.address = new CrmAddress();
    contact.address.street = formData.getFullBillingAddress();
    contact.address.city = formData.getBillingCity();
    contact.address.state = formData.getBillingState();
    contact.address.postalCode = formData.getBillingZip();
    contact.address.country = formData.getBillingCountryFullName();

    // TODO: leadsource?

    return env.donationsCrmService().insertContact(contact);
  }

  private void updateHouseholdContact(DonationFormData formData, Environment env) throws Exception {
    CrmContact contact = new CrmContact();
    contact.id = formData.getCrmContactId();
    contact.mobilePhone = emptyStringToNull(formData.getPhone());

    contact.address = new CrmAddress();
    contact.address.street = emptyStringToNull(formData.getFullBillingAddress());
    contact.address.city = emptyStringToNull(formData.getBillingCity());
    contact.address.state = emptyStringToNull(formData.getBillingState());
    contact.address.postalCode = emptyStringToNull(formData.getBillingZip());
    contact.address.country = emptyStringToNull(formData.getBillingCountryFullName());

    env.donationsCrmService().updateContact(contact);
  }

  private Customer createStripeCustomer(DonationFormData formData, Environment env) throws StripeException {
    StripeClient stripeClient = env.stripeClient();
    CustomerCreateParams.Builder customerBuilder = stripeClient.defaultCustomerBuilder(
        formData.getCustomerName(),
        formData.getCustomerEmail(),
        formData.getStripeToken()
    );
    Customer customer = stripeClient.createCustomer(customerBuilder);
    log.info("created Stripe Customer {}", customer.getId());
    return customer;
  }

  private void processStripe(Customer stripeCustomer, PaymentSource stripeSource, DonationFormData formData,
      StripeClient stripeClient, Environment env) throws StripeException {
    log.info("processing payment with Stripe");

    Map<String, String> customerMetadata = new HashMap<>();
//    if (formData.isBusiness()) {
//      customerMetadata.put("account_id", formData.getCrmOrganizationAccountId());
//    } else {
      customerMetadata.put("account_id", formData.getCrmAccountId());
//    }
    customerMetadata.put("contact_id", formData.getCrmContactId());
    // TODO: only if needed
    stripeClient.updateCustomer(
        stripeCustomer,
        customerMetadata
    );

    String currency = env.getConfig().currency;

    if (formData.isRecurring()) {
      Map<String, String> subscriptionMetadata = new HashMap<>();
      subscriptionMetadata.put("campaign", formData.getCampaignId());
      subscriptionMetadata.put("description", formData.getNotes());
      ProductCreateParams.Builder productBuilder = stripeClient.defaultProductBuilder(stripeCustomer, formData.getAmountInCents(), currency);
      PlanCreateParams.Builder planBuilder = stripeClient.defaultPlanBuilder(formData.getAmountInCents(), currency);
      SubscriptionCreateParams.Builder subscriptionBuilder = stripeClient.defaultSubscriptionBuilder(stripeCustomer, stripeSource)
          .setMetadata(subscriptionMetadata);
      Subscription subscription = stripeClient.createSubscription(productBuilder, planBuilder, subscriptionBuilder);
      log.info("created Stripe Subscription {}", subscription.getId());
    } else {
      Map<String, String> chargeMetadata = new HashMap<>();
      chargeMetadata.put("campaign", formData.getCampaignId());
      ChargeCreateParams.Builder chargeBuilder = stripeClient.defaultChargeBuilder(
          stripeCustomer,
          stripeSource,
          formData.getAmountInCents(),
          currency
      ).setDescription(formData.getNotes()).setMetadata(chargeMetadata);
      Charge charge = stripeClient.createCharge(chargeBuilder);
      log.info("created Stripe Charge {}", charge.getId());
    }
  }
}
