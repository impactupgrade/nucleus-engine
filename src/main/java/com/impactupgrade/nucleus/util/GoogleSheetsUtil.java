/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.Utils.getCsvData;

public class GoogleSheetsUtil {

  public static List<Map<String, String>> getSheetData(String url) throws IOException {
    return getCsvData(exportSheetAsCsv(url));
  }

  private static String exportSheetAsCsv(String url) {
    // support both of these formats:
    // https://docs.google.com/spreadsheets/d/ID/edit
    // https://drive.google.com/file/d/ID/view

    // construct the export url
    String csvUrl;
    if (url.contains("/spreadsheets/")) {
      csvUrl = "https://docs.google.com/spreadsheets/d/" + url.split("spreadsheets/d/")[1].split("/")[0]
          + "/export?format=csv";
    } else if (url.contains("/file/")) {
      csvUrl = "https://docs.google.com/spreadsheets/d/" + url.split("file/d/")[1].split("/")[0]
          + "/export?format=csv";
    } else {
      // shouldn't happen, but simply return the original
      csvUrl = url;
    }

    return get(csvUrl, HttpClient.HeaderBuilder.builder(), String.class);
  }
}
