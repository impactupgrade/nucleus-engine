package com.impactupgrade.nucleus.dao;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class HibernateDao<I extends Serializable, E> {

    private final Class<E> clazz;
    private final SessionFactory sessionFactory;

    public HibernateDao(Class<E> clazz, SessionFactory sessionFactory) {
        this.clazz = clazz;
        this.sessionFactory = sessionFactory;
    }

    public E create(E entity) {
        final Session session = sessionFactory.openSession();
        Transaction transaction = session.beginTransaction();
        session.saveOrUpdate(entity);
        transaction.commit();
        session.close();
        return entity;
    }

    public Optional<E> getById(I id) {
        final Session session = sessionFactory.openSession();
        E entity = session.get(clazz, id);
        session.close();
        return Optional.ofNullable(entity);
    }

    public List<E> getAll() {
        final Session session = sessionFactory.openSession();
        Query query = session.createQuery("from " + clazz.getName());
        List<E> entities = query.list();
        session.close();
        return entities;
    }

    public E update(E entity) {
        if (Objects.isNull(entity)) {
            return null;
        }
        final Session session = sessionFactory.openSession();
        Transaction transaction = session.beginTransaction();
        session.update(entity);
        transaction.commit();
        session.close();
        return entity;
    }

    public void delete(E entity) {
        if (Objects.isNull(entity)) {
            return;
        }
        final Session session = sessionFactory.openSession();
        Transaction transaction = session.beginTransaction();
        session.delete(entity);
        transaction.commit();
        session.close();
    }

    public void deleteById(I id) {
        final Session session = sessionFactory.openSession();
        Transaction transaction = session.beginTransaction();
        E entity = session.get(clazz, id);
        session.delete(entity);
        transaction.commit();
        session.close();
    }

    public Optional<E> getQueryResult(String queryString) {
        return getQueryResult(queryString, false);
    }

    public Optional<E> getQueryResult(String queryString, boolean isNative) {
        // TODO:
        return Optional.empty();
    }

    public List<E> getQueryResultList(String queryString) {
        return getQueryResultList(queryString, false);
    }

    public List<E> getQueryResultList(String queryString, boolean isNative) {
        if (StringUtils.isEmpty(queryString)) {
            return Collections.emptyList();
        }

        final Session session = sessionFactory.openSession();
        Query query = isNative ?
                session.createNativeQuery(queryString, clazz)
                : session.createQuery(queryString, clazz);

        List<E> entities = query.getResultList();
        session.close();
        return entities;
    }

}
