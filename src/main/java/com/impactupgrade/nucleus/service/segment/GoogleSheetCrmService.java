package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.util.GoogleSheetsUtil;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class GoogleSheetCrmService implements BasicCrmService {

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
    public Optional<CrmContact> getContactById(String id) throws Exception {
        return Optional.empty();
    }

    @Override
    public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
        return Optional.empty();
    }

    @Override
    public Optional<CrmContact> getFilteredContactByEmail(String email, String filter) throws Exception {
        return Optional.empty();
    }

    @Override
    public List<CrmContact> getContactsFromList(String listId) throws Exception {
        // listId is assumed to be the full URL of a GSheet
        return GoogleSheetsUtil.getSheetData(listId).stream()
            .map(this::toCrmContact).collect(Collectors.toList());
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
    public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
        return Collections.emptyList();
    }

    @Override
    public String insertDonation(CrmDonation crmDonation) throws Exception {
        return null;
    }

    @Override
    public void updateDonation(CrmDonation crmDonation) throws Exception {

    }

    @Override
    public void refundDonation(CrmDonation crmDonation) throws Exception {

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
    public List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
        return null;
    }

    @Override
    public List<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
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

        crmContact.crmRawObject = map;
        crmContact.fieldFetcher = map::get;
        return crmContact;
    }
}
