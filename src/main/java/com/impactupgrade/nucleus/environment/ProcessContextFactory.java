/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.environment;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MultivaluedMap;
import java.util.function.Supplier;

public class ProcessContextFactory {

  // We need a way for sub projects to provide a their extended ProcessContext impls. For now, stick to a bit of FP.
  public static Supplier<ProcessContext> SUPPLIER = ProcessContext::new;

  public static ProcessContext init(HttpServletRequest request) {
    ProcessContext processContext = SUPPLIER.get();
    processContext.request = request;
    return processContext;
  }

  public static ProcessContext init(MultivaluedMap<String, String> otherContext) {
    ProcessContext processContext = SUPPLIER.get();
    processContext.otherContext = otherContext;
    return processContext;
  }

  public static ProcessContext init() {
    return SUPPLIER.get();
  }

  // A unique case -- needed for manually called util methods that have ProcessContext provided to them.
//  public static ProcessContext init(ProcessContext processContext) {
//    ProcessContext newProcessContext = SUPPLIER.get();
//    newProcessContext.env = processContext.env;
//    newProcessContext.request = processContext.getRequest();
//    newProcessContext.otherContext = processContext.otherContext;
//    return newProcessContext;
//  }
}
