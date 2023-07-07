package com.impactupgrade.nucleus.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.Form;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static com.impactupgrade.nucleus.util.HttpClient.post;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;

public class OAuth2Util {

  private static final Logger log = LogManager.getLogger(OAuth2Util.class);

  public static Tokens refreshTokens(Tokens tokens, String tokenServerUrl) {
    return refreshTokens(tokens, Collections.emptyMap(), tokenServerUrl);
  }

  public static Tokens refreshTokens(Tokens tokens, Map<String, String> additionalParams, String tokenServerUrl) {
    if (tokens == null) {
      log.warn("can't refresh null!");
      return null;
    }
    if (tokens != null
        && !Strings.isNullOrEmpty(tokens.accessToken)
        && tokens.expiresAt != null
        && tokens.expiresAt.after(new Date())) {
      // No need to refresh
      log.info("access token is still valid - returning as-is...");
      return tokens;
    }

    log.info("refreshing access token...");

    Map<String, String> params = new HashMap<>();
    params.put("refresh_token", tokens.refreshToken);
    params.put("grant_type", "refresh_token");

    TokenResponse tokenResponse = getTokenResponse(tokenServerUrl, params, additionalParams);
    if (tokenResponse == null) {
      log.warn("failed to refresh tokens!");
    }

    return toTokens(tokenResponse);
  }

  public static Tokens getTokensForUsernameAndPassword(String username, String password, String tokenServerUrl) {
    return getTokensForUsernameAndPassword(username, password, Collections.emptyMap(), tokenServerUrl);
  }

  public static Tokens getTokensForUsernameAndPassword(String username, String password, Map<String, String> additionalParams, String tokenServerUrl) {
    log.info("getting new tokens for username and password...");

    Map<String, String> params = new HashMap<>();
    params.put("username", username);
    params.put("password", password);
    params.put("grant_type", "password");

    TokenResponse tokenResponse = getTokenResponse(tokenServerUrl, params, additionalParams);
    if (tokenResponse == null) {
      log.warn("failed to get new tokens for username and password!");
    }
    return toTokens(tokenResponse);
  }

  public static Tokens getTokensForClientCredentials(String clientId, String clientSecret, String tokenServerUrl) {
    return getTokensForClientCredentials(clientId, clientSecret, Collections.emptyMap(), tokenServerUrl);
  }

  public static Tokens getTokensForClientCredentials(String clientId, String clientSecret, Map<String, String> additionalParams, String tokenServerUrl) {
    log.info("getting new tokens for client id and client secret...");

    Map<String, String> params = new HashMap<>();
    params.put("client_id", clientId);
    params.put("client_secret", clientSecret);
    params.put("grant_type", "client_credentials");

    TokenResponse tokenResponse = getTokenResponse(tokenServerUrl, params, additionalParams);
    if (tokenResponse == null) {
      log.warn("failed to get new tokens for username and password!");
    }
    return toTokens(tokenResponse);
  }

  // Utils
  private static TokenResponse getTokenResponse(String url, Map<String, String> params, Map<String, String> additionalParams) {
    Form form = new Form();
    params.forEach((k, v) -> form.param(k, v));

    additionalParams.entrySet().stream()
        .filter(e -> !params.containsKey(e.getKey()))
        .forEach(e -> form.param(e.getKey(), e.getValue()));

    return post(
        url,
        form,
        APPLICATION_FORM_URLENCODED,
        HttpClient.HeaderBuilder.builder(),
        TokenResponse.class
    );
  }

  private static Tokens toTokens(TokenResponse tokenResponse) {
    if (tokenResponse == null) {
      return null;
    }
    Date expiresAt = null;

    if (tokenResponse.expiresInSeconds != null) {
      Instant expires = Instant.now().plusSeconds(tokenResponse.expiresInSeconds);
      expiresAt = Date.from(expires);
    }

    if (expiresAt == null) {
      expiresAt = getExpiresAt(tokenResponse.accessToken);
    }
    return new Tokens(tokenResponse.accessToken, expiresAt, tokenResponse.refreshToken);
  }

  public static Date getExpiresAt(String accessToken) {
    if (Strings.isNullOrEmpty(accessToken)) {
      return null;
    }
    Date expiresAt = null;
    try {
      DecodedJWT decodedJWT = JWT.decode(accessToken);
      expiresAt = decodedJWT.getExpiresAt();
    } catch (JWTDecodeException e) {
      log.warn("failed to decode access token! {}", e.getMessage());
    }
    return expiresAt;
  }

  public record Tokens(String accessToken, Date expiresAt, String refreshToken) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class TokenResponse {
    @JsonProperty("access_token")
    @JsonAlias("token")
    public String accessToken;
    @JsonProperty("expires_in")
    public Integer expiresInSeconds;
    @JsonProperty("refresh_token")
    public String refreshToken;
  }
}
