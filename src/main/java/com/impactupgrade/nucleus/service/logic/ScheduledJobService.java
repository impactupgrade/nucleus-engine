package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.AggregateCriteria;
import com.impactupgrade.nucleus.model.Criteria;
import com.impactupgrade.nucleus.model.JsonPathCriteria;
import com.impactupgrade.nucleus.model.SmsCampaignJobExecutor;
import com.impactupgrade.nucleus.model.Job;
import com.impactupgrade.nucleus.model.JobSchedule;
import com.impactupgrade.nucleus.util.HibernateUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;

import static com.impactupgrade.nucleus.model.JobType.SMS_CAMPAIGN;

public class ScheduledJobService {

    public static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";
    public static final String TIME_ZONE = "UTC";

    private static final Logger log = LogManager.getLogger(ScheduledJobService.class);

    private final HibernateDao<Long, Job> jobDao;
    private final HibernateDao<Long, JobSchedule> jobScheduleDao;

    public ScheduledJobService(SessionFactory sessionFactory) {
        this.jobDao = new HibernateDao<>(Job.class, sessionFactory);
        this.jobScheduleDao = new HibernateDao<>(JobSchedule.class, sessionFactory);
    }

    public void processJobSchedules(Environment environment) {
        String query = HibernateUtil.buildNativeQuery("job_schedule", buildJobScheduleCriteria());
        List<JobSchedule> jobSchedules = jobScheduleDao.getQueryResultList(query, true);

        if (CollectionUtils.isEmpty(jobSchedules)) {
            log.info("Could not find any active job schedules. Returning...");
        }
        for (JobSchedule jobSchedule : jobSchedules) {
            if (Objects.isNull(jobSchedule.job)) {
                // Should be unreachable
                log.warn("Job Schedule {} refers to un-existing job! Skipping...", jobSchedule.id);
            } else {
                processJobSchedule(jobSchedule, environment);
            }
        }
    }

    public void processJobSchedule(JobSchedule jobSchedule, Environment environment) {
        Job job = jobSchedule.job;
        log.info("Processing job {}...", job.id);
        if (SMS_CAMPAIGN == job.jobType) {
            // Process sms campaign job type
            new SmsCampaignJobExecutor(jobDao, environment).execute(job, jobSchedule);
        } else {
            log.warn("Job type {} is not yet supported!", job.jobType);
        }
    }

    // Utils
    private Criteria buildJobScheduleCriteria() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(DATE_FORMAT);
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone(TIME_ZONE));
        String dateString = simpleDateFormat.format(new Date());
        AggregateCriteria activeScheduleCriteria = new AggregateCriteria(
                Arrays.asList(
                        new JsonPathCriteria("payload", "start", "<=", dateString),
                        new JsonPathCriteria("payload", "stop", ">=", dateString)
                ), "and"
        );

        return activeScheduleCriteria;
    }

}
