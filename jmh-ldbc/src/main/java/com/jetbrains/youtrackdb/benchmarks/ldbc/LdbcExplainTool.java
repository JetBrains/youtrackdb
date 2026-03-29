package com.jetbrains.youtrackdb.benchmarks.ldbc;

import java.util.Date;
import java.util.LinkedHashMap;

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
      Date ic3Start = state.ic3StartDate(idx);
      Date ic3End = new Date(ic3Start.getTime() + 30L * 24 * 60 * 60 * 1000);
      Date ic4Start = state.ic4StartDate(idx);
      Date ic4End = new Date(ic4Start.getTime() + 30L * 24 * 60 * 60 * 1000);

      var queries = new LinkedHashMap<String, Object[]>();

      queries.put("IS1", new Object[] {LdbcQuerySql.IS1,
          "personId", state.isPersonId(idx)});
      queries.put("IS2", new Object[] {LdbcQuerySql.IS2,
          "personId", state.isPersonId(idx), "limit", 20});
      queries.put("IS3", new Object[] {LdbcQuerySql.IS3,
          "personId", state.isPersonId(idx)});
      queries.put("IS4", new Object[] {LdbcQuerySql.IS4,
          "messageId", state.isMessageId(idx)});
      queries.put("IS5", new Object[] {LdbcQuerySql.IS5,
          "messageId", state.isMessageId(idx)});
      queries.put("IS6", new Object[] {LdbcQuerySql.IS6,
          "messageId", state.isMessageId(idx)});
      queries.put("IS7", new Object[] {LdbcQuerySql.IS7,
          "messageId", state.isMessageId(idx)});
      queries.put("IC1", new Object[] {LdbcQuerySql.IC1,
          "personId", state.ic1PersonId(idx),
          "firstName", state.ic1FirstName(idx), "limit", 20});
      queries.put("IC2", new Object[] {LdbcQuerySql.IC2,
          "personId", state.ic2PersonId(idx),
          "maxDate", state.ic2MaxDate(idx), "limit", 20});
      queries.put("IC3", new Object[] {LdbcQuerySql.IC3,
          "personId", state.ic3PersonId(idx),
          "countryX", state.ic3CountryX(idx),
          "countryY", state.ic3CountryY(idx),
          "startDate", ic3Start, "endDate", ic3End, "limit", 20});
      queries.put("IC4", new Object[] {LdbcQuerySql.IC4,
          "personId", state.ic4PersonId(idx),
          "startDate", ic4Start, "endDate", ic4End, "limit", 20});
      queries.put("IC5", new Object[] {LdbcQuerySql.IC5,
          "personId", state.ic5PersonId(idx),
          "minDate", state.ic5Date(idx), "limit", 20});
      queries.put("IC6", new Object[] {LdbcQuerySql.IC6,
          "personId", state.ic6PersonId(idx),
          "tagName", state.ic6TagName(idx), "limit", 20});
      queries.put("IC7", new Object[] {LdbcQuerySql.IC7,
          "personId", state.ic7PersonId(idx), "limit", 20});
      queries.put("IC8", new Object[] {LdbcQuerySql.IC8,
          "personId", state.ic8PersonId(idx), "limit", 20});
      queries.put("IC9", new Object[] {LdbcQuerySql.IC9,
          "personId", state.ic9PersonId(idx),
          "maxDate", state.ic9MaxDate(idx), "limit", 20});
      queries.put("IC10", new Object[] {LdbcQuerySql.IC10,
          "personId", state.ic10PersonId(idx),
          "startMd", "0621", "endMd", "0722",
          "wrap", false, "limit", 20});
      queries.put("IC11", new Object[] {LdbcQuerySql.IC11,
          "personId", state.ic11PersonId(idx),
          "countryName", state.ic11CountryName(idx),
          "workFromYear", 2010, "limit", 20});
      queries.put("IC12", new Object[] {LdbcQuerySql.IC12,
          "personId", state.ic12PersonId(idx),
          "tagClassName", state.ic12TagClassName(idx), "limit", 20});
      queries.put("IC13", new Object[] {LdbcQuerySql.IC13,
          "person1Id", state.ic13Person1Id(idx),
          "person2Id", state.ic13Person2Id(idx)});

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
