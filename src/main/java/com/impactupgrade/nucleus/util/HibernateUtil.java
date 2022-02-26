package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.model.Criteria;
import com.impactupgrade.nucleus.model.Job;
import com.impactupgrade.nucleus.model.JobProgress;
import com.impactupgrade.nucleus.model.JobSchedule;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HibernateUtil {

    private static final Logger log = LoggerFactory.getLogger(HibernateUtil.class);

    private static SessionFactory sessionFactory = createSessionFactory();

    private static SessionFactory createSessionFactory() {
        if (sessionFactory == null) {
            try {
                final Configuration configuration = new Configuration();
                configuration.addAnnotatedClass(Job.class);
                configuration.addAnnotatedClass(JobSchedule.class);
                configuration.addAnnotatedClass(JobProgress.class);
                sessionFactory = configuration.buildSessionFactory(new StandardServiceRegistryBuilder().build());
            } catch (Throwable e) {
                log.info("Failed to create session factory (DB may not be configured): {}", e.getMessage());
            }
        }

        return sessionFactory;
    }

    public static SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public static String buildNativeQuery(String entityTable, Criteria criteria) {
        String baseQuery = "select * from " + entityTable;
        StringBuilder stringBuilder = new StringBuilder(baseQuery);
        String criteriaString = criteria.toSqlString();
        if (!Strings.isNullOrEmpty(criteriaString)) {
            stringBuilder.append(" where " + criteriaString);
        }
        return stringBuilder.toString();
    }

}
