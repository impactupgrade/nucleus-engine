package com.impactupgrade.nucleus.dao;

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
      final Configuration configuration = new Configuration();

      // core
      configuration.addAnnotatedClass(Job.class);
      configuration.addAnnotatedClass(JobProgress.class);
      configuration.addAnnotatedClass(Organization.class);

      return configuration.buildSessionFactory(new StandardServiceRegistryBuilder().build());
    } catch (Throwable e) {
      log.error("Failed to create session factory: {}", e.getMessage());
      return null;
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
