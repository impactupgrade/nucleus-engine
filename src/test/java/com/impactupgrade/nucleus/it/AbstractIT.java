/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import org.junit.jupiter.api.TestInstance;

// TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractIT/* extends JerseyTest*/ {

//  static {
//    System.setProperty("jersey.test.host", "localhost");
//    System.setProperty("jersey.config.test.container.port", "9009");
//  }
//
//  protected AbstractIT() {
//    super(new ExternalTestContainerFactory());
//  }
//
//  private final App app = getApp();
//
//  // TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
//  // do not name this setUp()
//  @BeforeAll
//  public void before() throws Exception {
//    TestUtil.SKIP_NEW_THREADS = true;
//
//    app.start();
//
//    super.setUp();
//  }
//  // do not name this tearDown()
//  @AfterAll
//  public void after() throws Exception {
//    super.tearDown();
//  }
//
//  @Override
//  protected Application configure() {
//    enable(TestProperties.LOG_TRAFFIC);
//    enable(TestProperties.DUMP_ENTITY);
//    // This seems stupid, but I can't figure out how to get Jersey's test framework to skip config if using
//    // the external container. They likely do it this way in case a test needs to add a custom controller.
//    return new ResourceConfig();
//  }
//
//  protected App getApp() {
//    return new App() {
//      @Override
//      public EnvironmentFactory envFactory() {
//        return new EnvironmentFactory() {
//          @Override
//          public Environment newEnv() {
//            return env();
//          }
//        };
//      }
//    };
//  }
//
//  protected Environment env() {
//    return new EnvironmentIT();
//  }
//
//  // Unlike App.java, let tests decide what they want to keep.
//  protected void registerAPIControllers(ResourceConfig apiConfig, EnvironmentFactory envFactory) {
//    apiConfig.register(new StripeController(envFactory));
//  }
//
//  protected void clearSfdc() throws Exception {
//    clearSfdcByName("Tester");
//  }
//
//  protected void clearSfdcByName(String name) throws Exception {
//    SfdcClient sfdcClient = env().sfdcClient();
//
//    List<SObject> existingAccounts = sfdcClient.getAccountsByName(name);
//    for (SObject existingAccount : existingAccounts) {
//      List<SObject> existingOpps = sfdcClient.getDonationsByAccountId(existingAccount.getId());
//      for (SObject existingOpp : existingOpps) {
//        sfdcClient.delete(existingOpp);
//      }
//
//      List<SObject> existingRDs = sfdcClient.getRecurringDonationsByAccountId(existingAccount.getId());
//      for (SObject existingRD : existingRDs) {
//        sfdcClient.delete(existingRD);
//      }
//
//      sfdcClient.delete(existingAccount);
//    }
//
//    // ensure we're actually clean
//    assertEquals(0, sfdcClient.getAccountsByName(name).size());
//  }
}
