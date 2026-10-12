package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.KnownEndpointBenchmarkProbe.Work;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Opt-in JMH and deterministic work experiments for known-endpoint access paths.
 * No JUnit test methods or Surefire test-name suffixes occur in this class.
 * Run main with --smoke for a short, validated CSV experiment. See the adjacent README.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class KnownEndpointAccessBenchmark extends DbTestBase {

  @Param({"source-small", "source-large", "degree-low", "degree-high", "schema-narrow",
      "schema-wide", "index-selective", "index-broad", "middle-low", "middle-high",
      "type-all", "type-rare", "rids-short", "rids-long", "checks-one", "checks-many",
      "limit-early", "limit-late", "caller-one", "stale-middle-adjacency", "uneven-middle",
      "budget-uneven-middle", "edge-filter", "budget-edge-filter", "stale-low",
      "stale-high", "budget-middle", "budget-list", "native-sparse", "native-hub",
      "native-tx-small", "native-tx-large", "native-tombstones"})
  public String scenario;

  @Param({"AUTO", "CURRENT", "ALTERNATIVE"})
  public String selector;

  /** MATCH retains the direct planner experiment. ON and OFF execute real Gremlin traversals. */
  @Param({"MATCH", "ON", "OFF"})
  public String translation;

  private YTDBGraphInternal graph;
  private Traversal.Admin<?, ?> gremlinTemplate;
  private Traversal.Admin<?, ?> gremlinInvocation;
  private Spec spec;
  private final List<RecordIdInternal> sources = new ArrayList<>();
  private final List<RecordIdInternal> targets = new ArrayList<>();
  private final List<Integer> expected = new ArrayList<>();
  private final Set<Integer> sourceCollections = new HashSet<>();
  private final Set<Integer> edgeCollections = new HashSet<>();
  private final List<RecordIdInternal> middles = new ArrayList<>();
  private RecordIdInternal middle;
  private String positive;
  private List<String> checks;
  private Map<String, Object> parameters;
  private SelectExecutionPlan template;
  private Method preparation;
  private long actualDegree;
  private long actualMiddleDegree;
  private long transactionEntries;
  private long middleEdgeRecords;
  private double middleAverage;

  private record Spec(int count, int degree, int breadth, int indexShare, int middleDegree,
      int noise, int ridCount, int extraChecks, boolean late, boolean limit, boolean one,
      double staleShare, int skew, boolean nativePattern, int transactionSize, int tombstones,
      int middleCount, boolean edgeFilter) {

    static Spec of(String name) {
      return new Spec(name.equals("source-large") ? 4096 : 512,
          name.equals("degree-high") ? 64 : name.equals("edge-filter") ? 4
              : name.equals("type-all") ? 34
                  : name.equals("native-hub")
                      || name.startsWith("native-tx") || name.equals("native-tombstones") ? 128 : 2,
          name.equals("schema-wide") ? 16 : 1,
          name.equals("index-selective") ? 1 : name.equals("index-broad") ? 128 : 0,
          name.equals("middle-high") ? 64 : name.equals("middle-low")
              || name.contains("middle") || name.equals("budget-edge-filter") ? 2 : 0,
          name.equals("type-rare") ? 32 : 0,
          name.equals("rids-short") ? 9 : name.equals("rids-long") ? 128
              : name.equals("budget-list") ? 100000 : 1,
          name.equals("checks-many") ? 3 : 0,
          name.equals("limit-late"), name.startsWith("limit-"), name.equals("caller-one"),
          name.equals("stale-low") ? 0.25 : name.equals("stale-high") ? 4 : 1,
          name.equals("stale-middle-adjacency") ? 128
              : name.equals("budget-middle") ? 10000 : 0,
          name.startsWith("native-"), name.equals("native-tx-small") ? 1
              : name.equals("native-tx-large") ? 4096 : 0,
          name.equals("native-tombstones") ? 512 : 0,
          name.equals("uneven-middle") ? 65 : name.equals("budget-uneven-middle")
              || name.equals("budget-edge-filter") ? 520 : 1,
          name.contains("edge-filter"));
    }
  }

  /** Each trial changes one named dimension and checks its actual database witness. */
  @Setup(Level.Trial)
  public void setup() throws Exception {
    spec = Spec.of(scenario);
    KnownEndpointBenchmarkProbe.install();
    sources.clear();
    targets.clear();
    expected.clear();
    sourceCollections.clear();
    edgeCollections.clear();
    middles.clear();
    actualMiddleDegree = 0;
    middleEdgeRecords = 0;
    middleAverage = 0;
    youTrackDB = createContext();
    databaseName = "knownEndpointAccessBenchmark";
    dbType = calculateDbType();
    createDatabase(dbType);
    var source = session.createVertexClass("BenchSource");
    source.createProperty("n", PropertyType.INTEGER);
    source.createProperty("bucket", PropertyType.INTEGER);
    session.createVertexClass("BenchTarget");
    session.createVertexClass("BenchMiddle");
    var noise = session.createVertexClass("BenchNoise");
    session.createEdgeClass("BenchLink");
    session.createEdgeClass("BenchTail");
    session.createEdgeClass("BenchExtra");
    // Disjoint linked endpoints make native forward-edge bounds exclude hub noise.
    session.execute("CREATE CLASS BenchNative EXTENDS E ABSTRACT").close();
    session.execute("CREATE CLASS BenchNativeSource EXTENDS BenchNative").close();
    session.execute("CREATE CLASS BenchNativeNoise EXTENDS BenchNative").close();
    session.getMetadata().getSchema().getClass("BenchNativeSource")
        .createProperty("out", PropertyType.LINK, source);
    session.getMetadata().getSchema().getClass("BenchNativeNoise")
        .createProperty("out", PropertyType.LINK, noise);
    for (int i = 1; i < spec.breadth; i++) {
      session.execute("CREATE CLASS BenchSourceSub" + i + " EXTENDS BenchSource").close();
      session.execute("CREATE CLASS BenchLinkSub" + i + " EXTENDS BenchLink").close();
    }
    session.begin();
    var created = new ArrayList<Vertex>();
    int count = spec.nativePattern ? 4 : spec.count;
    for (int i = 0; i < count; i++) {
      created.add(session.newVertex(i % spec.breadth == 0 ? "BenchSource"
          : "BenchSourceSub" + (i % spec.breadth)));
    }
    // RID-list length includes empty but visible targets, so loading cost really changes.
    int distinctTargets = spec.ridCount >= 100000 ? 1 : spec.ridCount;
    var endpointVertices = new ArrayList<Vertex>();
    for (int i = 0; i < distinctTargets; i++) {
      endpointVertices.add(session.newVertex("BenchTarget"));
    }
    var middleVertices = new ArrayList<Vertex>();
    for (int i = 0; i < spec.middleCount; i++) {
      var mid = session.newVertex("BenchMiddle");
      if (spec.edgeFilter) {
        mid.setProperty("ok", true);
      }
      middleVertices.add(mid);
    }
    session.commit();
    created.forEach(v -> sources.add((RecordIdInternal) v.getIdentity()));
    endpointVertices.forEach(v -> targets.add((RecordIdInternal) v.getIdentity()));
    middleVertices.forEach(v -> middles.add((RecordIdInternal) v.getIdentity()));
    middle = middles.getFirst();
    sources.sort(RecordIdInternal::compareTo);
    session.begin();
    for (int i = 0; i < count; i++) {
      var vertex = session.loadVertex(sources.get(i));
      vertex.setProperty("n", i);
      vertex.setProperty("bucket", i < spec.indexShare ? 1 : 0);
    }
    var endpoint = session.loadVertex(targets.getFirst());
    int degree = spec.nativePattern ? 2 : spec.degree;
    int matches = spec.middleDegree > 0 ? spec.middleDegree : degree;
    for (int i = 0; i < matches; i++) {
      int position = spec.late ? count - matches + i : i;
      var vertex = session.loadVertex(sources.get(position));
      // Uneven-degree fixtures concentrate real parallel edges on one middle and leave others
      // empty. Their schema average stays two without any reverse-only bag mutation.
      int links = spec.middleCount > 1 ? spec.middleCount : 1;
      boolean accepts = !spec.edgeFilter || spec.middleDegree > 0 || i % 2 == 0;
      for (int link = 0; link < links; link++) {
        var edge = vertex.addEdge(spec.middleDegree > 0 ? session.loadVertex(middle) : endpoint,
            spec.nativePattern ? "BenchNativeSource" : "BenchLink");
        if (spec.edgeFilter || spec.middleCount > 1) {
          edge.setProperty("weight", accepts ? 1 : 0);
        }
      }
      vertex.addEdge(endpoint, "BenchExtra");
      if (accepts && (spec.indexShare == 0 || position < spec.indexShare)) {
        expected.add(position);
      }
    }
    if (spec.middleDegree > 0) {
      session.loadVertex(middle).addEdge(endpoint, "BenchTail");
    }
    for (int i = 0; i < spec.noise; i++) {
      session.newVertex("BenchNoise").addEdge(endpoint, "BenchLink");
    }
    if (spec.nativePattern) {
      for (int i = 2; i < spec.degree; i++) {
        session.newVertex("BenchNoise").addEdge(endpoint, "BenchNativeNoise");
      }
    }
    var removed = new ArrayList<Vertex>();
    for (int i = 0; i < spec.tombstones; i++) {
      removed.add(session.newVertex("BenchSource"));
    }
    session.commit();
    session.begin();
    removed.forEach(v -> session.loadVertex(v.getIdentity()).delete());
    session.commit();
    if (spec.indexShare > 0) {
      session.execute("CREATE INDEX BenchSource.bucket ON BenchSource(bucket) NOTUNIQUE").close();
    }
    session.begin();
    actualDegree = java.util.stream.StreamSupport.stream(session.loadVertex(targets.getFirst())
        .getEdges(Direction.IN, spec.nativePattern ? "BenchNative" : spec.middleDegree > 0
            ? "BenchTail" : "BenchLink")
        .spliterator(), false).count();
    assertThat(actualDegree).isEqualTo(spec.nativePattern ? spec.degree
        : spec.middleDegree > 0 ? 1 : spec.degree + spec.noise);
    // Only stale-adjacency fixtures add reverse-only duplicates of a real edge/source pair.
    // Those duplicates leave both the forward oracle and the real edge count unchanged.
    if (spec.middleDegree > 0) {
      var bag = (LinkBag) ((EntityImpl) session.loadVertex(middle))
          .getPropertyInternal("in_BenchLink");
      var edge = session.loadVertex(sources.getFirst()).getEdges(Direction.OUT, "BenchLink")
          .iterator().next();
      for (int i = 0; i < spec.skew; i++) {
        bag.add(edge.getIdentity(), sources.getFirst());
      }
      actualMiddleDegree = bag.size();
      assertThat(actualMiddleDegree).isEqualTo(spec.middleDegree * spec.middleCount + spec.skew);
      middleEdgeRecords = session.countClass("BenchLink");
      middleAverage = EdgeFanOutEstimator.estimateFanOut(session, "BenchLink", "BenchMiddle",
          Direction.IN, null, null);
      assertThat(session.countClass("BenchMiddle")).isEqualTo(spec.middleCount);
      if (spec.middleCount > 1) {
        var distribution = new ArrayList<Long>();
        for (var rid : middles) {
          distribution.add(java.util.stream.StreamSupport.stream(session.loadVertex(rid)
              .getEdges(Direction.IN, "BenchLink").spliterator(), false).count());
        }
        assertThat(distribution.getFirst()).isEqualTo(actualMiddleDegree);
        assertThat(distribution.subList(1, distribution.size())).containsOnly(0L);
        assertThat(distribution.stream().mapToLong(Long::longValue).sum())
            .isEqualTo(middleEdgeRecords).isEqualTo(2L * spec.middleCount);
        long forwardEdges = sources.stream().mapToLong(rid -> java.util.stream.StreamSupport
            .stream(session.loadVertex(rid).getEdges(Direction.OUT, "BenchLink").spliterator(),
                false)
            .count()).sum();
        assertThat(forwardEdges).isEqualTo(middleEdgeRecords);
        assertThat(middleAverage).isEqualTo(2).isLessThan(actualMiddleDegree);
      }
    }
    for (int i = 0; i < spec.transactionSize; i++) {
      session.newVertex("BenchNoise").setProperty("pending", i);
    }
    transactionEntries = session.getTransactionInternal().getEntryCount();
    assertThat(transactionEntries).isGreaterThanOrEqualTo(spec.transactionSize);
    if (spec.transactionSize == 0 && spec.skew == 0) {
      assertThat(transactionEntries).isZero();
    }
    assertThat(session.countClass("BenchSource")).isEqualTo(count);
    assertThat(session.getMetadata().getSchema().getClass("BenchSource").getAllSubclasses())
        .hasSize(spec.breadth - 1);
    assertThat(session.countClass("BenchNoise")).isEqualTo(spec.noise
        + (spec.nativePattern ? Math.max(0, spec.degree - 2) : 0) + spec.transactionSize);
    for (int id : source.getPolymorphicCollectionIds()) {
      sourceCollections.add(id);
    }
    for (int id : session.getMetadata().getSchema().getClass("BenchLink")
        .getPolymorphicCollectionIds()) {
      edgeCollections.add(id);
    }
    positive = "MATCH {class:BenchSource,as:s"
        + (spec.indexShare > 0 ? ",where:(bucket=1)" : "") + "} RETURN s.n as n"
        + (spec.limit ? " LIMIT 1" : "");
    var restriction = spec.ridCount == 1 ? "@rid=:target" : "@rid IN :targets";
    String link = spec.edgeFilter
        ? ".outE('BenchLink'){class:BenchLink,as:e,where:(weight=1)}.inV()"
        : ".out('BenchLink')";
    String walk = spec.middleDegree > 0
        ? link + "{class:BenchMiddle,as:m" + (spec.edgeFilter ? ",where:(ok=true)" : "")
            + "}.out('BenchTail')"
        : link;
    checks = new ArrayList<>();
    checks.add("MATCH {as:s}" + walk + "{as:t,where:(" + restriction + ")} RETURN s");
    for (int i = 0; i < spec.extraChecks; i++) {
      checks.add("MATCH {as:s}.out('BenchExtra'){as:x" + i + ",where:(@rid=:target)} RETURN s");
    }
    parameters = Map.of("target", targets.getFirst(), "targets", spec.ridCount >= 100000
        ? Collections.nCopies(spec.ridCount, targets.getFirst()) : List.copyOf(targets));
    assertThat(((List<?>) parameters.get("targets"))).hasSize(spec.ridCount);
    assertThat(checks).hasSize(1 + spec.extraChecks);
    if (spec.nativePattern) {
      positive = "MATCH {class:BenchSource,as:s}.out('BenchNative')"
          + "{class:BenchTarget,as:t,where:(@rid=" + targets.getFirst() + ")} RETURN s.n as n";
    }
    template = build(session);
    if (spec.indexShare > 0) {
      assertThat(template.getSteps()).noneMatch(KnownEndpointExistsStep.class::isInstance);
      assertThat(template.prettyPrint(0, 2)).contains("INDEX");
      try (var rows = session.query("SELECT FROM BenchSource WHERE bucket=1")) {
        assertThat(rows.stream().count()).isEqualTo(spec.indexShare);
      }
    } else {
      assertThat(template.getSteps().getFirst()).as(template.prettyPrint(0, 2))
          .isInstanceOf(spec.nativePattern
              ? NativeKnownEndpointStep.class : KnownEndpointExistsStep.class);
      preparation = (spec.nativePattern ? NativeKnownEndpointStep.class
          : KnownEndpointExistsStep.class).getDeclaredMethod(
              spec.nativePattern ? "attemptSource" : "attemptTarget",
              com.jetbrains.youtrackdb.internal.core.command.CommandContext.class,
              spec.nativePattern ? NativeKnownEndpointStep.Path.class
                  : KnownEndpointExistsStep.Path.class);
      // Private preparation isolates decision work from retained-branch startup. Reflection stays
      // test-only and runs outside JMH execution/first-row measurements.
      preparation.setAccessible(true);
    }
    validate();
    if (!translation.equals("MATCH")) {
      setupGremlin();
    }
  }

  /** Build an uncached MATCH plan or construct and compile Gremlin, without query execution. */
  @Benchmark
  public Object planning() {
    if (gremlinTemplate != null) {
      try (var traversal = gremlin()) {
        traversal.applyStrategies();
        return traversal.getSteps().getFirst().getClass();
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }
    var plan = build(session);
    try {
      return plan.getSteps().getFirst().getClass();
    } finally {
      plan.close();
    }
  }

  /** Copy the MATCH plan or clone compiled Gremlin outside execution and first-row timing. */
  @Setup(Level.Invocation)
  public void copyForInvocation() {
    if (gremlinTemplate != null) {
      gremlinInvocation = gremlinTemplate.clone();
    } else {
      invocation = (SelectExecutionPlan) template.copy(context(executionSession()));
    }
  }

  private SelectExecutionPlan invocation;

  /** Complete query consumption, without parsing, planning, copying, assertions or mocks. */
  @Benchmark
  public Object execution() throws Exception {
    try (var scoped = probe(null)) {
      return gremlinTemplate != null
          ? consumeGremlin(gremlinInvocation, selector, false, new Work()).rows
          : consume(invocation, selector, false).rows;
    }
  }

  /** A lazy caller requests exactly one row without adding a SQL LIMIT. */
  @Benchmark
  public Object firstRow() throws Exception {
    try (var scoped = probe(null)) {
      return gremlinTemplate != null
          ? consumeGremlin(gremlinInvocation, selector, true, new Work()).rows
          : consume(invocation, selector, true).rows;
    }
  }

  /** Guarded MATCH preparation only. CURRENT and translator OFF return without preparation. */
  @Benchmark
  public Object decision() throws Exception {
    if (preparation == null || selector.equals("CURRENT") || translation.equals("OFF")) {
      return false;
    }
    var plan = gremlinTemplate != null ? gremlinPlan(gremlinInvocation) : invocation;
    var choice = choice(plan);
    if (choice == null) {
      return false;
    }
    try (var scoped = probe(null)) {
      var branch = (SelectExecutionPlan) preparation.invoke(choice,
          plan.getContext(), selected(selector));
      if (branch != null) {
        branch.close();
      }
      return branch != null;
    }
  }

  @TearDown(Level.Invocation)
  public void closeInvocation() throws Exception {
    if (gremlinInvocation != null) {
      gremlinInvocation.close();
      gremlinInvocation = null;
    }
    if (invocation != null) {
      invocation.close();
      invocation = null;
    }
  }

  @TearDown(Level.Trial)
  public void teardown() throws Exception {
    if (gremlinTemplate != null) {
      gremlinTemplate.close();
      gremlinTemplate = null;
    }
    if (graph != null) {
      graph.tx().rollback();
      graph.close();
      graph = null;
    }
    if (template != null) {
      template.close();
      template = null;
    }
    afterTest();
  }

  private DatabaseSessionEmbedded executionSession() {
    return session;
  }

  private AutoCloseable probe(Work work) {
    return KnownEndpointBenchmarkProbe.scope(work, sourceCollections, edgeCollections,
        spec.staleShare == 1 ? -1 : (long) (spec.count * spec.staleShare));
  }

  private BasicCommandContext context(DatabaseSessionEmbedded db) {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(db);
    ctx.setInputParameters(new HashMap<>(parameters));
    return ctx;
  }

  private SelectExecutionPlan build(DatabaseSessionEmbedded db) {
    var statement = parse(positive);
    if (spec.nativePattern) {
      return (SelectExecutionPlan) statement.createExecutionPlanNoCache(context(db), false);
    }
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var classes = new HashMap<String, String>();
    var filters = new HashMap<String, SQLWhereClause>();
    for (var expression : statement.getMatchExpressions()) {
      var nodes = new ArrayList<SQLMatchFilter>();
      nodes.add(expression.getOrigin());
      expression.getItems().forEach(i -> nodes.add(i.getFilter()));
      for (var node : nodes) {
        if (node.getClassName(context(db)) != null) {
          classes.put(node.getAlias(), node.getClassName(context(db)));
        }
        if (node.getFilter() != null) {
          filters.put(node.getAlias(), node.getFilter());
        }
      }
    }
    var inputs = MatchPlanInputs.builder(pattern).aliasClasses(classes).aliasFilters(filters)
        .existsMatchExpressions(checks.stream().map(KnownEndpointAccessBenchmark::parse)
            .map(s -> s.getMatchExpressions().getFirst()).toList())
        .returnItems(statement.getReturnItems()).returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections())
        .limit(statement.getLimit()).build();
    return (SelectExecutionPlan) new MatchExecutionPlanner(inputs)
        .createExecutionPlan(context(db), false, false);
  }

  private static SQLMatchStatement parse(String sql) {
    try {
      return (SQLMatchStatement) new YouTrackDBSql(new ByteArrayInputStream(
          sql.getBytes(StandardCharsets.UTF_8))).parse();
    } catch (Exception e) {
      throw new AssertionError(sql, e);
    }
  }

  private Object selected(String selection) {
    return spec.nativePattern ? switch (selection) {
      case "CURRENT" -> NativeKnownEndpointStep.Path.RID;
      case "ALTERNATIVE" -> NativeKnownEndpointStep.Path.SOURCE;
      default -> NativeKnownEndpointStep.Path.AUTO;
    } : switch (selection) {
      case "CURRENT" -> KnownEndpointExistsStep.Path.SOURCE;
      case "ALTERNATIVE" -> KnownEndpointExistsStep.Path.TARGET;
      default -> KnownEndpointExistsStep.Path.AUTO;
    };
  }

  private AutoCloseable force(String selection) {
    return spec.nativePattern ? NativeKnownEndpointStep.forcePath(
        (NativeKnownEndpointStep.Path) selected(selection))
        : KnownEndpointExistsStep.forcePath(
            (KnownEndpointExistsStep.Path) selected(selection));
  }

  private record Run(List<Integer> rows, String path, String reason, long sources, long candidates,
      long edges, long targets, double discovery, double budget, boolean switched,
      long recordLoads, long sourceLoads, long countPositions, long preflight,
      long adjacencyEntries, long rejectedPositions, long edgeLoads, long transactionVisits,
      long executionNanos, long firstNanos) {
    double observedWork() {
      // This diagnostic sum combines discovery charges, reads and adjacency visits. Some
      // discovery actions overlap the read counters. Assert the design budget only on discovery,
      // and report each physical counter separately so the sum cannot hide that overlap.
      return discovery + recordLoads + countPositions + adjacencyEntries + transactionVisits;
    }
  }

  private Run consume(SelectExecutionPlan plan, String selection, boolean one) throws Exception {
    return consume(plan, selection, one, new Work());
  }

  private Run consume(SelectExecutionPlan plan, String selection, boolean one, Work work)
      throws Exception {
    var rows = new ArrayList<Integer>();
    ExecutionStream stream = null;
    long elapsed;
    long firstRowNanos = -1;
    try (var forced = force(selection)) {
      long start = System.nanoTime();
      stream = plan.start();
      while (stream.hasNext(plan.getContext())) {
        rows.add(stream.next(plan.getContext()).getProperty("n"));
        if (firstRowNanos < 0) {
          firstRowNanos = System.nanoTime() - start;
        }
        if (one) {
          break;
        }
      }
      elapsed = System.nanoTime() - start;
    } finally {
      if (stream != null) {
        stream.close(plan.getContext());
      }
      plan.close();
    }
    var first = plan.getSteps().getFirst();
    if (first instanceof KnownEndpointExistsStep choice) {
      var c = choice.counters();
      return new Run(rows, c.path, c.reason, c.sourceRecordsRead, c.candidates, c.edgeReads,
          c.targetLoads, c.discoveryWork, c.workBudget, c.budgetSwitch, work.loads,
          work.sourceLoads, work.positions, work.preflight, work.edgeEntries,
          work.rejectedPositions, work.edgeLoads, work.transactionVisits, elapsed, firstRowNanos);
    }
    return new Run(rows, first instanceof NativeKnownEndpointStep choice ? choice.path() : "index",
        first instanceof NativeKnownEndpointStep choice ? choice.reason() : "ineligible",
        work.sourceLoads, 0, 0, 0, 0, 0, false, work.loads, work.sourceLoads, work.positions,
        work.preflight, work.edgeEntries, work.rejectedPositions, work.edgeLoads,
        work.transactionVisits, elapsed, firstRowNanos);
  }

  private Run measuredWork(String selection, boolean one) throws Exception {
    var work = new Work();
    var plan = (SelectExecutionPlan) template.copy(context(session));
    try (var scoped = probe(work)) {
      return consume(plan, selection, one, work);
    }
  }

  private record Prepared(Work work, KnownEndpointExistsStep.Counters counters, boolean ready) {
  }

  /** Observe reverse edge loads separately from the retained forward query's edge loads. */
  private Prepared measuredPreparation(String selection) throws Exception {
    var work = new Work();
    var plan = (SelectExecutionPlan) template.copy(context(session));
    try (var scoped = probe(work)) {
      var branch = (SelectExecutionPlan) preparation.invoke(plan.getSteps().getFirst(),
          plan.getContext(), selected(selection));
      if (branch != null) {
        branch.close();
      }
      return new Prepared(work, ((KnownEndpointExistsStep) plan.getSteps().getFirst()).counters(),
          branch != null);
    } finally {
      plan.close();
    }
  }

  /** Both forced paths must run, unless an asserted eligibility/security/budget gate forbids one. */
  private void validate() throws Exception {
    if (spec.staleShare != 1) {
      long actual = session.approximateCountClass("BenchSource", true);
      try (var scoped = probe(null)) {
        assertThat(session.approximateCountClass("BenchSource", true))
            .isEqualTo((long) (spec.count * spec.staleShare)).isNotEqualTo(actual);
      }
    }
    var wanted = new ArrayList<>(expected);
    if (spec.limit) {
      wanted = new ArrayList<>(wanted.subList(0, 1));
    }
    var current = measuredWork("CURRENT", false);
    var alternative = measuredWork("ALTERNATIVE", false);
    var automatic = measuredWork("AUTO", false);
    for (var run : List.of(current, alternative, automatic)) {
      if (spec.nativePattern) {
        assertThat(run.rows).containsExactlyInAnyOrderElementsOf(wanted);
      } else {
        assertThat(run.rows).containsExactlyElementsOf(wanted);
      }
      assertThat(run.recordLoads).isPositive();
      assertThat(run.adjacencyEntries).isPositive();
    }
    if (spec.indexShare > 0) {
      assertThat(current.path).isEqualTo("index");
      assertThat(alternative.path).isEqualTo("index");
      assertThat(automatic.path).isEqualTo("index");
      // The load hook also counts source reloads made by the retained forward EXISTS probe.
      assertThat(current.sourceLoads).isBetween((long) spec.indexShare, 3L * spec.indexShare);
    } else if (spec.nativePattern) {
      assertThat(current.path).isEqualTo("rid");
      assertThat(current.countPositions).isZero();
      assertThat(current.preflight).isZero();
      assertThat(current.adjacencyEntries).isEqualTo(actualDegree);
      if (spec.transactionSize > 0) {
        // A positive witness proves that the hook counts every operation in a class-count scan.
        var witness = new Work();
        try (var scoped = probe(witness)) {
          KnownEndpointBenchmarkProbe.nativeDecision(true);
          try {
            session.approximateCountClass("BenchSource");
          } finally {
            KnownEndpointBenchmarkProbe.nativeDecision(false);
          }
        }
        assertThat(witness.transactionVisits).isEqualTo(transactionEntries);
        for (var run : List.of(current, automatic, alternative)) {
          assertThat(run.transactionVisits).as("native decision transaction-operation visits")
              .isZero();
        }
        for (var run : List.of(automatic, alternative)) {
          assertThat(run.path).isEqualTo("rid");
          assertThat(run.reason).isEqualTo("pending transaction writes");
          assertThat(run.countPositions).isZero();
          assertThat(run.preflight).isZero();
          assertThat(run.recordLoads).isEqualTo(current.recordLoads);
          assertThat(run.adjacencyEntries).isEqualTo(current.adjacencyEntries);
        }
      } else {
        assertThat(alternative.path).isEqualTo("source");
        assertThat(alternative.preflight).isEqualTo(4);
        assertThat(alternative.countPositions).isGreaterThanOrEqualTo(6);
        if (spec.tombstones > 0) {
          assertThat(automatic.path).isEqualTo("rid");
          assertThat(automatic.reason).isEqualTo("exact count work budget");
          assertThat(automatic.countPositions)
              .isLessThanOrEqualTo(
                  (long) (actualDegree / KnownEndpointExistsCost.SAFETY_MARGIN));
          assertThat(automatic.rejectedPositions).isPositive();
        } else {
          assertThat(automatic.path).isEqualTo(spec.degree > 2 ? "source" : "rid");
        }
      }
    } else {
      assertThat(current.path).isEqualTo("source");
      assertThat(current.sources).isEqualTo(spec.limit
          ? spec.late ? spec.count - spec.degree + 1 : 1 : spec.count);
      assertThat(automatic.path).isEqualTo(scenario.startsWith("budget-")
          || Set.of("degree-high", "middle-high", "type-all", "type-rare", "rids-long")
              .contains(scenario)
                  ? "source" : "target");
      if (scenario.startsWith("budget-")) {
        for (var run : List.of(alternative, automatic)) {
          assertThat(run.path).isEqualTo("source");
          assertThat(run.switched).isTrue();
          assertThat(run.discovery).isPositive().isLessThanOrEqualTo(run.budget);
          assertThat(run.candidates).isZero();
          assertThat(run.sources).isEqualTo(current.sources);
          assertThat(run.reason).isEqualTo("work budget exceeded");
          if (spec.middleCount > 1) {
            assertThat(run.edges).isPositive().isLessThan(actualMiddleDegree + 1);
          }
          // The charge is an estimate-based bound. It is not an actual-work bound under LIMIT.
          assertThat(run.discovery - current.discovery).isLessThanOrEqualTo(run.budget);
        }
      } else {
        assertThat(alternative.path).isEqualTo("target");
        assertThat(alternative.switched).isFalse();
        assertThat(alternative.edges).isEqualTo(spec.middleDegree > 0
            ? 1 + actualMiddleDegree : actualDegree);
        assertThat(alternative.adjacencyEntries).isGreaterThanOrEqualTo(alternative.edges);
        assertThat(alternative.candidates).isEqualTo(spec.limit ? 1 : expected.size());
        assertThat(alternative.sources).isEqualTo(alternative.candidates);
        assertThat(alternative.targets).isEqualTo(targets.size());
        if (spec.extraChecks > 0) {
          var saved = checks;
          Run singleCheck;
          try {
            checks = saved.subList(0, 1);
            var work = new Work();
            var baseline = build(session);
            try (var scoped = probe(work)) {
              singleCheck = consume(baseline, "ALTERNATIVE", false, work);
            }
          } finally {
            checks = saved;
          }
          assertThat(singleCheck.path).isEqualTo("target");
          assertThat(singleCheck.rows).isEqualTo(alternative.rows);
          assertThat(alternative.recordLoads).isGreaterThan(singleCheck.recordLoads);
        }
      }
    }
    if (spec.edgeFilter) {
      assertThat(current.edgeLoads).isPositive();
      for (var selection : List.of("ALTERNATIVE", "AUTO")) {
        var prepared = measuredPreparation(selection);
        var c = prepared.counters;
        assertThat(prepared.work.edgeLoads).isPositive();
        assertThat(c.discoveryWork).isPositive().isLessThanOrEqualTo(c.workBudget);
        assertThat(c.sourceRecordsRead).as("preparation emits no source row").isZero();
        if (scenario.startsWith("budget-")) {
          assertThat(prepared.ready).isFalse();
          assertThat(c.budgetSwitch).isTrue();
          assertThat(c.reason).isEqualTo("work budget exceeded");
          assertThat(c.candidates).isZero();
          // The last visited pair can exhaust its edge-load charge before the load occurs.
          assertThat(prepared.work.edgeLoads).isBetween(c.edgeReads - 2, c.edgeReads - 1);
          assertThat(c.edgeReads).isLessThan(actualMiddleDegree);
        } else {
          assertThat(prepared.ready).isTrue();
          assertThat(c.budgetSwitch).isFalse();
          assertThat(prepared.work.edgeLoads).isEqualTo(actualDegree).isEqualTo(c.edgeReads);
          assertThat(alternative.candidates).isEqualTo(2);
          assertThat(alternative.rows).containsExactly(0, 2);
        }
      }
    }
    // All scenarios also exercise an actual one-at-a-time caller, with no query rewrite.
    var firstCurrent = measuredWork("CURRENT", true);
    var firstAlternative = measuredWork("ALTERNATIVE", true);
    var firstAutomatic = measuredWork("AUTO", true);
    assertThat(firstAutomatic.path).isEqualTo(automatic.path);
    if (!spec.nativePattern) {
      assertThat(firstAlternative.rows).isEqualTo(firstCurrent.rows);
      assertThat(firstAutomatic.rows).isEqualTo(firstCurrent.rows);
      for (var run : List.of(firstAlternative, firstAutomatic)) {
        if (run.path.equals("target")) {
          assertThat(run.sources).isEqualTo(1);
          assertThat(run.candidates).isEqualTo(1);
        }
      }
    }
  }

  /** Smoke timing has no latency assertion. Read instrumentation runs in a separate pass. */
  private void report(int repetitions) throws Exception {
    var runs = new HashMap<String, Run>();
    for (String path : List.of("CURRENT", "ALTERNATIVE", "AUTO")) {
      runs.put(path, measuredWork(path, spec.one));
    }
    double bestWork = Math.min(runs.get("CURRENT").observedWork(),
        runs.get("ALTERNATIVE").observedWork());
    long currentExecution = 0;
    for (String path : List.of("CURRENT", "ALTERNATIVE", "AUTO")) {
      selector = path;
      long[] planning = new long[repetitions];
      long[] execution = new long[repetitions];
      long[] first = new long[repetitions];
      long[] decision = new long[repetitions];
      // One untimed pass warms the parser, copies and selected branch.
      copyForInvocation();
      execution();
      closeInvocation();
      for (int i = 0; i < repetitions; i++) {
        long start = System.nanoTime();
        planning();
        planning[i] = System.nanoTime() - start;
        copyForInvocation();
        try (var scoped = probe(null)) {
          execution[i] = consume(invocation, path, false).executionNanos;
        }
        closeInvocation();
        copyForInvocation();
        try (var scoped = probe(null)) {
          first[i] = consume(invocation, path, true).firstNanos;
        }
        closeInvocation();
        copyForInvocation();
        start = System.nanoTime();
        decision();
        decision[i] = System.nanoTime() - start;
        closeInvocation();
      }
      if (path.equals("CURRENT")) {
        currentExecution = median(execution);
      }
      var work = runs.get(path);
      System.out.printf(java.util.Locale.ROOT,
          "KE,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%s,%d,%d,%d,%d,%.2f,%.4f,%d,%d,%d,%d%n",
          scenario, path, work.path, median(planning), median(execution), median(first),
          median(decision), work.sources, work.candidates, work.edges, work.targets,
          work.recordLoads, work.discovery, work.budget, work.switched, work.countPositions,
          work.preflight, actualDegree, transactionEntries, work.observedWork() - bestWork,
          (double) median(decision) / Math.max(1, currentExecution), work.adjacencyEntries,
          work.rejectedPositions, work.edgeLoads, work.transactionVisits);
      System.out.printf(java.util.Locale.ROOT,
          "WITNESS,%s,source_count=%d,breadth=%d,index_matches=%d,middle_degree=%d,"
              + "source_type_share=%.4f,rids=%d,checks=%d,estimate_share=%.2f,"
              + "middle_count=%d,middle_edge_records=%d,middle_average=%.2f%n",
          scenario, spec.nativePattern ? 4 : spec.count, spec.breadth, spec.indexShare,
          actualMiddleDegree, spec.nativePattern || spec.middleDegree > 0 ? 1
              : (double) expected.size() / Math.max(1, actualDegree),
          spec.ridCount, checks.size(), spec.staleShare, middles.size(), middleEdgeRecords,
          middleAverage);
      if (spec.edgeFilter && !path.equals("CURRENT")) {
        var prepared = measuredPreparation(path);
        System.out.printf(java.util.Locale.ROOT,
            "DECISION_WORK,%s,%s,edge_loads=%d,reverse_entries=%d,discovery=%.2f,"
                + "budget=%.2f,budget_switch=%s%n",
            scenario, path, prepared.work.edgeLoads, prepared.counters.edgeReads,
            prepared.counters.discoveryWork, prepared.counters.workBudget,
            prepared.counters.budgetSwitch);
      }
    }
  }

  /** Move pending fixture writes to the graph session, then prove both translation modes. */
  private void setupGremlin() throws Exception {
    session.rollback();
    session.close();
    graph = (YTDBGraphInternal) pool.asGraph();
    graph.tx().open();
    session = graph.tx().getDatabaseSession();
    if (spec.skew > 0) {
      var bag = (LinkBag) ((EntityImpl) session.loadVertex(middle))
          .getPropertyInternal("in_BenchLink");
      var edge = session.loadVertex(sources.getFirst()).getEdges(Direction.OUT, "BenchLink")
          .iterator().next();
      for (int i = 0; i < spec.skew; i++) {
        bag.add(edge.getIdentity(), sources.getFirst());
      }
    }
    for (int i = 0; i < spec.transactionSize; i++) {
      session.newVertex("BenchNoise").setProperty("pending", i);
    }
    assertThat(session.getTransactionInternal().getEntryCount()).isEqualTo(transactionEntries);
    var saved = translation;
    var onRows = new HashMap<String, List<Integer>>();
    var offRuns = new ArrayList<Run>();
    try {
      for (var mode : List.of("ON", "OFF")) {
        translation = mode;
        setTranslation();
        for (var selection : List.of("CURRENT", "ALTERNATIVE", "AUTO")) {
          var work = new Work();
          Run run;
          try (var traversal = gremlin()) {
            traversal.applyStrategies();
            assertThat(gremlinPlan(traversal) != null).as(mode + " boundary")
                .isEqualTo(mode.equals("ON"));
            try (var scoped = probe(work)) {
              run = consumeGremlin(traversal, selection, false, work);
            }
          }
          var wanted = spec.limit ? expected.subList(0, 1) : expected;
          assertThat(run.rows).containsExactlyInAnyOrderElementsOf(wanted);
          if (mode.equals("ON")) {
            onRows.put(selection, run.rows);
            if (spec.indexShare == 0) {
              assertThat(run.path).isEqualTo(spec.nativePattern
                  ? selection.equals("CURRENT") || spec.transactionSize > 0
                      || (selection.equals("AUTO") && (spec.degree == 2 || spec.tombstones > 0))
                          ? "rid" : "source"
                  : selection.equals("CURRENT") || scenario.startsWith("budget-")
                      || (selection.equals("AUTO") && Set.of("degree-high", "middle-high",
                          "type-all", "type-rare", "rids-long").contains(scenario))
                              ? "source" : "target");
            }
            if (scenario.startsWith("budget-") && !selection.equals("CURRENT")) {
              assertThat(run.switched).isTrue();
              assertThat(run.discovery).isPositive().isLessThanOrEqualTo(run.budget);
            }
          } else {
            assertThat(run.rows).containsExactlyInAnyOrderElementsOf(onRows.get(selection));
            assertThat(run.path).isEqualTo("gremlin");
            if (!spec.limit) {
              // Edge materialization can reload the source endpoint as well as scan it.
              assertThat(run.sourceLoads).isGreaterThanOrEqualTo(spec.indexShare > 0
                  ? spec.indexShare : spec.nativePattern ? 4 : spec.count);
            }
            assertThat(run.discovery).isZero();
            assertThat(run.countPositions).isZero();
            assertThat(run.transactionVisits).isZero();
            assertThat(run.switched).isFalse();
            offRuns.add(run);
          }
          if (spec.transactionSize > 0) {
            assertThat(run.transactionVisits).isZero();
            assertThat(run.countPositions).isZero();
            assertThat(run.preflight).isZero();
          }
        }
      }
      // The MATCH-only selectors must not alter native Gremlin work.
      var baseline = offRuns.getFirst();
      for (var run : offRuns) {
        assertThat(run.recordLoads).isEqualTo(baseline.recordLoads);
        assertThat(run.sourceLoads).isEqualTo(baseline.sourceLoads);
        assertThat(run.adjacencyEntries).isEqualTo(baseline.adjacencyEntries);
        assertThat(run.edgeLoads).isEqualTo(baseline.edgeLoads);
      }
    } finally {
      translation = saved;
      setTranslation();
    }
    gremlinTemplate = gremlin();
    gremlinTemplate.applyStrategies();
    try (var traversal = gremlinTemplate.clone(); var scoped = probe(new Work())) {
      var first = consumeGremlin(traversal, "AUTO", true, new Work());
      assertThat(first.rows).hasSize(1);
      assertThat(expected).contains(first.rows.getFirst());
      if (!spec.nativePattern) {
        assertThat(first.rows).containsExactly(expected.getFirst());
      }
    }
  }

  private void setTranslation() {
    session.getConfiguration().setValue(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, translation.equals("ON"));
  }

  /** Match the direct fixture, including EXISTS multiplicity and positive-hop source selection. */
  private Traversal.Admin<?, ?> gremlin() {
    var traversal = graph.traversal().V().hasLabel("BenchSource");
    if (spec.indexShare > 0) {
      traversal.has("bucket", 1);
    }
    if (spec.nativePattern) {
      return traversal.as("s").out("BenchNative").hasLabel("BenchTarget")
          .hasId(targets.getFirst()).select("s")
          .values("n").asAdmin();
    }
    GraphTraversal<?, ?> check = spec.edgeFilter
        ? __.outE("BenchLink").has("weight", 1).inV()
        : __.out("BenchLink");
    if (spec.middleDegree > 0) {
      check.hasLabel("BenchMiddle");
      if (spec.edgeFilter) {
        check.has("ok", true);
      }
      check.out("BenchTail");
    }
    var ids = ((List<?>) parameters.get("targets")).toArray();
    check.hasId(ids);
    traversal.filter(check);
    for (int i = 0; i < spec.extraChecks; i++) {
      traversal.filter(__.out("BenchExtra").hasId(targets.getFirst()));
    }
    if (spec.limit) {
      traversal.limit(1);
    }
    return traversal.values("n").asAdmin();
  }

  private static SelectExecutionPlan gremlinPlan(Traversal.Admin<?, ?> traversal) {
    return traversal.getSteps().stream().filter(YTDBMatchPlanStep.class::isInstance)
        .map(step -> (SelectExecutionPlan) ((YTDBMatchPlanStep<?, ?>) step).getPlan())
        .findFirst().orElse(null);
  }

  private static Object choice(InternalExecutionPlan plan) {
    if (plan == null) {
      return null;
    }
    for (var step : plan.getSteps()) {
      if (step instanceof KnownEndpointExistsStep || step instanceof NativeKnownEndpointStep) {
        return step;
      }
      for (var child : ((ExecutionStepInternal) step).getSubExecutionPlans()) {
        var found = choice((InternalExecutionPlan) child);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private Run consumeGremlin(Traversal.Admin<?, ?> traversal, String selection, boolean one,
      Work work) throws Exception {
    var rows = new ArrayList<Integer>();
    long elapsed;
    long first = -1;
    try (var forced = force(selection)) {
      long start = System.nanoTime();
      while (traversal.hasNext()) {
        rows.add(((Number) traversal.next()).intValue());
        if (first < 0) {
          first = System.nanoTime() - start;
        }
        if (one) {
          break;
        }
      }
      elapsed = System.nanoTime() - start;
    }
    var selected = choice(gremlinPlan(traversal));
    Run run;
    if (selected instanceof KnownEndpointExistsStep step) {
      var c = step.counters();
      run = new Run(rows, c.path, c.reason, c.sourceRecordsRead, c.candidates, c.edgeReads,
          c.targetLoads, c.discoveryWork, c.workBudget, c.budgetSwitch, work.loads,
          work.sourceLoads, work.positions, work.preflight, work.edgeEntries,
          work.rejectedPositions, work.edgeLoads, work.transactionVisits, elapsed, first);
    } else {
      run = new Run(rows, selected instanceof NativeKnownEndpointStep step ? step.path()
          : translation.equals("OFF") ? "gremlin" : "index",
          selected instanceof NativeKnownEndpointStep step ? step.reason() : "no choice",
          work.sourceLoads, 0, 0, 0, 0, 0, false, work.loads, work.sourceLoads, work.positions,
          work.preflight, work.edgeEntries, work.rejectedPositions, work.edgeLoads,
          work.transactionVisits, elapsed, first);
    }
    traversal.close();
    return run;
  }

  /** Gremlin counters come from an untimed pass, independent of JMH latency samples. */
  private void reportGremlin(int repetitions) throws Exception {
    System.out.printf("GSHAPE,%s,%s,%s%n", scenario, translation,
        gremlinTemplate.getSteps().stream().map(step -> step.getClass().getSimpleName()).toList());
    for (var selection : List.of("CURRENT", "ALTERNATIVE", "AUTO")) {
      selector = selection;
      var work = new Work();
      Run run;
      try (var traversal = gremlinTemplate.clone(); var scoped = probe(work)) {
        run = consumeGremlin(traversal, selection, spec.one, work);
      }
      long[] planning = new long[repetitions];
      long[] execution = new long[repetitions];
      long[] first = new long[repetitions];
      long[] decision = new long[repetitions];
      for (int i = 0; i < repetitions; i++) {
        long start = System.nanoTime();
        planning();
        planning[i] = System.nanoTime() - start;
        copyForInvocation();
        start = System.nanoTime();
        execution();
        execution[i] = System.nanoTime() - start;
        closeInvocation();
        copyForInvocation();
        start = System.nanoTime();
        firstRow();
        first[i] = System.nanoTime() - start;
        closeInvocation();
        copyForInvocation();
        start = System.nanoTime();
        decision();
        decision[i] = System.nanoTime() - start;
        closeInvocation();
      }
      System.out.printf(java.util.Locale.ROOT,
          "GKE,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%s,%d,%d,%d,%d%n",
          scenario, translation, selection, run.path, median(planning), median(execution),
          median(first), median(decision), run.rows.size(), run.sources, run.sourceLoads,
          run.recordLoads, run.adjacencyEntries, run.edgeLoads, run.discovery, run.budget,
          run.switched, run.edges, run.countPositions, run.preflight, run.transactionVisits);
    }
  }

  private static long median(long[] values) {
    java.util.Arrays.sort(values);
    return values[values.length / 2];
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--smoke")) {
      int repetitions = args.length > 1 ? Integer.parseInt(args[1]) : 3;
      assertThat(repetitions).isPositive();
      String[] cases = KnownEndpointAccessBenchmark.class.getField("scenario")
          .getAnnotation(Param.class).value();
      System.out.println("KE,scenario,selector,path,planning_ns,execution_ns,first_row_ns,"
          + "decision_ns,source_records,candidates,reverse_edges,target_loads,record_loads,"
          + "discovery_work,budget,budget_switch,count_positions,preflight,degree,tx_entries,"
          + "extra_vs_best_work,decision_over_current_execution,adjacency_entries,rejected_positions,"
          + "edge_record_loads,decision_tx_visits");
      System.out.println("GKE,scenario,translation,selector,path,planning_ns,execution_ns,"
          + "first_row_ns,decision_ns,rows,source_records,source_loads,record_loads,"
          + "adjacency_entries,edge_record_loads,discovery_work,budget,budget_switch,"
          + "reverse_edges,count_positions,preflight,decision_tx_visits");
      for (var name : cases) {
        if (args.length > 2 && !List.of(args[2].split(",")).contains(name)) {
          continue;
        }
        for (var mode : List.of("MATCH", "ON", "OFF")) {
          var benchmark = new KnownEndpointAccessBenchmark();
          benchmark.scenario = name;
          benchmark.selector = "AUTO";
          benchmark.translation = mode;
          try {
            benchmark.setup();
            if (mode.equals("MATCH")) {
              benchmark.report(repetitions);
            } else {
              benchmark.reportGremlin(repetitions);
            }
          } finally {
            benchmark.teardown();
          }
        }
      }
    } else {
      var commandLine = new org.openjdk.jmh.runner.options.CommandLineOptions(args);
      var options = new OptionsBuilder().parent(commandLine);
      if (commandLine.getIncludes().isEmpty()) {
        options.include(KnownEndpointAccessBenchmark.class.getSimpleName());
      }
      new Runner(options.build()).run();
    }
  }
}
