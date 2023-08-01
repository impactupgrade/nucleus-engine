package com.impactupgrade.nucleus.service.segment;

import java.util.List;
import java.util.Map;

/**
 * Supports sending transactional emails and syncing contacts.
 */
public interface EmailService extends SegmentService {

  void sendEmailText(String subject, String body, boolean isHtml, String to, String from);
  void sendEmailTemplate(String subject, String template, Map<String, Object> data, List<String> tos, String from);
}
