/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.environment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.EventBriteClient;
import com.impactupgrade.nucleus.client.FactsClient;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.client.SfdcBulkClient;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.SfdcMetadataClient;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.client.TwilioClient;
import com.impactupgrade.nucleus.client.VirtuousClient;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.service.logic.ActivityService;
import com.impactupgrade.nucleus.service.logic.ContactService;
import com.impactupgrade.nucleus.service.logic.DonationService;
import com.impactupgrade.nucleus.service.logic.MessagingService;
import com.impactupgrade.nucleus.service.logic.NotificationService;
import com.impactupgrade.nucleus.service.logic.ScheduledJobService;
import com.impactupgrade.nucleus.service.segment.AccountingPlatformService;
import com.impactupgrade.nucleus.service.segment.CommunicationService;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.service.segment.EmailService;
import com.impactupgrade.nucleus.service.segment.JobLoggingService;
import com.impactupgrade.nucleus.service.segment.BareCrmService;
import com.impactupgrade.nucleus.service.segment.PaymentGatewayService;
import com.impactupgrade.nucleus.service.segment.SegmentService;
import com.impactupgrade.nucleus.service.segment.DataSyncService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every action within Nucleus is kicked off by either an HTTP Request or a manual script. This class
 * is responsible for carrying the context of the flow throughout all processing steps, defining how
 * the organization's set of platforms, configurations, and customizations.
 * <p>
 * We can't statically define this, one per organization. Instead, the whole concept is dynamic,
 * per-flow. This allows multitenancy (ex: DR having a single instance with multiple "Funding Nations",
 * each with their own unique characteristics and platform keys) and dynamic
 * logic based on additional context. We also determine the context at this level (rather than
 * at the application level) to queue us up for SaaS models with DB-driven configs.
 */
public class Environment {

  private static final Logger log = LogManager.getLogger(Environment.class);

  // Additional context, if available.
  // It seems odd to track URI and headers separately, rather than simply storing HttpServletRequest itself.
  // However, many (most?) frameworks don't allow aspects of request to be accessed over and over. Due to the mechanics,
  // some only allow it to be streamed once.
  protected String uri = null;
  protected final CaseInsensitiveMap<String, String> headers = new CaseInsensitiveMap<>();
  protected final CaseInsensitiveMap<String, String> queryParams = new CaseInsensitiveMap<>();
  protected MultivaluedMap<String, String> otherContext = new MultivaluedHashMap<>();

  // Whenever possible, we focus on being configuration-driven using one, large JSON file.
  private final EnvironmentConfig _config = EnvironmentConfig.init();

  private final String jobTraceId = UUID.randomUUID().toString();

  public Environment() {}

  public Environment(String otherJsonFilename) {
    _config.addOtherJson(otherJsonFilename);
  }

  public EnvironmentConfig getConfig() {
    return _config;
  }

  public String getJobTraceId() {
    return jobTraceId;
  }

  public void setRequest(HttpServletRequest request) {
    if (request != null) {
      uri = request.getRequestURI();

      Enumeration<String> headerNames = request.getHeaderNames();
      while (headerNames.hasMoreElements()) {
        String headerName = headerNames.nextElement();
        headers.put(headerName, request.getHeader(headerName));
      }

      URLEncodedUtils.parse(request.getQueryString(), StandardCharsets.UTF_8).forEach(
          pair -> queryParams.put(pair.getName(), pair.getValue()));
    }
  }

  public String getUri() {
    return uri;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public Map<String, String> getQueryParams() {
    return queryParams;
  }

  public MultivaluedMap<String, String> getOtherContext() {
    return otherContext;
  }

  public void setOtherContext(MultivaluedMap<String, String> otherContext) {
    this.otherContext = otherContext;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // REGISTRY
  // Provides a simple registry of services, clients, etc. to allow subprojects to override concepts as needed!
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // TODO: Logic services and vendor clients could eventually move to ServiceLoader as well, but it's currently
  //  less of an issue since there's only one impl each and there are currently no need for interfaces.

  // logic services

  public ActivityService activityService() { return new ActivityService(this); }
  public DonationService donationService() { return new DonationService(this); }
  public ContactService contactService() { return new ContactService(this); }
  public MessagingService messagingService() { return new MessagingService(this); }
  public NotificationService notificationService() { return new NotificationService(this); }
  public ScheduledJobService scheduledJobService() { return new ScheduledJobService(this); }

  // segment services

  public CrmService crmService(final String name) {
    if (Strings.isNullOrEmpty(name)) {
      return primaryCrmService();
    } else {
      return segmentService(name, CrmService.class);
    }
  }

  public CrmService primaryCrmService() {
    if (Strings.isNullOrEmpty(getConfig().crmPrimary)) {
      //throw new RuntimeException("define a crmPrimary in environment.json");
      log.info("no CRM defined in env.json; defaulting to NoOpCrmService");
      return new BareCrmService();
    }

    // by default, always use the primary
    return crmService(getConfig().crmPrimary);
  }

  public CrmService donationsCrmService() {
    return crmService(getConfig().crmDonations);
  }

  public CrmService messagingCrmService() {
    return crmService(getConfig().crmMessaging);
  }

  public PaymentGatewayService paymentGatewayService(String name) {
    return segmentService(name, PaymentGatewayService.class);
  }

  public List<PaymentGatewayService> allPaymentGatewayServices() {
    return segmentServices(PaymentGatewayService.class);
  }

  public EmailService transactionalEmailService() {
    if (Strings.isNullOrEmpty(getConfig().emailTransactional)) {
      // default to SendGrid
      return segmentService("sendgrid", EmailService.class);
    }
    return segmentService(getConfig().emailTransactional, EmailService.class);
  }

  public CommunicationService communicationService(String name) {
    return segmentService(name, CommunicationService.class);
  }

  public List<CommunicationService> communicationServices(String serviceName) {
    if (!Strings.isNullOrEmpty(serviceName)) {
      return List.of(communicationService(serviceName));
    } else {
      return allCommunicationServices();
    }
  }

  public List<CommunicationService> allCommunicationServices() {
    return segmentServices(CommunicationService.class);
  }

  public DataSyncService dataSyncService(String name) {
    return segmentService(name, DataSyncService.class);
  }

  public List<DataSyncService> dataSyncServices(String serviceName) {
    if (!Strings.isNullOrEmpty(serviceName)) {
      return List.of(dataSyncService(serviceName));
    } else {
      return allDataSyncServices();
    }
  }

  public List<DataSyncService> allDataSyncServices() {
    return segmentServices(DataSyncService.class);
  }

  public Optional<AccountingPlatformService> accountingPlatformService() {
    if (Strings.isNullOrEmpty(getConfig().accountingPrimary)) {
      return Optional.empty();
    }
    return Optional.of(segmentService(getConfig().accountingPrimary, AccountingPlatformService.class));
  }

  public List<AccountingPlatformService> allAccountingPlatformServices() {
    return segmentServices(AccountingPlatformService.class);
  }

  private <T extends SegmentService> T segmentService(final String name, Class<T> clazz) {
    ServiceLoader<T> loader = java.util.ServiceLoader.load(clazz);
    T segmentService = loader.stream()
        .map(ServiceLoader.Provider::get)
        .filter(service -> name.equalsIgnoreCase(service.name()))
        // Custom overrides will appear first naturally due to CL order!
        .findFirst()
        .orElseThrow(() -> new RuntimeException("segment service not found: " + name));
    segmentService.init(this);
    return segmentService;
  }

  private <T extends SegmentService> List<T> segmentServices(Class<T> clazz) {
    // TODO: Allow custom instances to override segment services by name. The ServiceLoader will first find their
    //  own registered impls, due to ClassLoader ordering. But we then need to make sure that the default impls
    //  aren't also called.
    Map<String, T> discoveredServices = new HashMap<>();

    ServiceLoader<T> loader = java.util.ServiceLoader.load(clazz);
    return loader.stream()
        .filter(p -> !discoveredServices.containsKey(p.get().name()))
        .map(p -> {
          T segmentService = p.get();
          discoveredServices.put(segmentService.name(), segmentService);

          // segmentServices should only return the list of services that are actually configured in env.json
          if (!segmentService.isConfigured(this)) {
            return null;
          }

          segmentService.init(this);
          return segmentService;
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  // vendor clients

  public EventBriteClient eventBriteClient() { return new EventBriteClient(this); }
  public FactsClient factsClient() { return new FactsClient(this); }
  public SfdcClient sfdcClient() { return new SfdcClient(this); }
  public SfdcClient sfdcClient(String username, String password, boolean isSandbox) { return new SfdcClient(this, username, password, isSandbox); }
  public SfdcBulkClient sfdcBulkClient() { return new SfdcBulkClient(this); }
  public SfdcMetadataClient sfdcMetadataClient() { return new SfdcMetadataClient(this); }
  public StripeClient stripeClient() { return new StripeClient(this); }
  public TwilioClient twilioClient() { return new TwilioClient(this); }
  public VirtuousClient virtuousClient() { return new VirtuousClient(this); }
  public MailchimpClient mailchimpClient(EnvironmentConfig.CommunicationPlatform mailchimpConfig) { return new MailchimpClient(mailchimpConfig, this); }

  // job logging services

  public JobLoggingService jobLoggingService(String name) {
    return segmentService(name, JobLoggingService.class);
  }
  
  public Set<JobLoggingService> jobLoggingServices() {
    if (CollectionUtils.isNotEmpty(_config.loggers)) {
      return _config.loggers.stream()
          .map(name -> segmentService(name, JobLoggingService.class))
          .filter(service -> service.isConfigured(this))
          .collect(Collectors.toSet());
    } else {
      return Set.of(segmentService("console", JobLoggingService.class));
    }
  }

  public void startJobLog(JobType jobType, String username, String jobName, String originatingPlatform) {
    jobLoggingServices().forEach(logger -> logger.startLog(jobType, username, jobName, originatingPlatform));
  }

  public void logJobInfo(String message, Object... params) {
    jobLoggingServices().forEach(logger -> logger.info(message, params));
  }

  public void logJobWarn(String message, Object... params) {
    jobLoggingServices().forEach(logger -> logger.warn(message, params));
  }

  public void logJobError(String message, Object... params) {
    jobLoggingServices().forEach(logger -> logger.error(message, params));
  }

  public void endJobLog(JobStatus jobStatus) {
    jobLoggingServices().forEach(logger -> logger.endLog(jobStatus));
  }
}