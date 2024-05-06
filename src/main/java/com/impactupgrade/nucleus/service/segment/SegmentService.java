/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;

public interface SegmentService {

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // REGISTRATION (for use by environment.json and Environment.java)
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    String name();
    boolean isConfigured(Environment env);
    void init(Environment env);
}
