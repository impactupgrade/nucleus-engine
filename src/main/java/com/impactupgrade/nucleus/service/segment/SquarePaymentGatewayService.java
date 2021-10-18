package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayDeposit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Date;
import java.util.List;

public class SquarePaymentGatewayService implements PaymentGatewayService {

    private static final Logger log = LogManager.getLogger(StripePaymentGatewayService.class);

    protected Environment env;

    @Override
    public String name() { return "square"; }

    @Override
    public void init(Environment env) {
        this.env = env;
    }


    @Override
    public List<PaymentGatewayDeposit> getDeposits(Date startDate, Date endDate) throws Exception {
        // TODO:
        return null;
    }

    @Override
    public void updateSubscription(ManageDonationEvent manageDonationEvent) throws Exception {
        // TODO:
    }

    @Override
    public void closeSubscription(ManageDonationEvent manageDonationEvent) throws Exception {
        // TODO:
    }
}
