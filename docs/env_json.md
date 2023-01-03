# Environment JSON

TODO: Overview

## Full Schema

Note that **all** environments start with this default:
https://github.com/impactupgrade/nucleus-engine/blob/master/src/main/resources/environment-default.json

```json
{
  "apiKey": "abc123",

  "crmPrimary": "salesforce",
  "crmDonations": "salesforce",
  "crmMessaging": "hubspot",
  
  "emailTransactional": "sendgrid",
  
  "TEMPLATE FOR ANY CRM": {
    AUTH SECTION

    "fieldDefinitions": {
      "paymentGatewayName": "",
      "paymentGatewayTransactionId": "",
      "paymentGatewayCustomerId": "",
      "paymentGatewaySubscriptionId": "",
      "paymentGatewayRefundId": "",
      "paymentGatewayRefundDate": "",
      "paymentGatewayRefundDepositId": "",
      "paymentGatewayRefundDepositDate": "",
      "paymentGatewayDepositId": "",
      "paymentGatewayDepositDate": "",
      "paymentGatewayDepositNetAmount": "",
      "paymentGatewayDepositFee": "",
      "fund": "",
      "emailOptIn": "",
      "emailOptOut": "",
      "smsOptIn": "",
      "smsOptOut": "",
      "contactLanguage": ""
    },
    "customQueryFields": {
      "account": [],
      "campaign": [],
      "contact": [],
      "donation": [],
      "recurringDonation": [],
      "user": []
    }
  },

  "hubspot": {
    "secretKey": "Private App Key",
    "portalId": "123",
    "enableRecurring": true,

    "donationPipeline": {
      "id": "123",
      "successStageId": "123",
      "failedStageId": "123",
      "refundedStageId": "123"
    },
    "recurringDonationPipeline": {
      "id": "123",
      "openStageId": "123",
      "closedStageId": "123"
    },
    "fieldDefinitions": {
      START WITH THE TEMPLATE's FIELD DEFINITIONS -- NEED THE API ID FOR EACH, WHICH WILL LOOK LIKE:
      "paymentGatewayName": "payment_gateway_name",
      
      CUSTOM DEFINITIONS
              
      "recurringDonationDealId": "abc123",
      "recurringDonationFrequency": "abc123",
      "recurringDonationRealAmount": "abc123",
      "paymentGatewayAmountOriginal": "abc123",
      "paymentGatewayAmountOriginalCurrency": "abc123",
      "paymentGatewayAmountExchangeRate": "abc123"
    },
    "customQueryFields": {
      BY DEFAULT, INCLUDE ALL CUSTOM FIELDS FROM THE ABOVE DEFINITIONS
      
      "company": ["abc_123"],
      "contact": ["abc_123"],
      "deal": ["abc_123"]
    },
    
    "defaultSmsOptInList": "123"
  },

  "salesforce": {
    "username": "abc@123.com",
    "password": "password+token",
    "url": "abc123.my.salesforce.com",
    "npsp": true,

    "fieldDefinitions": {
      START WITH THE TEMPLATE's FIELD DEFINITIONS, BUT AUTO PROVISIONING PROVIDES THESE DEFAULTS
      
      "paymentGatewayName": "Payment_Gateway_Name__c",
      "paymentGatewayTransactionId": "Payment_Gateway_Transaction_ID__c",
      "paymentGatewayCustomerId": "Payment_Gateway_Customer_ID__c",
      "paymentGatewaySubscriptionId": "Payment_Gateway_Subscription_ID__c",
      "paymentGatewayRefundId": "Payment_Refund_ID__c",
      "paymentGatewayRefundDate": "Payment_Gateway_Refund_Date__c",
      "paymentGatewayRefundDepositId": "Payment_Gateway_Refund_Deposit_ID__c",
      "paymentGatewayRefundDepositDate": "Payment_Gateway_Refund_Deposit_Date__c",
      "paymentGatewayDepositId": "Payment_Gateway_Deposit_ID__c",
      "paymentGatewayDepositDate": "Payment_Gateway_Deposit_Date__c",
      "paymentGatewayDepositNetAmount": "Payment_Gateway_Deposit_Net__c",
      "paymentGatewayDepositFee": "Payment_Gateway_Deposit_Fee__c",
      "fund": "",
      "emailOptIn": "",
      "emailOptOut": "HasOptedOutOfEmail",
      "smsOptIn": "",
      "smsOptOut": "",
      "contactLanguage": "",
      
      "accountTypeToRecordTypeIds": {
        "HOUSEHOLD": "Account RecordType ID",
        "ORGANIZATION": "Account RecordType ID"
      },
      
      "paymentEventTypeToRecordTypeIds": {
        "DONATION": "Opportunity RecordType ID",
        "TICKET": "Opportunity RecordType ID"
      }
    },
    "customQueryFields": {
      BY DEFAULT, INCLUDE ALL CUSTOM FIELDS FROM THE ABOVE DEFINITIONS
      ORGS WITH CUSTOM CODE ALSO HAVE TO INCLUDE FIELDS THEY CARE ABOUT SO THEY'RE INCLUDED IN SOQL QUERIES AND AVAILABLE FOR USE
      
      "account": ["First_Donation_Date__c", "Total_Donations__c", "Total_Tax_Deductible_Gifts_This_Year__c", "Total_Tax_Deductible_Gifts_Last_Year__c", "Total_Tax_Deductible_Gifts_2_Years_Ago__c", "Custom_Informal_Greeting__c"],
      "campaign": ["ER_Integration_Donation_Name__c", "ER_Integration_Donation_Receipt_Category__c", "ER_Integration_Donation_Record_Type__c", "ER_Integration_Donation_Send_Receipt__c", "ER_Integration_Donation_Recur_Type__c"],
      "contact": ["Household_Total_Donations__c", "account.BillingStateCode", "account.BillingCountryCode", "RecordTypeId", "Household_Has_Active_Recurring_Donation__c", "account.Revenue_Pipeline__c", "iWave_PROscores__iWave_PROscore__c", "Topic_Opt_Ins__c"],
      "donation": ["isReconciled__c", "Reconciliation_Date__c", "Stripe_Refund_ID__c", "Refund_Date__c", "Stripe_Charge_ID__c", "Stripe_Payment_Intent_ID__c", "PaymentSpring_Transaction_Id__c", "Paypal_Transaction_Id__c", "Payment_Type__c", "Email__c", "Printed_Emailed__c", "Receipt_Category__c", "Campaign_Name__c", "Deposit_Date__c", "Deposit_ID__c", "Deposit_Net__c", "Deposit_Fee__c", "LeadSource", "FRU_Donation_ID__c", "FRU_Campaign_Name__c"],
      "recurringDonation": ["Stripe_Subscription_Id__c", "PaymentSpring_Subscription_Id__c", "Paypal_Subscription_Id__c", "Stripe_Customer_ID__c", "PaymentSpring_Customer_ID__c", "Paypal_Customer_ID__c", "Type__c", "Recurring_Payment_Source__c", "Recurring_Payment_Type__c", "FRU_Recurring_ID__c", "FRU_Campaign_Name__c"],
      "user": []
    },
    "defaultCampaignId": "abc123"
  },

  "virtuous": {
    VIRTUOUS IS SUPER BASIC SO FAR -- NOT USING CUSTOM FIELDS AT THE MOMENT
    
    "secretKey": "abc123"
  },

  "stripe": {
    "secretKey": "sk_live_abc123"
  },

  "raisely": {
    "username": "user@abc123.com",
    "password": "abc123",
    "stripeAppId": "ca_abc123"
  },

  "mailchimp": [
    {
      "secretKey": "abc123-us18",
      "lists": [
        {
          "id": "abc123",
          "type": "MARKETING",
          "crmFilter": "(NOT (HasOptedOutOfEmail=true OR ADV_Exclude_from_MKT_emails__c=true OR Never_Contact_in_Any_Way__c=true OR MKT_EU_GDPR_Compliance__c=true OR Shark_Donor__c=true OR X2021_PR_List__c=true OR npsp__Deceased__c=true OR HubSpot_Non_Marketing_Contact__c=true OR email like '%noemail%'))"
        },
        
        CAN PROVIDE MULTIPLE MARKETING LISTS WITH UNIQUE FILTERS, IF THE ORG HAS TRULY UNIQUE BUCKETS
      ]
    }
  ],
  
  "sendgrid": [
    {
      "secretKey": "abc123",
      "transactionalSender": true
    }
  ],

  "twilio": {
    "publicKey": "Account SID",
    "secretKey": "Secret Key",

    "senderPn": "PN or Message Service SID",

    "users": {
      "user1@abc123.com": {
        "senderPn": "+12345678900",
        "recordOwnerFilter": false
      },
      "user2@abc123.com": {
        "senderPn": "+12345678900",
        "recordOwnerFilter": true
      }
    }
  },

  "backblaze": {
    "publicKey": "abc123",
    "secretKey": "abc123",

    "bucketId": "abc123"
  },

  "notifications": {
    "donations:card-expiring": {
      "email": {
        "from": "PN or Message Service SID",
        "to": ["+12345678900","+12345678900"]
      },
      "sms": {
        "from": "noreply@abc123.com",
        "to": ["user1@abc123.com","user2@abc123.com"]
      },
      "task": {
        "assignTo": "CRM User ID"
      }
    }
  },

  "metadataKeys": {
    "account": ["account", "account_id", "sf_account", "sf_account_id"],
    "campaign": ["campaign", "campaign_id", "sf_campaign", "sf_campaign_id"],
    "fund": ["fund"],
    "contact": ["contact", "contact_id", "sf_contact", "sf_contact_id"],
    "recordType": ["sf_opp_record_type", "sf_opp_record_type_id"]
  },

  "currency": "usd"
}
```