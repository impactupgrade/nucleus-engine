/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus;

import com.impactupgrade.nucleus.controller.BackupController;
import com.impactupgrade.nucleus.controller.CrmController;
import com.impactupgrade.nucleus.controller.DonationFormController;
import com.impactupgrade.nucleus.controller.EmailController;
import com.impactupgrade.nucleus.controller.MailchimpController;
import com.impactupgrade.nucleus.controller.PaymentGatewayController;
import com.impactupgrade.nucleus.controller.SfdcController;
import com.impactupgrade.nucleus.controller.StripeController;
import com.impactupgrade.nucleus.controller.TwilioController;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.security.SecurityExceptionMapper;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.bus.CXFBusFactory;
import org.apache.cxf.transport.servlet.CXFNonSpringServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import javax.servlet.DispatcherType;
import java.util.EnumSet;

public class App {

  // $PORT env var provided by Heroku
  private static final int PORT = Integer.parseInt(System.getenv("PORT") != null ? System.getenv("PORT") : "9009");

  protected final EnvironmentFactory envFactory;

  public App() {
    envFactory = new EnvironmentFactory();
  }

  // Allow orgs to wire in their custom implementations of Environment.
  public App(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  private Server server = null;

  public void start() throws Exception {
    server = new Server();

    final ServerConnector httpConnector = new ServerConnector(server);
    httpConnector.setPort(PORT);
    // 5 min timeout due to some SFDC processes taking forever, but ensure this doesn't end up causing mem issues
    httpConnector.setIdleTimeout(5 * 60 * 1000);
    server.addConnector(httpConnector);

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    context.setSessionHandler(new SessionHandler());

    // CORS
    FilterHolder cors = context.addFilter(CrossOriginFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
    cors.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
    cors.setInitParameter(CrossOriginFilter.ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
    cors.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET,POST,PUT,DELETE,OPTIONS,HEAD");
    cors.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, "X-Requested-With,Content-Type,Accept,Origin,Authorization");

    // API/REST (Jersey)
    ResourceConfig apiConfig = new ResourceConfig();

    apiConfig.register(new SecurityExceptionMapper());
    apiConfig.register(MultiPartFeature.class);

    apiConfig.register(backupController());
    apiConfig.register(crmController());
    apiConfig.register(donationFormController());
    apiConfig.register(emailController());
    apiConfig.register(paymentGatewayController());
    apiConfig.register(sfdcController());
    apiConfig.register(stripeController());
    apiConfig.register(twilioController());
    apiConfig.register(mailchimpController());

    registerAPIControllers(apiConfig);

    context.addServlet(new ServletHolder(new ServletContainer(apiConfig)), "/api/*");

    // needed primarily to serve any static content or unique servlets from extension projects
    ServletHolder defaultServlet = new ServletHolder("default", DefaultServlet.class);
    context.addServlet(defaultServlet, "/");

    server.setHandler(context);
    server.start();

    // SOAP (CXF)
    System.setProperty(BusFactory.BUS_FACTORY_PROPERTY_NAME, CXFBusFactory.class.getName());
    CXFNonSpringServlet cxf = new CXFNonSpringServlet();
    ServletHolder soapServlet = new ServletHolder(cxf);
    soapServlet.setName("soap");
    soapServlet.setForcedPath("soap");
    context.addServlet(soapServlet, "/soap/*");
    Bus bus = cxf.getBus();
    BusFactory.setDefaultBus(bus);

    registerServlets(context);
  }

  public void stop() throws Exception {
    server.stop();
  }

  /**
   * Allow orgs to add custom Controllers, etc.
   */
  public void registerAPIControllers(ResourceConfig apiConfig) throws Exception {}

  /**
   * Allow orgs to add completely-custom servlets (CXF, etc.)
   */
  public void registerServlets(ServletContextHandler context) throws Exception {}

  // Allow orgs to override specific controllers.
  protected BackupController backupController() { return new BackupController(envFactory); }
  protected CrmController crmController() { return new CrmController(envFactory); }
  protected DonationFormController donationFormController() { return new DonationFormController(envFactory); }
  protected EmailController emailController() { return new EmailController(envFactory); }
  protected PaymentGatewayController paymentGatewayController() { return new PaymentGatewayController(envFactory); }
  protected SfdcController sfdcController() { return new SfdcController(envFactory); }
  protected StripeController stripeController() { return new StripeController(envFactory); }
  protected TwilioController twilioController() { return new TwilioController(envFactory); }
  protected MailchimpController mailchimpController() { return new MailchimpController(envFactory); }

  public EnvironmentFactory getEnvironmentFactory() {
    return envFactory;
  }

  /**
   * Run the engine in a local-test mode, pointing at test/sandbox accounts for various platforms.
   * By default, runs on port 9009.
   *
   * To use this in tests requiring webhook URLs (Twilio, Stripe, Mailchimp, etc.), run this with ngrok:
   * ngrok http 9009
   *
   * THIS IS TEMPORARY! Imminently, we'll be deploying the multi-tenant solution through nucleus-core, so
   * environment-local-test.json will instead be housed in the database!
   */
  public static void main(String[] args) throws Exception {
    new App(new EnvironmentFactory("environment-local-test.json")).start();
  }
}
