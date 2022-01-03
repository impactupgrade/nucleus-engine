package com.impactupgrade.nucleus.util;

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
}
