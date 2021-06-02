package com.impactupgrade.nucleus.service.logic;

import com.ecwid.maleorang.MailchimpException;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.service.segment.EmailPlatformService;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

public class EmailService {
//TODO: remove this class and add its functionality to EmailPlatFormService

  private static final Logger log = LogManager.getLogger(EmailService.class);

  private final Environment env;
  private final CrmService crmService;
  private final EmailPlatformService emailPlatformService;

  public EmailService(Environment env){
    this.env = env;
    crmService = env.crmService();
    this.emailPlatformService = env.emailPlatformService();
  }

  public void syncWithEmailPlatformService(String listName) throws Exception {
//    crmService.getNewContacts().stream().forEach(contact -> emailPlatformService.addContactToList(contact,listName)); //TODO
//
//    crmService.getUpdatedContacts().stream().forEach(contact -> emailPlatformService.updateContact(contact,listName));
  }

}
