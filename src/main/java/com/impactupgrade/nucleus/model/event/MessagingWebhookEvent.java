/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model.event;

import com.impactupgrade.nucleus.model.crm.CrmContact;

public class MessagingWebhookEvent {

  protected CrmContact crmContact = new CrmContact();
  protected String listId;

  public CrmContact getCrmContact() {
    return crmContact;
  }

  public void setCrmContact(CrmContact crmContact) {
    this.crmContact = crmContact;
  }

  public String getListId() {
    return listId;
  }

  public void setListId(String listId) {
    this.listId = listId;
  }
}
