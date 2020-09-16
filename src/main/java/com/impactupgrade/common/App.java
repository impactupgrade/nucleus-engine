package com.impactupgrade.common;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.bus.CXFBusFactory;
import org.apache.cxf.transport.servlet.CXFServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

public abstract class App {

  // $PORT env var provided by Heroku
  private static final int PORT = Integer.parseInt(System.getenv("PORT"));

  static {
    System.setProperty(BusFactory.BUS_FACTORY_PROPERTY_NAME, CXFBusFactory.class.getName());
  }

  protected void start() throws Exception {
    Server server = new Server();

    final ServerConnector httpConnector = new ServerConnector(server);
    httpConnector.setPort(PORT);
    // 5 min timeout due to some SFDC processes taking forever, but ensure this doesn't end up causing mem issues
    httpConnector.setIdleTimeout(5 * 60 * 1000);
    server.addConnector(httpConnector);

    ServletContextHandler applicationContext = new ServletContextHandler(ServletContextHandler.SESSIONS);
    applicationContext.setContextPath("/");
    applicationContext.setSessionHandler(new SessionHandler());

    // API/REST (Jersey)
    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(MultiPartFeature.class);
    registerServices(resourceConfig);
    applicationContext.addServlet(new ServletHolder(new ServletContainer(resourceConfig)), "/api/*");

    // SOAP (CXF)
    Bus bus = BusFactory.getDefaultBus(true);
    CXFServlet cxfServlet = new CXFServlet();
    cxfServlet.setBus(bus);
    ServletHolder cxfServletHolder = new ServletHolder(cxfServlet);
    applicationContext.addServlet(cxfServletHolder, "/soap/*");

    server.setHandler(applicationContext);
    server.start();
  }

  protected abstract void registerServices(ResourceConfig resourceConfig);
}
