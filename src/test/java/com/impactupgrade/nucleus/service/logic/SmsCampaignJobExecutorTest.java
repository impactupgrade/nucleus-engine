package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.AbstractMockTest;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.dao.HibernateUtil;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobFrequency;
import com.impactupgrade.nucleus.entity.JobProgress;
import com.impactupgrade.nucleus.entity.JobSequenceOrder;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

public class SmsCampaignJobExecutorTest extends AbstractMockTest {

  @Test
  public void testOneTime() throws Exception {
    Environment env = new DefaultEnvironment();
    SessionFactory sessionFactory = HibernateUtil.getSessionFactory();
    HibernateDao<Long, Job> jobDao = new HibernateDao<>(Job.class, sessionFactory);
    HibernateDao<Long, JobProgress> jobProgressDao = new HibernateDao<>(JobProgress.class, sessionFactory);

    ScheduledJobService service = new ScheduledJobService(env, sessionFactory);

    Job job = new Job();
    job.jobType = JobType.SMS_CAMPAIGN;
    job.status = JobStatus.ACTIVE;
    job.scheduleFrequency = JobFrequency.ONETIME;
    job.scheduleStart = Instant.now();
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

    service.processJobSchedules(Instant.now());

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.DONE, job.status);

    List<JobProgress> jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("contact1", jobProgresses.get(0).targetId);
    assertEquals("contact2", jobProgresses.get(1).targetId);
  }

  @Test
  public void testSequence() throws Exception {
    // Bit of a hack. We use this to
    Instant originalNow = Instant.now();

    Environment env = new DefaultEnvironment();
    SessionFactory sessionFactory = HibernateUtil.getSessionFactory();
    HibernateDao<Long, Organization> organizationDao = new HibernateDao<>(Organization.class, sessionFactory);
    HibernateDao<Long, Job> jobDao = new HibernateDao<>(Job.class, sessionFactory);
    HibernateDao<Long, JobProgress> jobProgressDao = new HibernateDao<>(JobProgress.class, sessionFactory);

    ScheduledJobService service = new ScheduledJobService(env, sessionFactory);

    Organization org = new Organization();
    org.setId(1);
    org.setNucleusApiKey(env.getConfig().apiKey);
    organizationDao.create(org);

    Job job = new Job();
    job.org = org;
    job.jobType = JobType.SMS_CAMPAIGN;
    // TODO: Another test with INACTIVE, making sure nothing fires.
    job.status = JobStatus.ACTIVE;
    job.scheduleFrequency = JobFrequency.DAILY;
    job.scheduleInterval = 2;
    // TODO: The -30s is a little ridiculous. But we run into timing issues if the start time and time used to kick
    //  off the job are identical.
    job.scheduleStart = originalNow.plus(2, ChronoUnit.DAYS).minus(30, ChronoUnit.SECONDS);
    job.scheduleEnd = originalNow.plus(30, ChronoUnit.DAYS).minus(30, ChronoUnit.SECONDS);
    // TODO: Another test with NEXT
    job.sequenceOrder = JobSequenceOrder.BEGINNING;
    job.jobProgresses = List.of();
    job.payload = MAPPER.readTree("""
        {
          "crm_list": "list1234",
          "messages": [
            {
              "id": 1,
              "seq": 1,
              "languages": {
                "EN": {"message": "This is message number 1."},
                "ES": {"message": "Este es el mensaje número 1."}
              }
            },
            {
              "id": 2,
              "seq": 2,
              "languages": {
                "EN": {"message": "This is message number 2."},
                "ES": {"message": "Este es el mensaje número 2."}
              }
            },
            {
              "id": 3,
              "seq": 3,
              "languages": {
                "EN": {"message": "This is message number 3."},
                "ES": {"message": "Este es el mensaje número 3."}
              }
            }
          ]
        }
    """);
    jobDao.create(job);

    List<CrmContact> contacts = new ArrayList<>();
    contacts.add(crmContact("contact1", "EN"));
    contacts.add(crmContact("contact2", "ES"));
    lenient().when(crmServiceMock.getContactsFromList("list1234")).thenReturn(contacts);

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 0
    // CONTACT LIST SIZE = 2
    //
    // RESULT: nothing fires
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    Instant now = originalNow;
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);

    List<JobProgress> jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 0.5 (simulate crontab running multiple times a day)
    // CONTACT LIST SIZE = 2
    //
    // RESULT: nothing fires
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(1, ChronoUnit.HALF_DAYS);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 2 minus 5 min (simulate a run immediately prior to the start time)
    // CONTACT LIST SIZE = 2
    //
    // RESULT: nothing fires
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(2, ChronoUnit.DAYS).minus(5, ChronoUnit.MINUTES);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 2
    // CONTACT LIST SIZE = 2
    //
    // RESULT: 2 contacts get the next message
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(2, ChronoUnit.DAYS);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("contact1", jobProgresses.get(0).targetId);
    String contact1ProgressPayload = jobProgresses.get(0).payload.toString();
    assertTrue(contact1ProgressPayload.contains("\"lastMessage\":1,"));
    assertTrue(contact1ProgressPayload.contains("\"sentMessages\":[1]"));
    assertEquals("contact2", jobProgresses.get(1).targetId);
    String contact2ProgressPayload = jobProgresses.get(1).payload.toString();
    assertTrue(contact2ProgressPayload.contains("\"lastMessage\":1,"));
    assertTrue(contact2ProgressPayload.contains("\"sentMessages\":[1]"));

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 2 plus 15 min (simulate crontab running multiple times a day)
    // CONTACT LIST SIZE = 2
    //
    // RESULT: nothing new should fire
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(2, ChronoUnit.DAYS).plus(5, ChronoUnit.MINUTES);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("contact1", jobProgresses.get(0).targetId);
    assertEquals(contact1ProgressPayload, jobProgresses.get(0).payload.toString());
    assertEquals("contact2", jobProgresses.get(1).targetId);
    assertEquals(contact2ProgressPayload, jobProgresses.get(1).payload.toString());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 3
    // CONTACT LIST SIZE = 2
    //
    // RESULT: nothing new should fire (job's interval is every-2-days)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(3, ChronoUnit.DAYS);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("contact1", jobProgresses.get(0).targetId);
    assertEquals(contact1ProgressPayload, jobProgresses.get(0).payload.toString());
    assertEquals("contact2", jobProgresses.get(1).targetId);
    assertEquals(contact2ProgressPayload, jobProgresses.get(1).payload.toString());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 4
    // CONTACT LIST SIZE = 2
    //
    // RESULT: message 2 should fire
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(4, ChronoUnit.DAYS);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("contact1", jobProgresses.get(0).targetId);
    contact1ProgressPayload = jobProgresses.get(0).payload.toString();
    assertTrue(contact1ProgressPayload.contains("\"lastMessage\":2,"));
    assertTrue(contact1ProgressPayload.contains("\"sentMessages\":[1,2]"));
    assertEquals("contact2", jobProgresses.get(1).targetId);
    contact2ProgressPayload = jobProgresses.get(1).payload.toString();
    assertTrue(contact2ProgressPayload.contains("\"lastMessage\":2,"));
    assertTrue(contact2ProgressPayload.contains("\"sentMessages\":[1,2]"));

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 5
    // CONTACT LIST SIZE = 3 (a new contact entered in the middle of the current interval cadence)
    //
    // RESULT: contacts 1-2 don't receive anything, contact 3 starts at message 1
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    contacts.add(crmContact("contact3", "EN"));

    now = originalNow.plus(5, ChronoUnit.DAYS);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(3, jobProgresses.size());
    assertEquals("contact1", jobProgresses.get(0).targetId);
    assertEquals(contact1ProgressPayload, jobProgresses.get(0).payload.toString());
    assertEquals("contact2", jobProgresses.get(1).targetId);
    assertEquals(contact2ProgressPayload, jobProgresses.get(1).payload.toString());
    assertEquals("contact3", jobProgresses.get(2).targetId);
    String contact3ProgressPayload = jobProgresses.get(2).payload.toString();
    assertTrue(contact3ProgressPayload.contains("\"lastMessage\":1,"));
    assertTrue(contact3ProgressPayload.contains("\"sentMessages\":[1]"));

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 6
    // CONTACT LIST SIZE = 3 (a new contact entered in the middle of the current interval cadence)
    //
    // RESULT: contacts 1-2 receive message 3, contact 3 stays at message 1
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(6, ChronoUnit.DAYS);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(3, jobProgresses.size());
    assertEquals("contact1", jobProgresses.get(0).targetId);
    contact1ProgressPayload = jobProgresses.get(0).payload.toString();
    assertTrue(contact1ProgressPayload.contains("\"lastMessage\":3,"));
    assertTrue(contact1ProgressPayload.contains("\"sentMessages\":[1,2,3]"));
    assertEquals("contact2", jobProgresses.get(1).targetId);
    contact2ProgressPayload = jobProgresses.get(1).payload.toString();
    assertTrue(contact2ProgressPayload.contains("\"lastMessage\":3,"));
    assertTrue(contact2ProgressPayload.contains("\"sentMessages\":[1,2,3]"));
    assertEquals("contact3", jobProgresses.get(2).targetId);
    // TODO: Failing due to getNextFireTime (see TODO there).
    assertEquals(contact3ProgressPayload, jobProgresses.get(2).payload.toString());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 7
    // CONTACT LIST SIZE = 3 (a new contact entered in the middle of the current interval cadence)
    //
    // RESULT: contacts 1-2 don't receive anything, contact 3 starts at message 1
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(7, ChronoUnit.DAYS);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(3, jobProgresses.size());
    assertEquals("contact1", jobProgresses.get(0).targetId);
    assertEquals(contact1ProgressPayload, jobProgresses.get(0).payload.toString());
    assertEquals("contact2", jobProgresses.get(1).targetId);
    assertEquals(contact2ProgressPayload, jobProgresses.get(1).payload.toString());
    assertEquals("contact3", jobProgresses.get(2).targetId);
    contact3ProgressPayload = jobProgresses.get(2).payload.toString();
    assertTrue(contact3ProgressPayload.contains("\"lastMessage\":2"));
    assertTrue(contact3ProgressPayload.contains("\"sentMessages\":[1,2]"));
  }

  private CrmContact crmContact(String id, String lang) {
    CrmContact crmContact = new CrmContact(id);
    crmContact.mobilePhone = "+12345678900";
    crmContact.contactLanguage = lang;
    return crmContact;
  }
}
