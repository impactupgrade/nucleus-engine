package com.impactupgrade.nucleus.service.segment;

import java.util.Calendar;

public interface DataSyncService extends SegmentService {

  void syncContacts(Calendar updatedAfter) throws Exception;
}
