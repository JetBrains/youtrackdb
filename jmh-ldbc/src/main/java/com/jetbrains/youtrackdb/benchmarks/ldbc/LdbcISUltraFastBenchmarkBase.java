package com.jetbrains.youtrackdb.benchmarks.ldbc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Abstract base class for ultra-fast LDBC SNB query benchmarks.
 * Contains queries with >2,700 ST ops/s on SF 1: IS1, IS3-IS6, IC13.
 *
 * <p>At this throughput, each 10s measurement iteration produces tens of
 * thousands of data points, so inter-fork JIT variance is negligible. 5 forks
 * with 3 measurement iterations of 10s each give 15 data points — more than
 * enough for <3% error.
 *
 * <p>Slower or noisier IS-tier queries (IS2, IS7, IC8) that need more warmup
 * and forks remain in {@link LdbcISBenchmarkBase}.
 *
 * <p>Subclasses specify the thread count via @Threads annotation.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 3, time = 10)
@Fork(value = 5, jvmArgsAppend = {
    "-Xms4g", "-Xmx4g",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.x509=ALL-UNNAMED",
    "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
})
public abstract class LdbcISUltraFastBenchmarkBase {

  private static final int LIMIT = 20;

  /**
   * IS1: Profile of a person.
   * Given a Person, retrieve their profile (name, birthday, IP, browser, city).
   */
  @Benchmark
  public List<Map<String, Object>> is1_personProfile(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS1,
        "personId", state.isPersonId(i));
  }

  /**
   * IS3: Friends of a person.
   * Given a Person, retrieve all friends with friendship creation dates.
   */
  @Benchmark
  public List<Map<String, Object>> is3_personFriends(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS3,
        "personId", state.isPersonId(i));
  }

  /**
   * IS4: Content of a message.
   * Given a Message (Post/Comment), retrieve its content and creation date.
   */
  @Benchmark
  public List<Map<String, Object>> is4_messageContent(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS4,
        "messageId", state.isMessageId(i));
  }

  /**
   * IS5: Creator of a message.
   * Given a Message, retrieve its author.
   */
  @Benchmark
  public List<Map<String, Object>> is5_messageCreator(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS5,
        "messageId", state.isMessageId(i));
  }

  /**
   * IS6: Forum of a message.
   * Given a Message, retrieve the containing Forum and its moderator.
   */
  @Benchmark
  public List<Map<String, Object>> is6_messageForum(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS6,
        "messageId", state.isMessageId(i));
  }

  /**
   * IC13: Single shortest path.
   * Find the shortest path between two Persons via KNOWS edges.
   * ~4,043 ops/s (ST) on SF 1.
   */
  @Benchmark
  public List<Map<String, Object>> ic13_shortestPath(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC13,
        "person1Id", state.ic13Person1Id(i),
        "person2Id", state.ic13Person2Id(i));
  }
}
