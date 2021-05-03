package com.impactupgrade.nucleus.integration;

import com.google.common.io.Resources;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.security.SecurityExceptionMapper;
import com.impactupgrade.nucleus.util.TestUtil;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

// TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class StripeToSalesforceIntegrationTest extends JerseyTest {

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

    Environment env = new IntegrationTestEnvironment();

    ResourceConfig apiConfig = new ResourceConfig();

    apiConfig.register(new SecurityExceptionMapper());
    apiConfig.register(MultiPartFeature.class);

    apiConfig.register(env.stripeController());

    return apiConfig;
  }

  @Test
  public void basicStripeToSFDC() throws Exception {
    // TODO: first delete the opps/contact/account using the payload's email address?

    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // TODO: get the records and verify everything we can think of
  }
}
