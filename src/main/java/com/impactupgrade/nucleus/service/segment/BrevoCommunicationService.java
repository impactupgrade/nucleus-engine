package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.client.BrevoClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;

import java.util.Calendar;

public class BrevoCommunicationService extends AbstractCommunicationService {

  @Override
  public String name() {
    return "brevo";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().brevo != null && !env.getConfig().brevo.isEmpty();
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    //TODO:
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    //TODO:
  }

  @Override
  public void upsertContact(String contactId) throws Exception {
    //TODO:
  }

  @Override
  public void massArchive() throws Exception {
    //TODO:
  }

  //TODO: remove once done with testing
  public static void main(String[] args) {
    EnvironmentConfig.CommunicationPlatform communicationPlatform = new EnvironmentConfig.CommunicationPlatform();
    communicationPlatform.secretKey = "...";
    BrevoClient brevoClient = new BrevoClient(communicationPlatform, null);
    brevoClient.getContacts();

  }
}
