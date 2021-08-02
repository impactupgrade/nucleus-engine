/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.impactupgrade.nucleus.environment.Environment;

import javax.servlet.http.HttpServletRequest;

public class EnvironmentIT extends Environment {

  public EnvironmentIT(HttpServletRequest request) {
    super(request);
  }

  // TODO
//  @Override
//  public SfdcClient sfdcClient() {
//    // https://impactupgrade-dev-ed.lightning.force.com
//    // technically "production" since we're using a developer education account, so don't let the "profile" dictate it
//    return new SfdcClient(this, getConfig().salesforce.username, getConfig().salesforce.password, SfdcClient.AUTH_URL_PRODUCTION);
//  }
}
