package com.impactupgrade.nucleus.it;

import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.security.SecurityExceptionMapper;
import com.impactupgrade.nucleus.util.TestUtil;
import com.sforce.soap.partner.sobject.SObject;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import javax.ws.rs.core.Application;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;

// TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AbstractIT extends JerseyTest {

  // TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
  // do not name this setUp()
  @BeforeAll
  public void before() throws Exception {
    super.setUp();
    TestUtil.SKIP_NEW_THREADS = true;
  }
  // do not name this tearDown()
  @AfterAll
  public void after() throws Exception {
    super.tearDown();
  }

  // TODO: Might be better to start App directly and use JerseyTest's external container, but the embedded Jetty
  //  test container is good enough for now...
  // TODO: If we do keep this, how to configure the test container to use the /api root?
  @Override
  protected Application configure() {
    enable(TestProperties.LOG_TRAFFIC);
    enable(TestProperties.DUMP_ENTITY);

    Environment env = getEnv();

    ResourceConfig apiConfig = new ResourceConfig();

    apiConfig.register(new SecurityExceptionMapper());
    apiConfig.register(MultiPartFeature.class);

    apiConfig.register(env.stripeController());
    env.registerAPIControllers(apiConfig);

    return apiConfig;
  }

  protected Environment getEnv() {
    return new EnvironmentIT();
  }

  protected void deleteSfdcRecords(String email) throws Exception {
    SfdcClient sfdcClient = getEnv().sfdcClient();

    Optional<SObject> existingContact = sfdcClient.getContactByEmail(email);
    if (existingContact.isPresent()) {
      String accountId = existingContact.get().getField("AccountId").toString();
      Optional<SObject> existingAccount = sfdcClient.getAccountById(accountId);

      List<SObject> existingOpps = sfdcClient.getDonationsByAccountId(accountId);
      for (SObject existingOpp : existingOpps) {
        sfdcClient.delete(existingOpp);
      }

      List<SObject> existingRDs = sfdcClient.getRecurringDonationsByAccountId(accountId);
      for (SObject existingRD : existingRDs) {
        sfdcClient.delete(existingRD);
      }

      sfdcClient.delete(existingContact.get());
      sfdcClient.delete(existingAccount.get());
    }

    // ensure we're actually clean
    assertFalse(sfdcClient.getContactByEmail(email).isPresent());
  }
}
