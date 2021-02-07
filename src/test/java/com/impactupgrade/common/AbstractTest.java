package com.impactupgrade.common;

import com.impactupgrade.common.crm.AggregateCrmDestinationService;
import com.impactupgrade.common.crm.CrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.sfdc.SfdcClient;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.environment.RequestEnvironment;
import com.impactupgrade.common.paymentgateway.DonationService;
import com.impactupgrade.common.paymentgateway.DonorService;
import com.impactupgrade.common.paymentgateway.stripe.StripeClient;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.ws.rs.container.ContainerRequestContext;

@ExtendWith(MockitoExtension.class)
public abstract class AbstractTest {

  // TODO: Do these need reset after each test method, or does Mockito/Junit do that automatically?
  @Mock protected DonationService donationServiceMock;
  @Mock protected DonorService donorServiceMock;
  @Mock protected SfdcClient sfdcClientMock;
  @Mock protected CrmSourceService crmSourceServiceMock;
  @Mock protected CrmDestinationService crmDestinationServiceMock;

  @Mock protected StripeClient stripeClientMock;

  public class DefaultEnvironment extends Environment {
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
    public CrmSourceService crmSourceService() {
      return crmSourceServiceMock;
    }

    @Override
    public AggregateCrmDestinationService crmDonationDestinationServices() {
      return new AggregateCrmDestinationService(crmDestinationServiceMock);
    }

    @Override
    public RequestEnvironment newRequestEnvironment(ContainerRequestContext context) {
      return new DefaultRequestEnvironment(context);
    }
  }

  public class DefaultRequestEnvironment extends RequestEnvironment {
    public DefaultRequestEnvironment(ContainerRequestContext context) {
      super(context);
    }

    @Override
    public StripeClient stripeClient() {
      return stripeClientMock;
    }
  }
}
