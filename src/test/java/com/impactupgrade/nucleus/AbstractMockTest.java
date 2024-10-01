/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.client.TwilioClient;
import com.impactupgrade.nucleus.dao.HibernateUtil;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.service.logic.ContactService;
import com.impactupgrade.nucleus.service.logic.DonationService;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.twilio.rest.api.v2010.account.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
public abstract class AbstractMockTest {

  protected static final ObjectMapper MAPPER = new ObjectMapper();

  // TODO: Do these need reset after each test method, or does Mockito/Junit do that automatically?
  @Mock protected ContactService contactServiceMock;
  @Mock protected DonationService donationServiceMock;
  @Mock protected CrmService crmServiceMock;
  @Mock protected SfdcClient sfdcClientMock;
  @Mock protected StripeClient stripeClientMock;
  @Mock protected TwilioClient twilioClientMock;

  public class DefaultEnvironment extends Environment {

    @Override
    public EnvironmentConfig getConfig() {
      EnvironmentConfig envConfig = new EnvironmentConfig();
      envConfig.apiKey = "abc123";
      return envConfig;
    }

    @Override
    public CrmService crmService(String name) {
      return crmServiceMock;
    }
    @Override
    public CrmService primaryCrmService() {
      return crmServiceMock;
    }

    @Override
    public ContactService contactService() {
      return contactServiceMock;
    }

    @Override
    public DonationService donationService() {
      return donationServiceMock;
    }

    @Override
    public SfdcClient sfdcClient() {
      return sfdcClientMock;
    }

    @Override
    public SfdcClient sfdcClient(String username, String password, boolean isSandbox) {
      return sfdcClientMock;
    }

    @Override
    public StripeClient stripeClient() {
      return stripeClientMock;
    }

    @Override
    public TwilioClient twilioClient() {
      return twilioClientMock;
    }
  }

  @BeforeEach
  public void beforeEach() {
    Message message = mock(Message.class);
    lenient().when(twilioClientMock.sendMessage(any(), any(), any(), any(), any())).thenReturn(message);
  }

  // Rebuild the SessionFactory each time, letting hibernate.hbm2ddl.auto create-drop (see hibernate.properties)
  // do its thing and wipe the DB for a clean slate per test.
  @AfterEach
  public void afterEach() {
    HibernateUtil.resetSessionFactory();
  }
}
