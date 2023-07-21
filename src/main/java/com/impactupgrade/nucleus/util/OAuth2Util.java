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

public class OAuth2Util {

  private static final Logger log = LogManager.getLogger(OAuth2Util.class);

  public static Tokens refreshTokens(Tokens tokens, String tokenServerUrl) {
    return refreshTokens(tokens, null, null, tokenServerUrl);
  }

  public static Tokens refreshTokens(Tokens tokens, Map<String, String> additionalParams, Map<String, String> headers, String tokenServerUrl) {
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
    
    TokenResponse tokenResponse = getTokenResponse(tokenServerUrl, params, additionalParams, headers);
    if (tokenResponse == null) {
      log.warn("failed to refresh tokens!");
    }

    return toTokens(tokenResponse);
  }

  // Utils
  private static TokenResponse getTokenResponse(String url, Map<String, String> params, Map<String, String> additionalParams, Map<String, String> headers) {
    Form form = new Form();
    params.forEach((k, v) -> form.param(k, v));

    if (MapUtils.isNotEmpty(additionalParams)) {
      additionalParams.entrySet().stream()
          .filter(e -> !params.containsKey(e.getKey()))
          .forEach(e -> form.param(e.getKey(), e.getValue()));
    }

    HttpClient.HeaderBuilder headerBuilder = HttpClient.HeaderBuilder.builder();
    headers.forEach((k,v) -> headerBuilder.header(k,v));
    
    return post(
        url,
        form,
        APPLICATION_FORM_URLENCODED,
        headerBuilder,
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
