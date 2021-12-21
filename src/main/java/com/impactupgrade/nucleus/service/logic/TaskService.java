package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.model.JsonPathCriteria;
import com.impactupgrade.nucleus.model.TaskProgress;
import com.impactupgrade.nucleus.model.TaskSchedule;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

public class TaskService {

    private static final Logger log = LogManager.getLogger(TaskService.class);
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    private final HibernateDao<Long, TaskSchedule> taskScheduleDao;
    private final HibernateDao<Long, TaskProgress> taskProgressDao;
    private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat(DATE_FORMAT);

    public TaskService(HibernateDao taskScheduleDao,
                       HibernateDao taskProgressDao) {
        this.taskScheduleDao = taskScheduleDao;
        this.taskProgressDao = taskProgressDao;
    }

    public List<TaskSchedule> findTaskSchedules(List<JsonPathCriteria> criterias) {
        String query = buildNativeQuery("public", "task_schedule", "payload", criterias);
        return taskScheduleDao.getQueryResultList(query, true);
    }

    public List<TaskProgress> findTaskProgresses(List<JsonPathCriteria> criterias) {
        String query = buildNativeQuery("public", "task_progress", "payload", criterias);
        return taskProgressDao.getQueryResultList(query, true);
    }

    // Utils
    private List<String> toConditions(String columnName, List<JsonPathCriteria> criterias) {
        if (CollectionUtils.isEmpty(criterias)) {
            return Collections.emptyList();
        }
        return criterias.stream().map(
                criteria -> {
                    String jsonPath = criteria.jsonPath;
                    String operator = criteria.operator;
                    String value = criteria.value instanceof Date ?
                            simpleDateFormat.format(criteria.value)
                            : criteria.value.toString();
                    jsonPath = jsonPath.replaceAll("\\.", "'->'");
                    String condition = columnName + " -> '" + jsonPath + "' " + operator + " '" + value + "'";
                    return replaceLastOccurence(condition, "->", "->>");
                }
        ).collect(Collectors.toList());
    }

    private String replaceLastOccurence(String input, String toReplace, String replacement) {
        int start = input.lastIndexOf(toReplace);
        StringBuilder builder = new StringBuilder();
        builder.append(input, 0, start);
        builder.append(replacement);
        builder.append(input.substring(start + toReplace.length()));
        return builder.toString();
    }

    private String buildNativeQuery(String schema, String entityTable, String jsonColumn, List<JsonPathCriteria> criteria) {
        String baseQuery = "select * from " + schema + "." + entityTable;
        StringBuilder stringBuilder = new StringBuilder(baseQuery);

        if (CollectionUtils.isNotEmpty(criteria)) {
            List<String> conditions = toConditions(jsonColumn, criteria);
            stringBuilder.append(buildWhereClause(conditions));
        }

        return stringBuilder.toString();
    }

    private String buildWhereClause(List<String> conditions) {
        StringBuilder stringBuilder = new StringBuilder();
        if (CollectionUtils.isNotEmpty(conditions)) {
            String where = " where " + conditions.stream()
                    .collect(Collectors.joining(" and "));
            stringBuilder.append(where);
        }
        return stringBuilder.toString();
    }

}
