package com.impactupgrade.nucleus.client;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

public class GoogleSheetsClient {

  private static final Logger log = LogManager.getLogger(GoogleSheetsClient.class);

  private final Sheets sheets;

  public GoogleSheetsClient(EnvironmentConfig.GoogleSheets googleSheetsConfig) throws GeneralSecurityException, IOException {
    // Build service account credential
    // Configuration parameters represent Google's JSON file with the credential information
    JSONObject jsonObject = new JSONObject();
    jsonObject.put("type", "service_account");
    jsonObject.put("project_id", googleSheetsConfig.projectId);
    jsonObject.put("private_key_id", googleSheetsConfig.privateKeyId);
    jsonObject.put("private_key", googleSheetsConfig.secretKey);
    jsonObject.put("client_email", googleSheetsConfig.clientEmail);
    jsonObject.put("client_id", googleSheetsConfig.clientId);
    jsonObject.put("auth_uri", googleSheetsConfig.authUri);
    jsonObject.put("token_uri", googleSheetsConfig.tokenServerUrl);
    jsonObject.put("auth_provider_x509_cert_url", googleSheetsConfig.authProviderCertUrl);
    jsonObject.put("client_x509_cert_url", googleSheetsConfig.clientCertUrl);
    jsonObject.put("client_x509_cert_url", googleSheetsConfig.clientCertUrl);
    jsonObject.put("universe_domain", "googleapis.com");

    // For this scenario a service account is needed, which is an account that belongs to the connected application instead of 
    // to an individual end user. 
    // Google APIs is called on behalf of the service account, so users 
    // aren't directly involved (there is no redirect to the login screen and user is not prompted to grant permissions to connected app)
    GoogleCredential googleCredentials = GoogleCredential
        .fromStream(new ByteArrayInputStream(jsonObject.toString().getBytes()))
        .createScoped(List.of(SheetsScopes.SPREADSHEETS));

    sheets = new Sheets.Builder(
        GoogleNetHttpTransport.newTrustedTransport(),
        GsonFactory.getDefaultInstance(), googleCredentials)
        .setApplicationName(googleSheetsConfig.applicationName)
        .build();
  }

  public void append(String sheetId, List<Object> cellValues) throws IOException {
    ValueRange valueRange = new ValueRange().setValues(List.of(cellValues));
    sheets.spreadsheets().values()
        // appending 1 row starting from 1st column
        .append(sheetId, "A1", valueRange)
        .setValueInputOption("RAW")
        .execute();
  }

  public static void main(String[] args) throws GeneralSecurityException, IOException {

    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.googleSheets.projectId = "gentle-complex-390515";
        envConfig.googleSheets.privateKeyId = "804553f7d5f18d087b62459f90e5a1b14ed509c9";
        envConfig.googleSheets.secretKey = "-----BEGIN PRIVATE KEY-----\nMIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDdUbanTn8rq7mG\nlPY42U8lrY0rUVCYy6YJjeYtl8Sv/RKXIlqkT+7YU2d0n0E7xP0NWo5fQ8PGJ3Px\n2zB23CepBREPjctc8TtRvDAguXkicu+hIiamAIT6mnmd0DLPpQyJogd1BUgVyJF4\n6HQtnmyBPUsm9Eu/FwZ3CDvkPV3zY6lBDg9Zwnt07uS5zWpTJbHSTfin3ZKWVGt4\nQu5n+gU0pvAFUWDd16MgBNfuSjXRMZGzHI0bdXHT40Um0Oinmhiab65N/xXtLZfU\nMGSiMgUc3BQXccchhMhiLcuBXATSTyE+B6MiJzunDSaCHG0cHCHHdPuH4WAsUZhs\n4jfg/hfZAgMBAAECggEACP0fX8KwqA+osJZevLbYv9VBaUu7bAVDaFpuyZXDK4mq\nBmDjEQ7dCsSybDpmnhyVUYRGyYg5TJRAIYfPO1icNMFrrLfL1WnHyL1NsBqQWK2V\n3XPDYZUeUYZSH657zdKshG+EAYT2JUpY3DIGu+6WBha89WdRJ0DyZoW7Vv0GEpM7\nj/3hPUTUXoQZdXRLKlhCX/dVJduo2QIJjIIu8bzCLmlqTCa8cqYAx3UwJulfHXoY\nz8Ug2TYzRTykr02JPNyuNuk0R4UA6M4SZSdYpMhDBpb70OGE+sBCndKEjeouOS2S\nQcHkLR0O1IMzp2sz7IxL1j5DQeb0SZCr2CboTJ8j7QKBgQDvFQrkzos44foW8tNn\nGnm78VnGv+T16bcSORpvHxUlm2pfCdIlVdHgKa1Dvm4pIs3LlSLnN6Mg550Ni0Mo\nOPdy2KLaG3GU74ytqOoFdbi+JifaI2/lRVvLrQ06rnUI6JLjY5fWJF6zdl3XyxsN\nf2+echzxDv85t/SCoXzPRO2NzQKBgQDs+uU1chU5WxqzOiYTB3v4jM2FTC6xJNjs\nzWf6kVHyQ7/sqDRG3ginYx8vMPB16e1aExzo3mgpfwyXKjxPQ+qa1QT0QEdlvi1p\nGfa7fdlN/7hAN7tl0DYIKzItk0hNXWJewyvuE2y8dK3ZS3gF+YMMMJ5e2hkrYbu1\nUlUu8tKGPQKBgQDOgy2awDIP21orsmoa2Aqo5eu3OpAqPkvtCLglngKlLl6uYwxL\nRZr49ub76iS7kZ2TqWmxsSROSuIlDdLfjn1njWr13Ni6XkT0yEAEoVAHp2urCAsi\nTkvhXcRcmM7s9//RPHit91J5z9d1i7H9ccNXaJhJPLwG/jfNEnJ9krtjTQKBgQDU\nMjipabTdfdljoO7U3T/BqHqjIDsy/ZaMO8UeVZ91+fpR86+TwV8oWxZiUEUQoF2a\n6UBauEO23H+un/AO3falm5brCt+jl+3bjZcj/aVmNVOLlRvlJ9Ip8Fvm+VmlhLf/\nuG2OqbAU87lzuCMJ3ojcknBM6Kfe84175/RErMOb1QKBgQCn3rN0cG+wrjtt/zmh\nz9FsAcNa9uz7hxYGjPaOd79whEeNYCI4bfDgZwDl68rdl+i6t6t8i3RRJ/A3THGo\noe52rawtJTq9ZumH9Zk0AzjvGzH7NK6oLRgmXTegGEiaZZXLJy4mjn5y6vJxHGO6\nev22ZUlhqKsvCu7t8ZF38n59kw==\n-----END PRIVATE KEY-----\n";
        envConfig.googleSheets.clientEmail = "service-accoint1@gentle-complex-390515.iam.gserviceaccount.com";
        envConfig.googleSheets.clientId = "108979308765806490060";
        envConfig.googleSheets.authUri = "https://accounts.google.com/o/oauth2/auth";
        envConfig.googleSheets.tokenServerUrl = "https://oauth2.googleapis.com/token";
        envConfig.googleSheets.authProviderCertUrl = "https://www.googleapis.com/oauth2/v1/certs";
        envConfig.googleSheets.clientCertUrl = "https://www.googleapis.com/robot/v1/metadata/x509/service-accoint1%40gentle-complex-390515.iam.gserviceaccount.com";
        return envConfig;
      }
    };


    GoogleSheetsClient googleSheetsClient = new GoogleSheetsClient(env.getConfig().googleSheets);
    googleSheetsClient.append(
        "13kEKngPh9_ksp3lC7GhiYDTGY36h8IIaNY8mIIuBvMk", 
        List.of("value1", "value2", "value3", "value4"));
  }

}
