/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.EnvironmentFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/accounting")
public class AccountingController {

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
//        Environment environment = environmentFactory.init(request);
//        SecurityUtil.verifyApiKey(environment);
//
//        Date startDate;
//        Date endDate;
//        if (Strings.isNullOrEmpty(start)) {
//            // If dates were not provided, this was likely a cronjob. Do the last 3 days.
//            Calendar startCal = Calendar.getInstance();
//            startCal.add(Calendar.HOUR, -72);
//            startDate = startCal.getTime();
//            endDate = Calendar.getInstance().getTime();
//        } else {
//            startDate = new SimpleDateFormat(DATE_FORMAT).parse(start);
//            endDate = new SimpleDateFormat(DATE_FORMAT).parse(end);
//        }
//
//        Runnable runnable = () -> {
//            // Get all payment services available (Stripe is it you?)
//            List<PaymentGatewayService> paymentGatewayServices = environment.allPaymentGatewayServices();
//
//            if (CollectionUtils.isEmpty(paymentGatewayServices)) {
//                env.logJobInfo("Payment Services not defined for environment! Returning...");
//                return;
//            }
//
//            List<PaymentGatewayDeposit> deposits = new ArrayList<>();
//
//            try {
//                for (PaymentGatewayService paymentGatewayService : paymentGatewayServices) {
//                    String serviceName = paymentGatewayService.name();
//                    env.logJobInfo("Payment service '{}' running...", serviceName);
//                    // TODO: This retrieves all charges/invoices/customers, so it can be a heavy lift. Break the method
//                    // down and retrieve only what we need?
//                    deposits.addAll(paymentGatewayService.getDeposits(startDate, endDate));
//                    env.logJobInfo("Payment service '{}' done.", serviceName);
//                }
//            } catch (Exception e) {
//                env.logJobError("Failed to get deposits! {}", e.getMessage());
//                return;
//            }
//
//            environment.accountingService().addDeposits(deposits);
//        };
//
//        // Away from the main thread
//        new Thread(runnable).start();

        return Response.ok().build();
    }

}
