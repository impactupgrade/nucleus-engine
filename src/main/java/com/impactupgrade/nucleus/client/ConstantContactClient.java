package com.impactupgrade.nucleus.client;

import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.util.HttpClient;
import com.impactupgrade.nucleus.util.OAuth2Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

public class ConstantContactClient {
  
  private static final Logger log = LogManager.getLogger(ConstantContactClient.class);
  
  private static final String API_BASE_URL = "https://api.cc.email/v3";
  private static final String AUTH_URL = "https://authz.constantcontact.com/oauth2/default/v1/token";
  
  private EnvironmentConfig.EmailPlatform constantContactConfig;
  private OAuth2Util.Tokens tokens;
  
  public ConstantContactClient(EnvironmentConfig.EmailPlatform constantContactConfig) {
    this.constantContactConfig = constantContactConfig;
    this.tokens = new OAuth2Util.Tokens(constantContactConfig.accessToken, null, constantContactConfig.refreshToken);
  }
  
  public void getUserPrivileges() {
    String s = HttpClient.get(API_BASE_URL + "/account/user/privileges", headers(), String.class);
    System.out.println(s);
  }

  protected HttpClient.HeaderBuilder headers() {
    String authHeader = constantContactConfig.clientId + ":" + constantContactConfig.clientSecret;
    tokens = OAuth2Util.refreshTokens(tokens, 
        Collections.emptyMap(), 
        Map.of(
            "Authorization", "Basic " + Base64.getEncoder().encodeToString(authHeader.getBytes(StandardCharsets.UTF_8))), 
        AUTH_URL);
//    if (tokens == null) {
//      tokens = OAuth2Util.getTokensForUsernameAndPassword(username, password, Map.of("requestAdminToken", "true"), AUTH_URL);
//    }
    String accessToken = tokens != null ? tokens.accessToken() : null;
    return HttpClient.HeaderBuilder.builder().authBearerToken(accessToken);
  }

  public static void main(String[] args) {
    EnvironmentConfig.EmailPlatform emailPlatform = new EnvironmentConfig.EmailPlatform();
    emailPlatform.clientId = "f7a59166-c879-4763-82b2-d5aff202a4a3";
    emailPlatform.clientSecret = "WSpU_LGm036P6EVnPw3bgQ";

    emailPlatform.refreshToken = "sACdjXG1iTL-Pdy2LkMFkMPA7oZEy7wHcvORITdO0Ck";

    ConstantContactClient constantContactClient = new ConstantContactClient(emailPlatform);

    constantContactClient.getUserPrivileges();
  }
  
}
