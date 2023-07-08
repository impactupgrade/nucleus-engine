package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.util.Utils;
import org.apache.commons.collections.CollectionUtils;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Path("/job")
public class JobController {

  private static final String DATE_FORMAT = "MM-dd-yyyy";
  private static final String TIME_FORMAT = "HH:mm";

  protected final EnvironmentFactory envFactory;

  public JobController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @GET
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response getJobs(
      @QueryParam("jobType") JobType jobType,
      @Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    List<Job> jobs = env.jobLoggingService().getJobs(jobType);
    List<JobDto> jobDtos = toJobDtos(jobs, env.getConfig().timezoneId);

    return Response.ok(jobDtos).build();
  }

  @GET
  @Path("/{trace-id}/logs")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getJobLogs(
      @PathParam("trace-id") String traceId,
      @Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    Job job = env.jobLoggingService().getJob(traceId);
    if (job == null) {
      return Response.status(404).entity("Failed to find job!").build();
    }

    return Response.ok(job.logs).build();
  }

  private List<JobDto> toJobDtos(List<Job> jobs, String timezoneId) {
    if (CollectionUtils.isEmpty(jobs)) {
      return Collections.emptyList();
    }
    String zoneId = !Strings.isNullOrEmpty(timezoneId) ? timezoneId : "EST";
    DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT);
    DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern(TIME_FORMAT);
    return jobs.stream()
        .map(job -> toJobDto(job, zoneId, dateFormatter, timeFormatter))
        .collect(Collectors.toList());
  }

  private JobDto toJobDto(Job job, String timezoneId, DateTimeFormatter dateFormatter, DateTimeFormatter timeFormatter) {
    JobDto jobDto = new JobDto();
    jobDto.traceId = job.traceId;
    ZoneId zoneId = ZoneId.of(timezoneId);
    LocalDateTime started = LocalDateTime.ofInstant(job.startedAt, zoneId);
    jobDto.date = dateFormatter.format(started.toLocalDate()) + " " + timezoneId;
    jobDto.time = timeFormatter.format(started.toLocalTime()) + " " + timezoneId;
    jobDto.platform = job.originatingPlatform;
    jobDto.task = job.jobName;
    jobDto.status = job.status.name();
    jobDto.user = job.startedBy;
    jobDto.started = timeFormatter.format(started) + " " + timezoneId;
    if (job.endedAt != null) {
      LocalDateTime ended = LocalDateTime.ofInstant(job.endedAt, zoneId);
      jobDto.ended = timeFormatter.format(ended) + " " + timezoneId;
      Duration runtime = Duration.between(started, ended);
      jobDto.runtime = Utils.formatDuration(runtime);
    }
    return jobDto;
  }

  private static final class JobDto {
    public String traceId;
    public String date;
    public String time;
    public String platform;
    public String task;
    public String status;
    public String user;
    public String started;
    public String ended;
    public String runtime;
  }
}