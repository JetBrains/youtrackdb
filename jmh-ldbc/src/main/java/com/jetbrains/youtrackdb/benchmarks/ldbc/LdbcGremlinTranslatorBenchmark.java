package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Translator-on against translator-off over the four Gremlin shapes in
 * {@link GremlinTraversalShapes}, on the LDBC schema.
 *
 * <p>The A/B axis is a JMH {@code @Param} on {@link TranslatorArm}, so each arm gets its own
 * forks and the kill-switch is flipped once per trial in-process. Nothing is set through
 * {@code -DargLine=}: on some modules a CLI {@code argLine} replaces the POM's block wholesale
 * (taking {@code -ea}, the heap sizing and every {@code --add-opens} with it) and on others plugin
 * configuration wins and the CLI value is inert. Neither failure is visible in the numbers.
 *
 * <p><b>What these numbers are not.</b> They are not comparable to this module's IC / IS figures —
 * see {@link GremlinTraversalShapes}. The baseline is Hetzner-scoped; a local run measures the
 * harness, not the feature.
 *
 * <p><b>Why the trial setup checks engagement and throws.</b> An A/B whose two arms both ran the
 * same path reports a difference of zero and looks like a clean result. {@link
 * TranslatorArm#setUp} therefore builds a witness traversal, applies strategies, and throws unless
 * the boundary step is present on the on-arm and absent on the off-arm. It throws rather than
 * asserting because the launcher at {@code jmh-ldbc/pom.xml} runs {@code java} without {@code -ea}
 * — see {@link GremlinTraversalShapes#requireTranslated}.
 *
 * <p>Run one arm only with {@code -Djmh.args=".*LdbcGremlinTranslator.* -p translatorEnabled=true"}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 3, time = 10)
@Threads(1)
@Fork(value = 3, jvmArgsAppend = {
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
public class LdbcGremlinTranslatorBenchmark {

  /**
   * Per-arm trial state: the kill-switch position, a RID pool for the by-id shape, and the person
   * id pool the three walk shapes rotate through.
   *
   * <p>Kept separate from {@link LdbcBenchmarkState} so that class stays untouched and {@code
   * curatedParams} stays private. The pool is built through the public {@code isPersonId} /
   * {@code ic1PersonId} accessors, which is all the by-id shape needs — it wants a RID while the
   * curated parameters hold LDBC {@code id} longs, so the resolution happens once here rather than
   * per invocation.
   */
  @State(Scope.Benchmark)
  public static class TranslatorArm {

    /**
     * The A/B axis. JMH runs a separate set of forks per value, so a trial never sees both
     * positions of the kill-switch.
     */
    @Param({"true", "false"})
    public boolean translatorEnabled;

    /** Pool size: large enough that invocation N and invocation N+1 rarely repeat a record. */
    private static final int POOL_SIZE = 64;

    private long[] personIds;
    private Object[] personRids;
    private boolean flagBeforeTrial;

    /**
     * Resolves the RID pool and proves the arm is really installed.
     *
     * <p>Order matters: the flag is set before anything else runs, so setup and measurement share
     * one arm. The pool is then resolved from curated person ids, skipping ids the dataset does not
     * hold, and an empty pool throws — an empty parameter feed would otherwise surface only as
     * suspiciously fast numbers on the first Hetzner run.
     */
    @Setup(Level.Trial)
    public void setUp(LdbcBenchmarkState state) {
      flagBeforeTrial =
          GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getValueAsBoolean();
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(translatorEnabled);

      var ids = new ArrayList<Long>(POOL_SIZE);
      var rids = new ArrayList<Object>(POOL_SIZE);
      state.traversal.executeInTx(t -> {
        for (var i = 0; i < POOL_SIZE; i++) {
          var personId = state.isPersonId(i);
          // Resolve the RID through a by-property lookup rather than a second curated pool: the
          // curated parameters hold LDBC id longs only, and V(rid) needs the record identity.
          var vertex = t.V().hasLabel(GremlinTraversalShapes.PERSON_LABEL)
              .has("id", personId)
              .tryNext();
          if (vertex.isPresent()) {
            ids.add(personId);
            rids.add(vertex.get().id());
          }
        }
      });

      if (ids.isEmpty()) {
        throw new IllegalStateException(
            "No curated Person id resolved to a record, so every benchmark below would measure an"
                + " empty traversal. Check that the LDBC database at -Dldbc.db.path is loaded.");
      }
      personIds = new long[ids.size()];
      for (var i = 0; i < ids.size(); i++) {
        personIds[i] = ids.get(i);
      }
      personRids = rids.toArray();

      checkArmInstalled(state);
    }

    /**
     * Builds the witness shape, applies strategies, and throws unless the boundary step's presence
     * matches the arm. Without this an arm that failed to flip reports a difference of zero and
     * reads as a clean null result.
     */
    private void checkArmInstalled(LdbcBenchmarkState state) {
      state.traversal.executeInTx(t -> {
        var witness = GremlinTraversalShapes.knowsFirstNames(t, personIds[0]).asAdmin();
        witness.applyStrategies();
        if (translatorEnabled) {
          GremlinTraversalShapes.requireTranslated("knowsFirstNames", witness);
        } else {
          GremlinTraversalShapes.requireNotTranslated("knowsFirstNames", witness);
        }
      });
    }

    /** Restores the flag so a same-JVM run of anything else is not left on this arm. */
    @TearDown(Level.Trial)
    public void restoreFlag() {
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(flagBeforeTrial);
    }

    long personId(long idx) {
      return personIds[(int) (idx % personIds.length)];
    }

    Object personRid(long idx) {
      return personRids[(int) (idx % personRids.length)];
    }
  }

  /**
   * Shape 1 — {@code g.V(rid)}. The one shape where translator-on can be strictly slower than
   * translator-off: native resolves the id without a query, while a RID-bearing walk sets
   * {@code cacheEligible=false} and so compiles an uncached MATCH plan.
   */
  @Benchmark
  public List<Vertex> gremlinVertexByRid(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.personByRid(t, arm.personRid(i)).toList());
  }

  /** Shape 2 — the {@code KNOWS} walk under {@code values}: one row per friend. */
  @Benchmark
  public List<String> gremlinKnowsFirstNames(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsFirstNames(t, arm.personId(i)).toList());
  }

  /** Shape 3 — the same walk under {@code count()}: the aggregate pushes into the MATCH plan. */
  @Benchmark
  public Long gremlinKnowsFirstNameCount(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsFirstNameCount(t, arm.personId(i)).next());
  }

  /**
   * Shape 4 — the same walk under {@code fold()}: Track 11's own terminator. Until the {@code
   * FoldStep} recogniser lands the shape declines, both arms run natively, and the two numbers
   * coincide.
   */
  @Benchmark
  public List<String> gremlinKnowsFirstNamesFolded(LdbcBenchmarkState state, TranslatorArm arm) {
    var i = state.nextIndex();
    return state.traversal.computeInTx(
        t -> GremlinTraversalShapes.knowsFirstNamesFolded(t, arm.personId(i)).next());
  }
}
