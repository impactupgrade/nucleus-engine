/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.sun.xml.ws.util.StringUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.ws.rs.core.MultivaluedMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Utils {

  public static String trim(String s) {
    if (s == null) return null;
    return s.trim();
  }

  public static String truncate(String s, int maxChars) {
    if (s == null) return null;
    return s.length() > maxChars ? s.substring(0, maxChars - 1) + "..." : s;
  }

  public static String lowercase(String s) {
    if (s == null) return null;
    return s.toLowerCase(Locale.ROOT);
  }

  public static String noWhitespace(String s) {
    if (s == null) return null;
    return s.replaceAll("\\s", "");
  }

  public static String alphanumericOnly(String s) {
    if (s == null) return null;
    return s.replaceAll("[^A-Za-z0-9]", "");
  }

  public static String numericOnly(String s) {
    if (s == null) return null;
    return s.replaceAll("\\D", "");
  }

  public static String[] fullNameToFirstLast(String fullName) {
    if (Strings.isNullOrEmpty(fullName)) {
      return new String[]{null, null};
    }

    String[] split = fullName.trim().split("\\s+");
    String firstName = null;
    String lastName = split[split.length - 1];
    // Some donors are using a single-word business name in the individual name field, so this won't exist.
    if (split.length > 1) {
      // But we might also have some multi-word first names (or family first names in a list). So, catch them all.
      // Rather than dealing with an array slice, simply remove the last name, then trim whitespace.
      firstName = fullName.replace(lastName, "").trim();
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
    if (checkboxValue == null) return false;

    return Set.of("yes", "on", "true", "1", "x").contains(checkboxValue.toLowerCase(Locale.ROOT));
  }

  public static String emptyStringToNull(String s) {
    // For update methods, don't allow empty string values to overwrite something already in SF.
    if (s == null || s.isEmpty()) {
      return null;
    }
    return s;
  }

  public static String nullToEmptyString(String s) {
    if (s == null) {
      return "";
    }
    return s;
  }

  public static Calendar getCalendarFromDateString(String date, String timezoneId) {
    return toCalendar(getZonedDateFromDateString(date, timezoneId), null);
  }

  public static ZonedDateTime getZonedDateFromDateString(String date, String timezoneId) {
    try {
      return getZonedDateFromDateString(date, timezoneId, "yyyy-M-d");
    } catch (DateTimeParseException e) {
      return getZonedDateFromDateString(date, timezoneId, "M/d/yyyy");
    }
  }

  public static ZonedDateTime getZonedDateFromDateString(String date, String timezoneId, String format) throws DateTimeParseException {
    if (!Strings.isNullOrEmpty(date)) {
      DateTimeFormatter dtf = DateTimeFormatter.ofPattern(format);
      LocalDate localDate = LocalDate.parse(date, dtf);
      return localDate.atStartOfDay(ZoneId.of(timezoneId));
    }
    return null;
  }

  public static Calendar getCalendarFromDateTimeString(String dateTime) {
    return toCalendar(getZonedDateTimeFromDateTimeString(dateTime), null);
  }

  public static ZonedDateTime getZonedDateTimeFromDateTimeString(String dateTime) {
    if (!Strings.isNullOrEmpty(dateTime)) {
      try {
        return ZonedDateTime.parse(dateTime);
      } catch (DateTimeParseException e) {
        return getZonedDateFromDateString(dateTime, "UTC");
      }
    }
    return null;
  }

  public static ZonedDateTime now(String timezoneId) {
    return ZonedDateTime.ofInstant(Instant.now(), ZoneId.of(timezoneId));
  }

  public static ZonedDateTime toZonedDateTime(Long epochSecond, String timezoneId) {
    return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneId.of(timezoneId));
  }

  public static Calendar toCalendar(ZonedDateTime zonedDateTime, String explicitTimezoneId) {
    if (zonedDateTime == null) {
      return null;
    }
    if (!Strings.isNullOrEmpty(explicitTimezoneId)) {
      zonedDateTime = zonedDateTime.withZoneSameInstant(ZoneId.of(explicitTimezoneId));
    }
    return GregorianCalendar.from(zonedDateTime);
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
    return toSlug(s, true);
  }

  public static String toSlug(String s, boolean lowercase) {
    if (s == null) return null;
    s = s.trim().replaceAll("[^A-Za-z0-9_]+", "_");
    if (s.startsWith("_")) s = s.substring(1);
    if (s.endsWith("_")) s = s.substring(0, s.length() - 1);
    if (lowercase) s = s.toLowerCase(Locale.ROOT);
    return s;
  }

  public static List<Map<String, String>> getCsvData(String csv) throws IOException {
    try (InputStream inputStream = new ByteArrayInputStream(csv.getBytes())) {
      return getCsvData(inputStream);
    }
  }

  public static List<Map<String, String>> getCsvData(InputStream inputStream) throws IOException {
    try (CSVParser csvParser = CSVParser.parse(
        inputStream,
        Charset.defaultCharset(),
        CSVFormat.DEFAULT
            .withIgnoreHeaderCase()
            .withTrim()
    )) {
      CSVRecord headerRecord = csvParser.iterator().next();
      List<String> headers = new ArrayList<>();
      List<Integer> headerIndices = new ArrayList<>();

      for (int i = 0; i < headerRecord.size(); i++) {
        String header = headerRecord.get(i);
        if (header != null && !header.trim().isEmpty()) {
          headers.add(header.trim());
          headerIndices.add(i);
        }
      }

      List<Map<String, String>> data = new ArrayList<>();
      while (csvParser.iterator().hasNext()) {
        CSVRecord csvRecord = csvParser.iterator().next();
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
          String header = headers.get(i);
          int index = headerIndices.get(i);
          if (index < csvRecord.size()) {
            String value = csvRecord.get(index);
            row.put(header, value);
          }
        }

        data.add(row);
      }
      return data;
    }
  }

  public static List<Map<String, String>> getExcelData(InputStream inputStream) throws IOException {
    Workbook workbook = new XSSFWorkbook(inputStream);
    Sheet sheet = workbook.getSheetAt(0);
    return getExcelData(sheet);
  }

  public static List<Map<String, String>> getExcelData(InputStream inputStream, String sheetName) throws IOException {
    Workbook workbook = new XSSFWorkbook(inputStream);
    Sheet sheet = workbook.getSheet(sheetName);
    return getExcelData(sheet);
  }

  public static List<Map<String, String>> getExcelData(InputStream inputStream, int sheetIndex) throws IOException {
    Workbook workbook = new XSSFWorkbook(inputStream);
    Sheet sheet = workbook.getSheetAt(sheetIndex);
    return getExcelData(sheet);
  }

  public static List<Map<String, String>> getExcelData(Sheet sheet) throws IOException {
    List<String> headerData = new ArrayList<>();
    List<Map<String, String>> data = new ArrayList<>();

    Iterator<Row> rowIterator = sheet.iterator();

    // first row is the header
    Row header = rowIterator.next();
    for (Cell cell : header) {
      switch (cell.getCellType()) {
        case NUMERIC -> headerData.add(formatDouble(cell.getNumericCellValue()));
        case BOOLEAN -> headerData.add(cell.getBooleanCellValue() + "");
        // note the use of trim -- vital since column names are used to fetch values
        default -> headerData.add(cell.getStringCellValue().trim());
      }
    }

    int numCols = headerData.size();

    while (rowIterator.hasNext()) {
      Row row = rowIterator.next();
      Map<String, String> rowData = new HashMap<>();
      for (int i = 0; i < numCols; i++) {
        Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
        if (cell == null) {
          rowData.put(headerData.get(i), "");
        } else {
          switch (cell.getCellType()) {
            case NUMERIC, FORMULA -> {
              try {
                if (DateUtil.isCellDateFormatted(cell)) {
                  rowData.put(headerData.get(i), new SimpleDateFormat("yyyy-MM-dd").format(cell.getDateCellValue()));
                } else {
                  rowData.put(headerData.get(i), formatDouble(cell.getNumericCellValue()));
                }
              } catch (Exception e) {
                // Seeing odd issues where the cell type is date/numeric, but it complains about having only string values.
                rowData.put(headerData.get(i), cell.getStringCellValue().trim());
              }
            }
            case BOOLEAN -> rowData.put(headerData.get(i), cell.getBooleanCellValue() + "");
            // also use trim here -- running into lots of sheets with extra whitespace
            default -> rowData.put(headerData.get(i), cell.getStringCellValue().trim());
          }
        }
      }
      data.add(rowData);
    }

    return data;
  }

  private static String formatDouble(double d) {
    String formatPattern = d % 1 == 0 ? "#" : "#.##";
    return new DecimalFormat(formatPattern).format(d);
  }

  public static String formatDuration(Duration duration) {
    long seconds = duration.getSeconds();
    long absSeconds = Math.abs(seconds);
    String positive = String.format(
        "%02d min",
        (absSeconds % 3600) / 60);
    return seconds < 0 ? "-" + positive : positive;
  }

  public static String getFileExtension(String fileName) {
    if (Strings.isNullOrEmpty(fileName)) {
      return null;
    }
    String extension = "";
    int index = fileName.lastIndexOf('.');
    if (index > 0) {
      extension = fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }
    return extension;
  }

  public static Map<String, Object> toMap(MultivaluedMap<String, Object> multiMap) {
    Map<String, Object> hashMap = new HashMap<>();
    for (String key : multiMap.keySet()) {
      hashMap.put(key, multiMap.getFirst(key));
    }
    return hashMap;
  }
  
  public static String getPhoneFromMap(Map<String, String> map) {
    // TODO: This is crazy. Object mapper framework with flexibility?
    if (!Strings.isNullOrEmpty(map.get("Mobile Phone Number")))
      return map.get("Mobile Phone Number");
    else if (!Strings.isNullOrEmpty(map.get("Mobile Phone")))
      return map.get("Mobile Phone");
    else if (!Strings.isNullOrEmpty(map.get("Primary Phone Number")))
      return map.get("Primary Phone Number");
    else if (!Strings.isNullOrEmpty(map.get("Primary Phone")))
      return map.get("Primary Phone");
    else if (!Strings.isNullOrEmpty(map.get("Phone Number")))
      return map.get("Phone Number");
    else if (!Strings.isNullOrEmpty(map.get("Phone")))
      return map.get("Phone");

    return null;
  }

  public static String normalizeStreet(String street) {
    if (Strings.isNullOrEmpty(street)) {
      return "";
    }

    street = street.replace("Avenue", "Ave");
    street = street.replace("Court", "Ct");
    street = street.replace("Cove", "Cv");
    street = street.replace("Drive", "Dr");
    street = street.replace("Lane", "Ln");
    street = street.replace("Place", "Pl");
    street = street.replace("Ridge", "Rdg");
    street = street.replace("Road", "Rd");
    street = street.replace("Street", "St");
    street = alphanumericOnly(street);

    return street.toLowerCase(Locale.ROOT).trim();
  }

  public static List<String> parsePhoneNumber(String phoneNumber) {
    if (Strings.isNullOrEmpty(phoneNumber)) {
      return Collections.emptyList();
    }
    boolean internationalFormat = phoneNumber.startsWith("+");
    String phone = phoneNumber.replaceAll("[\\D.]", "");

    if (internationalFormat) {
      if (phone.length() < 8) {
        // invalid phone number
        return Collections.emptyList();
      }

      boolean validCode = false;
      for (CountryCallingCode countryCallingCode: CountryCallingCode.values()) {
        String code = countryCallingCode.getCode().toString();

        if (phone.startsWith(code)) {
          validCode = true;
          phone = phone.substring(code.length());
          break;
        }
      }

      if (!validCode) {
        // invalid international code
        return  Collections.emptyList();
      }
    }

    List<String> phoneParts = new ArrayList<>();
    phoneParts.add(phone.substring(0, 3));
    phoneParts.add(phone.substring(3, Math.min(6, phone.length())));
    if (phone.length() > 6) {
      phoneParts.add(phone.substring(6));
    }
    return phoneParts;
  }

  public static Long parseLong(String s) {
    Long l = null;
    try {
      l = Long.parseLong(s);
    } catch (NumberFormatException e) {
      // ignore and return null
    }
    return l;
  }

  public static String toDateString(Calendar calendar, String dateTimeformat) {
    if (calendar == null) {
      return null;
    }
    Date date = calendar.getTime();
    ZonedDateTime zdt = date.toInstant().atZone(ZoneId.of("UTC"));
    return zdt.format(DateTimeFormatter.ofPattern(dateTimeformat));
  }
}
