package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.model.FutureTask;
import org.hibernate.SessionFactory;

import java.util.List;

public class ScheduledTasksService {

    private final HibernateDao<Long, FutureTask> futureTaskDao;

    public ScheduledTasksService(SessionFactory sessionFactory) {
        this.futureTaskDao = new HibernateDao<>(FutureTask.class, sessionFactory);
    }

    public List<FutureTask> getTasksToProcess() {
        return futureTaskDao.getQueryResultList("from FutureTask where scheduledAt < current_date");
    }

}
