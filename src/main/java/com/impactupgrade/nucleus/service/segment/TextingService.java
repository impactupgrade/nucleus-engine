package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmContact;

public interface TextingService extends SegmentService{
  public void sendMessage(String message, CrmContact crmContact, String to, String from);
  public CrmContact processSignup(String phone, String firstName, String lastName, String email, String __emailOptIn, String __smsOptIn, String language, String campaignId, String listId ) throws Exception;
  public void optIn(String phone) throws Exception;
  public void optOut(String phone) throws Exception;
  public void optOut(CrmContact crmContact) throws Exception;
}
