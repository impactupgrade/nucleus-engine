package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.util.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RaisersEdgeToSalesforceDuplicates {

  private static final Logger log = LogManager.getLogger(RaisersEdgeToSalesforceDuplicates.class);

  public static void main(String[] args) throws Exception {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.crmPrimary = "salesforce";
        envConfig.salesforce.sandbox = false;
        envConfig.salesforce.url = "concordialutheranhs.my.salesforce.com";
        envConfig.salesforce.username = "team+clhs@impactupgrade.com";
        envConfig.salesforce.password = "pqp.gnj1xcd8DFC2mgfpwl7cq1UOBQZYOZKEppFDTQ4";
        envConfig.salesforce.enhancedRecurringDonations = true;
        return envConfig;
      }
    };

    migrate(env);
  }

  private static void migrate(Environment env) throws Exception {
    // The sheets are so huge that the Apache framework we're using to read XLSX thinks it's malicious...
    IOUtils.setByteArrayMaxOverride(Integer.MAX_VALUE);

    File file = new File("/home/brmeyer/Downloads/Constituent+Spouse-v3-25relationships.xlsx");
    InputStream inputStream = new FileInputStream(file);
    List<Map<String, String>> rows = Utils.getExcelData(inputStream);

    Map<String, List<Map<String, String>>> rowsByName = new HashMap<>();
    Map<String, List<Map<String, String>>> rowsByEmail = new HashMap<>();

    for (Map<String, String> row : rows) {
      String id = row.get("CnBio_ID");
      String firstName = row.get("CnBio_First_Name");
      String lastName = row.get("CnBio_Last_Name");
      String email = null;
      for (int i = 1; i <= 3; i++) {
        String cnPhType = "CnPh_1_0" + i + "_Phone_type";
        String cnPhPhone = "CnPh_1_0" + i + "_Phone_number";

        if ("Email".equalsIgnoreCase(row.get(cnPhType))) {
          email = row.get(cnPhPhone);
        }
      }

      if (!Strings.isNullOrEmpty(firstName) && !Strings.isNullOrEmpty(lastName)) {
        if (!rowsByName.containsKey(firstName + " " + lastName)) {
          rowsByName.put(firstName + " " + lastName, new ArrayList<>());
        }
        rowsByName.get(firstName + " " + lastName).add(row);
      }

      if (!Strings.isNullOrEmpty(email)) {
        if (!rowsByEmail.containsKey(email)) {
          rowsByEmail.put(email, new ArrayList<>());
        }
        rowsByEmail.get(email).add(row);
      }
    }

    rowsByName.entrySet().stream().filter(e -> e.getValue().size() > 1).forEach(e ->
        System.out.println("NAME: " + e.getValue().size() + " " + e.getKey())
    );

    rowsByEmail.entrySet().stream().filter(e -> e.getValue().size() > 1).forEach(e ->
        System.out.println("EMAIL: " + e.getValue().size() + " " + e.getKey())
    );
  }
}
