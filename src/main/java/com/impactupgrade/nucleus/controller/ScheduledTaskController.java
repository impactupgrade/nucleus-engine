package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.logic.ScheduledTaskService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

@Path("/scheduled-task")
public class ScheduledTaskController {

    private static final Logger log = LogManager.getLogger(ScheduledTaskController.class);

    protected final EnvironmentFactory envFactory;
    protected final SessionFactory sessionFactory;
    protected final ScheduledTaskService scheduledTaskService;

    public ScheduledTaskController(EnvironmentFactory envFactory, SessionFactory sessionFactory) {
        this.envFactory = envFactory;
        this.sessionFactory = sessionFactory;
        this.scheduledTaskService = new ScheduledTaskService(sessionFactory);
    }

    @GET
    public Response execute(@Context HttpServletRequest request) {
        log.info("executing scheduled tasks");

        Environment env = envFactory.init(request);
        SecurityUtil.verifyApiKey(env);

        new Thread(() -> scheduledTaskService.processTaskSchedules(env)).start();
        return Response.ok().build();
    }

}
