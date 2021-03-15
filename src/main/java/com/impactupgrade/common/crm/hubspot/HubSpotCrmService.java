package com.impactupgrade.common.crm.hubspot;

import com.google.common.base.Strings;
import com.impactupgrade.common.crm.CrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.model.CrmContact;
import com.impactupgrade.common.crm.model.CrmDonation;
import com.impactupgrade.common.crm.model.CrmRecurringDonation;
import com.impactupgrade.common.messaging.MessagingWebhookEvent;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;
import com.impactupgrade.integration.hubspot.builder.ContactBuilder;
import com.impactupgrade.integration.hubspot.exception.DuplicateContactException;
import com.impactupgrade.integration.hubspot.exception.HubSpotException;
import com.impactupgrade.integration.hubspot.model.Contact;
import com.impactupgrade.integration.hubspot.model.ContactArray;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

// TODO: will conflict with the hubspot branch -- also created there
public class HubSpotCrmService implements CrmSourceService, CrmDestinationService {

  private static final Logger log = LogManager.getLogger(HubSpotCrmService.class);

  // TODO: After merging the hubspot branch, move this to env.json
  private static final String DEFAULT_HUBSPOT_SMS_LIST_ID = System.getenv("HUBSPOT_SMSLISTID");

  // TODO: replace this with the new search capability, once merged with the hubspot branch
  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    ContactArray contacts = HubSpotClientFactory.client().contacts().search(phone);

    if (contacts == null || contacts.getContacts().isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(new CrmContact(contacts.getContacts().get(0).getVid() + "", null));
  }

  // TODO: replace with V3
  @Override
  public String insertContact(MessagingWebhookEvent messagingWebhookEvent) throws Exception {
    try {
      Contact contact = HubSpotClientFactory.client().contacts().insert(new ContactBuilder()
          .phone(messagingWebhookEvent.getPhone())
          .firstName(messagingWebhookEvent.getFirstName())
          .lastName(messagingWebhookEvent.getLastName())
          .email(messagingWebhookEvent.getEmail())
      );
      log.info("created HubSpot contact {}", contact.getVid());
    } catch (DuplicateContactException e) {
      // likely due to an email collision...
      log.info("contact already existed in HubSpot: {}", e.getVid());
    } catch (HubSpotException e) {
      log.error("HubSpot failed for an unknown reason: {}", e.getMessage());
    }

    return null;
  }

  // TODO: replace with V3
  @Override
  public void smsSignup(MessagingWebhookEvent messagingWebhookEvent) {
    String listId = messagingWebhookEvent.getListId();
    if (Strings.isNullOrEmpty(listId)) {
      log.info("explicit HubSpot list ID not provided; using the default {}", DEFAULT_HUBSPOT_SMS_LIST_ID);
      listId = DEFAULT_HUBSPOT_SMS_LIST_ID;
    }
    // note that HubSpot auto-prevents duplicate entries in lists
    HubSpotClientFactory.client().lists().addContactToList(Long.parseLong(listId), Long.parseLong(messagingWebhookEvent.getCrmContactId()));
    log.info("added HubSpot contact {} to list {}", messagingWebhookEvent.getCrmContactId(), listId);
  }

  // TODO: all implemented in the hubspot branch

  @Override
  public String insertAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return null;
  }

  @Override
  public String insertContact(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return null;
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return null;
  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

  }

  @Override
  public void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return null;
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(String recurringDonationId) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return Optional.empty();
  }
}
