package com.impactupgrade.nucleus.client;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.OAuth2;
import org.json.JSONObject;

public class OrgConfiguredClient {

  protected final Environment env;
  protected final HibernateDao<Long, Organization> organizationDao;

  public OrgConfiguredClient(Environment env) {
    this.env = env;
    this.organizationDao = new HibernateDao<>(Organization.class);
  }

  protected JSONObject getEnvJson() {
    return getOrganization(env.getConfig().apiKey).getEnvironmentJson();
  }

  protected void updateEnvJson(String clientConfigKey, OAuth2.Context context) {
    Organization org = getOrganization(env.getConfig().apiKey);
    JSONObject envJson = org.getEnvironmentJson();
    JSONObject clientConfigJson = envJson.getJSONObject(clientConfigKey);

    clientConfigJson.put("accessToken", context.accessToken());
    clientConfigJson.put("expiresAt", context.expiresAt() != null ? context.expiresAt() : null);
    clientConfigJson.put("refreshToken", context.refreshToken());

    org.setEnvironmentJson(envJson);
    organizationDao.update(org);
  }

  private Organization getOrganization(String apiKey) {
    return organizationDao.getQueryResult(
        "from Organization o where o.nucleusApiKey=:apiKey", 
        query -> query.setParameter("apiKey", apiKey)
    ).get();
  }
}
