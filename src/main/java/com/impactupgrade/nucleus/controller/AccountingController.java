package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.PaymentGatewayDeposit;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.segment.PaymentGatewayService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Path("/accounting")
public class AccountingController {

    private static final Logger log = LogManager.getLogger(AccountingController.class);
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    protected final EnvironmentFactory environmentFactory;

    public AccountingController(EnvironmentFactory environmentFactory) {
        this.environmentFactory = environmentFactory;
    }

    @Path("/sync-transactions")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response syncTransactions(
            @FormParam("start_date") String start,
            @FormParam("end_date") String end,
            @Context HttpServletRequest request
    ) throws Exception {

        Environment environment = environmentFactory.init(request);
        SecurityUtil.verifyApiKey(environment);

        Date startDate = new SimpleDateFormat(DATE_FORMAT).parse(start);
        Date endDate = new SimpleDateFormat(DATE_FORMAT).parse(end);

        Runnable runnable = () -> {
            // Get all payment services available (Stripe is it you?)
            List<PaymentGatewayService> paymentGatewayServices = environment.allPaymentGatewayServices();

            if (CollectionUtils.isEmpty(paymentGatewayServices)) {
                log.info("Payment Services not defined for environment! Returning...");
                return;
            }

            List<PaymentGatewayDeposit> deposits = new ArrayList<>();

            try {
                for (PaymentGatewayService paymentGatewayService : paymentGatewayServices) {
                    String serviceName = paymentGatewayService.name();
                    log.info("Payment service '{}' running...", serviceName);
                    // TODO: This retrieves all charges/invoices/customers, so it can be a heavy lift. Break the method
                    // down and retrieve only what we need?
                    deposits.addAll(paymentGatewayService.getDeposits(startDate, endDate));
                    log.info("Payment service '{}' done.", serviceName);
                }
            } catch (Exception e) {
                log.error("Failed to get deposits! {}", e.getMessage());
                return;
            }

            environment.accountingService().addDeposits(deposits);
        };

        // Away from the main thread
        new Thread(runnable).start();

        return Response.ok().build();
    }

}

