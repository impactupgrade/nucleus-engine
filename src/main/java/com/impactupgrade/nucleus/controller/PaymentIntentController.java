package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.DonationFormData;
import com.impactupgrade.nucleus.service.logic.AntiFraudService;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.Utils;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentSource;
import com.stripe.model.Subscription;
import com.stripe.param.*;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Path("/payment-intent")
public class PaymentIntentController {

  private static final Logger log = LogManager.getLogger(PaymentIntentController.class);

  protected final EnvironmentFactory envFactory;

  public PaymentIntentController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response createPaymentIntent(
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

      if (!EmailValidator.getInstance().isValid(formData.getEmail())) {
        return Response.status(400).entity("Invalid email address: " + formData.getEmail() + " -- please try again.").build();
      }

      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // CRM
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // If we're in Stripe mode, it's VITAL that we create/update the Customer with the payment source before inserting
      // anything new in SF! For fraud attempts, adding a source to a Customer is when Stripe validates the card.
      // Fail as early as possible!
      CrmService crmService = env.donationsCrmService();
      Optional<CrmContact> existingContact = crmService.searchContacts(ContactSearch.byEmail(formData.getEmail())).getSingleResult();
      if (existingContact.isEmpty()) {
        log.info("unable to find CRM contact using email {}; creating a new account/contact", formData.getEmail());

        String accountId = createHouseholdAccount(formData, env);
        formData.setCrmAccountId(accountId);
        String contactId = createHouseholdContact(formData, env);
        formData.setCrmContactId(contactId);

      } else {
        log.info("found CRM contact {} and account {} using email {}; updating them...",
            formData.getCrmContactId(), formData.getCrmAccountId(), formData.getEmail());

        // Existing account -- first set the accountId so that we can look for an existing customer.
        formData.setCrmAccountId(existingContact.get().accountId);
        formData.setCrmContactId(existingContact.get().id);
      }

      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // Payment gateways.
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      Customer stripeCustomer;
      PaymentSource stripePaymentSource;
      PaymentIntent paymentIntent;
      if (formData.isStripe()) {
        // Attempt/validate *before* creating the new records!
        CustomerAndSource customerAndSource = getOrCreateStripeCustomer(formData, env);
        stripeCustomer = customerAndSource.customer;
        stripePaymentSource = customerAndSource.source;

        // Create Payment Intent
        paymentIntent = processStripe(stripeCustomer, stripePaymentSource, formData, env.stripeClient(), env);
        String paymentIntentJson = new Gson().toJson(paymentIntent);

        return Response.ok(paymentIntentJson).build();
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
      log.warn("Stripe failed to create payment intent: {}", e.getMessage());
      return Response.status(400).entity(e.getMessage()).build();
    } catch (Exception e) {
      log.error("failed to process the donation", e);
      return Response.status(500).build();
    }

    return Response.ok().build();
  }

  @Path("/{id}")
  @PUT
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updatePaymentIntent(
      @PathParam("id") String id,
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

      if (!EmailValidator.getInstance().isValid(formData.getEmail())) {
        return Response.status(400).entity("Invalid email address: " + formData.getEmail() + " -- please try again.").build();
      }

      // STRIPE
      if (formData.isStripe()) {
        StripeClient stripeClient = env.stripeClient();
        PaymentIntent existing = stripeClient.getPaymentIntent(id);
        PaymentIntent updated;

        if (existing == null) {
          return Response.status(404).entity("Stripe failed to find payment intent for id: {}" + id).build();
        }

        String invoiceId = existing.getInvoice();

        if (!Strings.isNullOrEmpty(invoiceId)) {
          Invoice invoice = stripeClient.getInvoice(invoiceId);
          log.info("found Invoice {}", invoiceId);

          stripeClient.updateSubscriptionAmountAndCurrency(invoice.getSubscription(), formData.getAmount(), formData.getCurrency());
          log.info("updated Subscription {}", invoice.getSubscription());

          updated = stripeClient.getPaymentIntent(invoice.getPaymentIntent());
        } else {
          PaymentIntentUpdateParams.Builder updateParamsBuilder = PaymentIntentUpdateParams.builder()
              .setAmount(formData.getAmountInCents())
              .setCurrency(formData.getCurrency());

          if (formData.getCustomMetadataPaymentIntent() != null) {
            updateParamsBuilder.setMetadata(formData.getCustomMetadataPaymentIntent());
          }

          updated = env.stripeClient().updatePaymentIntent(existing, updateParamsBuilder);
          log.info("updated Payment Intent {}", updated.getId());
        }

        String paymentIntentJson = new Gson().toJson(updated);
        return Response.ok(paymentIntentJson).build();
      } else {
        // UNKNOWN
        log.error("unknown payment gateway handling");
        return Response.status(500).build();
      }
    } catch (StripeException e) {
      log.warn("Stripe failed to update payment intent: {}", e.getMessage());
      return Response.status(400).entity(e.getMessage()).build();
    } catch (Exception e) {
      log.error("failed to update payment intent!", e);
      return Response.status(500).build();
    }
  }

  protected record CustomerAndSource(Customer customer, PaymentSource source) {}

  protected String createHouseholdAccount(DonationFormData formData, Environment env) throws Exception {
    CrmAccount account = new CrmAccount();

    account.name = formData.getLastName() + " Household";

    // If it's a biz donation, this will all be the biz billing info and we shouldn't set it on the individual.
//    if (!formData.isBusiness()) {
    account.address.street = formData.getFullBillingAddress();
    account.address.city = formData.getBillingCity();
    account.address.state = formData.getBillingState();
    account.address.postalCode = formData.getBillingZip();
    account.address.country = formData.getBillingCountryFullName();
//    }

    return env.donationsCrmService().insertAccount(account);  }

  protected String createHouseholdContact(DonationFormData formData, Environment env) throws Exception {
    CrmContact contact = new CrmContact();
    contact.accountId = formData.getCrmAccountId();

    contact.firstName = formData.getFirstName();
    contact.lastName = formData.getLastName();
    contact.email = formData.getEmail();
    contact.mobilePhone = formData.getPhone();

    // If it's a biz donation, this will all be the biz billing info and we shouldn't set it on the individual.
    contact.address.street = formData.getFullBillingAddress();
    contact.address.city = formData.getBillingCity();
    contact.address.state = formData.getBillingState();
    contact.address.postalCode = formData.getBillingZip();
    contact.address.country = formData.getBillingCountryFullName();

    // TODO: leadsource?

    return env.donationsCrmService().insertContact(contact);
  }

  // TODO: Not a huge fan of returning a tuple here, but it helps keep the logic clean for callers...
  protected CustomerAndSource getOrCreateStripeCustomer(DonationFormData formData, Environment env) throws StripeException {
    StripeClient stripeClient = env.stripeClient();

    Customer customer = null;
    PaymentSource source = null;

    // TODO: Also check by customerId, if the field is configured in env.json?
    if (!Strings.isNullOrEmpty(formData.getCustomerEmail())) {
      customer = stripeClient.getCustomerByEmail(formData.getCustomerEmail()).orElse(null);
      if (customer != null) {
        log.info("found Stripe Customer {}", customer.getId());
        source = stripeClient.updateCustomerSource(customer, formData.getStripeToken());
        log.info("updated payment source on Stripe Customer {}", customer.getId());
      }
    }
    if (customer == null) {
      CustomerCreateParams.Builder customerBuilder = stripeClient.defaultCustomerBuilder(
          formData.getCustomerName(),
          formData.getCustomerEmail(),
          formData.getStripeToken()
      );
      customer = stripeClient.createCustomer(customerBuilder);
      source = customer.getSources().getData().get(0);
      log.info("created Stripe Customer {}", customer.getId());
    }

    return new CustomerAndSource(customer, source);
  }

  protected PaymentIntent processStripe(Customer stripeCustomer, PaymentSource stripeSource, DonationFormData formData,
                               StripeClient stripeClient, Environment env) throws StripeException {
    log.info("creating Stripe payment intent");

    Map<String, String> customerMetadata = new HashMap<>();
    customerMetadata.put("account_id", formData.getCrmAccountId());
    customerMetadata.put("contact_id", formData.getCrmContactId());
    if (formData.getCustomMetadataCustomer() != null) {
      customerMetadata.putAll(formData.getCustomMetadataCustomer());
    }
    // TODO: only if needed
//    stripeClient.updateCustomer(
//        stripeCustomer,
//        customerMetadata
//    );

    String currency = formData.getCurrency();
    if (Strings.isNullOrEmpty(currency)) {
      currency = env.getConfig().currency;
    }

    PaymentIntent paymentIntent = null;
    if (formData.isRecurring()) {
      // Subscription
      // TODO: check frequency and support monthly vs. quarterly vs. yearly?
      Map<String, String> subscriptionMetadata = new HashMap<>();
      subscriptionMetadata.put("campaign", formData.getCampaignId());
      subscriptionMetadata.put("description", formData.getNotes());
      if (formData.getCustomMetadataSubscription() != null) {
        subscriptionMetadata.putAll(formData.getCustomMetadataSubscription());
      }
      ProductCreateParams.Builder productBuilder = stripeClient.defaultProductBuilder(stripeCustomer, formData.getAmountInCents(), currency);
      PlanCreateParams.Builder planBuilder = stripeClient.defaultPlanBuilder(formData.getAmountInCents(), currency);
      SubscriptionCreateParams.Builder subscriptionBuilder = stripeClient.defaultSubscriptionBuilder(stripeCustomer, stripeSource)
          .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
          .setMetadata(subscriptionMetadata);
      Subscription subscription = stripeClient.createSubscription(productBuilder, planBuilder, subscriptionBuilder);
      log.info("created Subscription {}", subscription.getId());

      Invoice invoice = stripeClient.getInvoice(subscription.getLatestInvoice());
      log.info("got latest Invoice {}", invoice.getId());

      paymentIntent = stripeClient.getPaymentIntent(invoice.getPaymentIntent());
      log.info("got Payment Intent {}", invoice.getPaymentIntent());

    } else {
      // One-time payment
      Map<String, String> paymentIntentMetadata = new HashMap<>();
      paymentIntentMetadata.put("campaign", formData.getCampaignId());
      if (formData.getCustomMetadataPaymentIntent() != null) {
        paymentIntentMetadata.putAll(formData.getCustomMetadataPaymentIntent());
      }

      PaymentIntentCreateParams.Builder paymentIntentCreateParamsBuilder = stripeClient.defaultPaymentIntentBuilder(
          formData.getAmountInCents(), formData.getCurrency()
      );
      paymentIntentCreateParamsBuilder
          .setCustomer(stripeCustomer.getId())
          .setDescription(formData.getNotes())
          .putAllMetadata(paymentIntentMetadata);

      paymentIntent = stripeClient.createPaymentIntent(paymentIntentCreateParamsBuilder);
      log.info("created Payment Intent {}", paymentIntent.getId());
    }
    return paymentIntent;
  }

}
