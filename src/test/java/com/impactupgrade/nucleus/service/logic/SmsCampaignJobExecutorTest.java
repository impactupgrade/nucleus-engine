package com.impactupgrade.nucleus.service.logic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.impactupgrade.nucleus.AbstractMockTest;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobFrequency;
import com.impactupgrade.nucleus.entity.JobProgress;
import com.impactupgrade.nucleus.entity.JobSequenceOrder;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SmsCampaignJobExecutorTest extends AbstractMockTest {

  @Test
  public void testOneTime() throws Exception {
    Environment env = new DefaultEnvironment();
    HibernateDao<Long, Job> jobDao = new HibernateDao<>(Job.class);
    HibernateDao<Long, JobProgress> jobProgressDao = new HibernateDao<>(JobProgress.class);
    HibernateDao<Long, Organization> organizationDao = new HibernateDao<>(Organization.class);

    ScheduledJobService service = new ScheduledJobService(env);

    Organization org = new Organization();
    org.setId(1);
    org.setNucleusApiKey(env.getConfig().apiKey);
    organizationDao.insert(org);

    Job job = new Job();
    job.org = org;
    job.jobType = JobType.SMS_CAMPAIGN;
    job.status = JobStatus.ACTIVE;
    job.scheduleFrequency = JobFrequency.ONETIME;
    job.scheduleStart = Instant.now();
    job.jobProgresses = List.of();
    job.payload = MAPPER.readTree("""
        {
          "crm_list": "list1234",
          "languages": [
            {
              "code": "EN",
              "default": true
            }
          ],
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
    job.traceId = UUID.randomUUID().toString();
    job.startedBy = "IT test";
    job.jobName = "Test job";
    job.originatingPlatform = "IT test";
    job.startedAt = Instant.now();
    jobDao.insert(job);

    CrmContact contact1 = crmContact("contact1", "+12345678901", "EN");
    CrmContact contact2 = crmContact("contact2", "+12345678902", "EN");
    CrmContact contact3 = crmContact("contact3", "+12345678902", "EN"); // dup
    CrmContact contact4 = crmContact("contact4", "12345678902", "EN"); // dup
    CrmContact contact5 = crmContact("contact5", "2345678902", "EN"); // dup
    CrmContact contact6 = crmContact("contact6", "(234) 567-8902", "EN"); // dup
    CrmContact contact7 = crmContact("contact7", "234.567.8902", "EN"); // dup
    CrmContact contact8 = crmContact("contact8", "+1 234 567 8902", "EN"); // dup
    when(crmServiceMock.getContactsFromList("list1234")).thenReturn(List.of(contact1, contact2, contact3, contact4, contact5, contact6, contact7, contact8));

    service.processJobSchedules(Instant.now());

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.DONE, job.status);

    List<JobProgress> jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("+12345678901", jobProgresses.get(0).targetId);
    assertEquals("+12345678902", jobProgresses.get(1).targetId);
  }

  @Test
  public void testSequenceWithBeginning() throws Exception {
    testSequence(JobSequenceOrder.BEGINNING);
  }

  @Test
  public void testSequenceWithNext() throws Exception {
    testSequence(JobSequenceOrder.NEXT);
  }

  private void testSequence(JobSequenceOrder sequenceOrder) throws Exception {
    // Bit of a hack. We use this to
    Instant originalNow = Instant.now();

    Environment env = new DefaultEnvironment();
    HibernateDao<Long, Organization> organizationDao = new HibernateDao<>(Organization.class);
    HibernateDao<Long, Job> jobDao = new HibernateDao<>(Job.class);
    HibernateDao<Long, JobProgress> jobProgressDao = new HibernateDao<>(JobProgress.class);

    ScheduledJobService service = new ScheduledJobService(env);

    Organization org = new Organization();
    org.setId(1);
    org.setNucleusApiKey(env.getConfig().apiKey);
    organizationDao.insert(org);

    Job job = new Job();
    job.org = org;
    job.jobType = JobType.SMS_CAMPAIGN;
    job.status = JobStatus.ACTIVE;
    job.scheduleFrequency = JobFrequency.DAILY;
    job.scheduleInterval = 2;
    // TODO: The -30s is a little ridiculous. But we run into timing issues if the start time and time used to kick
    //  off the job are identical.
    job.scheduleStart = originalNow.plus(2, ChronoUnit.DAYS).minus(30, ChronoUnit.SECONDS);
    job.scheduleEnd = originalNow.plus(30, ChronoUnit.DAYS).minus(30, ChronoUnit.SECONDS);
    job.sequenceOrder = sequenceOrder;
    job.jobProgresses = List.of();
    job.payload = MAPPER.readTree("""
        {
          "crm_list": "list1234",
          "languages": [
            {
              "code": "EN",
              "default": true
            },
            {
              "code": "ES",
              "default": false
            }
          ],
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
    job.traceId = UUID.randomUUID().toString();
    job.startedBy = "IT test";
    job.jobName = "Test job";
    job.originatingPlatform = "IT test";
    job.startedAt = Instant.now();
    jobDao.insert(job);

    List<CrmContact> contacts = new ArrayList<>();
    contacts.add(crmContact("contact1", "+12345678901", "EN"));
    contacts.add(crmContact("contact2", "+12345678902", "ES"));
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
    // need these later
    Instant firstTimestamp = now;
    Instant lastTimestamp = now;

    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);
    assertTrue(job.payload.toString().contains("\"firstTimestamp\":" + firstTimestamp.toEpochMilli()));
    assertTrue(job.payload.toString().contains("\"lastTimestamp\":" + lastTimestamp.toEpochMilli()));

    jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("+12345678901", jobProgresses.get(0).targetId);
    String contact1ProgressPayload = jobProgresses.get(0).payload.toString();
    assertTrue(contact1ProgressPayload.contains("\"lastMessage\":1,"));
    assertTrue(contact1ProgressPayload.contains("\"sentMessages\":[1]"));
    assertEquals("+12345678902", jobProgresses.get(1).targetId);
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
    assertTrue(job.payload.toString().contains("\"firstTimestamp\":" + firstTimestamp.toEpochMilli()));
    assertTrue(job.payload.toString().contains("\"lastTimestamp\":" + lastTimestamp.toEpochMilli()));

    jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("+12345678901", jobProgresses.get(0).targetId);
    assertEquals(contact1ProgressPayload, jobProgresses.get(0).payload.toString());
    assertEquals("+12345678902", jobProgresses.get(1).targetId);
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
    assertTrue(job.payload.toString().contains("\"firstTimestamp\":" + firstTimestamp.toEpochMilli()));
    assertTrue(job.payload.toString().contains("\"lastTimestamp\":" + lastTimestamp.toEpochMilli()));

    jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("+12345678901", jobProgresses.get(0).targetId);
    assertEquals(contact1ProgressPayload, jobProgresses.get(0).payload.toString());
    assertEquals("+12345678902", jobProgresses.get(1).targetId);
    assertEquals(contact2ProgressPayload, jobProgresses.get(1).payload.toString());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 4
    // CONTACT LIST SIZE = 2
    //
    // RESULT: message 2 should fire
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(4, ChronoUnit.DAYS);
    lastTimestamp = now;
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);
    assertTrue(job.payload.toString().contains("\"firstTimestamp\":" + firstTimestamp.toEpochMilli()));
    assertTrue(job.payload.toString().contains("\"lastTimestamp\":" + lastTimestamp.toEpochMilli()));

    jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("+12345678901", jobProgresses.get(0).targetId);
    contact1ProgressPayload = jobProgresses.get(0).payload.toString();
    assertTrue(contact1ProgressPayload.contains("\"lastMessage\":2,"));
    assertTrue(contact1ProgressPayload.contains("\"sentMessages\":[1,2]"));
    assertEquals("+12345678902", jobProgresses.get(1).targetId);
    contact2ProgressPayload = jobProgresses.get(1).payload.toString();
    assertTrue(contact2ProgressPayload.contains("\"lastMessage\":2,"));
    assertTrue(contact2ProgressPayload.contains("\"sentMessages\":[1,2]"));

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 5
    // CONTACT LIST SIZE = 3 (a new contact entered in the middle of the current interval cadence)
    //
    // RESULT: contacts 1-2 don't receive anything, contact 3 doesn't either (instead starts on the next window)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    contacts.add(crmContact("contact3", "+12345678903", "EN"));

    now = originalNow.plus(5, ChronoUnit.DAYS);

    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);
    assertTrue(job.payload.toString().contains("\"firstTimestamp\":" + firstTimestamp.toEpochMilli()));
    assertTrue(job.payload.toString().contains("\"lastTimestamp\":" + lastTimestamp.toEpochMilli()));

    jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("+12345678901", jobProgresses.get(0).targetId);
    assertEquals(contact1ProgressPayload, jobProgresses.get(0).payload.toString());
    assertEquals("+12345678902", jobProgresses.get(1).targetId);
    assertEquals(contact2ProgressPayload, jobProgresses.get(1).payload.toString());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 6
    // CONTACT LIST SIZE = 3 (a new contact entered in the middle of the current interval cadence)
    //
    // RESULT: contacts 1-2 receive message 3, contact 3 receives message 1
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(6, ChronoUnit.DAYS);
    lastTimestamp = now;
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);
    assertTrue(job.payload.toString().contains("\"firstTimestamp\":" + firstTimestamp.toEpochMilli()));
    assertTrue(job.payload.toString().contains("\"lastTimestamp\":" + lastTimestamp.toEpochMilli()));

    jobProgresses = jobProgressDao.getAll();
    assertEquals(3, jobProgresses.size());
    assertEquals("+12345678901", jobProgresses.get(0).targetId);
    contact1ProgressPayload = jobProgresses.get(0).payload.toString();
    assertTrue(contact1ProgressPayload.contains("\"lastMessage\":3,"));
    assertTrue(contact1ProgressPayload.contains("\"sentMessages\":[1,2,3]"));
    assertEquals("+12345678902", jobProgresses.get(1).targetId);
    contact2ProgressPayload = jobProgresses.get(1).payload.toString();
    assertTrue(contact2ProgressPayload.contains("\"lastMessage\":3,"));
    assertTrue(contact2ProgressPayload.contains("\"sentMessages\":[1,2,3]"));
    assertEquals("+12345678903", jobProgresses.get(2).targetId);
    String contact3ProgressPayload = jobProgresses.get(2).payload.toString();
    if (sequenceOrder == JobSequenceOrder.BEGINNING) {
      assertTrue(contact3ProgressPayload.contains("\"lastMessage\":1,"));
      assertTrue(contact3ProgressPayload.contains("\"sentMessages\":[1]"));
    } else {
      assertTrue(contact3ProgressPayload.contains("\"lastMessage\":3,"));
      assertTrue(contact3ProgressPayload.contains("\"sentMessages\":[3]"));
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 7
    // CONTACT LIST SIZE = 3 (a new contact entered in the middle of the current interval cadence)
    //
    // RESULT: contacts 1-3 don't receive anything
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(7, ChronoUnit.DAYS);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.ACTIVE, job.status);
    assertTrue(job.payload.toString().contains("\"firstTimestamp\":" + firstTimestamp.toEpochMilli()));
    assertTrue(job.payload.toString().contains("\"lastTimestamp\":" + lastTimestamp.toEpochMilli()));

    jobProgresses = jobProgressDao.getAll();
    assertEquals(3, jobProgresses.size());
    assertEquals("+12345678901", jobProgresses.get(0).targetId);
    assertEquals(contact1ProgressPayload, jobProgresses.get(0).payload.toString());
    assertEquals("+12345678902", jobProgresses.get(1).targetId);
    assertEquals(contact2ProgressPayload, jobProgresses.get(1).payload.toString());
    assertEquals("+12345678903", jobProgresses.get(2).targetId);
    assertEquals(contact3ProgressPayload, jobProgresses.get(2).payload.toString());
  }

  @Test
  public void testInactive() throws Exception {
    // Bit of a hack. We use this to
    Instant originalNow = Instant.now();

    Environment env = new DefaultEnvironment();
    HibernateDao<Long, Organization> organizationDao = new HibernateDao<>(Organization.class);
    HibernateDao<Long, Job> jobDao = new HibernateDao<>(Job.class);
    HibernateDao<Long, JobProgress> jobProgressDao = new HibernateDao<>(JobProgress.class);

    ScheduledJobService service = new ScheduledJobService(env);

    Organization org = new Organization();
    org.setId(1);
    org.setNucleusApiKey(env.getConfig().apiKey);
    organizationDao.insert(org);

    Job job = new Job();
    job.org = org;
    job.jobType = JobType.SMS_CAMPAIGN;
    job.status = JobStatus.INACTIVE;
    job.scheduleFrequency = JobFrequency.DAILY;
    job.scheduleInterval = 2;
    // TODO: The -30s is a little ridiculous. But we run into timing issues if the start time and time used to kick
    //  off the job are identical.
    job.scheduleStart = originalNow.plus(2, ChronoUnit.DAYS).minus(30, ChronoUnit.SECONDS);
    job.scheduleEnd = originalNow.plus(30, ChronoUnit.DAYS).minus(30, ChronoUnit.SECONDS);
    job.sequenceOrder = JobSequenceOrder.BEGINNING;
    job.jobProgresses = List.of();
    job.payload = MAPPER.readTree("""
        {
          "crm_list": "list1234",
          "languages": [
            {
              "code": "EN",
              "default": true
            },
            {
              "code": "ES",
              "default": false
            }
          ],
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
    job.traceId = UUID.randomUUID().toString();
    job.startedBy = "IT test";
    job.jobName = "Test job";
    job.originatingPlatform = "IT test";
    job.startedAt = Instant.now();
    jobDao.insert(job);

    List<CrmContact> contacts = new ArrayList<>();
    contacts.add(crmContact("contact1", "+12345678901", "EN"));
    contacts.add(crmContact("contact2", "+12345678902", "ES"));
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
    assertEquals(JobStatus.INACTIVE, job.status);

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
    assertEquals(JobStatus.INACTIVE, job.status);

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
    assertEquals(JobStatus.INACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 2
    // CONTACT LIST SIZE = 2
    //
    // RESULT: nothing fires
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(2, ChronoUnit.DAYS).minus(5, ChronoUnit.MINUTES);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.INACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 2 plus 15 min (simulate crontab running multiple times a day)
    // CONTACT LIST SIZE = 2
    //
    // RESULT: nothing new should fire
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(2, ChronoUnit.DAYS).minus(5, ChronoUnit.MINUTES);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.INACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 3
    // CONTACT LIST SIZE = 2
    //
    // RESULT: nothing new should fire
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(2, ChronoUnit.DAYS).minus(5, ChronoUnit.MINUTES);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.INACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 4
    // CONTACT LIST SIZE = 2
    //
    // RESULT: nothing new should fire
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(2, ChronoUnit.DAYS).minus(5, ChronoUnit.MINUTES);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.INACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 5
    // CONTACT LIST SIZE = 3 (a new contact entered in the middle of the current interval cadence)
    //
    // RESULT: nothing new should fire
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    contacts.add(crmContact("contact3", "+12345678903", "EN"));

    now = originalNow.plus(2, ChronoUnit.DAYS).minus(5, ChronoUnit.MINUTES);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.INACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 6
    // CONTACT LIST SIZE = 3 (a new contact entered in the middle of the current interval cadence)
    //
    // RESULT: nothing new should fire
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(2, ChronoUnit.DAYS).minus(5, ChronoUnit.MINUTES);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.INACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOW = day 7
    // CONTACT LIST SIZE = 3 (a new contact entered in the middle of the current interval cadence)
    //
    // RESULT: nothing new should fire
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    now = originalNow.plus(2, ChronoUnit.DAYS).minus(5, ChronoUnit.MINUTES);
    service.processJobSchedules(now);

    job = jobDao.getById(job.id).get();
    assertEquals(JobStatus.INACTIVE, job.status);

    jobProgresses = jobProgressDao.getAll();
    assertEquals(0, jobProgresses.size());
  }

  @Test
  public void testTargetIdChange() throws Exception {
    Environment env = new DefaultEnvironment();
    HibernateDao<Long, Organization> organizationDao = new HibernateDao<>(Organization.class);
    HibernateDao<Long, Job> jobDao = new HibernateDao<>(Job.class);
    HibernateDao<Long, JobProgress> jobProgressDao = new HibernateDao<>(JobProgress.class);

    ScheduledJobService service = new ScheduledJobService(env);

    Organization org = new Organization();
    org.setId(1);
    org.setNucleusApiKey(env.getConfig().apiKey);
    organizationDao.insert(org);

    Job job = new Job();
    job.org = org;
    job.jobType = JobType.SMS_CAMPAIGN;
    job.status = JobStatus.ACTIVE;
    job.scheduleFrequency = JobFrequency.DAILY;
    job.scheduleInterval = 1;
    job.scheduleStart = Instant.now();;
    job.sequenceOrder = JobSequenceOrder.BEGINNING;
    job.jobProgresses = List.of();
    job.payload = MAPPER.readTree("""
        {
          "crm_list": "list1234",
          "languages": [
            {
              "code": "EN",
              "default": true
            },
            {
              "code": "ES",
              "default": false
            }
          ],
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
    job.traceId = UUID.randomUUID().toString();
    job.startedBy = "IT test";
    job.jobName = "Test job";
    job.originatingPlatform = "IT test";
    job.startedAt = Instant.now();
    jobDao.insert(job);

    JobProgress jobProgress = new JobProgress();
    jobProgress.job = job;
    // purposefully use the CRM contact ID as the target
    jobProgress.targetId = "contact1";

    ObjectMapper objectMapper = new ObjectMapper();
    jobProgress.payload = objectMapper.createObjectNode();
    ((ObjectNode) jobProgress.payload).put("lastMessage", 1);
    if (Objects.isNull(jobProgress.payload.findValue("sentMessages"))) {
      ((ObjectNode) jobProgress.payload).putArray("sentMessages");
    }
    ((ArrayNode) jobProgress.payload.findValue("sentMessages")).add(1);
    jobProgressDao.insert(jobProgress);

    List<CrmContact> contacts = new ArrayList<>();
    contacts.add(crmContact("contact1", "+12345678901", "EN"));
    contacts.add(crmContact("contact2", "+12345678902", "ES"));
    lenient().when(crmServiceMock.getContactsFromList("list1234")).thenReturn(contacts);

    service.processJobSchedules(Instant.now());

    List<JobProgress> jobProgresses = jobProgressDao.getAll();
    assertEquals(2, jobProgresses.size());
    assertEquals("+12345678901", jobProgresses.get(0).targetId);
    assertEquals("+12345678902", jobProgresses.get(1).targetId);
  }

  @Test
  public void testDefaultLanguage() throws Exception {
    Environment env = new DefaultEnvironment();
    HibernateDao<Long, Job> jobDao = new HibernateDao<>(Job.class);
    HibernateDao<Long, JobProgress> jobProgressDao = new HibernateDao<>(JobProgress.class);
    HibernateDao<Long, Organization> organizationDao = new HibernateDao<>(Organization.class);

    ScheduledJobService service = new ScheduledJobService(env);

    Organization org = new Organization();
    org.setId(1);
    org.setNucleusApiKey(env.getConfig().apiKey);
    organizationDao.insert(org);

    Job job = new Job();
    job.org = org;
    job.jobType = JobType.SMS_CAMPAIGN;
    job.status = JobStatus.ACTIVE;
    job.scheduleFrequency = JobFrequency.ONETIME;
    job.scheduleStart = Instant.now();
    job.jobProgresses = List.of();
    job.payload = MAPPER.readTree("""
        {
          "crm_list": "list1234",
          "languages": [
            {
              "language":"English",
              "code":"EN",
              "default":false
            },
            {
              "language":"Burmese",
              "code":"MY",
              "default":true
            },
            {
              "language":"S'gaw Karen",
              "code":"KSW"
            },
            {
              "language":"French",
              "code":"FR"
            }
          ],
          "messages": [
            {
              "id": 1,
              "seq": 1,
              "languages": {
                "EN": {
                  "useAttachment": "primary_attachment",
                  "attachmentUrl": "this is EN attachment",
                  "message": "this is in english"
                },
                "MY": {
                  "useAttachment": "own_attachment",
                  "attachmentUrl": "this is MY attachment",
                  "message": "this is in burmese"
                },
                "KSW": {
                  "useAttachment": "none",
                  "message": "this is in karen"
                },
                "FR": {
                  "useAttachment": "own_attachment",
                  "attachmentUrl": "this is FR attachment",
                  "message": "this is in french"
                }
              }
            }
          ]
        }
    """);
    job.traceId = UUID.randomUUID().toString();
    job.startedBy = "IT test";
    job.jobName = "Test job";
    job.originatingPlatform = "IT test";
    job.startedAt = Instant.now();
    jobDao.insert(job);

    CrmContact contact1 = crmContact("contact1", "+12345678901", null);
    CrmContact contact2 = crmContact("contact2", "+12345678902", "EN");
    CrmContact contact3 = crmContact("contact3", "+12345678903", "KSW");
    CrmContact contact4 = crmContact("contact4", "+12345678904", "FR");
    when(crmServiceMock.getContactsFromList("list1234")).thenReturn(List.of(contact1, contact2, contact3, contact4));

    service.processJobSchedules(Instant.now());

    ArgumentCaptor<String> messageBodyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> mediaUrlCaptor = ArgumentCaptor.forClass(String.class);
    verify(twilioClientMock, times(4)).sendMessage(any(), any(), messageBodyCaptor.capture(), mediaUrlCaptor.capture(), any());
    List<String> allMessages = messageBodyCaptor.getAllValues();
    assertEquals("this is in burmese", allMessages.get(0)); // first contact had no lang and used the default
    assertEquals("this is in english", allMessages.get(1)); // second contact used their set lang
    assertEquals("this is in karen", allMessages.get(2)); // third contact used their set lang
    assertEquals("this is in french", allMessages.get(3)); // fourth contact used their set lang
    List<String> allMedia = mediaUrlCaptor.getAllValues();
    assertEquals("this is MY attachment", allMedia.get(0)); // first contact used primary attachment (for default language 'BY')
    assertEquals("this is MY attachment", allMedia.get(1)); // second contact used their own attachment (BY)
    assertNull(allMedia.get(2)); // third contact did not use any attachment
    assertEquals("this is FR attachment", allMedia.get(3)); // fourth contact used their own attachment (FR)
  }

  private CrmContact crmContact(String id, String mobilePhone, String lang) {
    CrmContact crmContact = new CrmContact(id);
    crmContact.mobilePhone = mobilePhone;
    crmContact.language = lang;
    return crmContact;
  }
}
