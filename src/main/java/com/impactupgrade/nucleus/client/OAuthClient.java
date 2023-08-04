package com.impactupgrade.nucleus.client;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import javax.ws.rs.core.Form;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.impactupgrade.nucleus.util.HttpClient.post;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;

public abstract class OAuthClient extends DBConfiguredClient {

  private static final Logger log = LogManager.getLogger(OAuthClient.class);

  protected final String name;

  protected OAuthContext oAuthContext = null;

  public OAuthClient(String name, Environment env) {
    super(env);
    this.name = name;
  }

  protected abstract OAuthContext oAuthContext();

  // Some clients, like Mailchimp, will override this if they have more than one platform per env.json. Finding the
  // correct node to update tends to be pretty vendor specific. But by default, it's a simple by-key lookup.
  protected JSONObject getClientConfigJson(JSONObject envJson) {
    return envJson.getJSONObject(name);
  }

  protected void updateEnvJson(OAuthContext oAuthContext) {
    if (env.getConfig().isDatabaseConnected()) {
      Organization org = getOrganization();
      JSONObject envJson = org.getEnvironmentJson();
      JSONObject clientConfigJson = getClientConfigJson(envJson);

      clientConfigJson.put("accessToken", oAuthContext.accessToken());
      clientConfigJson.put("expiresAt", oAuthContext.expiresAt() != null ? oAuthContext.expiresAt() : null);
      clientConfigJson.put("refreshToken", oAuthContext.refreshToken());

      org.setEnvironmentJson(envJson);
      organizationDao.update(org);
    }
  }

  protected HttpClient.HeaderBuilder headers() {
    if (oAuthContext == null) {
      oAuthContext = oAuthContext();
    }

    String accessToken = oAuthContext.accessToken();
    if (!Objects.equals(oAuthContext.refresh().accessToken(), accessToken)) {
      // tokens updated - need to update config in db
      updateEnvJson(oAuthContext);
    }
    return HttpClient.HeaderBuilder.builder().authBearerToken(oAuthContext.accessToken());
  }

  protected static abstract class OAuthContext {

    protected Tokens tokens;
    protected final String tokenUrl;
    protected final boolean enableRefresh;

    protected Map<String, String> getTokensAdditionalHeaders; // if any
    protected Map<String, String> getTokensAdditionalParams; // if any
    protected Map<String, String> refreshTokensAdditionalHeaders; // if any
    protected Map<String, String> refreshTokensAdditionalParams; // if any

    public OAuthContext(EnvironmentConfig.Platform platform, String tokenUrl, boolean enableRefresh) {
      Date expiresAtDate = platform.expiresAt != null ? Date.from(Instant.ofEpochSecond(platform.expiresAt)) : null;
      this.tokens = new Tokens(platform.accessToken, expiresAtDate, platform.refreshToken);
      this.tokenUrl = tokenUrl;
      this.enableRefresh = enableRefresh;
    }

    public OAuthContext refresh() {
      if (tokens.isValid()) {
        log.info("access token is still valid - returning as-is...");
        return this;
      }

      if (enableRefresh) {
        tokens = refreshTokens();
      }
      if (tokens == null || !tokens.isValid()) {
        tokens = getTokens();
      }
      return this;
    }

    protected String accessToken() {
      return tokens != null ? tokens.accessToken : null;
    }

    protected Date expiresAt() {
      return tokens != null ? tokens.expiresAt : null;
    }

    protected String refreshToken() {
      return tokens != null ? tokens.refreshToken : null;
    }

    protected Tokens refreshTokens() {
      if (tokens == null) {
        log.warn("can't refresh null!");
        return null;
      }

      log.info("refreshing access token...");

      Map<String, String> params = new HashMap<>();
      params.put("refresh_token", tokens.refreshToken);
      params.put("grant_type", "refresh_token");
      mergeAdditionalParams(params, refreshTokensAdditionalParams);

      TokenResponse tokenResponse = getTokenResponse(tokenUrl, refreshTokensAdditionalHeaders, params);
      if (tokenResponse == null) {
        log.warn("failed to refresh tokens!");
      }

      return toTokens(tokenResponse);
    }

    protected abstract Tokens getTokens();
  }

  public static final class ClientCredentialsOAuthContext extends OAuthContext {

    private final String clientId;
    private final String clientSecret;

    public ClientCredentialsOAuthContext(EnvironmentConfig.Platform platform, String tokenUrl, boolean enableRefresh) {
      super(platform, tokenUrl, enableRefresh);
      this.clientId = platform.clientId;
      this.clientSecret = platform.clientSecret;
    }

    @Override
    public Tokens getTokens() {
      log.info("getting new tokens for client id and client secret...");

      Map<String, String> params = new HashMap<>();
      params.put("client_id", clientId);
      params.put("client_secret", clientSecret);
      params.put("grant_type", "client_credentials");
      mergeAdditionalParams(params, getTokensAdditionalParams);

      TokenResponse tokenResponse = getTokenResponse(tokenUrl, getTokensAdditionalHeaders, params);
      if (tokenResponse == null) {
        log.warn("failed to get new tokens for client_id={}", clientId);
      }
      return toTokens(tokenResponse);
    }
  }

  public final class UsernamePasswordOAuthContext extends OAuthContext {

    private final String username;
    private final String password;

    public UsernamePasswordOAuthContext(EnvironmentConfig.Platform platform, String tokenUrl, boolean enableRefresh) {
      super(platform, tokenUrl, enableRefresh);
      this.username = platform.username;
      this.password = platform.password;
    }

    @Override
    public Tokens getTokens() {
      log.info("getting new tokens for username and password...");

      Map<String, String> params = new HashMap<>();
      params.put("username", username);
      params.put("password", password);
      params.put("grant_type", "password");
      params.put("scope", "offline_access");
      mergeAdditionalParams(params, getTokensAdditionalParams);

      TokenResponse tokenResponse = getTokenResponse(tokenUrl, getTokensAdditionalHeaders, params);
      if (tokenResponse == null) {
        log.warn("failed to get new tokens for username={}", username);
      }
      return toTokens(tokenResponse);
    }
  }

  // Utils

  private static Map<String, String> mergeAdditionalParams(Map<String, String> baseParams, Map<String, String> additionalParams) {
    if (MapUtils.isEmpty(baseParams) || MapUtils.isEmpty(additionalParams)) {
      return baseParams;
    }
    additionalParams.forEach(baseParams::putIfAbsent);
    return baseParams;
  }

  private static TokenResponse getTokenResponse(String url, Map<String, String> headers, Map<String, String> params) {
    HttpClient.HeaderBuilder headerBuilder = HttpClient.HeaderBuilder.builder();
    if (MapUtils.isNotEmpty(headers)) {
      headers.forEach((k, v) -> headerBuilder.header(k, v));
    }

    Form form = new Form();
    params.forEach((k, v) -> form.param(k, v));

    return post(url, form, APPLICATION_FORM_URLENCODED, headerBuilder, TokenResponse.class);
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

  private static class Tokens {
    public String accessToken;
    public Date expiresAt;
    public String refreshToken;

    public Tokens(String accessToken, Date expiresAt, String refreshToken) {
      this.accessToken = accessToken;
      this.expiresAt = expiresAt;
      this.refreshToken = refreshToken;
    }

    public boolean isValid() {
      return !Strings.isNullOrEmpty(accessToken)
          && expiresAt != null
          && expiresAt.after(new Date());
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  protected static final class TokenResponse {
    @JsonProperty("access_token")
    @JsonAlias("token")
    public String accessToken;
    @JsonProperty("expires_in")
    public Integer expiresInSeconds;
    @JsonProperty("refresh_token")
    public String refreshToken;
  }
}
