package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmContact;

public interface TextingService extends SegmentService{
  public void sendMessage(String message, CrmContact crmContact, String to, String from);
}
