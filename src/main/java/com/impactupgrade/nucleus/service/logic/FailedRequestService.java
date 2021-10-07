package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.dao.FailedRequestDao;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.FailedRequest;
import org.apache.cxf.common.util.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.util.Date;
import java.util.Objects;
import java.util.Optional;

public class FailedRequestService {

    private static final Logger log = LogManager.getLogger(FailedRequestService.class);

    private Environment env;
    private FailedRequestDao failedRequestDao;

    public FailedRequestService(Environment env) {
        this.env = env;
        this.failedRequestDao = new FailedRequestDao();
    }

    public <T> void persist(String id, JSONObject jsonObject, String errorMessage) {
        if (StringUtils.isEmpty(id) || Objects.isNull(jsonObject)) {
            return;
        }
        try {
            upsert(newFailedRequest(id, jsonObject, errorMessage));
        } catch (Exception e) {
            log.error("Failed to persist failed request!", e);
        }

    }

    public <T> void delete(String id) {
        if (StringUtils.isEmpty(id)) {
            return;
        }
        try {
            failedRequestDao.delete(id);
        } catch (Exception e) {
            log.error("Failed to delete failed request!", e);
        }


    }

    // Utils
    private FailedRequest newFailedRequest(String id, JSONObject payload, String errorMessage) {
        FailedRequest failedRequest = new FailedRequest();
        failedRequest.setId(id);
        failedRequest.setPayload(payload.toString());
        failedRequest.setErrorMessage(errorMessage);
        failedRequest.setAttemptCount(1);
        failedRequest.setFirstAttemptTime(new Date());
        failedRequest.setLastAttemptTime(new Date());
        return failedRequest;
    }

    private void upsert(FailedRequest failedRequest) {
        String id = failedRequest.getId();
        Optional<FailedRequest> existingRecord = failedRequestDao.get(id);
        if (existingRecord.isEmpty()) {
            failedRequestDao.create(failedRequest);
        } else {
            int attempts = existingRecord.get().getAttemptCount() + 1;
            failedRequest.setAttemptCount(attempts);
            failedRequest.setLastAttemptTime(new Date());
            failedRequestDao.update(failedRequest);
        }
    }

}
