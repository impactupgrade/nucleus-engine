/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;

public abstract class DBConfiguredClient {

  protected final Environment env;
  protected final HibernateDao<Long, Organization> organizationDao;

  public DBConfiguredClient(Environment env) {
    this.env = env;
    if (env.getConfig().isDatabaseConnected()) {
      this.organizationDao = new HibernateDao<>(Organization.class);
    } else {
      this.organizationDao = null;
    }
  }

  protected Organization getOrganization() {
    if (env.getConfig().isDatabaseConnected()) {
      return organizationDao.getQueryResult(
          "from Organization o where o.nucleusApiKey=:apiKey",
          query -> query.setParameter("apiKey", env.getConfig().apiKey)
      ).get();
    } else {
      return null;
    }
  }
}
