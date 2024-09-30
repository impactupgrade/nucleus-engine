package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.service.segment.AccountingPlatformService;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.sforce.soap.partner.sobject.SObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AccountingServiceTest {

  protected static final String SUPPORTER_ID_FIELD_NAME = "Supporter_ID__c";

  @Mock
  private Environment environment;
  @Mock
  private CrmService donationsCrmService;
  @Mock
  private AccountingPlatformService accountingPlatformService;
  @Captor
  ArgumentCaptor<List<CrmContact>> contactsCaptor;

  @InjectMocks
  private AccountingService unit;

  private CrmDonation crmDonation;
  private CrmAccount crmAccount;
  private CrmContact crmContact;

  @BeforeEach
  public void beforeEach() throws Exception {
    String firstname = "Tom";
    String lastname = "Ford";
    crmAccount = new CrmAccount();
    crmAccount.id = "123";
    crmAccount.name = firstname + " " + lastname;

    SObject crmAccountRawObject = new SObject();
    crmAccountRawObject.setField(SUPPORTER_ID_FIELD_NAME, "supporter987");
    crmAccount.crmRawObject = crmAccountRawObject;

    CrmAddress crmAddress = new CrmAddress();
    crmAddress.street = "1st";
    crmAccount.billingAddress = crmAddress;

    crmContact = new CrmContact();
    crmContact.id = "234";
    crmContact.email = "tf@email.com";
    crmContact.mobilePhone = "911-922-1234";
    SObject crmContactRawObject = new SObject();
    crmContactRawObject.setField(SUPPORTER_ID_FIELD_NAME, "supporter876");
    crmAccount.crmRawObject = crmContactRawObject;

    crmDonation = new CrmDonation();
    crmDonation.account = crmAccount;
    crmDonation.contact = crmContact;

    when(environment.donationsCrmService()).thenReturn(donationsCrmService);
    lenient().when(donationsCrmService.getAccountById(eq(crmAccount.id))).thenReturn(Optional.of(crmAccount));
    lenient().when(donationsCrmService.getContactById(eq(crmContact.id))).thenReturn(Optional.of(crmContact));
    when(environment.accountingPlatformService()).thenReturn(Optional.of(accountingPlatformService));

    unit = new AccountingService(environment);
  }

  @Test
  public void getDonationContact_noAccount() throws Exception {
    crmDonation.account.id = null;

    unit.processTransaction(crmDonation);

    verify(accountingPlatformService).updateOrCreateContacts(contactsCaptor.capture());
    assertEquals(contactsCaptor.getValue().get(0), crmContact);
  }

  @Test
  public void getDonationContact_householdAccount() throws Exception {
    crmAccount.recordType = EnvironmentConfig.AccountType.HOUSEHOLD;

    unit.processTransaction(crmDonation);

    verify(accountingPlatformService).updateOrCreateContacts(contactsCaptor.capture());
    assertEquals(contactsCaptor.getValue().get(0), crmContact);
  }

  @Test
  public void getDonationContact_organizationAccount() throws Exception {
    crmAccount.recordType = EnvironmentConfig.AccountType.ORGANIZATION;

    unit.processTransaction(crmDonation);

    verify(accountingPlatformService).updateOrCreateContacts(contactsCaptor.capture());
    List<CrmContact> contacts = contactsCaptor.getValue();
    CrmContact contact = contacts.get(0);
    assertNotNull(contact);
    assertEquals(contact.firstName, crmAccount.name.split("\\s")[0]);
    assertEquals(contact.lastName, crmAccount.name.split("\\s")[1]);
    assertEquals(contact.email, crmContact.email);
    assertEquals(contact.mobilePhone, crmContact.mobilePhone);
    assertEquals(contact.mailingAddress, crmAccount.billingAddress);
    assertEquals(contact.crmRawObject, crmAccount.crmRawObject);
  }
}
