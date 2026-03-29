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
 * Abstract base class for noisy IS-tier LDBC SNB query benchmarks.
 * Contains IS2, IS7, and IC8 which have moderate throughput (400-2700 ST ops/s
 * on SF 1) but higher inter-fork variance due to page cache and JIT sensitivity.
 *
 * <p>10 forks with 3 warmup iterations per benchmark are needed to bring error
 * below 7%. Ultra-fast queries (IS1, IS3-IS6, IC13) that need fewer forks are
 * in {@link LdbcISUltraFastBenchmarkBase}.
 *
 * <p>Subclasses specify the thread count via @Threads annotation.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 3, time = 10)
@Fork(value = 10, jvmArgsAppend = {
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
public abstract class LdbcISBenchmarkBase {

  private static final int LIMIT = 20;

  /**
   * IS2: Recent messages of a person.
   * Given a Person, retrieve their last 10 messages with original post info.
   * ~468 ST ops/s on SF 1 — needs extra warmup and forks to stabilize.
   */
  @Benchmark
  public List<Map<String, Object>> is2_personPosts(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS2,
        "personId", state.isPersonId(i),
        "limit", LIMIT);
  }

  /**
   * IS7: Replies of a message.
   * Given a Message, retrieve 1-hop reply Comments with author-knows-author
   * flag. ~2,700 ST ops/s on SF 1 — fast but noisy due to inter-fork JIT
   * variance at this throughput. Needs 10 forks to stabilize below 7%.
   */
  @Benchmark
  public List<Map<String, Object>> is7_messageReplies(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IS7,
        "messageId", state.isMessageId(i));
  }

  /**
   * IC8: Recent replies.
   * Find most recent Comments replying to a Person's Messages.
   * ~791 ST ops/s on SF 1 — needs extra warmup and forks to stabilize.
   */
  @Benchmark
  public List<Map<String, Object>> ic8_recentReplies(LdbcBenchmarkState state) {
    long i = state.nextIndex();
    return state.executeSql(LdbcQuerySql.IC8,
        "personId", state.ic8PersonId(i),
        "limit", LIMIT);
  }
}
