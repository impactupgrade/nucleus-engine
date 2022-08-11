package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.util.GoogleSheetsUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class GoogleSheetCrmService implements BasicCrmService {

    private static final Logger log = LogManager.getLogger(GoogleSheetCrmService.class);

    protected Environment env;

    @Override
    public String name() {
        return "googlesheet";
    }

    @Override
    public boolean isConfigured(Environment env) {
        // TODO: For now, this service is purely on-demand, for tools like SMS Blast. In the future, it might need
        //  to be env.json driven if it becomes more full featured.
        return true;
    }

    @Override
    public void init(Environment env) {
        this.env = env;
    }

    @Override
    public Optional<CrmAccount> getAccountByName(String name) throws Exception {
        return Optional.empty();
    }

    @Override
    public Optional<CrmContact> getContactById(String id) throws Exception {
        return Optional.empty();
    }

    @Override
    public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
        return Optional.empty();
    }

    @Override
    public List<CrmContact> getContactsByIds(List<String> ids) throws Exception {
        return BasicCrmService.super.getContactsByIds(ids);
    }

    @Override
    public List<CrmContact> getContactsFromList(String listId) throws Exception {
        // listId is assumed to be the full URL of a GSheet
        return GoogleSheetsUtil.getSheetData(listId).stream()
            .map(this::toCrmContact).collect(Collectors.toList());
    }

    @Override
    public Optional<CrmDonation> getDonationById(String id) throws Exception {
        return Optional.empty();
    }

    @Override
    public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) throws Exception {
        return null;
    }

    @Override
    public String insertContact(CrmContact crmContact) throws Exception {
        return null;
    }

    @Override
    public void updateContact(CrmContact crmContact) throws Exception {

    }

    @Override
    public void batchUpdateContact(CrmContact crmContact) throws Exception {
        BasicCrmService.super.batchUpdateContact(crmContact);
    }

    @Override
    public void batchFlush() throws Exception {
        BasicCrmService.super.batchFlush();
    }

    @Override
    public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
        return Optional.empty();
    }

    @Override
    public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
        return BasicCrmService.super.getDonationsByTransactionIds(transactionIds);
    }

    @Override
    public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
        return Optional.empty();
    }

    @Override
    public String insertAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        return BasicCrmService.super.insertAccount(paymentGatewayEvent);
    }

    @Override
    public String insertContact(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        return BasicCrmService.super.insertContact(paymentGatewayEvent);
    }

    @Override
    public Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        return BasicCrmService.super.getDonation(paymentGatewayEvent);
    }

    @Override
    public List<CrmDonation> getDonations(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception {
        return BasicCrmService.super.getDonations(paymentGatewayEvents);
    }

    @Override
    public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        return null;
    }

    @Override
    public String insertDonation(CrmDonation donation) throws Exception {
        return null;
    }

    @Override
    public void updateDonation(CrmDonation donation) throws Exception {

    }

    @Override
    public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

    }

    @Override
    public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

    }

    @Override
    public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        return BasicCrmService.super.getRecurringDonation(paymentGatewayEvent);
    }

    @Override
    public String insertRecurringDonation(CrmRecurringDonation recurringDonation) throws Exception {
        return null;
    }

    @Override
    public void updateRecurringDonation(CrmRecurringDonation recurringDonation) throws Exception {

    }

    @Override
    public String insertContact(OpportunityEvent opportunityEvent) throws Exception {
        return BasicCrmService.super.insertContact(opportunityEvent);
    }

    @Override
    public void updateContact(OpportunityEvent opportunityEvent) throws Exception {
        BasicCrmService.super.updateContact(opportunityEvent);
    }

    @Override
    public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
        return null;
    }

    @Override
    public double getDonationsTotal(String filter) throws Exception {
        return 0;
    }

    @Override
    public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
        return null;
    }

    protected CrmContact toCrmContact(Map<String, String> map) {
        CrmContact crmContact = new CrmContact();

        // TODO: This is crazy. Object mapper framework with flexibility?
        if (!Strings.isNullOrEmpty(map.get("Mobile Phone Number")))
            crmContact.mobilePhone = map.get("Mobile Phone Number");
        else if (!Strings.isNullOrEmpty(map.get("Mobile Phone")))
            crmContact.mobilePhone = map.get("Mobile Phone");
        else if (!Strings.isNullOrEmpty(map.get("Primary Phone Number")))
            crmContact.mobilePhone = map.get("Primary Phone Number");
        else if (!Strings.isNullOrEmpty(map.get("Primary Phone")))
            crmContact.mobilePhone = map.get("Primary Phone");
        else if (!Strings.isNullOrEmpty(map.get("Phone Number")))
            crmContact.mobilePhone = map.get("Phone Number");
        else if (!Strings.isNullOrEmpty(map.get("Phone")))
            crmContact.mobilePhone = map.get("Phone");

        crmContact.rawObject = map;
        crmContact.fieldFetcher = map::get;
        return crmContact;
    }
}
