/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.DonorwranglerClient;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StripeToDonorWranglerIT extends AbstractIT {

  // TODO: DW currently has no delete methods, so it needs to be manually cleared out first :(

  protected StripeToDonorWranglerIT() {
    super(new App(new EnvironmentFactory("environment-it-donorwrangler-stripe.json")));
  }

  @Test
  public void coreOneTime() throws Exception {
    clearDonorwrangler();

    // play a Stripe webhook, captured directly from our Stripe account itself
//    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
//    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
//    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    DonorwranglerClient donorwranglerClient = env.donorwranglerClient();

    // verify ContactService -> SfdcCrmService
    Optional<DonorwranglerClient.DwDonor> donorO = donorwranglerClient.getContactByEmail("team+integration+tester@impactupgrade.com");
    assertTrue(donorO.isPresent());
    DonorwranglerClient.DwDonor donor = donorO.get();
    assertEquals("Integration", donor.firstName());
    assertEquals("Tester", donor.lastName());
    assertEquals("13022 Redding Drive", donor.address1());
    assertEquals("Fort Wayne", donor.city());
    assertEquals("IN", donor.state());
    assertEquals("46814", donor.zip());
//    assertEquals("US", donor.country());
    assertEquals("team+integration+tester@impactupgrade.com", donor.email());
    assertEquals("+12603495732", donor.phone());

    // verify DonationService -> SfdcCrmService
    List<DonorwranglerClient.DwDonation> donations = donorwranglerClient.getDonationsByDonorId(donor.id());
    assertEquals(1, donations.size());
    DonorwranglerClient.DwDonation donation = donations.get(0);
    assertEquals("Stripe", donation.source());
    // TODO: Josh -- need a field for this
//    assertEquals("pi_1ImrOLHAwJOu5brrpQ71F1G9", donation.transactionId());
    // TODO: Josh -- need a field for this
//    assertEquals("cus_JPgkris8GTsXIH", donation.customerId());
    // TODO: date not in response
//    assertEquals("2021-05-03", donation.date());
    assertEquals("100.00", donation.giftAmount());
    assertEquals("General", donation.fund());
    assertEquals("", donation.directedPurpose());
  }
}
