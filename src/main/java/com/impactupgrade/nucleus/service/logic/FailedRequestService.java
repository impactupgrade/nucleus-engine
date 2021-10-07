package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.dao.FailedRequestDao;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.FailedRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public class FailedRequestService {

    private static final Logger log = LogManager.getLogger(FailedRequestService.class);

    private Environment env;
    private FailedRequestDao failedRequestDao;

    public FailedRequestService(Environment env) {
        this.env = env;
        this.failedRequestDao = new FailedRequestDao();
    }

    public <T> void persist(T t,
                            Function<T, String> getUniqueKeyFunction,
                            Function<T, JSONObject> toJSONObjectFunction,
                            String errorMessage) {
        if (Objects.isNull(t) || Objects.isNull(getUniqueKeyFunction)) {
            return;
        }
        // convert
        String id = getId(t, getUniqueKeyFunction);
        JSONObject jsonPayload = getJsonPayload(t, toJSONObjectFunction);
        // and save
        try {
            upsert(newFailedRequest(id, jsonPayload, errorMessage));
        } catch (Exception e) {
            log.info("Failed to persist!" + e);
        }

    }

    public <T> void delete(T t,
                           Function<T, String> getUniqueKeyFunction) {
        if (Objects.isNull(t) || Objects.isNull(getUniqueKeyFunction)) {
            return;
        }
        String id = getUniqueKeyFunction.apply(t);
        log.info("Deleting record with id {}...", id);

        try {
            Optional<FailedRequest> deleted = failedRequestDao.delete(id);
            if (deleted.isEmpty()) {
                log.info("Could not delete record for id {}! (Not exists?)", id);
            } else {
                log.info("Deleted!");
            }
        } catch (Exception e) {
            log.info("Failed to delete!" + e);
        }


    }

    // Utils
    private <T> String getId(T t, Function<T, String> getUniqueKeyFunction) {
        if (Objects.isNull(getUniqueKeyFunction)) {
            return null;
        }
        return getUniqueKeyFunction.apply(t);
    }

    private <T> JSONObject getJsonPayload(T t, Function<T, JSONObject> toJSONObjectFunction) {
        JSONObject jsonObject;
        if (Objects.nonNull(toJSONObjectFunction)) {
            jsonObject = toJSONObjectFunction.apply(t);
        } else {
            // Default converting function
            jsonObject = new JSONObject(t);
        }
        return jsonObject;
    }

    private FailedRequest newFailedRequest(String id, JSONObject payload, String errorMessage) {
        FailedRequest failedRequest = new FailedRequest();
        failedRequest.setId(id);
        failedRequest.setPayload(payload.toString());
        failedRequest.setAttemptCount(1);
        failedRequest.setFirstAttemptTime(new Date());
        failedRequest.setLastAttemptTime(new Date());
        return failedRequest;
    }

    private void upsert(FailedRequest failedRequest) {
        String id = failedRequest.getId();
        log.info("Checking if record exists for id {}...", id);
        Optional<FailedRequest> existingRecord = failedRequestDao.get(id);
        if (existingRecord.isEmpty()) {
            log.info("Will create a new record...");
            failedRequestDao.create(failedRequest, Optional.empty());
            log.info("Created!");
        } else {
            log.info("Will update existing id {}...", id);

            int attempts = existingRecord.get().getAttemptCount() + 1;
            failedRequest.setAttemptCount(attempts);
            failedRequest.setLastAttemptTime(new Date());

            failedRequestDao.update(failedRequest);
            log.info("Updated!");
        }
    }

}
