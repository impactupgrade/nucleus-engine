package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CRMImportEvent;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.segment.CrmDestinationService;
import com.impactupgrade.nucleus.util.GoogleSheetsUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/crm")
public class CrmController {

  private static final Logger log = LogManager.getLogger(CrmController.class.getName());

  private final CrmDestinationService crmDestinationService;

  public CrmController(Environment env) {
    crmDestinationService = env.crmDonationDestinationServices();
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
        crmDestinationService.processImport(importEvents);
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
      @FormDataParam("google-sheet-url") String gsheetUrl,
      @Context HttpServletRequest request) {
    SecurityUtil.verifyApiKey(request);

    Runnable thread = () -> {
      try {
        List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(gsheetUrl);
        List<CRMImportEvent> importEvents = CRMImportEvent.fromGeneric(data);
        crmDestinationService.processImport(importEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

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
        crmDestinationService.processImport(importEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }
}
