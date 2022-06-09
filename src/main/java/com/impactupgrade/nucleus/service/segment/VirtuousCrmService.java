package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.VirtuousClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VirtuousCrmService implements BasicCrmService {

    private static final Logger log = LogManager.getLogger(VirtuousCrmService.class);
    private static final String DATE_FORMAT = "MM/dd/yyyy";
    private static final String DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

    private VirtuousClient virtuousClient;
    protected Environment env;

    @Override
    public String name() {
        return "virtuous";
    }

    @Override
    public boolean isConfigured(Environment env) {
        return env.getConfig().virtuous != null;
    }

    @Override
    public void init(Environment env) {
        this.virtuousClient = new VirtuousClient(env);
    }

    // Contacts
    @Override
    public Optional<CrmContact> getContactById(String id) throws Exception {
        Integer contactId;
        try {
            contactId = Integer.parseInt(id);
        } catch (NumberFormatException nfe) {
            log.error("Failed to parse numeric id from string {}!", id);
            return Optional.empty();
        }
        VirtuousClient.Contact contact = virtuousClient.getContactById(contactId);
        return Optional.ofNullable(asCrmContact(contact));
    }

    @Override
    public String insertContact(CrmContact crmContact) throws Exception {
        VirtuousClient.Contact contact = asContact(crmContact);
        VirtuousClient.Contact createdContact = virtuousClient.createContact(contact);
        if (Objects.nonNull(createdContact)) {
            return createdContact.id + "";
        } else {
            return null;
        }
    }

    @Override
    public void updateContact(CrmContact crmContact) throws Exception {
        VirtuousClient.Contact updatingContact = asContact(crmContact);
        VirtuousClient.Contact existingContact = virtuousClient.getContactById(updatingContact.id);

        VirtuousClient.ContactIndividual updatingIndividual = getPrimaryContactIndividual(updatingContact);
        VirtuousClient.ContactIndividual existingIndividual = getPrimaryContactIndividual(existingContact);

        List<VirtuousClient.ContactMethod> contactMethodsToCreate = getContactMethodsToCreate(existingIndividual, updatingIndividual);
        for (VirtuousClient.ContactMethod contactMethod : contactMethodsToCreate) {
            log.info("Creating contact method...");
            VirtuousClient.ContactMethod createdContactMethod = virtuousClient.createContactMethod(contactMethod);
            if (Objects.isNull(createdContactMethod)) {
                log.error("Failed to create contact method {}/{}!", contactMethod.id, contactMethod.type);
                return;
            }
            log.info("Contact method created.");
        }

        List<VirtuousClient.ContactMethod> contactMethodsToUpdate = getContactMethodsToUpdate(existingIndividual, updatingIndividual);
        for (VirtuousClient.ContactMethod contactMethod : contactMethodsToUpdate) {
            log.info("Updating contact method...");
            if (Objects.isNull(virtuousClient.updateContactMethod(contactMethod))) {
                log.error("Failed to update contact method {}/{}!", contactMethod.id, contactMethod.type);
                return;
            }
            log.info("Contact method updated.");
        }

        List<VirtuousClient.ContactMethod> contactMethodsToDelete = getContactMethodsToDelete(existingIndividual, updatingIndividual);
        for (VirtuousClient.ContactMethod contactMethod : contactMethodsToDelete) {
            log.info("Deleting contact method...");
            if (Objects.isNull(virtuousClient.deleteContactMethod(contactMethod))) {
                log.error("Failed to delete contact method {}/{}!", contactMethod.id, contactMethod.type);
                return;
            }
            log.info("Contact method deleted.");
        }

        virtuousClient.updateContact(updatingContact);
    }

    private List<VirtuousClient.ContactMethod> getContactMethodsToCreate(VirtuousClient.ContactIndividual existing, VirtuousClient.ContactIndividual updating) {
        List<VirtuousClient.ContactMethod> toCreate = new ArrayList<>();
        for (VirtuousClient.ContactMethod updatingContactMethod : updating.contactMethods) {
            boolean contactMethodExists = existing.contactMethods.stream()
                    .filter(contactMethod -> StringUtils.equals(contactMethod.type, updatingContactMethod.type))
                    .findAny().isPresent();
            if (!contactMethodExists) {
                updatingContactMethod.contactIndividualId = existing.id;
                toCreate.add(updatingContactMethod);
            }
        }
        return toCreate;
    }

    private List<VirtuousClient.ContactMethod> getContactMethodsToUpdate(VirtuousClient.ContactIndividual existing, VirtuousClient.ContactIndividual updating) {
        for (VirtuousClient.ContactMethod existingContactMethod : existing.contactMethods) {
            for (VirtuousClient.ContactMethod updatingContactMethod : updating.contactMethods) {
                // Assuming contact individual has 1 of each type (as crmContact has)
                if (StringUtils.equals(existingContactMethod.type, updatingContactMethod.type)) {
                    existingContactMethod.value = updatingContactMethod.value;
                    existingContactMethod.isOptedIn = updatingContactMethod.isOptedIn;
                    existingContactMethod.isPrimary = updatingContactMethod.isPrimary;
                    existingContactMethod.canBePrimary = updatingContactMethod.canBePrimary;
                }
            }
        }
        return existing.contactMethods;
    }

    private List<VirtuousClient.ContactMethod> getContactMethodsToDelete(VirtuousClient.ContactIndividual existing, VirtuousClient.ContactIndividual updating) {
        List<VirtuousClient.ContactMethod> toDelete = new ArrayList<>();
        for (VirtuousClient.ContactMethod existingContactMethod : existing.contactMethods) {
            boolean updatingContactMethod = updating.contactMethods.stream()
                    .filter(contactMethod -> StringUtils.equals(contactMethod.type, existingContactMethod.type))
                    .findAny().isPresent();
            if (!updatingContactMethod) {
                toDelete.add(existingContactMethod);
            }
        }
        return toDelete;
    }

    @Override
    public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) {
        List<VirtuousClient.QueryCondition> conditions = new ArrayList<>();
//        if (!Strings.isNullOrEmpty(firstName)) {
//            conditions.add(queryCondition("First Name", "Is", firstName));
//        }
//        if (!Strings.isNullOrEmpty(lastName)) {
//            conditions.add(queryCondition("Last Name", "Is", lastName));
//        }
        if (!Strings.isNullOrEmpty(contactSearch.email)) {
            conditions.add(queryCondition("Email Address", "Is", contactSearch.email));
        }
        if (!Strings.isNullOrEmpty(contactSearch.phone)) {
            conditions.add(queryCondition("Phone Number", "Is", contactSearch.phone));
        }
//        if (!Strings.isNullOrEmpty(address)) {
//            conditions.add(queryCondition("Address Line 1", "Is", address));
//        }
        VirtuousClient.ContactQuery contactQuery = contactQuery(conditions);
        List<CrmContact> contacts = virtuousClient.queryContacts(contactQuery).stream().map(this::asCrmContact).collect(Collectors.toList());
        return PagedResults.getPagedResultsFromCurrentOffset(contacts, contactSearch);
    }

    private VirtuousClient.ContactQuery contactQuery(List<VirtuousClient.QueryCondition> queryConditions) {
        VirtuousClient.QueryConditionGroup group = new VirtuousClient.QueryConditionGroup();
        group.conditions = queryConditions;
        VirtuousClient.ContactQuery query = new VirtuousClient.ContactQuery();
        //query.queryLocation = null; // TODO: decide if we need this param
        query.groups = List.of(group);
        query.sortBy = "Last Name";
        query.descending = false;
        return query;
    }

    private VirtuousClient.QueryCondition queryCondition(String parameter, String operator, String value) {
        VirtuousClient.QueryCondition queryCondition = new VirtuousClient.QueryCondition();
        queryCondition.parameter = parameter;
        queryCondition.operator = operator;
        queryCondition.value = value;
        return queryCondition;
    }

    // TODO: move to a mapper class?
    private CrmContact asCrmContact(VirtuousClient.Contact contact) {
        if (Objects.isNull(contact)) {
            return null;
        }
        CrmContact crmContact = new CrmContact();
        crmContact.id = contact.id + "";
        //crmContact.accountId = // ?
        VirtuousClient.ContactIndividual contactIndividual = getPrimaryContactIndividual(contact);
        crmContact.firstName = contactIndividual.firstName;
        crmContact.lastName = contactIndividual.lastName;
        crmContact.fullName = contact.name;

        Optional<VirtuousClient.ContactMethod> emailContactMethodOptional = getContactMethod(contactIndividual, "Home Email");
        if (emailContactMethodOptional.isPresent()) {
            crmContact.email = emailContactMethodOptional.get().value;
            crmContact.emailOptIn = emailContactMethodOptional.get().isOptedIn;
        }

        crmContact.homePhone = getContactMethodValue(contactIndividual, "Home Phone");
        crmContact.mobilePhone = getContactMethodValue(contactIndividual, "Mobile Phone");
        crmContact.workPhone = getContactMethodValue(contactIndividual, "Work Phone");
        crmContact.otherPhone = getContactMethodValue(contactIndividual, "Other Phone");
        //crmContact.preferredPhone = CrmContact.PreferredPhone.MOBILE // ?

        crmContact.address = getCrmAddress(contact.address);

        //crmContact.emailOptIn;
        //crmContact.emailOptOut;
        //crmContact.smsOptIn;
        //crmContact.smsOptOut;
        //crmContact.ownerId;
        //crmContact.ownerName;
        //crmContact.totalDonationAmount = contact.lifeToDateGiving; // Parse double
        // crmContact.numDonations;
        //crmContact.firstDonationDate;
        crmContact.lastDonationDate = getCalendar(contact.lastGiftDate);
        crmContact.notes = contact.description;
        //  public List<String> emailGroups;
        //  public String contactLanguage;

        return crmContact;
    }

    private VirtuousClient.ContactIndividual getPrimaryContactIndividual(VirtuousClient.Contact contact) {
        return contact.contactIndividuals.stream()
                .filter(contactIndividual -> Boolean.TRUE == contactIndividual.isPrimary)
                .findFirst().orElse(null);
    }

    private Optional<VirtuousClient.ContactMethod> getContactMethod(VirtuousClient.ContactIndividual contactIndividual, String contactMethodType) {
        return contactIndividual.contactMethods.stream()
                .filter(contactMethod -> contactMethodType.equals(contactMethod.type))
                .findFirst();
    }

    private String getContactMethodValue(VirtuousClient.ContactIndividual contactIndividual, String contactMethodType) {
        return contactIndividual.contactMethods.stream()
                .filter(contactMethod -> contactMethodType.equals(contactMethod.type))
                .findFirst()
                .map(contactMethod -> contactMethod.value).orElse(null);
    }

    private CrmAddress getCrmAddress(VirtuousClient.Address address) {
        if (Objects.isNull(address)) {
            return null;
        }
        CrmAddress crmAddress = new CrmAddress();
        crmAddress.country = address.country;
        crmAddress.state = address.state;
        crmAddress.city = address.city;
        crmAddress.postalCode = address.postal;
        crmAddress.street = address.address1;
        return crmAddress;
    }

    private Calendar getCalendar(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar;
    }

    private Calendar getCalendar(String dateString) {
        Calendar calendar = null;
        try {
            Date date = new SimpleDateFormat(DATE_TIME_FORMAT).parse(dateString);
            calendar = Calendar.getInstance();
            calendar.setTime(date);
        } catch (ParseException e) {
            log.error("Failed to parse date from string {}!", dateString);
        }
        return calendar;
    }

    private VirtuousClient.Contact asContact(CrmContact crmContact) {
        if (Objects.isNull(crmContact)) {
            return null;
        }
        VirtuousClient.Contact contact = new VirtuousClient.Contact();
        if (Objects.nonNull(crmContact.id)) {
            contact.id = Integer.parseInt(crmContact.id);
        }
        contact.name = crmContact.fullName;
        contact.isPrivate = false;
        contact.contactType =
                "Household"; // Foundation/Organization/Household ?

        contact.address = asAddress(crmContact.address);

        VirtuousClient.ContactIndividual contactIndividual = new VirtuousClient.ContactIndividual();
        contactIndividual.contactId = contact.id;
        contactIndividual.firstName = crmContact.firstName;
        contactIndividual.lastName = crmContact.lastName;
        contactIndividual.isPrimary = true;
        contactIndividual.isSecondary = false;
        contactIndividual.isDeceased = false;
        contactIndividual.contactMethods = Stream.of(
                contactMethod("Home Email", crmContact.email, true, Boolean.TRUE == crmContact.emailOptIn),
                contactMethod("Home Phone", crmContact.homePhone, crmContact.preferredPhone == CrmContact.PreferredPhone.HOME, false),
                contactMethod("Mobile Phone", crmContact.mobilePhone, crmContact.preferredPhone == CrmContact.PreferredPhone.MOBILE, false),
                contactMethod("Work Phone", crmContact.workPhone, crmContact.preferredPhone == CrmContact.PreferredPhone.WORK, false),
                contactMethod("Other Phone", crmContact.otherPhone, crmContact.preferredPhone == CrmContact.PreferredPhone.OTHER, false)
        ).filter(Objects::nonNull).collect(Collectors.toList());

        contact.contactIndividuals = List.of(contactIndividual);

        return contact;
    }

    private VirtuousClient.Address asAddress(CrmAddress crmAddress) {
        if (Objects.isNull(crmAddress)) {
            return null;
        }
        VirtuousClient.Address address = new VirtuousClient.Address();
        address.country = crmAddress.country;
        address.state = crmAddress.state;
        address.city = crmAddress.city;
        address.postal = crmAddress.postalCode;
        address.address1 = crmAddress.street;
        address.isPrimary = true; // ?
        return address;
    }

    private VirtuousClient.ContactMethod contactMethod(String type, String value, boolean isPrimary, boolean isOptedIn) {
        if (Strings.isNullOrEmpty(value)) {
            return null;
        }
        VirtuousClient.ContactMethod contactMethod = new VirtuousClient.ContactMethod();
        contactMethod.type = type;
        contactMethod.value = value;
        contactMethod.isPrimary = isPrimary;
        contactMethod.isOptedIn = isOptedIn;
        return contactMethod;
    }

    // Donations
    @Override
    public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
        // TODO: For now, safe to assume Stripe here,
        //  but might need an interface change...
        VirtuousClient.Gift gift = virtuousClient.getGiftByTransactionSourceAndId("stripe", transactionId);
        return Optional.ofNullable(asCrmDonation(gift));
    }

    @Override
    public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        VirtuousClient.Gift gift = asGift(paymentGatewayEvent);
        VirtuousClient.Gift createdGift = virtuousClient.createGift(gift);
        if (Objects.nonNull(createdGift)) {
            return createdGift.id + "";
        } else {
            return null;
        }
    }

    public void insertDonationAsync(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        VirtuousClient.GiftTransaction giftTransaction = asGiftTransaction(paymentGatewayEvent);
        virtuousClient.createGiftAsync(giftTransaction);
    }

    @Override
    public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        CrmDonation existingDonation = getDonation(paymentGatewayEvent).get();
        VirtuousClient.Gift gift = asGift(paymentGatewayEvent);
        try {
            gift.id = Integer.parseInt(existingDonation.id);
        } catch (NumberFormatException nfe) {
            log.error("Failed to parse numeric id from string {}!", existingDonation.id);
            return;
        }
        virtuousClient.updateGift(gift);
    }

    @Override
    public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        VirtuousClient.Gift gift = virtuousClient.getGiftByTransactionSourceAndId(paymentGatewayEvent.getGatewayName(), paymentGatewayEvent.getTransactionId());
        if (Objects.nonNull(gift)) {
            virtuousClient.createReversingTransaction(gift);
        }
    }

    private CrmDonation asCrmDonation(VirtuousClient.Gift gift) {
        if (Objects.isNull(gift)) {
            return null;
        }
        CrmDonation crmDonation = new CrmDonation();
        crmDonation.id = gift.id + "";
        crmDonation.name = gift.transactionSource + "/" + gift.transactionId; //?
        crmDonation.amount = gift.amount;
        crmDonation.paymentGatewayName = gift.transactionSource; // ?
        //crmDonation.status = CrmDonation.Status.SUCCESSFUL; // ?
        crmDonation.closeDate = getCalendar(gift.giftDate);
        crmDonation.crmUrl = gift.giftUrl;
        return crmDonation;
    }

    private VirtuousClient.Gift asGift(PaymentGatewayEvent paymentGatewayEvent) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return null;
        }
        VirtuousClient.Gift gift = new VirtuousClient.Gift();

        gift.contactId = paymentGatewayEvent.getCrmContact().id;
        gift.giftType = "Credit"; // ?
        gift.giftDate = new SimpleDateFormat(DATE_TIME_FORMAT).format(paymentGatewayEvent.getTransactionDate().getTime());
        gift.amount = paymentGatewayEvent.getTransactionAmountInDollars();
        gift.transactionSource = paymentGatewayEvent.getGatewayName();
        gift.transactionId = paymentGatewayEvent.getTransactionId();
        gift.isPrivate = true; // ?
        gift.isTaxDeductible = true; // ?

        return gift;
    }

    private VirtuousClient.GiftTransaction asGiftTransaction(PaymentGatewayEvent paymentGatewayEvent) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return null;
        }
        VirtuousClient.GiftTransaction giftTransaction = new VirtuousClient.GiftTransaction();

        giftTransaction.transactionSource = paymentGatewayEvent.getGatewayName(); // ?
        giftTransaction.transactionId = paymentGatewayEvent.getTransactionId(); // ?

        giftTransaction.amount = paymentGatewayEvent.getTransactionAmountInDollars() + ""; // TODO: double check if string indeed
        giftTransaction.giftDate = paymentGatewayEvent.getTransactionDate().getTime().toString();
        giftTransaction.contact = asContact(paymentGatewayEvent.getCrmContact());

        giftTransaction.recurringGiftTransactionUpdate = false; // ?
        giftTransaction.isPrivate = false; // ?
        giftTransaction.isTaxDeductible = false; // ?
        return giftTransaction;
    }

    @Override
    public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
        List<VirtuousClient.Contact> contacts = virtuousClient.getContactsModifiedAfter(updatedSince);
        if (CollectionUtils.isEmpty(contacts)) {
            return Collections.emptyList();
        }

        if (!Strings.isNullOrEmpty(filter)) {
            List<VirtuousClient.ContactIndividualShort> contactIndividuals = virtuousClient.getContactIndividuals(filter);
            if (CollectionUtils.isEmpty(contactIndividuals)) {
                return Collections.emptyList();
            }
            Set<Integer> ids = contactIndividuals.stream()
                    .map(contactIndividualShort -> contactIndividualShort.id)
                    .collect(Collectors.toSet());
            contacts = contacts.stream()
                    .filter(contact -> ids.contains(contact.id))
                    .collect(Collectors.toList());
        }

        return contacts.stream()
                .map(this::asCrmContact)
                .collect(Collectors.toList());
    }

    @Override
    public List<CrmContact> getEmailDonorContacts(Calendar updatedSince, String filter) throws Exception {
        return null;
    }

    @Override
    public double getDonationsTotal(String filter) throws Exception {
        return 0;
    }

    @Override
    public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
        // TODO:
    }

    @Override
    public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {
        //TODO:
    }

    @Override
    public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
        return null;
    }

}
