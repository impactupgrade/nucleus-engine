/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class AntiFraudService {

  private static Logger log = LoggerFactory.getLogger(AntiFraudService.class);

  private static final String RECAPTCHA_SITE_VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";
  private static final double MIN_SCORE = 0.3;

  private final String siteSecret;

  public AntiFraudService(Environment env) {
    if (!Strings.isNullOrEmpty(env.getConfig().recaptcha.siteSecret)) {
      siteSecret = env.getConfig().recaptcha.siteSecret;
    } else {
      siteSecret = System.getenv("RECAPTCHA_SITE_SECRET");
    }
  }

  // Use case example: Donation Spring, which has a single, non-client-specific key.
  public AntiFraudService(String siteSecret) {
    this.siteSecret = siteSecret;
  }

  public boolean isRecaptchaTokenValid(String recaptchaToken) throws IOException {
    if (Strings.isNullOrEmpty(siteSecret)) {
      log.info("recaptcha: disabled");
      return true;
    }

    if (Strings.isNullOrEmpty(recaptchaToken)) {
      log.info("recaptcha: null or empty recaptchaToken");
      return false;
    }

    URL url = new URL(RECAPTCHA_SITE_VERIFY_URL);
    StringBuilder postData = new StringBuilder();
    addParam(postData, "secret", siteSecret);
    addParam(postData, "response", recaptchaToken);

    // TODO: Taken from https://github.com/googlecodelabs/recaptcha-codelab/blob/master/final/src/main/java/com/example/feedback/FeedbackServlet.java, but this could be cleaned up...

    try {
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
      JSONObject jsonObject = new JSONObject(jsonTokener);

      boolean success = jsonObject.has("success") ? jsonObject.getBoolean("success") : false;
      double score = jsonObject.has("score") ? jsonObject.getDouble("score") : 0.0;
      String hostname = jsonObject.has("hostname") ? jsonObject.getString("hostname") : "";

      if (!success) {
        log.warn("recaptcha failed: {}", jsonObject);
      } else {
        log.info("recaptcha: score={} hostname={}", score, hostname);
      }

      return success && score >= MIN_SCORE;
    } catch (Exception e) {
      log.warn("recaptcha failed; defaulting to invalid", e);
      return false;
    }
  }

  private StringBuilder addParam(
      StringBuilder postData, String param, String value)
      throws UnsupportedEncodingException {
    if (postData.length() != 0) {
      postData.append("&");
    }
    return postData.append(
        String.format("%s=%s",
            URLEncoder.encode(param, StandardCharsets.UTF_8.displayName()),
            URLEncoder.encode(value, StandardCharsets.UTF_8.displayName())));
  }
}
