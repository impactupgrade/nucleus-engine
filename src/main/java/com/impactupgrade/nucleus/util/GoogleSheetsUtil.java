/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class GoogleSheetsUtil {

  public static List<Map<String, String>> getSheetData(String url) throws IOException {
    String csv = exportSheetAsCsv(url);
    List<Map<String, String>> result = new LinkedList<>();
    CsvMapper mapper = new CsvMapper();
    CsvSchema schema = CsvSchema.emptySchema().withHeader();
    MappingIterator<Map<String, String>> iterator = mapper.readerFor(Map.class).with(schema).readValues(csv);
    while (iterator.hasNext()) {
      result.add(iterator.next());
    }
    return result;
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

    return HttpClient.getAsString(csvUrl);
  }
}
