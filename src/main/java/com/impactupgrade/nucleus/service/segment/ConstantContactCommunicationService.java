package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.ConstantContactClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;

import java.util.Calendar;
import java.util.List;
import java.util.Optional;

public class ConstantContactCommunicationService extends AbstractCommunicationService {

  @Override
  public String name() {
    return "constantContact";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().constantContact != null && !env.getConfig().constantContact.isEmpty();
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.CommunicationPlatform communicationPlatform : env.getConfig().constantContact) {
      ConstantContactClient constantContactClient = new ConstantContactClient(communicationPlatform, env);

      for (EnvironmentConfig.CommunicationList communicationList : communicationPlatform.lists) {
        List<CrmContact> crmContacts = env.primaryCrmService().getSmsContacts(lastSync, communicationList);
        for (CrmContact crmContact : crmContacts) {
          if (!Strings.isNullOrEmpty(crmContact.phone())) {
            env.logJobInfo("upserting contact {} {} on list {}", crmContact.id, crmContact.phone(), communicationList.id);
            constantContactClient.upsertContact(crmContact, communicationList.id);
          }
        }
      }
    }
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    //TODO: remove contacts?
  }

  @Override
  public void upsertContact(String contactId) throws Exception {
    CrmService crmService = env.primaryCrmService();

    for (EnvironmentConfig.CommunicationPlatform communicationPlatform : env.getConfig().constantContact) {
      ConstantContactClient constantContactClient = new ConstantContactClient(communicationPlatform, env);

      for (EnvironmentConfig.CommunicationList communicationList : communicationPlatform.lists) {
        Optional<CrmContact> crmContact = crmService.getFilteredContactById(contactId, communicationList.crmFilter);

        if (crmContact.isPresent() && !Strings.isNullOrEmpty(crmContact.get().phone())) {
          env.logJobInfo("upserting contact {} {} on list {}", crmContact.get().id, crmContact.get().phone(), communicationList.id);
          constantContactClient.upsertContact(crmContact.get(), communicationList.id);
        }
      }
    }
  }
}