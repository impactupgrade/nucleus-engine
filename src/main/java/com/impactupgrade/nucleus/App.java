/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {

  // $PORT env var provided by Heroku
//  private static final int PORT = Integer.parseInt(System.getenv("PORT") != null ? System.getenv("PORT") : "9009");

  public static void main(String... args) throws Exception {
    SpringApplication.run(App.class, args);
  }

  public void start() throws Exception {
//    Server server = new Server();
//
//    final ServerConnector httpConnector = new ServerConnector(server);
//    httpConnector.setPort(PORT);
//    // 5 min timeout due to some SFDC processes taking forever, but ensure this doesn't end up causing mem issues
//    httpConnector.setIdleTimeout(5 * 60 * 1000);
//    server.addConnector(httpConnector);
//
//    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
//    context.setContextPath("/");
//    context.setSessionHandler(new SessionHandler());
//
//    // CORS
//    FilterHolder cors = context.addFilter(CrossOriginFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
//    cors.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
//    cors.setInitParameter(CrossOriginFilter.ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
//    cors.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET,POST,PUT,DELETE,OPTIONS,HEAD");
//    cors.setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, "X-Requested-With,Content-Type,Accept,Origin,Authorization");
//
//    // API/REST (Jersey)
//    ResourceConfig apiConfig = new ResourceConfig();
//
//    apiConfig.register(new SecurityExceptionMapper());
//    apiConfig.register(MultiPartFeature.class);
//
//    apiConfig.register(backupController());
//    apiConfig.register(crmController());
//    apiConfig.register(paymentGatewayController());
//    apiConfig.register(paymentSpringController());
//    apiConfig.register(sfdcController());
//    apiConfig.register(stripeController());
//    apiConfig.register(twilioController());
//
//    registerAPIControllers(apiConfig);
//
//    context.addServlet(new ServletHolder(new ServletContainer(apiConfig)), "/api/*");
//
//    // needed primarily to serve any static content or unique servlets from extension projects
//    ServletHolder defaultServlet = new ServletHolder("default", DefaultServlet.class);
//    context.addServlet(defaultServlet, "/");
//
//    server.setHandler(context);
//    server.start();
//
//    // SOAP (CXF)
//    System.setProperty(BusFactory.BUS_FACTORY_PROPERTY_NAME, CXFBusFactory.class.getName());
//    CXFNonSpringServlet cxf = new CXFNonSpringServlet();
//    ServletHolder soapServlet = new ServletHolder(cxf);
//    soapServlet.setName("soap");
//    soapServlet.setForcedPath("soap");
//    context.addServlet(soapServlet, "/soap/*");
//    Bus bus = cxf.getBus();
//    BusFactory.setDefaultBus(bus);
//
//    registerServlets(context);
  }
}
