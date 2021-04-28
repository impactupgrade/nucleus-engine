package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.DonationSpringFormData;
import com.impactupgrade.nucleus.util.EmailUtil;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.PaymentSource;
import com.stripe.model.Subscription;
import com.stripe.param.CustomerCreateParams;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.Query;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Path("/donationspring")
public class DonationSpringController {

  private static final Logger log = LogManager.getLogger(DonationSpringController.class);

  private final Environment env;
  // TODO: Eventually, Hibernate logic should be pulled into a service/dao paradigm. But hold off until we see how
  // else it's needed. For now, just keep this self-contained.
  private final SessionFactory sessionFactory;

  public DonationSpringController(Environment env) {
    this.env = env;

    // Automatically pulls in hibernate.properties
    final Configuration configuration = new Configuration();
    configuration.addAnnotatedClass(Organization.class);
    sessionFactory = configuration.buildSessionFactory(new StandardServiceRegistryBuilder().build());
  }

  @Path("/{apikey}/ds.js")
  @GET
  @Produces("text/javascript")
  public Response js(@PathParam("apikey") String apiKey, @Context HttpServletRequest request) throws IOException {
    try (
      InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("static/ds/js/ds.js")
    ) {
      Organization org = getOrganization(apiKey);
      if (org == null) {
        return Response.status(400).entity("Invalid Donation Spring API key!").build();
      }
      if (org.isDeactivated()) {
        return Response.status(400).entity("This Donation Spring account has been deactivated. If this is in error, please contact us!").build();
      }

      String dsJs = IOUtils.toString(inputStream, StandardCharsets.UTF_8.name());
      dsJs = dsJs.replace("REPLACE_ME_DS_API_KEY", org.getApiKey());
      dsJs = dsJs.replace("REPLACE_ME_STRIPE_PUBLIC_KEY", org.getPaymentGatewayPublicKey());

      return Response.ok().entity(dsJs).build();
    }
  }

  @Path("/{apikey}/donate")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response donate(
      @PathParam("apikey") String apiKey,
      @BeanParam DonationSpringFormData formData,
      @Context HttpServletRequest request
  ) {
    // TODO: REMOVE AFTER INITIAL LAUNCH TESTS
    log.info(formData.toStringFull());
//    log.info(formData);

    // TODO: temporary, looking for more anti-fraud measures
    StringBuilder headers = new StringBuilder();
    Enumeration<String> headerNames = request.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      String headerName = headerNames.nextElement();
      String headerValue = request.getHeader(headerName);
      headers.append(headerName).append("=").append(headerValue).append(" ");
    }
    log.info("HEADERS: " + headers);

    try {
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // Sanity checks to block bots.
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

      if (formData.isFraudAttempt()) {
        // Return something meaningful, just in case it's actually a human, but not detailed to the point where
        // it tips off the nefarious person/bot to the specific issue we detected.
        return Response.status(400).entity("Donation blocked as possible spam. If this is in error, please try again or contact us!").build();
      }

      // TODO: recaptcha

      Organization org = getOrganization(apiKey);
      if (org == null) {
        return Response.status(400).entity("Invalid Donation Spring API key!").build();
      }
      if (org.isDeactivated()) {
        return Response.status(400).entity("This Donation Spring account has been deactivated. If this is in error, please contact us!").build();
      }

      if (Strings.isNullOrEmpty(formData.getStripeToken())) {
        return Response.status(400).entity("We were unable to process your donation. Try again?").build();
      }

      StripeClient stripeClient = new StripeClient(org.getPaymentGatewaySecretKey());

      // Create/update the Customer with the payment source, first. For fraud attempts, adding a source to a Customer is
      // when Stripe validates the card. Fail as early as possible, preventing Radar hits!
      Customer stripeCustomer;
      PaymentSource stripePaymentSource;
      Optional<Customer> existingStripeCustomer = stripeClient.getCustomerByEmail(formData.getCustomerEmail());
      if (existingStripeCustomer.isPresent()) {
        stripeCustomer = existingStripeCustomer.get();
        stripePaymentSource = stripeClient.updateCustomerSource(stripeCustomer, formData.getStripeToken());
      } else {
        stripeCustomer = createStripeCustomer(formData, stripeClient);
        stripePaymentSource = stripeCustomer.getSources().getData().get(0);
      }

      processStripe(stripeCustomer, stripePaymentSource, formData, org, stripeClient);

      // To save a bit of response time, spin these off to a new thread.
      Runnable thread = () -> {
        try {
          EmailUtil.sendEmail(org.getDonorEmailSubject(), null,
              org.getDonorEmailBody() + "<br/><br/>" + formData.toStringEmail(org), formData.getEmail(), "info@donationspring.com");

          EmailUtil.sendEmail("New Transaction", null, formData.toStringEmail(org), org.getNotificationEmail(), "info@donationspring.com");
        } catch (Exception e) {
          log.error("failed to send donation emails", e);
        }
      };
      new Thread(thread).start();
    } catch (StripeException e) {
      log.warn("Stripe failed to process the donation", e);
      return Response.status(400).entity(e.getMessage()).build();
    } catch (Exception e) {
      log.error("failed to process the donation", e);
      return Response.status(400).entity("We were unable to process your donation. Try again? If this continues, please contact us!").build();
    }

    return Response.status(200).build();
  }

  private Organization getOrganization(String apiKey) {
    final Session s = sessionFactory.openSession();
    s.getTransaction().begin();
    Query q = s.createQuery("from Organization o where o.apiKey = :apiKey");
    q.setParameter("apiKey", apiKey);
    Organization org = (Organization) q.getSingleResult();
    s.getTransaction().commit();
    return org;
  }

  private Customer createStripeCustomer(DonationSpringFormData formData, StripeClient stripeClient) throws StripeException {
    Map<String, String> customerMetadata = new HashMap<>();
    put(customerMetadata, "donate_as_business", formData.getDonateAsBusiness());
    put(customerMetadata, "first_name", formData.getFirstName());
    put(customerMetadata, "last_name", formData.getLastName());
    put(customerMetadata, "email", formData.getEmail());
    put(customerMetadata, "phone", formData.getPhone());
    put(customerMetadata, "business_name", formData.getBusinessName());
    put(customerMetadata, "business_email", formData.getBusinessEmail());
    put(customerMetadata, "business_address", formData.getBusinessAddress());
    put(customerMetadata, "business_address_2", formData.getBusinessAddress2());
    put(customerMetadata, "business_city", formData.getBusinessCity());
    put(customerMetadata, "business_state", formData.getBusinessState());
    put(customerMetadata, "business_zip", formData.getBusinessZip());
    put(customerMetadata, "business_country", formData.getBusinessCountryFullName());
    put(customerMetadata, "billing_address", formData.getBillingAddress());
    put(customerMetadata, "billing_address_2", formData.getBillingAddress2());
    put(customerMetadata, "billing_city", formData.getBillingCity());
    put(customerMetadata, "billing_state", formData.getBillingState());
    put(customerMetadata, "billing_zip", formData.getBillingZip());
    put(customerMetadata, "billing_country", formData.getBillingCountryFullName());

    CustomerCreateParams customerParams = CustomerCreateParams.builder()
        .setName(formData.getCustomerName())
        // Important to use the name as the description! Allows the Subscriptions list to display customers
        // by-name, otherwise it's limited to email.
        .setDescription(formData.getCustomerName())
        .setEmail(formData.getCustomerEmail())
        .setSource(formData.getStripeToken())
        .setMetadata(customerMetadata)
        .addExpand("sources")
        .build();

    Customer customer = stripeClient.createCustomer(customerParams);
    log.info("created Stripe Customer {}", customer.getId());
    return customer;
  }

  private void processStripe(Customer stripeCustomer, PaymentSource stripeSource, DonationSpringFormData formData,
      Organization org, StripeClient stripeClient) throws StripeException {
    log.info("processing payment with Stripe");

    Map<String, String> metadata = new HashMap<>();
    put(metadata, "fund", formData.getFund());
    put(metadata, "notes", formData.getNotes());

    if (formData.isRecurring()) {
      Subscription subscription = stripeClient.createSubscription(
          stripeCustomer,
          stripeSource,
          formData.getAmountInCents(),
          org.getPaymentGatewayCurrency(),
          formData.getNotes(),
          metadata
      );
      log.info("created Stripe Subscription {}", subscription.getId());
    } else {
      Charge charge = stripeClient.createCharge(
          stripeCustomer,
          stripeSource,
          formData.getAmountInCents(),
          org.getPaymentGatewayCurrency(),
          formData.getNotes(),
          metadata
      );
      log.info("created Stripe Charge {}", charge.getId());
    }
  }

  // Stripe doesn't like empty strings
  private <K, V> void put(Map<K, V> map, K key, V value) {
    if (value instanceof String) {
      map.put(key, Strings.isNullOrEmpty((String) value) ? null : value);
    } else {
      map.put(key, value);
    }
  }
}
