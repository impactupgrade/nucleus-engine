package com.impactupgrade.nucleus.entity;

import com.google.common.base.Strings;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "core_organization")
public class Organization {

  @Id
  private long id;

  private String name;

  @Column(name = "donationspring_api_key", unique = true)
  private String apiKey;

  // TODO: Leaving this abstraction for now, in case we introduce Square or others.
  @Enumerated(EnumType.STRING)
  @Column(name = "donationspring_payment_gateway")
  private PaymentGateway paymentGateway = PaymentGateway.STRIPE;

  @Column(name = "donationspring_payment_gateway_public_key")
  private String paymentGatewayPublicKey;

  @Column(name = "donationspring_payment_gateway_secret_key")
  private String paymentGatewaySecretKey;

  @Column(name = "donationspring_payment_gateway_currency")
  private String paymentGatewayCurrency;

  @Column(name = "donationspring_donor_email_subject")
  private String donorEmailSubject;

  @Column(name = "donationspring_donor_email_body")
  private String donorEmailBody;

  @Column(name = "donationspring_deactivated")
  private boolean deactivated;

  @Column(name = "donationspring_notification_email")
  private String notificationEmail;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public PaymentGateway getPaymentGateway() {
    return paymentGateway;
  }

  public void setPaymentGateway(PaymentGateway paymentGateway) {
    this.paymentGateway = paymentGateway;
  }

  public String getPaymentGatewayPublicKey() {
    // Stripe will give an error if any spaces are present.
    return paymentGatewayPublicKey != null ? paymentGatewayPublicKey.trim() : null;
  }

  public void setPaymentGatewayPublicKey(String paymentGatewayPublicKey) {
    this.paymentGatewayPublicKey = paymentGatewayPublicKey;
  }

  public String getPaymentGatewaySecretKey() {
    // Stripe will give an error if any spaces are present.
    return paymentGatewaySecretKey != null ? paymentGatewaySecretKey.trim() : null;
  }

  public void setPaymentGatewaySecretKey(String paymentGatewaySecretKey) {
    this.paymentGatewaySecretKey = paymentGatewaySecretKey;
  }

  public String getPaymentGatewayCurrency() {
    return paymentGatewayCurrency;
  }

  public void setPaymentGatewayCurrency(String paymentGatewayCurrency) {
    this.paymentGatewayCurrency = paymentGatewayCurrency;
  }

  public String getDonorEmailSubject() {
    return Strings.isNullOrEmpty(donorEmailSubject) ? "Thank you!" : donorEmailSubject;
  }

  public void setDonorEmailSubject(String donorEmailSubject) {
    this.donorEmailSubject = donorEmailSubject;
  }

  public String getDonorEmailBody() {
    return Strings.isNullOrEmpty(donorEmailBody)
        ? "Thank you for your donation! Here is a receipt for your records:" : donorEmailBody;
  }

  public void setDonorEmailBody(String donorEmailBody) {
    this.donorEmailBody = donorEmailBody;
  }

  public boolean isDeactivated() {
    return deactivated;
  }

  public void setDeactivated(boolean deactivated) {
    this.deactivated = deactivated;
  }

  public String getNotificationEmail() {
    return notificationEmail;
  }

  public void setNotificationEmail(String notificationEmail) {
    this.notificationEmail = notificationEmail;
  }
}
