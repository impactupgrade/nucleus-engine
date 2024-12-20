/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmContactListType;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.util.GoogleSheetsUtil;
import com.impactupgrade.nucleus.util.Utils;

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
        //  to be env.json driven if it becomes more full featured, like a single sheet positioned as a writable CRM.
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
    public List<CrmDonation> getDonationsByCustomerId(String customerId) throws Exception {
        return List.of();
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
    public List<CrmDonation> getDonations(Calendar updatedAfter) throws Exception {
        return List.of();
    }

    @Override
    public PagedResults.ResultSet<CrmContact> queryMoreContacts(String queryLocator) throws Exception {
        return null;
    }

    @Override
    public PagedResults.ResultSet<CrmAccount> queryMoreAccounts(String queryLocator) throws Exception {
        return null;
    }

    @Override
    public Map<String, String> getContactLists(CrmContactListType listType) throws Exception {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getFieldOptions(String object) throws Exception {
        return Collections.emptyMap();
    }

    @Override
    public PagedResults<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
        return new PagedResults<>();
    }

    @Override
    public PagedResults<CrmAccount> getEmailAccounts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
        return new PagedResults<>();
    }

    @Override
    public PagedResults<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
        return new PagedResults<>();
    }

    @Override
    public PagedResults<CrmContact> getDonorIndividualContacts(Calendar updatedSince) throws Exception {
        return new PagedResults<>();
    }

    @Override
    public PagedResults<CrmAccount> getDonorOrganizationAccounts(Calendar updatedSince) throws Exception {
        return new PagedResults<>();
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

        crmContact.mobilePhone = Utils.getPhoneFromMap(map);

        crmContact.crmRawObject = map;
        crmContact.fieldFetcher = map::get;
        return crmContact;
    }
}
