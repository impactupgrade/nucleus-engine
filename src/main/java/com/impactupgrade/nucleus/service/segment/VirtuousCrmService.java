package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.VirtuousClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.PagedResults;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VirtuousCrmService implements BasicCrmService {

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
        return !Strings.isNullOrEmpty(env.getConfig().virtuous.secretKey);
    }

    @Override
    public void init(Environment env) {
        this.env = env;
        this.virtuousClient = new VirtuousClient(env);
    }

    // Contacts
    @Override
    public Optional<CrmContact> getContactById(String id) throws Exception {
        int contactId;
        try {
            contactId = Integer.parseInt(id);
        } catch (NumberFormatException nfe) {
            env.logJobError("Failed to parse numeric id from string {}!", id);
            return Optional.empty();
        }
        VirtuousClient.Contact contact = virtuousClient.getContactById(contactId);
        return Optional.ofNullable(asCrmContact(contact));
    }

    @Override
    public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
        //Currently not implemented
        return Optional.empty();
    }

    @Override
    public Optional<CrmContact> getFilteredContactByEmail(String email, String filter) throws Exception {
        //TODO Not currently implemented
        return Optional.empty();
    }

    @Override
    public String insertContact(CrmContact crmContact) throws Exception {
        VirtuousClient.Contact contact = asContact(crmContact);
        VirtuousClient.Contact createdContact = virtuousClient.createContact(contact);
        return createdContact == null ? null : createdContact.id + "";
    }

    @Override
    public void updateContact(CrmContact crmContact) throws Exception {
        VirtuousClient.Contact updatingContact = asContact(crmContact);
        VirtuousClient.Contact existingContact = virtuousClient.getContactById(updatingContact.id);

        VirtuousClient.ContactIndividual updatingIndividual = getPrimaryContactIndividual(updatingContact);
        VirtuousClient.ContactIndividual existingIndividual = getPrimaryContactIndividual(existingContact);

        List<VirtuousClient.ContactMethod> contactMethodsToCreate = getContactMethodsToCreate(existingIndividual, updatingIndividual);
        for (VirtuousClient.ContactMethod contactMethod : contactMethodsToCreate) {
            env.logJobInfo("Creating contact method...");
            VirtuousClient.ContactMethod createdContactMethod = virtuousClient.createContactMethod(contactMethod);
            if (createdContactMethod == null) {
                env.logJobError("Failed to create contact method {}/{}!", contactMethod.id, contactMethod.type);
                return;
            }
            env.logJobInfo("Contact method created.");
        }

        List<VirtuousClient.ContactMethod> contactMethodsToUpdate = getContactMethodsToUpdate(existingIndividual, updatingIndividual);
        for (VirtuousClient.ContactMethod contactMethod : contactMethodsToUpdate) {
            env.logJobInfo("Updating contact method...");
            if (virtuousClient.updateContactMethod(contactMethod) == null) {
                env.logJobError("Failed to update contact method {}/{}!", contactMethod.id, contactMethod.type);
                return;
            }
            env.logJobInfo("Contact method updated.");
        }

        List<VirtuousClient.ContactMethod> contactMethodsToDelete = getContactMethodsToDelete(existingIndividual, updatingIndividual);
        for (VirtuousClient.ContactMethod contactMethod : contactMethodsToDelete) {
            env.logJobInfo("Deleting contact method...");
            virtuousClient.deleteContactMethod(contactMethod);
        }

        virtuousClient.updateContact(updatingContact);
    }

    private List<VirtuousClient.ContactMethod> getContactMethodsToCreate(VirtuousClient.ContactIndividual existing, VirtuousClient.ContactIndividual updating) {
        List<VirtuousClient.ContactMethod> toCreate = new ArrayList<>();
        for (VirtuousClient.ContactMethod updatingContactMethod : updating.contactMethods) {
            boolean contactMethodExists = existing.contactMethods.stream()
                    .anyMatch(contactMethod -> StringUtils.equals(contactMethod.type, updatingContactMethod.type));
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
                    .anyMatch(contactMethod -> StringUtils.equals(contactMethod.type, existingContactMethod.type));
            if (!updatingContactMethod) {
                toDelete.add(existingContactMethod);
            }
        }
        return toDelete;
    }

    @Override
    public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) {
        List<VirtuousClient.QueryCondition> conditions = new ArrayList<>();
        if (!Strings.isNullOrEmpty(contactSearch.email)) {
            conditions.add(queryCondition("Email Address", "Is", contactSearch.email));
        }
        if (!Strings.isNullOrEmpty(contactSearch.phone)) {
            // TODO: Contains?
            conditions.add(queryCondition("Phone Number", "Is", contactSearch.phone));
        }
        if (!Strings.isNullOrEmpty(contactSearch.keywords)) {
            String[] keywordSplit = contactSearch.keywords.trim().split("\\s+");
            for (String keyword : keywordSplit) {
                if (keyword.equalsIgnoreCase("and") || keyword.equalsIgnoreCase("&")) {
                    continue;
                }
                conditions.add(queryCondition("Contact Name", "Contains", keyword));
            }
        }

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
        if (contact == null) {
            return null;
        }
        CrmContact crmContact = new CrmContact();
        crmContact.id = contact.id + "";
        //crmContact.accountId = // ?
        VirtuousClient.ContactIndividual contactIndividual = getPrimaryContactIndividual(contact);
        crmContact.firstName = contactIndividual.firstName;
        crmContact.lastName = contactIndividual.lastName;

        Optional<VirtuousClient.ContactMethod> emailContactMethodOptional = getContactMethod(contactIndividual, "Home Email");
        if (emailContactMethodOptional.isPresent()) {
            crmContact.email = emailContactMethodOptional.get().value;
            crmContact.emailOptIn = emailContactMethodOptional.get().isOptedIn;
        }

        crmContact.homePhone = getContactMethodValue(contactIndividual, "Home Phone");
        crmContact.mobilePhone = getContactMethodValue(contactIndividual, "Mobile Phone");
        crmContact.workPhone = getContactMethodValue(contactIndividual, "Work Phone");
        //crmContact.preferredPhone = CrmContact.PreferredPhone.MOBILE // ?

        crmContact.mailingAddress = getCrmAddress(contact.address);

        //crmContact.emailOptIn;
        //crmContact.emailOptOut;
        //crmContact.smsOptIn;
        //crmContact.smsOptOut;
        //crmContact.ownerId;
        //crmContact.ownerName;
        //crmContact.totalDonationAmount = contact.lifeToDateGiving; // Parse double
        // crmContact.numDonations;
        //crmContact.firstDonationDate;
        crmContact.lastDonationDate = getDate(contact.lastGiftDate);
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
        CrmAddress crmAddress = new CrmAddress();

        if (address != null) {
            crmAddress.country = address.country;
            crmAddress.state = address.state;
            crmAddress.city = address.city;
            crmAddress.postalCode = address.postal;
            crmAddress.street = address.address1;
        }

        return crmAddress;
    }

    private ZonedDateTime getDateTime(String dateTimeString) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT);
        LocalDateTime localDateTime = LocalDateTime.parse(dateTimeString, dateTimeFormatter);
        return localDateTime.atZone(ZoneId.of(env.getConfig().timezoneId));
    }

    private Calendar getDate(String dateString) {
        if (!"unavailable".equalsIgnoreCase(dateString)) {
            try {
                Date date = new SimpleDateFormat(DATE_FORMAT).parse(dateString);
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(date);
                return calendar;
            } catch (ParseException e) {
                env.logJobError("Failed to parse date from string {}!", dateString);
            }
        }
        return null;
    }

    private VirtuousClient.Contact asContact(CrmContact crmContact) {
        if (crmContact == null) {
            return null;
        }
        VirtuousClient.Contact contact = new VirtuousClient.Contact();
        if (!Strings.isNullOrEmpty(crmContact.id)) {
            contact.id = Integer.parseInt(crmContact.id);
        }
        contact.name = crmContact.getFullName();
        contact.isPrivate = false;
        contact.contactType =
                "Household"; // Foundation/Organization/Household ?

        contact.contactAddresses.add(asAddress(crmContact.mailingAddress));

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
                contactMethod("Work Phone", crmContact.workPhone, crmContact.preferredPhone == CrmContact.PreferredPhone.WORK, false)
        ).filter(Objects::nonNull).collect(Collectors.toList());

        contact.contactIndividuals = List.of(contactIndividual);

        return contact;
    }

    private VirtuousClient.Address asAddress(CrmAddress crmAddress) {
        if (crmAddress == null) {
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
    public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
        // TODO: possible to query for the whole list at once?
        // TODO: For now, safe to assume Stripe here, but might need an interface change...
         List<CrmDonation> donations = new ArrayList<>();
         for (String transactionId : transactionIds) {
             VirtuousClient.Gift gift = virtuousClient.getGiftByTransactionSourceAndId("stripe", transactionId);
             if (gift != null) {
                 donations.add(asCrmDonation(gift));
             }
         }
         return donations;
    }

    @Override
    public String insertDonation(CrmDonation crmDonation) throws Exception {
        VirtuousClient.Gift gift = asGift(crmDonation);
        VirtuousClient.Gift createdGift = virtuousClient.createGift(gift);
        if (Objects.nonNull(createdGift)) {
            return createdGift.id + "";
        } else {
            return null;
        }
    }

    public void insertDonationAsync(CrmDonation crmDonation) throws Exception {
        VirtuousClient.GiftTransaction giftTransaction = asGiftTransaction(crmDonation);
        virtuousClient.createGiftAsync(giftTransaction);
    }

    @Override
    public void updateDonation(CrmDonation crmDonation) throws Exception {
        VirtuousClient.Gift gift = asGift(crmDonation);
        try {
            gift.id = Integer.parseInt(crmDonation.id);
        } catch (NumberFormatException nfe) {
            env.logJobError("Failed to parse numeric id from string {}!", crmDonation.id);
            return;
        }
        virtuousClient.updateGift(gift);
    }

    @Override
    public void refundDonation(CrmDonation crmDonation) throws Exception {
        VirtuousClient.Gift gift = virtuousClient.getGiftByTransactionSourceAndId(crmDonation.gatewayName, crmDonation.transactionId);
        if (Objects.nonNull(gift)) {
            virtuousClient.createReversingTransaction(gift);
        }
    }

    private CrmDonation asCrmDonation(VirtuousClient.Gift gift) {
        if (gift == null) {
            return null;
        }
        CrmDonation crmDonation = new CrmDonation();
        crmDonation.id = gift.id + "";
        crmDonation.name = gift.transactionSource + "/" + gift.transactionId; //?
        crmDonation.amount = Double.parseDouble(gift.amount.replace("$", ""));
        crmDonation.gatewayName = gift.transactionSource; // ?
        // TODO: Need this so that DonationService doesn't flag it as a "non-posted state". But it doesn't look like
        //  Virtuous actually has a status to even have a failed state?
        crmDonation.status = CrmDonation.Status.SUCCESSFUL;
        crmDonation.closeDate = getDateTime(gift.giftDate);
        crmDonation.crmUrl = gift.giftUrl;
        return crmDonation;
    }

    private VirtuousClient.Gift asGift(CrmDonation crmDonation) {
        if (crmDonation == null) {
            return null;
        }
        VirtuousClient.Gift gift = new VirtuousClient.Gift();

        gift.contactId = crmDonation.contact.id;
        gift.giftType = "Credit"; // ?
        gift.giftDate = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT).format(crmDonation.closeDate);
        gift.amount = crmDonation.amount + "";
        gift.transactionSource = crmDonation.gatewayName;
        gift.transactionId = crmDonation.transactionId;
        gift.isPrivate = false; // ?
        gift.isTaxDeductible = true; // ?

        return gift;
    }

    private VirtuousClient.GiftTransaction asGiftTransaction(CrmDonation crmDonation) {
        if (crmDonation == null) {
            return null;
        }
        VirtuousClient.GiftTransaction giftTransaction = new VirtuousClient.GiftTransaction();

        giftTransaction.transactionSource = crmDonation.gatewayName; // ?
        giftTransaction.transactionId = crmDonation.transactionId; // ?

        giftTransaction.amount = crmDonation.amount + ""; // TODO: double check if string indeed
        giftTransaction.giftDate = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT).format(crmDonation.closeDate);
        giftTransaction.contact = asContact(crmDonation.contact);

        giftTransaction.recurringGiftTransactionUpdate = false; // ?
        giftTransaction.isPrivate = false; // ?
        giftTransaction.isTaxDeductible = true; // ?
        return giftTransaction;
    }

    @Override
    public List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
        List<VirtuousClient.Contact> contacts = virtuousClient.getContactsModifiedAfter(updatedSince);
        if (CollectionUtils.isEmpty(contacts)) {
            return Collections.emptyList();
        }

        if (!Strings.isNullOrEmpty(communicationList.crmFilter)) {
            List<VirtuousClient.ContactIndividualShort> contactIndividuals = virtuousClient.getContactIndividuals(communicationList.crmFilter);
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
    public List<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
        List<VirtuousClient.Contact> contacts = virtuousClient.getContactsModifiedAfter(updatedSince);
        if (CollectionUtils.isEmpty(contacts)) {
            return Collections.emptyList();
        }

        if (!Strings.isNullOrEmpty(communicationList.crmFilter)) {
            List<VirtuousClient.ContactIndividualShort> contactIndividuals = virtuousClient.getContactIndividuals(communicationList.crmFilter);
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
    public List<CrmUser> getUsers() throws Exception {
        return Collections.emptyList();
    }

    @Override
    public Map<String, String> getContactLists() throws Exception {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getFieldOptions(String object) throws Exception {
        return Collections.emptyMap();
    }

    @Override
    public String insertTask(CrmTask crmTask) throws Exception {
        VirtuousClient.Task task = asTask(crmTask);
        VirtuousClient.Task createdTask = virtuousClient.createTask(task);
        return createdTask == null ? null : createdTask.id + "";
    }

    private VirtuousClient.Task asTask(CrmTask crmTask) {
        if (crmTask == null) {
            return null;
        }
        VirtuousClient.Task task = new VirtuousClient.Task();
        task.taskType = VirtuousClient.Task.Type.GENERAL;
        task.task = crmTask.subject;
        task.description = crmTask.description;
        if (crmTask.dueDate != null) {
            task.dueDateTime = new SimpleDateFormat(DATE_TIME_FORMAT).format(crmTask.dueDate.getTime());
        }
        try {
            task.contactId = Integer.parseInt(crmTask.targetId);
        } catch (NumberFormatException e) {
            env.logJobWarn("Failed to parse Integer from String '{}'!", task.contactId);
        }

        task.contact = crmTask.targetId;
        return task;
    }

    @Override
    public double getDonationsTotal(String filter) throws Exception {
        return 0;
    }

    @Override
    public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
        return null;
    }

}
