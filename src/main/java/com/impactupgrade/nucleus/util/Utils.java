/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.google.common.base.Strings;
import com.sun.xml.ws.util.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Utils {

  public static String trim(String s) {
    if (s == null) return null;
    return s.trim();
  }

  public static String noWhitespace(String s) {
    if (s == null) return null;
    return s.replaceAll("\\s", "");
  }

  public static String[] fullNameToFirstLast(String fullName) {
    String[] split = fullName.split("\s+");
    String firstName = split[0];
    String lastName = null;
    // Some donors are using a single-word business name in the individual name field, so this won't exist.
    if (split.length > 1) {
      // But we might also have some multi-word last names. So, catch them all. Rather than dealing with an array
      // slice, simply remove the first name, then trim leading whitespace.
      lastName = fullName.replace(firstName, "").trim();
    }
    return new String[]{firstName, lastName};
  }

  public static String nameToTitleCase(String name) {
    // primarily for reformatting/capitalizing names
    // handles spaces and hyphens
    // Ex. first name bill smith-jones -> Bill Smith-Jones
    return Stream.of(name.trim().split("((?<=[-\\s])|(?=[-\\s]))"))
            .map(x -> StringUtils.capitalize(x))
            .collect(Collectors.joining());
  }

  public static boolean checkboxToBool(String checkboxValue) {
    return "yes".equalsIgnoreCase(checkboxValue)
        || "on".equalsIgnoreCase(checkboxValue)
        || "true".equalsIgnoreCase(checkboxValue);
  }

  public static String emptyStringToNull(String s) {
    // For update methods, don't allow empty string values to overwrite something already in SF.
    if (s == null || s.isEmpty()) {
      return null;
    }
    return s;
  }

  public static Calendar getCalendarFromDateString(String date) throws ParseException {
    if (date != null && !date.isEmpty()) {
      Date localDate = new SimpleDateFormat("yyyy-MM-dd").parse(date);
      return new Calendar.Builder().setInstant(localDate.getTime()).build();
    }
    return null;
  }

  public static String cleanUnicode(String s) {
    if (Strings.isNullOrEmpty(s)) {
      return s;
    }

    return s.replaceAll("[\\u2018\\u2019]", "'")
        .replaceAll("[\\u201C\\u201D]", "\"")
        .replaceAll("[\\u254C\\u254D\\u2013\\u2014]", "--")
        .replaceAll("[\\u2026]", "...")
        .replaceAll("&quot;", "\"")
        .replaceAll("&#039;", "'");
  }

  public static String toSlug(String s) {
    if (s == null) return null;
    s = s.toLowerCase(Locale.ROOT).trim().replaceAll("[^A-Za-z0-9]+", "_");
    if (s.startsWith("_")) s = s.substring(1);
    if (s.endsWith("_")) s = s.substring(0, s.length() - 1);
    return s;
  }

  public static List<Map<String, String>> getCsvData(String csv) throws IOException {
    CsvMapper mapper = new CsvMapper();
    CsvSchema schema = CsvSchema.emptySchema().withHeader();
    MappingIterator<Map<String, String>> iterator = mapper.readerFor(Map.class).with(schema).readValues(csv);
    List<Map<String, String>> result = new LinkedList<>();
    while (iterator.hasNext()) {
      result.add(iterator.next());
    }
    return result;
  }

  public static List<Map<String, String>> getExcelData(InputStream inputStream) throws IOException {
    Workbook workbook = new XSSFWorkbook(inputStream);
    Sheet sheet = workbook.getSheetAt(0);

    List<String> headerData = new ArrayList<>();
    List<Map<String, String>> data = new ArrayList<>();

    Iterator<Row> rowIterator = sheet.iterator();

    // first row is the header
    Row header = rowIterator.next();
    for (Cell cell : header) {
      headerData.add(cell.getStringCellValue());
      switch (cell.getCellType()) {
        case NUMERIC -> headerData.add(cell.getNumericCellValue() + "");
        case BOOLEAN -> headerData.add(cell.getBooleanCellValue() + "");
        default -> headerData.add(cell.getStringCellValue());
      }
    }

    while (rowIterator.hasNext()) {
      Row row = rowIterator.next();
      Map<String, String> rowData = new HashMap<>();
      int cellIndex = 0;
      for (Cell cell : row) {
        switch (cell.getCellType()) {
          case NUMERIC -> rowData.put(headerData.get(cellIndex), cell.getNumericCellValue() + "");
          case BOOLEAN -> rowData.put(headerData.get(cellIndex), cell.getBooleanCellValue() + "");
          default -> rowData.put(headerData.get(cellIndex), cell.getStringCellValue());
        }
        cellIndex++;
      }
      data.add(rowData);
    }

    return data;
  }
}
