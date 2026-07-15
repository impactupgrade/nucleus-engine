/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.dao;

import com.google.common.base.Strings;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;
import org.springframework.util.StringUtils;

import javax.persistence.NoResultException;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.Consumer;

public class HibernateDao<I extends Serializable, E> {

  private final Class<E> clazz;
  private final SessionFactory sessionFactory;

  public HibernateDao(Class<E> clazz) {
    this.clazz = clazz;
    this.sessionFactory = HibernateUtil.getSessionFactory();
  }

  public HibernateDao(Class<E> clazz, SessionFactory sessionFactory) {
    this.clazz = clazz;
    this.sessionFactory = sessionFactory;
  }

  public E insert(E entity) {
    try (Session session = openSession()) {
      Transaction transaction = session.beginTransaction();
      insert(entity, session);
      transaction.commit();
      return entity;
    }
  }

  public E insert(E entity, Session session) {
    session.save(entity);
    return entity;
  }

  public Optional<E> getById(I id) {
    try (Session session = openSession()) {
      return getById(id, session);
    }
  }

  public Optional<E> getById(I id, Session session) {
    try {
      E entity = session.get(clazz, id);
      return Optional.ofNullable(entity);
    } catch (NoResultException e) {
      return Optional.empty();
    }
  }

  public List<E> getAll() {
    try (Session session = openSession()) {
      return getAll(session);
    }
  }

  public List<E> getAll(Session session) {
    return session.createQuery("from " + clazz.getName()).list();
  }

  public Optional<E> getQueryResult(String queryString) {
    return getQueryResult(queryString, false, query -> {});
  }

  public Optional<E> getQueryResult(String queryString, Session session) {
    return getQueryResult(queryString, false, query -> {}, session);
  }

  public Optional<E> getQueryResult(String queryString, boolean isNative) {
    return getQueryResult(queryString, isNative, query -> {});
  }

  public Optional<E> getQueryResult(String queryString, boolean isNative, Session session) {
    return getQueryResult(queryString, isNative, query -> {}, session);
  }

  public Optional<E> getQueryResult(String queryString, Consumer<Query> queryConsumer) {
    return getQueryResult(queryString, false, queryConsumer);
  }

  public Optional<E> getQueryResult(String queryString, Consumer<Query> queryConsumer, Session session) {
    return getQueryResult(queryString, false, queryConsumer, session);
  }

  public Optional<E> getQueryResult(String queryString, boolean isNative, Consumer<Query> queryConsumer) {
    try (Session session = openSession()) {
      return getQueryResult(queryString, isNative, queryConsumer, session);
    }
  }

  public Optional<E> getQueryResult(String queryString, boolean isNative, Consumer<Query> queryConsumer,
      Session session) {
    try {
      Query<E> query = isNative ?
          session.createNativeQuery(queryString, clazz)
          : session.createQuery(queryString, clazz);
      queryConsumer.accept(query);
      return Optional.ofNullable(query.getSingleResult());
    } catch (NoResultException e) {
      return Optional.empty();
    }
  }

  public List<E> getQueryResultList(String queryString) {
    return getQueryResultList(queryString, false);
  }

  public List<E> getQueryResultList(String queryString, Session session) {
    return getQueryResultList(queryString, false, session);
  }

  public List<E> getQueryResultList(String queryString, boolean isNative) {
    try (Session session = openSession()) {
      return getQueryResultList(queryString, isNative, session);
    }
  }

  public List<E> getQueryResultList(String queryString, boolean isNative, Session session) {
    if (StringUtils.isEmpty(queryString)) {
      return Collections.emptyList();
    }

    Query query = isNative ?
        session.createNativeQuery(queryString, clazz)
        : session.createQuery(queryString, clazz);

    return query.getResultList();
  }

  public List<E> getQueryResultList(String queryString, Consumer<Query> queryConsumer) {
    return getQueryResultList(queryString, queryConsumer, entities -> {});
  }

  public List<E> getQueryResultList(String queryString, Consumer<Query> queryConsumer, Session session) {
    return getQueryResultList(queryString, queryConsumer, entities -> {}, session);
  }

  public List<E> getQueryResultList(String queryString, Consumer<Query> queryConsumer, Consumer<List<E>> subselectConsumer) {
    try (Session session = openSession()) {
      return getQueryResultList(queryString, queryConsumer, subselectConsumer, session);
    }
  }

  public List<E> getQueryResultList(String queryString, Consumer<Query> queryConsumer, Consumer<List<E>> subselectConsumer, Session session) {
    Query query = session.createQuery(queryString, clazz);
    queryConsumer.accept(query);
    List<E> entities = query.getResultList();
    // A little ridiculous, but this gives callers the opportunity to initialize any lazy collections using FetchMode.SUBSELECT.
    subselectConsumer.accept(entities);
    return entities;
  }

  public String buildNativeQuery(String entityTable, Criteria criteria) {
    String baseQuery = "select * from " + entityTable;
    StringBuilder stringBuilder = new StringBuilder(baseQuery);
    String criteriaString = criteria.toSqlString();
    if (!Strings.isNullOrEmpty(criteriaString)) {
      stringBuilder.append(" where " + criteriaString);
    }
    return stringBuilder.toString();
  }

  public E update(E entity) {
    if (Objects.isNull(entity)) {
      return null;
    }
    try (Session session = openSession()) {
      Transaction transaction = session.beginTransaction();
      update(entity, session);
      transaction.commit();
      return entity;
    }
  }

  public E update(E entity, Session session) {
    if (Objects.isNull(entity)) {
      return null;
    }
    session.update(entity);
    return entity;
  }

  public void delete(E entity) {
    if (Objects.isNull(entity)) {
      return;
    }
    try (Session session = openSession()) {
      Transaction transaction = session.beginTransaction();
      delete(entity, session);
      transaction.commit();
    }
  }

  public void delete(E entity, Session session) {
    if (Objects.isNull(entity)) {
      return;
    }
    session.delete(entity);
  }

  public void deleteById(I id) {
    try (Session session = openSession()) {
      Transaction transaction = session.beginTransaction();
      deleteById(id, session);
      transaction.commit();
    }
  }

  public void deleteById(I id, Session session) {
    E entity = session.get(clazz, id);
    session.delete(entity);
  }

  public Session openSession() {
    return sessionFactory.withOptions()
        .jdbcTimeZone(TimeZone.getTimeZone("UTC"))
        .openSession();
  }
}
