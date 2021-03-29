package com.impactupgrade.nucleus;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.security.SecurityExceptionMapper;
import org.apache.cxf.BusFactory;
import org.apache.cxf.bus.CXFBusFactory;
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
import java.net.URL;
import java.util.EnumSet;

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

    apiConfig.register(getEnvironment().backupController());
    apiConfig.register(getEnvironment().donationSpringController());
    apiConfig.register(getEnvironment().paymentGatewayController());
    apiConfig.register(getEnvironment().paymentSpringController());
    apiConfig.register(getEnvironment().sfdcController());
    apiConfig.register(getEnvironment().stripeController());
    apiConfig.register(getEnvironment().twilioController());

    apiConfig.register(MultiPartFeature.class);

    getEnvironment().registerAPIControllers(apiConfig);

    context.addServlet(new ServletHolder(new ServletContainer(apiConfig)), "/api/*");

    getEnvironment().registerServlets(context);

    // static resources
    ClassLoader cl = App.class.getClassLoader();
    // get a reference to any resource in the static dir
    URL staticFileUrl = cl.getResource("static/test.txt");
    // grab the absolute path of the static dir itself
    String staticDirPath = staticFileUrl.toURI().resolve("./").normalize().toString();
    // use that as the static resource base
    context.setResourceBase(staticDirPath);

    // needed primarily to serve the static content
    ServletHolder defaultServlet = new ServletHolder("default", DefaultServlet.class);
    context.addServlet(defaultServlet, "/");

    server.setHandler(context);
    server.start();
  }

  protected Environment getEnvironment() {
    return __defaultEnv;
  }

  // TODO: Temporary? Not sure if we'll run hub-common directly, or wrap it with nucleus-core for multitenancy.
  // For now, this is fine -- mainly need it for DS.
  public static void main(String... args) throws Exception {
    new App().start();
  }
}
