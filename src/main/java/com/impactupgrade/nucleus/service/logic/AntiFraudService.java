/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class AntiFraudService {

  protected static final String RECAPTCHA_SITE_VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";
  protected static final double MIN_SCORE = 0.3;

  protected final Environment env;

  public AntiFraudService(Environment env) {
    this.env = env;
  }

  public boolean isRecaptchaTokenV2Valid(String recaptchaToken) throws IOException {
    String siteSecret;
    if (!Strings.isNullOrEmpty(env.getConfig().recaptcha.v2SiteSecret)) {
      siteSecret = env.getConfig().recaptcha.v2SiteSecret;
    } else if (!Strings.isNullOrEmpty(env.getConfig().recaptcha.siteSecret)) {
      siteSecret = env.getConfig().recaptcha.siteSecret;
    } else if (!Strings.isNullOrEmpty(System.getenv("RECAPTCHA_V2_SITE_SECRET"))) {
      siteSecret = System.getenv("RECAPTCHA_V2_SITE_SECRET");
    } else {
      siteSecret = System.getenv("RECAPTCHA_SITE_SECRET");
    }

    if (Strings.isNullOrEmpty(siteSecret)) {
      env.logJobInfo("recaptcha: disabled");
      return true;
    }

    if (Strings.isNullOrEmpty(recaptchaToken)) {
      env.logJobInfo("recaptcha: null or empty recaptchaToken");
      return false;
    }

    try {
      JSONObject jsonObject = getRecaptchaResponse(recaptchaToken, siteSecret);
      boolean success = jsonObject.has("success") && jsonObject.getBoolean("success");
      env.logJobInfo("recaptcha: {}", jsonObject);
      return success;
    } catch (Exception e) {
      env.logJobWarn("recaptcha failed; defaulting to invalid", e);
      return false;
    }
  }

  public boolean isRecaptchaTokenV3Valid(String recaptchaToken) throws IOException {
    String siteSecret;
    if (!Strings.isNullOrEmpty(env.getConfig().recaptcha.v3SiteSecret)) {
      siteSecret = env.getConfig().recaptcha.v3SiteSecret;
    } else if (!Strings.isNullOrEmpty(env.getConfig().recaptcha.siteSecret)) {
      siteSecret = env.getConfig().recaptcha.siteSecret;
    } else if (!Strings.isNullOrEmpty(System.getenv("RECAPTCHA_V3_SITE_SECRET"))) {
      siteSecret = System.getenv("RECAPTCHA_V3_SITE_SECRET");
    } else {
      siteSecret = System.getenv("RECAPTCHA_SITE_SECRET");
    }

    if (Strings.isNullOrEmpty(siteSecret)) {
      env.logJobInfo("recaptcha: disabled");
      return true;
    }

    if (Strings.isNullOrEmpty(recaptchaToken)) {
      env.logJobInfo("recaptcha: null or empty recaptchaToken");
      return false;
    }

    try {
      JSONObject jsonObject = getRecaptchaResponse(recaptchaToken, siteSecret);

      boolean success = jsonObject.has("success") && jsonObject.getBoolean("success");
      double score = jsonObject.has("score") ? jsonObject.getDouble("score") : 0.0;
      String hostname = jsonObject.has("hostname") ? jsonObject.getString("hostname") : "";

      if (!success) {
        env.logJobWarn("recaptcha failed: {}", jsonObject);
        // TODO: We're hitting the following often, especially from Axis.
        //  {"error-codes":["browser-error"],"success":false}
        //  {"error-codes":["timeout-or-duplicate"],"success":false}
        return false;
      } else {
        env.logJobInfo("recaptcha: score={} hostname={}", score, hostname);
      }

      return success && score >= MIN_SCORE;
    } catch (Exception e) {
      env.logJobWarn("recaptcha failed; defaulting to invalid", e);
      return false;
    }
  }

  protected JSONObject getRecaptchaResponse(String recaptchaToken, String siteSecret) throws IOException {
    URL url = new URL(RECAPTCHA_SITE_VERIFY_URL);
    StringBuilder postData = new StringBuilder();
    addParam(postData, "secret", siteSecret);
    addParam(postData, "response", recaptchaToken);

    // TODO: Taken from https://github.com/googlecodelabs/recaptcha-codelab/blob/master/final/src/main/java/com/example/feedback/FeedbackServlet.java, but this could be cleaned up...

    HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
    urlConnection.setDoOutput(true);
    urlConnection.setRequestMethod("POST");
    urlConnection.setRequestProperty(
        "Content-Type", "application/x-www-form-urlencoded");
    urlConnection.setRequestProperty(
        "charset", StandardCharsets.UTF_8.displayName());
    urlConnection.setRequestProperty(
        "Content-Length", Integer.toString(postData.length()));
    urlConnection.setUseCaches(false);
    urlConnection.getOutputStream()
        .write(postData.toString().getBytes(StandardCharsets.UTF_8));
    JSONTokener jsonTokener = new JSONTokener(urlConnection.getInputStream());
    return new JSONObject(jsonTokener);
  }

  private StringBuilder addParam(
      StringBuilder postData, String param, String value)
      throws UnsupportedEncodingException {
    if (!postData.isEmpty()) {
      postData.append("&");
    }
    return postData.append(
        String.format("%s=%s",
            URLEncoder.encode(param, StandardCharsets.UTF_8.displayName()),
            URLEncoder.encode(value, StandardCharsets.UTF_8.displayName())));
  }
}
