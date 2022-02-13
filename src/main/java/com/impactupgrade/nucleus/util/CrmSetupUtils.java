package com.impactupgrade.nucleus.util;

import com.impactupgrade.nucleus.client.SfdcMetadataClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.sforce.soap.metadata.FieldType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// TODO: This is a start of what could become Portal endpoints for auto-provisioning CRMs for Nucleus integration.
//  At the moment, it's limited to creating all custom fields using default names. But more granular control is needed.
public class CrmSetupUtils {

  private static final Logger log = LogManager.getLogger(CrmSetupUtils.class.getName());

  public void setupSfdcCustomFields(Environment env) {
    SfdcMetadataClient sfdcMetadataClient = new SfdcMetadataClient(env);
    try {
      sfdcMetadataClient.createCustomField("Opportunity", "Payment_Gateway_Name__c", "Payment Gateway Name", FieldType.Text, 100);
      sfdcMetadataClient.createCustomField("Opportunity", "Payment_Gateway_Transaction_ID__c", "Payment Gateway Transaction ID", FieldType.Text, 100);
      sfdcMetadataClient.createCustomField("Opportunity", "Payment_Gateway_Customer_ID__c", "Payment Gateway Customer ID", FieldType.Text, 100);
      sfdcMetadataClient.createCustomField("npe03__Recurring_Donation__c", "Payment_Gateway_Customer_ID__c", "Payment Gateway Customer ID", FieldType.Text, 100);
      sfdcMetadataClient.createCustomField("npe03__Recurring_Donation__c", "Payment_Gateway_Subscription_ID__c", "Payment Gateway Subscription ID", FieldType.Text, 100);
      sfdcMetadataClient.createCustomField("Opportunity", "Payment_Gateway_Deposit_ID__c", "Payment Gateway Deposit ID", FieldType.Text, 100);
      sfdcMetadataClient.createCustomField("Opportunity", "Payment_Gateway_Deposit_Date__c", "Payment Gateway Deposit Date", FieldType.Date, null);
      sfdcMetadataClient.createCustomField("Opportunity", "Payment_Gateway_Deposit_Net_Amount__c", "Payment Gateway Deposit Net Amount", FieldType.Currency, null);
      sfdcMetadataClient.createCustomField("Opportunity", "Payment_Gateway_Deposit_Fee__c", "Payment Gateway Deposit Fee", FieldType.Currency, null);
    } catch (Exception e) {
      log.error("failed to create custom fields", e);
    }
  }
}