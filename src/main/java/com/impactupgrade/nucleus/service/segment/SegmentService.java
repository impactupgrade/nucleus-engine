package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;

public interface SegmentService {

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // REGISTRATION (for use by environment.json and Environment.java)
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    String name();
    void init(Environment env);
}
