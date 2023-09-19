package com.impactupgrade.nucleus.client;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;

public abstract class DBConfiguredClient {

  protected final Environment env;
  protected final HibernateDao<Long, Organization> organizationDao;

  public DBConfiguredClient(Environment env) {
    this.env = env;
    this.organizationDao = new HibernateDao<>(Organization.class);
  }

  protected Organization getOrganization() {
    return organizationDao.getQueryResult(
        "from Organization o where o.nucleusApiKey=:apiKey", 
        query -> query.setParameter("apiKey", env.getConfig().apiKey)
    ).get();
  }
}
