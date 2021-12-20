package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.model.Task;
import com.impactupgrade.nucleus.model.TaskCriteria;
import com.impactupgrade.nucleus.model.TaskProgress;
import com.impactupgrade.nucleus.model.TaskProgressCriteria;
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
    private final HibernateDao<Long, TaskProgress> taskProgressDao;

    public TaskService(HibernateDao taskConfigurationDao, HibernateDao taskProgressDao) {
        this.taskConfigurationDao = taskConfigurationDao;
        this.taskProgressDao = taskProgressDao;
    }

    public List<Task> getTaskConfigurations(TaskCriteria taskCriteria) {
        String baseQuery = "select * from public.task_configuration";
        StringBuilder stringBuilder = new StringBuilder(baseQuery);

        if (Objects.nonNull(taskCriteria)) {
            List<String> conditions = new ArrayList<>();
            conditions.addAll(toConditions(taskCriteria));
            conditions.addAll(toConditions("configuration", taskCriteria.jsonPathCriteria));

            stringBuilder.append(buildWhereClause(conditions));
        }

        return taskConfigurationDao.getQueryResultList(stringBuilder.toString());
    }

    public List<TaskProgress> getTaskProgresses(TaskProgressCriteria taskProgressCriteria) {
        String baseQuery = "select * from public.task_progress";
        StringBuilder stringBuilder = new StringBuilder(baseQuery);

        if (Objects.nonNull(taskProgressCriteria)) {
            List<String> conditions = new ArrayList<>();
            conditions.addAll(toConditions(taskProgressCriteria));
            conditions.addAll(toConditions("progress", taskProgressCriteria.jsonPathCriteria));

            stringBuilder.append(buildWhereClause(conditions));
        }

        return taskProgressDao.getQueryResultList(stringBuilder.toString());
    }

    // Utils
    private List<String> toConditions(TaskCriteria taskCriteria) {
        if (Objects.isNull(taskCriteria)) {
            return Collections.emptyList();
        }

        List<String> conditions = new ArrayList<>();
        if (Objects.nonNull(taskCriteria.taskType)) {
            conditions.add("task = '" + taskCriteria.taskType + "'");
        }
        if (!Strings.isNullOrEmpty(taskCriteria.orgId)) {
            conditions.add("org_id = '" + taskCriteria.orgId + "'");
        }
        return conditions;
    }

    private List<String> toConditions(TaskProgressCriteria taskProgressCriteria) {
        List<String> conditions = new ArrayList<>();
        conditions.addAll(toConditions((TaskCriteria) taskProgressCriteria));

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
