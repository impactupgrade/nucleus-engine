package com.impactupgrade.nucleus.util.crmsync;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.HasId;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public abstract class SyncHelper<T extends HasId> {

  private static final Logger log = LogManager.getLogger(SyncHelper.class.getName());

  protected final CrmService primaryCrm;
  protected final CrmService secondaryCrm;

  protected SyncHelper(Environment env) {
    this.primaryCrm = env.primaryCrmService();
    this.secondaryCrm = env.secondaryCrmService();
  }

  // Finds the record in the primary CRM from the record ID passed to CrmController
  protected abstract Optional<T> getPrimaryRecord(String primaryRecordId) throws Exception;

  protected abstract Optional<T> getSecondaryRecord(T secondaryCrmRecord) throws Exception;

  protected abstract void insertRecordToSecondary(T secondaryCrmRecord) throws Exception;
  protected abstract void updateRecordInSecondary(T secondaryCrmRecord) throws Exception;

  public void sync(String id) throws Exception {
    Optional<T> primaryRecord = getPrimaryRecord(id);
    if (primaryRecord.isEmpty()) {
      log.warn("could not find primary record {}; skipping sync...", id);
      return;
    }

    Optional<T> secondaryRecord = getSecondaryRecord(primaryRecord.get());

    if (secondaryRecord.isPresent()) {
      primaryRecord.get().id = secondaryRecord.get().id;
      updateRecordInSecondary(primaryRecord.get());
    } else {
      insertRecordToSecondary(primaryRecord.get());
    }

    log.info("upserted primary record {} {} to the secondary CRM", primaryRecord.getClass().getName(), id);
  }
}

