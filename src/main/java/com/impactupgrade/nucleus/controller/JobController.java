package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.security.SecurityUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Path("/job")
public class JobController {

  private static final Logger log = LogManager.getLogger(JobController.class.getName());

  protected final EnvironmentFactory envFactory;

  public JobController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response getJobs(
      @FormParam("page") Integer page,
      @FormParam("pageSize") Integer pageSize,
      @Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    List<Job> jobs = env.getJobs();
    if (CollectionUtils.isEmpty(jobs)) {
      return Response.status(404).entity("Failed to find any jobs!").build();
    }

    return Response.ok(toResponseDto(jobs, page, pageSize, env.getConfig().timezoneId)).build();
  }

  @GET
  @Path("/{trace-id}/logs")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getJobLogs(
      @PathParam("trace-id") String traceId,
      @Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    Job job = env.getJob(traceId);
    if (job == null) {
      return Response.status(404).entity("Failed to find job!").build();
    }

    return Response.ok(job.logs).build();
  }

  private ResponseDto toResponseDto(List<Job> jobs, Integer page, Integer pageSize, String timezoneId) {
    int partitionSize = Math.max(pageSize, 1);
    List<Job> sortedByStartedAt = jobs.stream()
        .sorted(Comparator.comparingLong(job -> job.startedAt.toEpochMilli()))
        .collect(Collectors.toList());
    List<List<Job>> jobsPaged = Lists.partition(sortedByStartedAt, partitionSize);

    int partitionIndex = Math.max(page, 1);
    int index = Math.min(partitionIndex, jobsPaged.size()) - 1; // 0 based
    List<JobDto> jobDtos = toJobDtos(jobsPaged.get(index), timezoneId);

    ResponseDto responseDto = new ResponseDto();
    responseDto.page = index + 1; // 1 based
    responseDto.pageSize = partitionSize;
    responseDto.totalSize = jobs.size();
    responseDto.jobs = jobDtos;

    return responseDto;
  }

  private List<JobDto> toJobDtos(List<Job> jobs, String timezoneId) {
    String zoneId = !Strings.isNullOrEmpty(timezoneId) ? timezoneId : "EST";
    return jobs.stream()
        .map(job -> toJobDto(job, zoneId))
        .collect(Collectors.toList());
  }

  private static final class ResponseDto {
    public Integer page;
    public Integer pageSize;
    public Integer totalSize;
    public List<JobDto> jobs;
  }

  private static final class JobDto {
    public String traceId;
    public LocalDate date;
    public LocalTime time;
    public String platform;
    public String task;
    public String status;
    public String user;
    public LocalDateTime started;
    public LocalDateTime ended;
    public Duration runtime;
  }

  private JobDto toJobDto(Job job, String timezoneId) {
    JobDto jobDto = new JobDto();
    jobDto.traceId = job.traceId;
    ZoneId zoneId = ZoneId.of(timezoneId);
    LocalDateTime started = LocalDateTime.ofInstant(job.startedAt, zoneId);
    jobDto.date = started.toLocalDate();
    jobDto.time = started.toLocalTime();
    jobDto.platform = job.originatingPlatform;
    jobDto.task = job.jobName;
    jobDto.status = job.status.name();
    jobDto.user = job.startedBy;
    jobDto.started = started;
    if (job.endedAt != null) {
      jobDto.ended = LocalDateTime.ofInstant(job.endedAt, zoneId);
      jobDto.runtime = Duration.between(jobDto.started, jobDto.ended);
    }
    return jobDto;
  }
}