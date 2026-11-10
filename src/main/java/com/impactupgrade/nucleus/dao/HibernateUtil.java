/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.dao;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobProgress;
import com.impactupgrade.nucleus.entity.Organization;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HibernateUtil {

  private static final Logger log = LoggerFactory.getLogger(HibernateUtil.class);

  private static SessionFactory sessionFactory;

  private static SessionFactory createSessionFactory() {
    try {
      log.info("building the default SessionFactory");

      final Configuration configuration = new Configuration();

      // core
      configuration.addAnnotatedClass(Job.class);
      configuration.addAnnotatedClass(JobProgress.class);
      configuration.addAnnotatedClass(Organization.class);

      // hibernate.properties is loaded by the registry builder, so env var overrides must be applied there (not on
      // the Configuration, which buildSessionFactory(ServiceRegistry) ignores)
      final StandardServiceRegistryBuilder registryBuilder = new StandardServiceRegistryBuilder();
      applyEnv(registryBuilder, "hibernate.hikari.dataSource.url", "DATABASE_URL");
      applyEnv(registryBuilder, "hibernate.hikari.dataSource.user", "DATABASE_USER");
      applyEnv(registryBuilder, "hibernate.hikari.dataSource.password", "DATABASE_PASSWORD");

      return configuration.buildSessionFactory(registryBuilder.build());
    } catch (Throwable e) {
      log.error("failed to create the default SessionFactory: {}", e.getMessage());
      return null;
    }
  }

  private static void applyEnv(StandardServiceRegistryBuilder registryBuilder, String setting, String envVar) {
    String value = System.getenv(envVar);
    if (!Strings.isNullOrEmpty(value)) {
      registryBuilder.applySetting(setting, value);
    }
  }

  // allow custom impls to provide their own, if needed
  public static void setSessionFactory(SessionFactory _sessionFactory) {
    sessionFactory = _sessionFactory;
  }

  public static SessionFactory getSessionFactory() {
    if (sessionFactory == null) {
      sessionFactory = createSessionFactory();
    }

    return sessionFactory;
  }

  public static void resetSessionFactory() {
    sessionFactory = null;
  }
}
