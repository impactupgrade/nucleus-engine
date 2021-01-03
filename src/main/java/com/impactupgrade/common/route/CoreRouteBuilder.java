package com.impactupgrade.common.route;

import com.impactupgrade.common.paymentgateway.stripe.StripeProcessor;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;

public class CoreRouteBuilder extends RouteBuilder {

  @Override
  public void configure() throws Exception {

    restConfiguration().component("jetty").host("localhost").port(9000).bindingMode(RestBindingMode.json);

    // Receives and processes *all* webhooks from Stripe.
    from("rest:post:api/stripe/webhook")
        // stripe-java uses GSON, so Jersey/Jackson won't work on its own
        .unmarshal().json(JsonLibrary.Gson, com.stripe.model.Event.class)
        .setProperty("stripeEventType", simple("${body.type}"))
        .log(LoggingLevel.INFO, "received event ${body.type}: ${body.id}")
        .bean(StripeProcessor.class, "processEvent")
        .choice()
            .when(simple("${exchangeProperty.stripeEventType} == 'bar'"))
            .to("direct:b")
    ;
  }
}
