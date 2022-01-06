package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.service.logic.TaskService;
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
    protected final TaskService taskService;

    public ScheduledTaskController(EnvironmentFactory envFactory, SessionFactory sessionFactory) {
        this.envFactory = envFactory;
        this.sessionFactory = sessionFactory;
        this.taskService = new TaskService(sessionFactory);
    }

    @GET
    @Path("/weekly")
    public Response weekly(@Context HttpServletRequest request) {
        log.info("executing scheduled tasks");
        new Thread(() -> taskService.processTaskSchedules()).start();
        return Response.ok().build();
    }

}
