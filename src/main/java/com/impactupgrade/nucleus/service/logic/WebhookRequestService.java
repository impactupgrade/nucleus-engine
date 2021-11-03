package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.dao.WebhookRequestDao;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.WebhookRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.util.Date;
import java.util.Objects;
import java.util.Optional;

public class WebhookRequestService {

    private static final Logger log = LogManager.getLogger(WebhookRequestService.class);

    private Environment env;
    private WebhookRequestDao webhookRequestDao;

    public WebhookRequestService(Environment env) {
        this.env = env;
        this.webhookRequestDao = new WebhookRequestDao();
    }

    public void persist(String id, String payloadType, JSONObject jsonObject) {
        if (Strings.isNullOrEmpty(id) || Strings.isNullOrEmpty(payloadType) || Objects.isNull(jsonObject)) {
            return;
        }
        try {
            upsert(newWebhookRequest(id, payloadType, jsonObject));
        } catch (Exception e) {
            log.error("Failed to persist webhook request!", e);
        }
    }

    public void updateWithErrorMessage(String id, String errorMessage) {
        if (Strings.isNullOrEmpty(id)) {
            return;
        }
        try {
            Optional<WebhookRequest> existingRecord = webhookRequestDao.get(id);
            if (existingRecord.isPresent()) {
                WebhookRequest webhookRequest = existingRecord.get();
                webhookRequest.errorMessage = errorMessage;
                webhookRequestDao.update(webhookRequest);
            }
        } catch (Exception e) {
            log.error("Failed to update webhook request!", e);
        }
    }

    public void delete(String id) {
        if (Strings.isNullOrEmpty(id)) {
            return;
        }
        try {
            webhookRequestDao.delete(id);
        } catch (Exception e) {
            log.error("Failed to delete failed request!", e);
        }
    }

    // Utils
    private WebhookRequest newWebhookRequest(String id, String payloadType, JSONObject payload) {
        WebhookRequest webhookRequest = new WebhookRequest();
        webhookRequest.id = id;
        webhookRequest.payloadType = payloadType;
        webhookRequest.payload = payload.toString();
        webhookRequest.attemptCount = 1;
        webhookRequest.firstAttemptTime = new Date();
        webhookRequest.lastAttemptTime = new Date();
        return webhookRequest;
    }

    private void upsert(WebhookRequest webhookRequest) {
        Optional<WebhookRequest> existingRecord = webhookRequestDao.get(webhookRequest.id);
        if (existingRecord.isEmpty()) {
            webhookRequestDao.create(webhookRequest);
        } else {
            int attempts = existingRecord.get().attemptCount + 1;
            webhookRequest.attemptCount = attempts;
            webhookRequest.lastAttemptTime =new Date();
            webhookRequestDao.update(webhookRequest);
        }
    }

}
