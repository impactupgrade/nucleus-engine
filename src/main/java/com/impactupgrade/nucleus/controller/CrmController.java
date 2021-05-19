package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CRMImportEvent;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.GoogleSheetsUtil;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/crm")
public class CrmController {

  private static final Logger log = LogManager.getLogger(CrmController.class.getName());

  private final CrmService crmService;

  public CrmController(Environment env) {
    crmService = env.crmService();
  }

  @Path("/bulk-import/file")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImport(
      @FormDataParam("file") File file,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request) {
    SecurityUtil.verifyApiKey(request);

    Runnable thread = () -> {
      try {
        CSVParser csvParser = CSVParser.parse(
            file,
            Charset.defaultCharset(),
            CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .withIgnoreHeaderCase()
                .withTrim()
        );
        List<Map<String, String>> data = new ArrayList<>();
        for (CSVRecord csvRecord : csvParser) {
          data.add(csvRecord.toMap());
        }

        List<CRMImportEvent> importEvents = CRMImportEvent.fromGeneric(data);
        crmService.processImport(importEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/bulk-import/gsheet")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImport(
      @FormParam("google-sheet-url") String gsheetUrl,
      @Context HttpServletRequest request) {
    SecurityUtil.verifyApiKey(request);

    Runnable thread = () -> {
      try {
        List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(gsheetUrl);
        List<CRMImportEvent> importEvents = CRMImportEvent.fromGeneric(data);
        crmService.processImport(importEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  // TODO: Wasn't ultimately used by STS, and needs further testing, but keeping it for now...
  @Path("/bulk-import/fb-fundraisers")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImportFBFundraisers(
      @FormDataParam("file") File file,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request) {
    SecurityUtil.verifyApiKey(request);

    Runnable thread = () -> {
      try {
        CSVParser csvParser = CSVParser.parse(
            file,
            Charset.defaultCharset(),
            CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .withIgnoreHeaderCase()
                .withTrim()
        );
        List<Map<String, String>> data = new ArrayList<>();
        for (CSVRecord csvRecord : csvParser) {
          data.add(csvRecord.toMap());
        }

        List<CRMImportEvent> importEvents = CRMImportEvent.fromFBFundraiser(data);
        crmService.processImport(importEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }
}
