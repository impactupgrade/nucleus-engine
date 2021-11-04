package com.impactupgrade.nucleus.dao;

import com.impactupgrade.nucleus.model.WebhookRequest;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.springframework.util.StringUtils;

import java.util.Optional;

public class WebhookRequestDao implements Dao<String, WebhookRequest> {

    private final SessionFactory sessionFactory;

    public WebhookRequestDao() {
        final Configuration configuration = new Configuration();
        configuration.addAnnotatedClass(WebhookRequest.class);
        this.sessionFactory = configuration.buildSessionFactory(new StandardServiceRegistryBuilder().build());
    }

    @Override
    public WebhookRequest create(WebhookRequest webhookRequest) {
        final Session session = sessionFactory.openSession();
        Transaction transaction = session.beginTransaction();
        session.save(webhookRequest);
        transaction.commit();
        session.close();
        return webhookRequest;
    }

    @Override
    public Optional<WebhookRequest> get(String id) {
        if (StringUtils.isEmpty(id)) {
            return Optional.empty();
        }

        final Session session = sessionFactory.openSession();
        WebhookRequest webhookRequest = session.get(WebhookRequest.class, id);
        session.close();
        return Optional.ofNullable(webhookRequest);
    }

    @Override
    public WebhookRequest update(WebhookRequest webhookRequest) {
        final Session session = sessionFactory.openSession();
        Transaction transaction = session.beginTransaction();
        session.update(webhookRequest);
        transaction.commit();
        session.close();
        return webhookRequest;
    }

    @Override
    public Optional<WebhookRequest> delete(String id) {
        final Session session = sessionFactory.openSession();
        Transaction transaction = session.beginTransaction();
        WebhookRequest webhookRequest = session.get(WebhookRequest.class, id);
        session.delete(webhookRequest);
        transaction.commit();
        session.close();
        return Optional.ofNullable(webhookRequest);
    }
}
