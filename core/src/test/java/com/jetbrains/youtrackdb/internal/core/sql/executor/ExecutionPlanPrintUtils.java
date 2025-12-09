package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.query.BasicResultSet;

/**
 *
 */
public class ExecutionPlanPrintUtils {

  public static void printExecutionPlan(BasicResultSet result) {
    printExecutionPlan(null, result);
  }

  public static void printExecutionPlan(String query, BasicResultSet result) {
    //    if (query != null) {
    //      System.out.println(query);
    //    }
    //    result.getExecutionPlan().ifPresent(x -> System.out.println(x.prettyPrint(0, 3)));
    //    System.out.println();
  }
}
