package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.service.segment.EmailPlatformService;
import net.bytebuddy.asm.Advice;

import java.time.LocalDate;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;

public class EmailController {

  private final Environment env;
  private final Calendar lastSync = Calendar.getInstance();
  private final int syncLength = 3;

  public EmailController(Environment env){
    this.env = env;
    lastSync.add(Calendar.DATE, (0 - syncLength));;
  }

  public void syncCRMContacts() throws Exception {
    env.emailPlatformService().syncNewContacts(lastSync);
    env.emailPlatformService().syncNewDonors(lastSync);
  }

}
