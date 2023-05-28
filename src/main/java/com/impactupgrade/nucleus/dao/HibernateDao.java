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

  public E create(E entity) {
    final Session session = openSession();
    Transaction transaction = session.beginTransaction();
    session.saveOrUpdate(entity);
    transaction.commit();
    session.close();
    return entity;
  }

  public Optional<E> getById(I id) {
    try (Session session = openSession()) {
      E entity = session.get(clazz, id);
      return Optional.ofNullable(entity);
    } catch (NoResultException e) {
      return Optional.empty();
    }
  }

  public List<E> getAll() {
    final Session session = openSession();
    Query query = session.createQuery("from " + clazz.getName());
    List<E> entities = query.list();
    session.close();
    return entities;
  }

  public Optional<E> getQueryResult(String queryString, Consumer<Query> queryConsumer) {
    return getQueryResult(queryString, false, queryConsumer);
  }

  public Optional<E> getQueryResult(String queryString, boolean isNative, Consumer<Query> queryConsumer) {
    try (Session session = openSession()) {
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

  public List<E> getQueryResultList(String queryString, boolean isNative) {
    if (StringUtils.isEmpty(queryString)) {
      return Collections.emptyList();
    }

    final Session session = openSession();
    Query query = isNative ?
        session.createNativeQuery(queryString, clazz)
        : session.createQuery(queryString, clazz);

    List<E> entities = query.getResultList();
    session.close();
    return entities;
  }

  public List<E> getQueryResultList(String queryString, Consumer<Query> queryConsumer) {
    return getQueryResultList(queryString, queryConsumer, entities -> {});
  }

  public List<E> getQueryResultList(String queryString, Consumer<Query> queryConsumer, Consumer<List<E>> subselectConsumer) {
    final Session session = openSession();
    Query query = session.createQuery(queryString, clazz);
    queryConsumer.accept(query);
    List<E> entities = query.getResultList();
    // A little ridiculous, but this gives callers the opportunity to initialize any lazy collections using FetchMode.SUBSELECT.
    subselectConsumer.accept(entities);
    session.close();
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
    final Session session = openSession();
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
    final Session session = openSession();
    Transaction transaction = session.beginTransaction();
    session.delete(entity);
    transaction.commit();
    session.close();
  }

  public void deleteById(I id) {
    final Session session = openSession();
    Transaction transaction = session.beginTransaction();
    E entity = session.get(clazz, id);
    session.delete(entity);
    transaction.commit();
    session.close();
  }

  private Session openSession() {
    return sessionFactory.withOptions()
        .jdbcTimeZone(TimeZone.getTimeZone("UTC"))
        .openSession();
  }
}
