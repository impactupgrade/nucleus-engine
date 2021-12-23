package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.model.Criteria;
import com.impactupgrade.nucleus.model.TaskProgress;
import com.impactupgrade.nucleus.model.TaskSchedule;

import java.util.List;

public class TaskService {

    private final HibernateDao<Long, TaskSchedule> taskScheduleDao;
    private final HibernateDao<Long, TaskProgress> taskProgressDao;


    public TaskService(HibernateDao taskScheduleDao,
                       HibernateDao taskProgressDao) {
        this.taskScheduleDao = taskScheduleDao;
        this.taskProgressDao = taskProgressDao;
    }

    public List<TaskSchedule> findTaskSchedules(Criteria criteria) {
        String query = buildNativeQuery("public", "task_schedule", criteria);
        return taskScheduleDao.getQueryResultList(query, true);
    }

    public List<TaskProgress> findTaskProgresses(Criteria criteria) {
        String query = buildNativeQuery("public", "task_progress", criteria);
        return taskProgressDao.getQueryResultList(query, true);
    }

    // Utils
    private String buildNativeQuery(String schema, String entityTable, Criteria criteria) {
        String baseQuery = "select * from " + schema + "." + entityTable;
        StringBuilder stringBuilder = new StringBuilder(baseQuery);
        stringBuilder.append(buildWhereClause(criteria));
        return stringBuilder.toString();
    }

    private String buildWhereClause(Criteria criteria) {
        String where = "";
        String sqlString = criteria.toSqlString();
        if (!Strings.isNullOrEmpty(sqlString)) {
            where = " where " + sqlString;
        }
        return where;
    }

}
