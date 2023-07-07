package com.impactupgrade.nucleus.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.Form;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static com.impactupgrade.nucleus.util.HttpClient.post;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;

public class OAuth2 {

  private static final Logger log = LogManager.getLogger(OAuth2.class);

  public static abstract class Context {

    protected Tokens tokens;
    protected String tokenUrl;

    public Context(String accessToken, Long expiresAt, String refreshToken, String tokenUrl) {
      Date expiresAtDate = expiresAt != null ? Date.from(Instant.ofEpochSecond(expiresAt)) : null; 
      this.tokens = new Tokens(accessToken, expiresAtDate, refreshToken);
      this.tokenUrl = tokenUrl;
    }

    public Context refresh() {
      // Refresh tokens using current refresh token
      tokens = refreshTokens();
      if (tokens == null) {
        // Get a new pair of tokens if failed to refresh
        tokens = getTokens();
      }
      return this;
    }

    public String accessToken() {
      return tokens != null ? tokens.accessToken() : null;
    }

    public Date expiresAt() {
      return tokens != null ? tokens.expiresAt : null;
    }

    public String refreshToken() {
      return tokens != null ? tokens.refreshToken() : null;
    }

    protected Tokens refreshTokens() {
      if (tokens == null) {
        log.warn("can't refresh null!");
        return null;
      }
      if (!Strings.isNullOrEmpty(tokens.accessToken)
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

      TokenResponse tokenResponse = getTokenResponse(tokenUrl, params, null);
      if (tokenResponse == null) {
        log.warn("failed to refresh tokens!");
      }

      return toTokens(tokenResponse);
    }

    protected abstract Tokens getTokens();
  }

  public static final class ClientCredentialsContext extends Context {

    private final String clientId;
    private final String clientSecret;

    public ClientCredentialsContext(String clientId, String clientSecret, String accessToken, Long expiresAt, String refreshToken, String tokenUrl) {
      super(accessToken, expiresAt, refreshToken, tokenUrl);
      this.clientId = clientId;
      this.clientSecret = clientSecret;
    }

    @Override
    public Tokens getTokens() {
      log.info("getting new tokens for client id and client secret...");

      Map<String, String> params = new HashMap<>();
      params.put("client_id", clientId);
      params.put("client_secret", clientSecret);
      params.put("grant_type", "client_credentials");

      TokenResponse tokenResponse = getTokenResponse(tokenUrl, params, null);
      if (tokenResponse == null) {
        log.warn("failed to get new tokens for username and password!");
      }
      return toTokens(tokenResponse);
    }
  }

  public static final class UsernamePasswordContext extends Context {

    private final String username;
    private final String password;

    private final Map<String, String> requestTokenParams;

    public UsernamePasswordContext(
        String username, String password,
        Map<String, String> requestTokenParams,
        String accessToken, Long expiresAt, String refreshToken, String tokenUrl) {
      super(accessToken, expiresAt, refreshToken, tokenUrl);
      this.username = username;
      this.password = password;
      this.requestTokenParams = requestTokenParams;
    }

    @Override
    public Tokens getTokens() {
      log.info("getting new tokens for username and password...");

      Map<String, String> params = new HashMap<>();
      params.put("username", username);
      params.put("password", password);
      params.put("grant_type", "password");

      TokenResponse tokenResponse = getTokenResponse(tokenUrl, params, requestTokenParams);
      if (tokenResponse == null) {
        log.warn("failed to get new tokens for username and password!");
      }
      return toTokens(tokenResponse);
    }
  }

  // Utils
  private static TokenResponse getTokenResponse(String url, Map<String, String> params, Map<String, String> additionalParams) {
    Form form = new Form();
    params.forEach((k, v) -> form.param(k, v));

    if (MapUtils.isNotEmpty(additionalParams)) {
      additionalParams.entrySet().stream()
          .filter(e -> !params.containsKey(e.getKey()))
          .forEach(e -> form.param(e.getKey(), e.getValue()));
    }

    return post(url, form, APPLICATION_FORM_URLENCODED, HttpClient.HeaderBuilder.builder(), TokenResponse.class);
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

  private static Date getExpiresAt(String accessToken) {
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

  private record Tokens(String accessToken, Date expiresAt, String refreshToken) {}

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
