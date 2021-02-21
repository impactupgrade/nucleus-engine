package com.impactupgrade.common;

import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.filter.CORSFilter;
import com.impactupgrade.common.security.SecurityExceptionMapper;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.bus.CXFBusFactory;
import org.apache.cxf.transport.servlet.CXFNonSpringServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import javax.servlet.ServletConfig;
import java.net.URL;

public class App {

  // $PORT env var provided by Heroku
  private static final int PORT = Integer.parseInt(System.getenv("PORT"));

  static {
    System.setProperty(BusFactory.BUS_FACTORY_PROPERTY_NAME, CXFBusFactory.class.getName());
  }

  private final Environment __defaultEnv = new Environment();

  protected void start() throws Exception {
    Server server = new Server();

    final ServerConnector httpConnector = new ServerConnector(server);
    httpConnector.setPort(PORT);
    // 5 min timeout due to some SFDC processes taking forever, but ensure this doesn't end up causing mem issues
    httpConnector.setIdleTimeout(5 * 60 * 1000);
    server.addConnector(httpConnector);

    ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
    servletHandler.setContextPath("/");
    servletHandler.setSessionHandler(new SessionHandler());

    // API/REST (Jersey)
    ResourceConfig apiConfig = new ResourceConfig();

    apiConfig.register(new CORSFilter());
    apiConfig.register(new SecurityExceptionMapper());

    apiConfig.register(getEnvironment().backupController());
    apiConfig.register(getEnvironment().paymentGatewayController());
    apiConfig.register(getEnvironment().paymentSpringController());
    apiConfig.register(getEnvironment().sfdcController());
    apiConfig.register(getEnvironment().stripeController());
    apiConfig.register(getEnvironment().twilioController());

    apiConfig.register(MultiPartFeature.class);

    getEnvironment().registerServices(apiConfig);

    servletHandler.addServlet(new ServletHolder(new ServletContainer(apiConfig)), "/api/*");

    // SOAP (CXF)
    CXFNonSpringServlet cxfServlet = new CXFNonSpringServlet() {
      @Override
      public void loadBus(ServletConfig servletConfig) {
        super.loadBus(servletConfig);
        Bus bus = getBus();
        BusFactory.setDefaultBus(bus);
      }
    };
    servletHandler.addServlet(new ServletHolder(cxfServlet), "/soap/*");

    // static resources
    ClassLoader cl = App.class.getClassLoader();
    // get a reference to any resource in the static dir
    URL staticFileUrl = cl.getResource("static/test.txt");
    // grab the absolute path of the static dir itself
    String staticDirPath = staticFileUrl.toURI().resolve("./").normalize().toString();
    // use that as the resource base
    servletHandler.setResourceBase(staticDirPath);

    // servlet for static resources
    ServletHolder holderHome = new ServletHolder("static", DefaultServlet.class);
    holderHome.setInitParameter("resourceBase", staticDirPath);
    holderHome.setInitParameter("pathInfoOnly", "true");
    servletHandler.addServlet(holderHome, "/static/*");

    // TODO: We may not end up needing this, but standard practice...
    ServletHolder defaultServlet = new ServletHolder("default", DefaultServlet.class);
    servletHandler.addServlet(defaultServlet, "/");

    server.setHandler(servletHandler);
    server.start();
  }

  protected Environment getEnvironment() {
    return __defaultEnv;
  }

  // TODO: Temporary, mainly for DS testing.
  public static void main(String... args) throws Exception {
    new App().start();
  }
}
