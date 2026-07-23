package com.jetbrains.youtrackdb.benchmarks.ldbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads all LDBC SNB Interactive read query SQL from classpath resources.
 */
final class LdbcQuerySql {

  // Interactive Short queries
  static final String IS1 = loadResource("ldbc-queries/IS1.sql");
  static final String IS2 = loadResource("ldbc-queries/IS2.sql");
  static final String IS3 = loadResource("ldbc-queries/IS3.sql");
  static final String IS4 = loadResource("ldbc-queries/IS4.sql");
  static final String IS5 = loadResource("ldbc-queries/IS5.sql");
  static final String IS6 = loadResource("ldbc-queries/IS6.sql");
  static final String IS7 = loadResource("ldbc-queries/IS7.sql");

  // Interactive Complex queries
  static final String IC1 = loadResource("ldbc-queries/IC1.sql");
  static final String IC2 = loadResource("ldbc-queries/IC2.sql");
  static final String IC3 = loadResource("ldbc-queries/IC3.sql");
  static final String IC4 = loadResource("ldbc-queries/IC4.sql");
  /** Counts friends' posts before startDate — the NOT pattern scan cost in IC4. */
  static final String IC4_OLDPOST_COUNT =
      loadResource("ldbc-queries/IC4-oldpost-count.sql");
  static final String IC5 = loadResource("ldbc-queries/IC5.sql");
  static final String IC6 = loadResource("ldbc-queries/IC6.sql");
  static final String IC7 = loadResource("ldbc-queries/IC7.sql");
  static final String IC8 = loadResource("ldbc-queries/IC8.sql");
  static final String IC9 = loadResource("ldbc-queries/IC9.sql");
  static final String IC10 = loadResource("ldbc-queries/IC10.sql");
  static final String IC11 = loadResource("ldbc-queries/IC11.sql");
  static final String IC12 = loadResource("ldbc-queries/IC12.sql");
  static final String IC13 = loadResource("ldbc-queries/IC13.sql");

  // -- Extension queries (not part of LDBC standard) --

  /**
   * Recent KNOWS connections via bothE — targets the bidirectional pre-filter
   * optimization introduced with {@code PreFilterableChainedIterable}.
   * Requires {@code KNOWS.creationDate} index (added to {@code ldbc-schema.sql}).
   */
  static final String BOTH_E_KNOWS = loadResource("ldbc-queries/both-e-knows.sql");

  /**
   * Forum recent-joiners via bothE(HAS_MEMBER) — hub-shape variant of the
   * pre-filter benchmark, targeting Forums with thousands of members. Requires
   * {@code HAS_MEMBER.joinDate} index (present in {@code ldbc-schema.sql}).
   */
  static final String FORUM_RECENT_JOINERS =
      loadResource("ldbc-queries/forum-recent-joiners.sql");

  /**
   * Forum joiner-count via bothE(HAS_MEMBER) — count-only variant designed to
   * maximise the visible speedup from the bothE pre-filter: no .inV() loads,
   * no ORDER BY materialization, no attribute projection. The only cost is
   * the edge scan/filter itself. Uses a narrow 99th-percentile lower-bound
   * date so ~1% of edges survive.
   */
  static final String FORUM_JOINER_COUNT =
      loadResource("ldbc-queries/forum-joiner-count.sql");

  private LdbcQuerySql() {
  }

  private static String loadResource(String path) {
    try (var is = LdbcQuerySql.class.getClassLoader().getResourceAsStream(path)) {
      if (is == null) {
        throw new IllegalStateException("SQL resource not found: " + path);
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
