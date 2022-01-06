package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.model.Criteria;
import com.impactupgrade.nucleus.model.Task;
import com.impactupgrade.nucleus.model.TaskProgress;
import com.impactupgrade.nucleus.model.TaskSchedule;
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
                configuration.addAnnotatedClass(Task.class);
                configuration.addAnnotatedClass(TaskSchedule.class);
                configuration.addAnnotatedClass(TaskProgress.class);
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

    public static String buildNativeQuery(String schema, String entityTable, Criteria criteria) {
        String baseQuery = "select * from " + schema + "." + entityTable;
        StringBuilder stringBuilder = new StringBuilder(baseQuery);
        stringBuilder.append(buildWhereClause(criteria));
        return stringBuilder.toString();
    }

    public static String buildWhereClause(Criteria criteria) {
        String where = "";
        String sqlString = criteria.toSqlString();
        if (!Strings.isNullOrEmpty(sqlString)) {
            where = " where " + sqlString;
        }
        return where;
    }

}
