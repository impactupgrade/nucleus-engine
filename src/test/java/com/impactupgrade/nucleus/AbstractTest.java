package com.impactupgrade.nucleus;

import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.Environment.RequestEnvironment;
import com.impactupgrade.nucleus.service.logic.DonationService;
import com.impactupgrade.nucleus.service.logic.DonorService;
import com.impactupgrade.nucleus.service.segment.CrmService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public abstract class AbstractTest {

  // TODO: Do these need reset after each test method, or does Mockito/Junit do that automatically?
  @Mock protected DonationService donationServiceMock;
  @Mock protected DonorService donorServiceMock;
  @Mock protected SfdcClient sfdcClientMock;
  @Mock protected CrmService crmServiceMock;

  @Mock protected StripeClient stripeClientMock;

  // TODO: Need to provide a default EnvironmentConfig
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
    public CrmService crmService() {
      return crmServiceMock;
    }

    @Override
    public RequestEnvironment newRequestEnvironment(HttpServletRequest request) {
      return new DefaultRequestEnvironment(request);
    }
  }

  public class DefaultRequestEnvironment extends RequestEnvironment {
    public DefaultRequestEnvironment(HttpServletRequest request) {
      super(request);
    }

    @Override
    public StripeClient stripeClient() {
      return stripeClientMock;
    }
  }
}
