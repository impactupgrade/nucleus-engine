package com.impactupgrade.nucleus.util;

import com.google.api.client.auth.oauth2.PasswordTokenRequest;
import com.google.api.client.auth.oauth2.RefreshTokenRequest;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.common.base.Strings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Date;

public class OAuth2Client {

  private static final Logger log = LogManager.getLogger(OAuth2Client.class);

  public static Tokens refreshTokens(Tokens tokens, String tokenServerUrl) {
    if (tokens != null
        && !Strings.isNullOrEmpty(tokens.accessToken)
        && tokens.expiresAt != null
        //TODO: try to decode given access token (jwt decode) 
        // to parse expiration date
        && tokens.expiresAt.after(new Date())) {
      // No need to refresh
      return tokens;
    }

    Tokens refreshedTokens = null;
    try {
      TokenResponse tokenResponse = new RefreshTokenRequest(new NetHttpTransport(), new JacksonFactory(),
          new GenericUrl(tokenServerUrl), tokens.refreshToken)
          .execute();

      refreshedTokens = toTokens(tokenResponse);
      // TODO: not safe to have these in the logs, but allowing it for a moment while we debug
      log.info("tokens refreshed; accessToken={} refreshToken={}", tokens.accessToken, tokens.refreshToken);

    } catch (Exception e) {
      log.error("Failed to refresh access token!", e);
      logTokenResponseException(e);
    }
    return refreshedTokens;
  }

  public static Tokens getTokens(String username, String password, String tokenServerUrl) {
    Tokens tokens = null;
    try {
      TokenResponse tokenResponse = new PasswordTokenRequest(
          new NetHttpTransport(), new JacksonFactory(),
          new GenericUrl(tokenServerUrl), username, password)
          .execute();

      tokens = toTokens(tokenResponse);
      // TODO: not safe to have these in the logs, but allowing it for a moment while we debug
      log.info("tokens refreshed; accessToken={} refreshToken={}", tokens.accessToken, tokens.refreshToken);

    } catch (Exception e) {
      log.error("Failed to refresh access token!", e);
      logTokenResponseException(e);
    }
    return tokens;
  }

  // Utils
  private static Tokens toTokens(TokenResponse tokenResponse) {
    if (tokenResponse == null) {
      return null;
    }
    Instant expiresAt = Instant.now().plusSeconds(tokenResponse.getExpiresInSeconds());
    Tokens tokens = new Tokens(
        tokenResponse.getAccessToken(), Date.from(expiresAt), tokenResponse.getRefreshToken()
    );
    return tokens;
  }

  private static void logTokenResponseException(Exception e) {
    if (e instanceof TokenResponseException) {
      TokenErrorResponse tokenErrorResponse = ((TokenResponseException) e).getDetails();
      if (tokenErrorResponse != null) {
        log.warn("error={} errorDescription={} errorUri={}",
            tokenErrorResponse.getError(), tokenErrorResponse.getErrorDescription(), tokenErrorResponse.getErrorUri());
      }
    }
  }

  public record Tokens(String accessToken, Date expiresAt, String refreshToken) {
  }
}
