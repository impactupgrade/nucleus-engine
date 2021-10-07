package com.impactupgrade.nucleus.dao;

import com.impactupgrade.nucleus.model.FailedRequest;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.springframework.util.StringUtils;

import java.util.Optional;

public class FailedRequestDao implements Dao<String, FailedRequest> {

    private final SessionFactory sessionFactory;

    public FailedRequestDao() {
        final Configuration configuration = new Configuration();
        configuration.addAnnotatedClass(FailedRequest.class);
        this.sessionFactory = configuration.buildSessionFactory(new StandardServiceRegistryBuilder().build());
    }

    @Override
    public FailedRequest create(FailedRequest failedRequest, Optional<Long> ttl) {
        final Session session = sessionFactory.openSession();
        Transaction transaction = session.beginTransaction();
        session.save(failedRequest);
        transaction.commit();
        session.close();

//        session.getTransaction().begin();
//
//        Query query = session.createSQLQuery("INSERT INTO "
//                + "request_payload(id, payload, created_at) "
//                + "VALUES(:id, :json, :created_at)");
//        query.setParameter("id", payload.id);
//        query.setParameter("json", payload.json);
//        query.setParameter("created_at", new Date());
//        query.executeUpdate();
//
//        session.getTransaction().commit();
//        session.close();

        return failedRequest;
    }

    @Override
    public Optional<FailedRequest> get(String id) {
        if (StringUtils.isEmpty(id)) {
            return Optional.empty();
        }

        final Session session = sessionFactory.openSession();
        FailedRequest failedRequest = session.get(FailedRequest.class, id);
        session.close();

//        session.getTransaction().begin();
//        //Query q = session.createQuery("from Payload o where o.id = :id");
//        Query q = session.createSQLQuery("SELECT * FROM request_payload " +
//                "WHERE " +
//                "id = :id");
//        q.setParameter("id", id);
//        Payload org = (Payload) q.getSingleResult();
//        session.getTransaction().commit();

        return Optional.ofNullable(failedRequest);
    }

    @Override
    public FailedRequest update(FailedRequest failedRequest) {
        final Session session = sessionFactory.openSession();
        Transaction transaction = session.beginTransaction();
        session.update(failedRequest);
        transaction.commit();
        session.close();

//        session.getTransaction().begin();
//        Query query = session.createSQLQuery("UPDATE request_payload "
//                + "SET " +
//                "id = :id, " +
//                "payload = :json, " +
//                "created_at = :created_at " +
//                "WHERE " +
//                "id = :id");
//        query.setParameter("id", payload.id);
//        query.setParameter("json", payload.json);
//        query.setParameter("created_at", new Date());
//        query.executeUpdate();
//        session.getTransaction().commit();
//        session.close();

        return failedRequest;
    }

    @Override
    public Optional<FailedRequest> delete(String id) {
        final Session session = sessionFactory.openSession();
        Transaction transaction = session.beginTransaction();
        FailedRequest failedRequest = session.get(FailedRequest.class, id);
        session.delete(failedRequest);
        transaction.commit();
        session.close();

//        Session session = sessionFactory.openSession();
//        session.beginTransaction();
//        session.delete(failedRequest);
//        session.getTransaction().begin();
//        Query query = session.createSQLQuery("UPDATE request_payload "
//                + "SET " +
//                "id = :id, " +
//                "payload = :json, " +
//                "created_at = :created_at " +
//                "WHERE " +
//                "id = :id");
//        query.setParameter("id", payload.id);
//        query.setParameter("json", payload.json);
//        query.setParameter("created_at", new Date());
//        query.executeUpdate();
//        session.getTransaction().commit();
//        session.close();

        return Optional.ofNullable(failedRequest);
    }
}
