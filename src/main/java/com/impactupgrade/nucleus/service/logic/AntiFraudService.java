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
  private static final double MIN_SCORE = 0.5;

  private final Environment env;

  public AntiFraudService(Environment env) {
    this.env = env;
  }

  public boolean isRecaptchaTokenValid(String recaptchaToken) throws IOException {
    if (Strings.isNullOrEmpty(recaptchaToken)) {
      log.info("recaptcha: null or empty recaptchaToken");
      return false;
    }

    URL url = new URL(RECAPTCHA_SITE_VERIFY_URL);
    StringBuilder postData = new StringBuilder();
    addParam(postData, "secret", env.getConfig().recaptcha.siteSecret);
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
    JSONObject jsonObject = new JSONObject(jsonTokener);

    boolean success = jsonObject.getBoolean("success");
    double score = jsonObject.getDouble("score");
    String hostname = jsonObject.getString("hostname");

    log.info("recaptcha: success={} score={} hostname={}", success, score, hostname);

    return success && score >= MIN_SCORE;
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
