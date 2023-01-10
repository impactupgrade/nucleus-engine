package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.client.SycamoreClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Path("/sycamore")
public class SycamoreController {

  private static final Logger log = LogManager.getLogger(SycamoreController.class.getName());

  protected final EnvironmentFactory envFactory;

  public SycamoreController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @Path("/sync-students/all")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response syncAllStudents(
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SycamoreClient sycamoreClient = new SycamoreClient(env);
    CrmService crmService = env.primaryCrmService();

    Runnable runnable = () -> {
      List<SycamoreClient.SchoolStudent> schoolStudents = sycamoreClient.getSchoolStudents();
      log.info("Found students: {}", schoolStudents.size());

      for (SycamoreClient.SchoolStudent schoolStudent : schoolStudents) {
        SycamoreClient.Student student = sycamoreClient.getStudent(schoolStudent.id);
        SycamoreClient.Family family = sycamoreClient.getFamily(schoolStudent.familyId);
        List<SycamoreClient.FamilyContact> familyContacts = sycamoreClient.getFamilyContacts(schoolStudent.familyId);
        try {
          sycamoreClient.upsertStudent(student, family, familyContacts, crmService);
        } catch (Exception e) {
          log.error("Failed to upsert student! Student code/name {}/{}", schoolStudent.studentCode, schoolStudent.firstName + " " + schoolStudent.lastName, e);
        }
      }
    };
    // Away from the main thread
    new Thread(runnable).start();
    return Response.ok().build();
  }
}
