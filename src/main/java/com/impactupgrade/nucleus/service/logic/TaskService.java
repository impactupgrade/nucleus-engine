package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.model.Task;
import com.impactupgrade.nucleus.model.TaskBaseCriteria;
import com.impactupgrade.nucleus.model.TaskProgress;
import com.impactupgrade.nucleus.model.TaskProgressCriteria;
import com.impactupgrade.nucleus.model.TaskSchedule;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class TaskService {

    private final HibernateDao<Long, Task> taskConfigurationDao;
    private final HibernateDao<Long, TaskSchedule> taskScheduleDao;
    private final HibernateDao<Long, TaskProgress> taskProgressDao;

    public TaskService(HibernateDao taskConfigurationDao,
                       HibernateDao taskScheduleDao,
                       HibernateDao taskProgressDao) {
        this.taskConfigurationDao = taskConfigurationDao;
        this.taskScheduleDao = taskScheduleDao;
        this.taskProgressDao = taskProgressDao;
    }

    public List<TaskSchedule> findTaskSchedules(TaskBaseCriteria taskBaseCriteria) {
        String query = buildNativeQuery(taskBaseCriteria, "public", "task_schedule", "payload");
        return taskScheduleDao.getQueryResultList(query, true);
    }

    public List<TaskProgress> findTaskProgresses(TaskProgressCriteria taskProgressCriteria) {
        String baseQuery = "select * from public.task_progress";
        StringBuilder stringBuilder = new StringBuilder(baseQuery);

        if (Objects.nonNull(taskProgressCriteria)) {
            List<String> conditions = new ArrayList<>();
            conditions.addAll(toConditions(taskProgressCriteria));
            conditions.addAll(toConditions("payload", taskProgressCriteria.jsonPathCriteria));

            stringBuilder.append(buildWhereClause(conditions));
        }

        return taskProgressDao.getQueryResultList(stringBuilder.toString(), true);
    }

    // Utils
    private List<String> toConditions(TaskBaseCriteria taskBaseCriteria) {
        if (Objects.isNull(taskBaseCriteria)) {
            return Collections.emptyList();
        }

        List<String> conditions = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(taskBaseCriteria.taskIds)) {
            String ids = taskBaseCriteria.taskIds.stream()
                    .map(id -> id.toString())
                    .collect(Collectors.joining(","));
            conditions.add("task_id in (" + ids + ")");
        }
        return conditions;
    }

    private List<String> toConditions(TaskProgressCriteria taskProgressCriteria) {
        List<String> conditions = new ArrayList<>();
        conditions.addAll(toConditions((TaskBaseCriteria) taskProgressCriteria));

        if (!Strings.isNullOrEmpty(taskProgressCriteria.contactId)) {
            conditions.add("contact_id = '" + taskProgressCriteria.contactId + "'");
        }
        return conditions;
    }

    private List<String> toConditions(String columnName, Map<String, String> jsonPathCriteria) {
        if (MapUtils.isEmpty(jsonPathCriteria)) {
            return Collections.emptyList();
        }
        return jsonPathCriteria.entrySet().stream()
                .map(e -> {
                    String jsonPath = e.getKey();
                    String value = e.getValue();
                    jsonPath = jsonPath.replaceAll("\\.", "'->'");
                    return "UPPER(" + columnName + " ->'" + jsonPath + "'->>0) = UPPER('" + value + "')";
                }).collect(Collectors.toList());
    }

    private String buildNativeQuery(TaskBaseCriteria taskBaseCriteria, String schema, String entityTable, String jsonColumn) {
        String baseQuery = "select * from " + schema + "." + entityTable;
        StringBuilder stringBuilder = new StringBuilder(baseQuery);

        if (Objects.nonNull(taskBaseCriteria)) {
            List<String> conditions = new ArrayList<>();
            conditions.addAll(toConditions(taskBaseCriteria));
            conditions.addAll(toConditions(jsonColumn, taskBaseCriteria.jsonPathCriteria));

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
