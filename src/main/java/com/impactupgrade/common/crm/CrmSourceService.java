package com.impactupgrade.common.crm;

import com.impactupgrade.common.crm.model.CrmCampaign;
import com.impactupgrade.common.crm.model.CrmContact;
import com.impactupgrade.common.crm.model.CrmDonation;
import com.impactupgrade.common.crm.model.CrmRecurringDonation;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;

import java.util.Optional;

public interface CrmSourceService {

  Optional<CrmCampaign> getCampaignByIdOrDefault(String campaignId) throws Exception;

  Optional<CrmContact> getContactByEmail(String email) throws Exception;

  Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;

  Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
}
