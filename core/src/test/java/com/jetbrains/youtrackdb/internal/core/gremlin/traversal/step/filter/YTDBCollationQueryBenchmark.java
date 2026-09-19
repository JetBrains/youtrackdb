package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.GValue;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Measures end-to-end Gremlin query costs around declared property collation.
 *
 * <p>The timed operands use the same case as stored values. Therefore the same source and expected
 * counts apply before and after YTDB-1297. Native scan scenarios start with a RID, cross an edge,
 * and filter after the hop. This prevents graph-start SQL folding from hiding native container
 * costs. The separate by-ID scenario retains its property predicate inside {@link YTDBGraphStep}.
 * Every invocation creates a fresh traversal and transaction, then drains every result. Transaction
 * startup, the per-session translator switch, traversal construction, and commit are timed equally
 * on both revisions.
 *
 * <p>Trial setup requires {@code -Dytdb.collation.mixedCaseExpectation=current} on YTDB-1297 or
 * {@code baseline} on its pre-change parent. The option defaults to {@code current}. Untimed
 * mixed-case witnesses prove every collated native shape and the translated control before timing.
 *
 * <p>Recommended comparison settings are the annotation defaults. This benchmark requires exactly
 * one worker thread because its benchmark-scoped graph and transaction state is not thread-safe.
 * Keep {@code -t 1} when overriding JMH CLI arguments. A quick smoke run can use {@code -f 1 -wi
 * 1 -i 1 -w 1s -r 1s -t 1}. Add {@code -prof gc} in a separate run to measure allocation. Compare
 * identical source in independent checkouts.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
// Benchmark-scoped graph, transaction, and RID state require exactly one worker thread.
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
    "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED"
})
public class YTDBCollationQueryBenchmark {

  private static final String DB_NAME = "collation-query-benchmark";
  private static final String USER = "admin";
  private static final String PASSWORD = "admin";
  private static final int SCALAR_RECORDS = 2_048;
  private static final int COLLECTION_RECORDS = 512;
  private static final int MEMBERSHIP_VALUES = 256;
  private static final int LIST_VALUES = 64;
  private static final String EXPECTATION_PROPERTY = "ytdb.collation.mixedCaseExpectation";

  private YouTrackDB youTrackDB;
  private YTDBGraphTraversalSource g;
  private Object scalarSourceId;
  private Object collectionSourceId;
  private Object[] scalarIds;
  private List<String> membershipEarly;
  private List<String> membershipMiss;
  private List<Object> parameterizedMembershipEarly;
  private List<Object> parameterizedMembershipMiss;
  private List<String> equalList;
  private List<String> unequalList;
  private int nextScalarId;
  private ExpectedRevision expectedRevision;

  @Setup(Level.Trial)
  public void setUp() {
    expectedRevision = ExpectedRevision.parse(
        System.getProperty(EXPECTATION_PROPERTY, "current"));
    youTrackDB = YourTracks.instance("./target/databases/YTDBCollationQueryBenchmark");
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.MEMORY, USER, PASSWORD, "admin");
    g = youTrackDB.openTraversal(DB_NAME, USER, PASSWORD);

    createSchemaAndData();
    createOperands();
    validateScenarios();
  }

  private void createSchemaAndData() {
    var txSource = begin(false);
    var session = ((YTDBTransaction) g.tx()).getDatabaseSession();
    try {
      session.createVertexClass("BenchmarkSource");
      var scalar = session.createVertexClass("ScalarRecord");
      scalar.createProperty("declaredName", PropertyType.STRING);
      scalar.createProperty("collatedName", PropertyType.STRING).setCollate("ci");
      var collection = session.createVertexClass("CollectionRecord");
      collection.createProperty("memberName", PropertyType.STRING).setCollate("ci");
      collection.createProperty("aliases", PropertyType.EMBEDDEDLIST)
          .setLinkedType(PropertyType.STRING)
          .setCollate("ci");
      session.createEdgeClass("containsScalar");
      session.createEdgeClass("containsCollection");

      var scalarSource = txSource.addV("BenchmarkSource").next();
      scalarSourceId = scalarSource.id();
      scalarIds = new Object[SCALAR_RECORDS];
      for (var index = 0; index < SCALAR_RECORDS; index++) {
        var record = txSource.addV("ScalarRecord")
            .property("declaredName", "match")
            .property("collatedName", "match")
            .property("undeclaredName", "match")
            .next();
        scalarSource.addEdge("containsScalar", record);
        scalarIds[index] = record.id();
      }

      var collectionSource = txSource.addV("BenchmarkSource").next();
      collectionSourceId = collectionSource.id();
      var aliases = values("item-", LIST_VALUES);
      for (var index = 0; index < COLLECTION_RECORDS; index++) {
        var record = txSource.addV("CollectionRecord")
            .property("memberName", "value-0")
            .property("aliases", aliases)
            .next();
        collectionSource.addEdge("containsCollection", record);
      }
      g.tx().commit();
    } catch (RuntimeException | Error failure) {
      if (g.tx().isOpen()) {
        g.tx().rollback();
      }
      throw failure;
    }
  }

  private void createOperands() {
    membershipEarly = values("value-", MEMBERSHIP_VALUES);
    membershipMiss = values("missing-", MEMBERSHIP_VALUES);
    parameterizedMembershipEarly = parameterizedValues(membershipEarly, "earlyTail");
    parameterizedMembershipMiss = parameterizedValues(membershipMiss, "missTail");
    equalList = values("item-", LIST_VALUES);
    unequalList = new ArrayList<>(equalList);
    unequalList.set(0, "different");

    if (!P.within(parameterizedMembershipEarly).isParameterized()
        || !P.within(parameterizedMembershipMiss).isParameterized()) {
      throw new IllegalStateException("GValue membership operands must remain parameterized");
    }
  }

  private static List<String> values(String prefix, int count) {
    var values = new ArrayList<String>(count);
    for (var index = 0; index < count; index++) {
      values.add(prefix + index);
    }
    return List.copyOf(values);
  }

  private static List<Object> parameterizedValues(List<String> values, String variableName) {
    var parameterized = new ArrayList<Object>(values);
    parameterized.set(parameterized.size() - 1,
        GValue.of(variableName, values.getLast()));
    return List.copyOf(parameterized);
  }

  /** Default declared collation delegates to native scalar equality. */
  @Benchmark
  public void defaultScalarFallback(Blackhole blackhole) {
    drain(nativeScalar("declaredName", "match"), blackhole);
  }

  /** An undeclared property delegates to native scalar equality. */
  @Benchmark
  public void undeclaredScalarFallback(Blackhole blackhole) {
    drain(nativeScalar("undeclaredName", "match"), blackhole);
  }

  /** A post-hop native equality evaluates declared case-insensitive collation per candidate. */
  @Benchmark
  public void collatedScalarNativePostHop(Blackhole blackhole) {
    drain(nativeScalar("collatedName", "match"), blackhole);
  }

  /** A rotating RID prices useful point lookup with a collated native equality filter. */
  @Benchmark
  public void collatedScalarNativeById(Blackhole blackhole) {
    var id = scalarIds[nextScalarId++ & (scalarIds.length - 1)];
    drain(run(false, tx -> tx.V(id).has("collatedName", "match").asAdmin()), blackhole);
  }

  /** Cached membership finds the first operand for every candidate. */
  @Benchmark
  public void cachedMembershipEarlyMatch(Blackhole blackhole) {
    drain(nativeMembership(P.within(membershipEarly)), blackhole);
  }

  /** Cached membership scans the complete stable operand for every candidate. */
  @Benchmark
  public void cachedMembershipNoMatch(Blackhole blackhole) {
    drain(nativeMembership(P.within(membershipMiss)), blackhole);
  }

  /** Public GValue parameterization keeps membership transformation lazy on an early match. */
  @Benchmark
  public void parameterizedMembershipEarlyMatch(Blackhole blackhole) {
    drain(nativeMembership(P.within(parameterizedMembershipEarly)), blackhole);
  }

  /** Public GValue parameterization scans the complete operand while bounded caching retains
   * transformations.
   */
  @Benchmark
  public void parameterizedMembershipNoMatch(Blackhole blackhole) {
    drain(nativeMembership(P.within(parameterizedMembershipMiss)), blackhole);
  }

  /** List equality stops after the first unequal member. */
  @Benchmark
  public void listEqualityEarlyMismatch(Blackhole blackhole) {
    drain(nativeListEquality(unequalList), blackhole);
  }

  /** List equality transforms and compares every member when lists are equal. */
  @Benchmark
  public void listEqualityFullEqual(Blackhole blackhole) {
    drain(nativeListEquality(equalList), blackhole);
  }

  /** Leading scalar equality is a translated MATCH control outside the native machinery. */
  @Benchmark
  public void translatedScalarControl(Blackhole blackhole) {
    drain(run(true, tx -> tx.V().hasLabel("ScalarRecord")
        .has("collatedName", "match").asAdmin()), blackhole);
  }

  private Traversal.Admin<?, Vertex> nativeScalar(String property, String operand) {
    return run(false, tx -> tx.V(scalarSourceId).out("containsScalar")
        .has(property, operand).asAdmin());
  }

  private Traversal.Admin<?, Vertex> nativeMembership(P<?> predicate) {
    return run(false, tx -> tx.V(collectionSourceId).out("containsCollection")
        .has("memberName", predicate).asAdmin());
  }

  private Traversal.Admin<?, Vertex> nativeListEquality(List<String> operand) {
    return run(false, tx -> tx.V(collectionSourceId).out("containsCollection")
        .has("aliases", P.eq(operand)).asAdmin());
  }

  private Traversal.Admin<?, Vertex> run(
      boolean translatorEnabled,
      Function<YTDBGraphTraversalSource, Traversal.Admin<?, Vertex>> traversalFactory) {
    var txSource = begin(translatorEnabled);
    try {
      return traversalFactory.apply(txSource);
    } catch (RuntimeException | Error failure) {
      if (g.tx().isOpen()) {
        g.tx().rollback();
      }
      throw failure;
    }
  }

  private YTDBGraphTraversalSource begin(boolean translatorEnabled) {
    g.tx().open();
    var configuration = ((YTDBTransaction) g.tx()).getDatabaseSession().getConfiguration();
    configuration.setValue(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, translatorEnabled);
    return g;
  }

  private void drain(Traversal.Admin<?, ?> traversal, Blackhole blackhole) {
    try {
      while (traversal.hasNext()) {
        blackhole.consume(traversal.next());
      }
      g.tx().commit();
    } catch (RuntimeException | Error failure) {
      if (g.tx().isOpen()) {
        g.tx().rollback();
      }
      throw failure;
    }
  }

  private void validateScenarios() {
    requireCount("defaultScalarFallback", nativeScalar("declaredName", "match"), SCALAR_RECORDS);
    requireCount("undeclaredScalarFallback",
        nativeScalar("undeclaredName", "match"), SCALAR_RECORDS);
    requireCount("collatedScalarNativePostHop",
        nativeScalar("collatedName", "match"), SCALAR_RECORDS);
    requireCount("collatedScalarNativeById",
        run(false, tx -> tx.V(scalarIds[0]).has("collatedName", "match").asAdmin()), 1);
    requireCount("cachedMembershipEarlyMatch",
        nativeMembership(P.within(membershipEarly)), COLLECTION_RECORDS);
    requireCount("cachedMembershipNoMatch", nativeMembership(P.within(membershipMiss)), 0);
    requireCount("parameterizedMembershipEarlyMatch",
        nativeMembership(P.within(parameterizedMembershipEarly)), COLLECTION_RECORDS);
    requireCount("parameterizedMembershipNoMatch",
        nativeMembership(P.within(parameterizedMembershipMiss)), 0);
    requireCount("listEqualityEarlyMismatch", nativeListEquality(unequalList), 0);
    requireCount("listEqualityFullEqual", nativeListEquality(equalList), COLLECTION_RECORDS);

    requireTranslated(
        "translatedScalarControl",
        run(true, tx -> tx.V().hasLabel("ScalarRecord")
            .has("collatedName", "match").asAdmin()),
        SCALAR_RECORDS);
    validateCollationWitnesses();
  }

  private void validateCollationWitnesses() {
    var nativeExpected = expectedRevision == ExpectedRevision.CURRENT ? COLLECTION_RECORDS : 0;
    var scalarExpected = expectedRevision == ExpectedRevision.CURRENT ? SCALAR_RECORDS : 0;
    var byIdExpected = expectedRevision == ExpectedRevision.CURRENT ? 1 : 0;
    var mixedMembership = withUppercaseFirst(membershipEarly);
    var parameterizedMixedMembership =
        parameterizedValues(mixedMembership, "mixedMembershipTail");
    if (!P.within(parameterizedMixedMembership).isParameterized()) {
      throw new IllegalStateException("Mixed membership witness must remain parameterized");
    }

    requireNativePostHop(
        "collated scalar mixed-case witness",
        nativeScalar("collatedName", "MATCH"),
        scalarExpected);
    requireNativePostHop(
        "cached membership mixed-case witness",
        nativeMembership(P.within(mixedMembership)),
        nativeExpected);
    requireNativePostHop(
        "parameterized membership mixed-case witness",
        nativeMembership(P.within(parameterizedMixedMembership)),
        nativeExpected);
    requireNativePostHop(
        "list equality mixed-case witness",
        nativeListEquality(equalList.stream()
            .map(value -> value.toUpperCase(Locale.ROOT)).toList()),
        nativeExpected);
    requireNativeById("MATCH", byIdExpected, "by-ID mixed-case witness");
    requireNativeById("definitely-not-a-match", 0, "by-ID negative predicate witness");
    requireTranslated(
        "translated scalar mixed-case witness",
        run(true, tx -> tx.V().hasLabel("ScalarRecord")
            .has("collatedName", "MATCH").asAdmin()),
        SCALAR_RECORDS);
  }

  private static List<String> withUppercaseFirst(List<String> values) {
    var mixed = new ArrayList<>(values);
    mixed.set(0, mixed.getFirst().toUpperCase(Locale.ROOT));
    return List.copyOf(mixed);
  }

  private void requireNativePostHop(
      String scenario, Traversal.Admin<?, Vertex> traversal, int expected) {
    traversal.applyStrategies();
    if (traversal.getSteps().stream().anyMatch(AbstractMatchPlanStep.class::isInstance)) {
      rollbackAndFail(scenario + " unexpectedly installed a MATCH boundary");
    }
    if (traversal.getSteps().stream().noneMatch(HasStep.class::isInstance)) {
      rollbackAndFail(scenario + " did not retain its property filter step");
    }
    requireCount(scenario, traversal, expected);
  }

  private void requireNativeById(String operand, int expected, String scenario) {
    var traversal = run(false, tx -> tx.V(scalarIds[0])
        .has("collatedName", operand).asAdmin());
    traversal.applyStrategies();
    if (!(traversal.getStartStep() instanceof YTDBGraphStep<?, ?>)) {
      rollbackAndFail(scenario + " did not retain its native graph step");
    }
    var graphStep = (YTDBGraphStep<?, ?>) traversal.getStartStep();
    if (traversal.getSteps().stream().anyMatch(AbstractMatchPlanStep.class::isInstance)) {
      rollbackAndFail(scenario + " unexpectedly installed a MATCH boundary");
    }
    var retainedPredicate = graphStep.getHasContainers().stream()
        .anyMatch(container -> "collatedName".equals(container.getKey())
            && operand.equals(container.getPredicate().getValue()));
    if (!retainedPredicate) {
      rollbackAndFail(scenario + " did not retain its property predicate");
    }
    requireCount(scenario, traversal, expected);
  }

  private void requireTranslated(
      String scenario, Traversal.Admin<?, Vertex> traversal, int expected) {
    traversal.applyStrategies();
    if (traversal.getSteps().stream().noneMatch(AbstractMatchPlanStep.class::isInstance)) {
      rollbackAndFail(scenario + " did not install a MATCH boundary");
    }
    requireCount(scenario, traversal, expected);
  }

  private void requireCount(String scenario, Traversal.Admin<?, ?> traversal, int expected) {
    var actual = 0;
    try {
      while (traversal.hasNext()) {
        traversal.next();
        actual++;
      }
      g.tx().commit();
    } catch (RuntimeException | Error failure) {
      if (g.tx().isOpen()) {
        g.tx().rollback();
      }
      throw failure;
    }
    if (actual != expected) {
      throw new IllegalStateException(
          scenario + " returned " + actual + " results, expected " + expected);
    }
  }

  private void rollbackAndFail(String message) {
    if (g.tx().isOpen()) {
      g.tx().rollback();
    }
    throw new IllegalStateException(message);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (g != null) {
      if (g.tx().isOpen()) {
        g.tx().rollback();
      }
      g.close();
    }
    if (youTrackDB != null) {
      if (youTrackDB.exists(DB_NAME)) {
        youTrackDB.drop(DB_NAME);
      }
      youTrackDB.close();
    }
  }

  public static void main(String[] args) throws Exception {
    var options = new OptionsBuilder()
        .parent(new CommandLineOptions(args))
        .include(YTDBCollationQueryBenchmark.class.getSimpleName())
        .shouldFailOnError(true)
        .build();
    new Runner(options).run();
  }

  private enum ExpectedRevision {
    CURRENT, BASELINE;

    private static ExpectedRevision parse(String value) {
      return switch (value) {
        case "current" -> CURRENT;
        case "baseline" -> BASELINE;
        default -> throw new IllegalArgumentException(
            EXPECTATION_PROPERTY + " must be current or baseline, but was: " + value);
      };
    }
  }
}
