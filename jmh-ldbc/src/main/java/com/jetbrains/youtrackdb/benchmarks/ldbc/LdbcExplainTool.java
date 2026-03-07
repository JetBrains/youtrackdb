package com.jetbrains.youtrackdb.benchmarks.ldbc;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs EXPLAIN and PROFILE on all LDBC queries against an existing or freshly-loaded DB.
 * Absolute timings from PROFILE are only meaningful on a quiet, dedicated machine;
 * on shared hosts only logical plan structure and record counts are reliable.
 */
public class LdbcExplainTool {

  public static void main(String[] args) throws Exception {
    var state = new LdbcBenchmarkState();
    state.setup();

    try {
      long idx = 0;
      Date startDate = state.maxDate(idx);
      Date endDate = new Date(startDate.getTime() + 30L * 24 * 60 * 60 * 1000);

      var queries = new LinkedHashMap<String, Object[]>();

      queries.put("IS1", new Object[]{LdbcQuerySql.IS1,
          "personId", state.personId(idx)});
      queries.put("IS2", new Object[]{LdbcQuerySql.IS2,
          "personId", state.personId(idx), "limit", 20});
      queries.put("IS3", new Object[]{LdbcQuerySql.IS3,
          "personId", state.personId(idx)});
      queries.put("IS4", new Object[]{LdbcQuerySql.IS4,
          "messageId", state.messageId(idx)});
      queries.put("IS5", new Object[]{LdbcQuerySql.IS5,
          "messageId", state.messageId(idx)});
      queries.put("IS6", new Object[]{LdbcQuerySql.IS6,
          "messageId", state.messageId(idx)});
      queries.put("IS7", new Object[]{LdbcQuerySql.IS7,
          "messageId", state.messageId(idx)});
      queries.put("IC1", new Object[]{LdbcQuerySql.IC1,
          "personId", state.personId(idx), "firstName", state.firstName(idx), "limit", 20});
      queries.put("IC2", new Object[]{LdbcQuerySql.IC2,
          "personId", state.personId(idx), "maxDate", state.maxDate(idx), "limit", 20});
      queries.put("IC3", new Object[]{LdbcQuerySql.IC3,
          "personId", state.personId(idx), "countryX", state.countryName(idx),
          "countryY", state.countryName2(idx), "startDate", startDate, "endDate", endDate,
          "limit", 20});
      queries.put("IC4", new Object[]{LdbcQuerySql.IC4,
          "personId", state.personId(idx), "startDate", startDate, "endDate", endDate,
          "limit", 20});
      queries.put("IC5", new Object[]{LdbcQuerySql.IC5,
          "personId", state.personId(idx), "minDate", state.maxDate(idx), "limit", 20});
      queries.put("IC6", new Object[]{LdbcQuerySql.IC6,
          "personId", state.personId(idx), "tagName", state.tagName(idx), "limit", 20});
      queries.put("IC7", new Object[]{LdbcQuerySql.IC7,
          "personId", state.personId(idx), "limit", 20});
      queries.put("IC8", new Object[]{LdbcQuerySql.IC8,
          "personId", state.personId(idx), "limit", 20});
      queries.put("IC9", new Object[]{LdbcQuerySql.IC9,
          "personId", state.personId(idx), "maxDate", state.maxDate(idx), "limit", 20});
      queries.put("IC10", new Object[]{LdbcQuerySql.IC10,
          "personId", state.personId(idx), "startMd", "0621", "endMd", "0722",
          "wrap", false, "limit", 20});
      queries.put("IC11", new Object[]{LdbcQuerySql.IC11,
          "personId", state.personId(idx), "countryName", state.countryName(idx),
          "workFromYear", 2010, "limit", 20});
      queries.put("IC12", new Object[]{LdbcQuerySql.IC12,
          "personId", state.personId(idx), "tagClassName", state.tagClassName(idx),
          "limit", 20});
      queries.put("IC13", new Object[]{LdbcQuerySql.IC13,
          "person1Id", state.personId(idx), "person2Id", state.personId2(idx)});

      for (var entry : queries.entrySet()) {
        String name = entry.getKey();
        Object[] params = entry.getValue();
        String sql = (String) params[0];
        Object[] keyValues = new Object[params.length - 1];
        System.arraycopy(params, 1, keyValues, 0, keyValues.length);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("QUERY: " + name);
        System.out.println("=".repeat(80));

        // EXPLAIN
        try {
          var result = state.executeSql("EXPLAIN " + sql, keyValues);
          System.out.println("\n--- EXPLAIN ---");
          for (var row : result) {
            Object plan = row.get("executionPlanAsString");
            if (plan != null) {
              System.out.println(plan);
            }
          }
        } catch (Exception e) {
          System.out.println("EXPLAIN failed: " + e.getMessage());
        }

        // PROFILE
        try {
          var result = state.executeSql("PROFILE " + sql, keyValues);
          System.out.println("\n--- PROFILE ---");
          for (var row : result) {
            Object plan = row.get("executionPlanAsString");
            if (plan != null) {
              System.out.println(plan);
            }
          }
        } catch (Exception e) {
          System.out.println("PROFILE failed: " + e.getMessage());
        }
      }
    } finally {
      state.tearDown();
    }
  }
}
