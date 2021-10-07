package com.impactupgrade.nucleus.dao;

import com.impactupgrade.nucleus.model.FailedRequest;

import java.util.Optional;

public class FailedRequestMongoDbDao implements Dao<String, FailedRequest> {

    @Override
    public FailedRequest create(FailedRequest failedRequest, Optional<Long> ttl) {
        // TODO:
        return null;
    }

    @Override
    public Optional<FailedRequest> get(String id) {
        // TODO:
        return Optional.empty();
    }

    @Override
    public FailedRequest update(FailedRequest failedRequest) {
        // TODO:
        return null;
    }

    @Override
    public Optional<FailedRequest> delete(String id) {
        // TODO:
        return Optional.empty();
    }
}
