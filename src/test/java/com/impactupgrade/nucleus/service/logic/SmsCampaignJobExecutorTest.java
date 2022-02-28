package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.AbstractMockTest;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.dao.HibernateUtil;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobFrequency;
import com.impactupgrade.nucleus.entity.JobProgress;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

public class SmsCampaignJobExecutorTest extends AbstractMockTest {

  @Test
  public void testOneTime() throws Exception {
    EnvironmentConfig envConfig = new EnvironmentConfig();
    Environment env = new DefaultEnvironment() {
      @Override
      public EnvironmentConfig getConfig() {
        return envConfig;
      }
    };
    SessionFactory sessionFactory = HibernateUtil.getSessionFactory();
    HibernateDao<Long, Job> jobDao = new HibernateDao<>(Job.class, sessionFactory);
    HibernateDao<Long, JobProgress> jobProgressDao = new HibernateDao<>(JobProgress.class, sessionFactory);

    SmsCampaignJobExecutor executor = new SmsCampaignJobExecutor(env, sessionFactory);

    Job job = new Job();
    job.jobType = JobType.SMS_CAMPAIGN;
    job.status = JobStatus.ACTIVE;
    job.frequency = JobFrequency.ONETIME;
    job.start = Instant.now();
    job.jobProgresses = List.of();
    job.payload = MAPPER.readTree("""
        {
          "crm_list": "list1234",
          "messages": [
            {
              "id": 1,
              "seq": 1,
              "languages": {
                "EN": {"message": "marketing message blast 1"}
              }
            },
            {
              "id": 2,
              "seq": 2,
              "languages": {
                "EN": {"message": "marketing message blast 2"}
              }
            }
          ]
        }
    """);
    jobDao.create(job);

    CrmContact contact1 = new CrmContact("contact1");
    CrmContact contact2 = new CrmContact("contact2");
    when(crmServiceMock.getContactsFromList("list1234")).thenReturn(List.of(contact1, contact2));

    executor.execute(job);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.DONE, job.status);

    List<JobProgress> jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("contact1", jobProgresses.get(0).targetId);
    assertEquals("contact2", jobProgresses.get(1).targetId);
  }
}
