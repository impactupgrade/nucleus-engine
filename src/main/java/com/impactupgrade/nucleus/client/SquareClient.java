package com.impactupgrade.nucleus.client;

import com.impactupgrade.nucleus.environment.Environment;
import com.stripe.net.RequestOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SquareClient {

    private static final Logger log = LogManager.getLogger(StripeClient.class.getName());

    protected final RequestOptions requestOptions;

    public SquareClient(Environment env) {
//        requestOptions = RequestOptions.builder().setApiKey(env.getConfig().square.secretKey).build();
        requestOptions = RequestOptions.builder().build();
    }

    public SquareClient(RequestOptions requestOptions) {
        this.requestOptions = requestOptions;
    }



}
