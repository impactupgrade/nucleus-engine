/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus;

import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.service.logic.DonationService;
import com.impactupgrade.nucleus.service.logic.DonorService;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public abstract class AbstractMockTest {

  // TODO: Do these need reset after each test method, or does Mockito/Junit do that automatically?
  @Mock protected DonationService donationServiceMock;
  @Mock protected DonorService donorServiceMock;
  @Mock protected SfdcClient sfdcClientMock;
  @Mock protected CrmService crmServiceMock;

  @Mock protected StripeClient stripeClientMock;

  public class DefaultEnvironment extends Environment {

    @Override
    public CrmService crmService() {
      return crmServiceMock;
    }

    @Override
    public DonationService donationService() {
      return donationServiceMock;
    }

    @Override
    public DonorService donorService() {
      return donorServiceMock;
    }

    @Override
    public SfdcClient sfdcClient() {
      return sfdcClientMock;
    }

    @Override
    public SfdcClient sfdcClient(String username, String password) {
      return sfdcClientMock;
    }

    @Override
    public StripeClient stripeClient() {
      return stripeClientMock;
    }
  }
}
