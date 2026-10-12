package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.exception.SessionNotActivatedException;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Native positive MATCH start choices, security oracles and observable acceptance contracts. */
@Category(SequentialTest.class)
public class NativeKnownEndpointTest extends DbTestBase {

  private RecordIdInternal target;
  private RecordIdInternal sparse;
  private final List<RecordIdInternal> sources = new ArrayList<>();
  private SelectExecutionPlan securedTemplate;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    var sourceClass = session.createVertexClass("NativeSource");
    sourceClass.createProperty("n", PropertyType.INTEGER);
    session.execute("CREATE CLASS NativeSub EXTENDS NativeSource").close();
    var noiseClass = session.createVertexClass("NativeNoise");
    session.createVertexClass("NativeTarget");
    session.execute("CREATE CLASS NativeLink EXTENDS E ABSTRACT").close();
    session.execute("CREATE CLASS NativeSourceLink EXTENDS NativeLink").close();
    session.execute("CREATE CLASS NativeNoiseLink EXTENDS NativeLink").close();
    session.getMetadata().getSchema().getClass("NativeSourceLink")
        .createProperty("out", PropertyType.LINK, sourceClass);
    session.getMetadata().getSchema().getClass("NativeNoiseLink")
        .createProperty("out", PropertyType.LINK, noiseClass);
    session.begin();
    var t = session.newVertex("NativeTarget");
    var low = session.newVertex("NativeTarget");
    var vertices = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex>();
    for (int i = 0; i < 4; i++) {
      var vertex = session.newVertex("NativeSource");
      vertices.add(vertex);
    }
    for (int i = 0; i < 64; i++) {
      session.newVertex("NativeNoise").addEdge(t, "NativeNoiseLink");
    }
    session.commit();
    target = (RecordIdInternal) t.getIdentity();
    sparse = (RecordIdInternal) low.getIdentity();
    vertices.forEach(v -> sources.add((RecordIdInternal) v.getIdentity()));
    sources.sort(RecordIdInternal::compareTo);
    session.begin();
    for (int i = 0; i < sources.size(); i++) {
      session.loadVertex(sources.get(i)).setProperty("n", i);
    }
    // Permanent RID order makes the hidden candidate the first scan record.
    session.loadVertex(sources.get(0)).addEdge(session.loadVertex(target), "NativeSourceLink");
    session.loadVertex(sources.get(0)).addEdge(session.loadVertex(target), "NativeSourceLink");
    session.loadVertex(sources.get(1)).addEdge(session.loadVertex(target), "NativeSourceLink");
    session.loadVertex(sources.get(1)).addEdge(session.loadVertex(sparse), "NativeSourceLink");
    session.commit();
    session.begin();
  }

  /** AUTO switches on high degree, retains sparse RID, and never deduplicates parallel edges. */
  @Test
  public void autoUsesExactBoundsAndBothForcedStartsKeepMultiplicity() throws Exception {
    for (boolean edgeRecords : List.of(false, true)) {
      var high = parity(target, "", edgeRecords);
      assertThat(high.rows).containsExactlyInAnyOrder(0, 0, 1);
      assertThat(high.path).isEqualTo("source");
      assertThat(high.reason).isEqualTo("exact count margin");
      var low = parity(sparse, "", edgeRecords);
      assertThat(low.rows).containsExactly(1);
      assertThat(low.path).isEqualTo("rid");
      assertThat(low.reason).isEqualTo("degree cannot meet margin");
    }
  }

  /** A failing target predicate must raise the RID error even when no source has an edge. */
  @Test
  public void targetPredicateErrorsRetainRidOracle() throws Exception {
    for (var source : sources) {
      var edges = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.Edge>();
      session.loadVertex(source).getEdges(Direction.OUT, "NativeSourceLink").forEach(edges::add);
      edges.forEach(com.jetbrains.youtrackdb.internal.core.db.record.record.Edge::delete);
    }
    session.loadVertex(target).setProperty("n", 0);
    session.commit();
    session.begin();
    var query = sql(target, "", false).replace("@rid=" + target,
        "@rid=" + target + " AND 1/n > 0");
    var oracle = executeQuery(NativeKnownEndpointStep.Path.RID, query);
    assertThat(oracle.error).isEqualTo(ArithmeticException.class.getName());
    for (var selected : List.of(NativeKnownEndpointStep.Path.AUTO,
        NativeKnownEndpointStep.Path.SOURCE)) {
      var actual = executeQuery(selected, query);
      assertThat(actual.error).isEqualTo(oracle.error);
      assertThat(actual.rows).isEqualTo(oracle.rows);
      assertThat(actual.path).isEqualTo("rid");
    }
  }

  /** A false target predicate rejects all rows, while a true residual predicate may switch. */
  @Test
  public void targetResidualPredicatesKeepRidRowOutcome() throws Exception {
    session.loadVertex(target).setProperty("n", 0);
    session.commit();
    session.begin();
    for (var predicate : List.of("n > 0", "n = 0")) {
      var query = sql(target, "", false).replace("@rid=" + target,
          "@rid=" + target + " AND " + predicate);
      var oracle = executeQuery(NativeKnownEndpointStep.Path.RID, query);
      assertThat(oracle.error).isNull();
      assertThat(oracle.rows).containsExactlyInAnyOrderElementsOf(predicate.equals("n > 0")
          ? List.of() : List.of(0, 0, 1));
      for (var selected : List.of(NativeKnownEndpointStep.Path.AUTO,
          NativeKnownEndpointStep.Path.SOURCE)) {
        var actual = executeQuery(selected, query);
        assertThat(actual.error).isEqualTo(oracle.error);
        assertThat(actual.rows).containsExactlyInAnyOrderElementsOf(oracle.rows);
        assertThat(actual.path).isEqualTo(predicate.equals("n > 0") ? "rid" : "source");
      }
    }
  }

  /** A rid: slot of another class must keep the rows accepted by the RID-rooted pattern. */
  @Test
  public void mismatchedTargetSlotClassRetainsRidRows() throws Exception {
    var other = session.newVertex("NativeNoise");
    for (int i = 0; i < 64; i++) {
      session.newVertex("NativeNoise").addEdge(other, "NativeNoiseLink");
    }
    session.loadVertex(sources.get(0)).addEdge(other, "NativeSourceLink");
    session.commit();
    session.begin();
    var query = sql(target, "", false).replace("where:(@rid=" + target + ")",
        "rid:" + other.getIdentity());
    var oracle = executeQuery(NativeKnownEndpointStep.Path.RID, query);
    assertThat(oracle.error).isNull();
    assertThat(oracle.rows).containsExactly(0);
    for (var selected : List.of(NativeKnownEndpointStep.Path.AUTO,
        NativeKnownEndpointStep.Path.SOURCE)) {
      var actual = executeQuery(selected, query);
      assertThat(actual.rows).isEqualTo(oracle.rows);
      assertThat(actual.error).isEqualTo(oracle.error);
      assertThat(actual.path).isEqualTo("rid");
      assertThat(actual.reason).isEqualTo("target class mismatch");
    }
  }

  /** A cheap rejection must skip the exact source count, not merely return the same rows. */
  @Test
  public void approximateSourceGateSkipsExactCount() throws Exception {
    for (int i = 0; i < 36; i++) {
      session.newVertex("NativeSource").setProperty("n", 100 + i);
    }
    session.commit();
    session.begin();
    var oracle = execute(NativeKnownEndpointStep.Path.RID, target, "", false);
    try (var injected = KnownEndpointExistsStep.injectFailure("native counts",
        new IllegalStateException("exact count must not run"))) {
      var actual = execute(NativeKnownEndpointStep.Path.AUTO, target, "", false);
      assertThat(actual.rows).isEqualTo(oracle.rows);
      assertThat(actual.error).isEqualTo(oracle.error);
      assertThat(actual.path).isEqualTo("rid");
      assertThat(actual.reason).isEqualTo("approximate source margin");
    }
  }

  /** Deleted source positions exhaust the work budget without logging a storage error. */
  @Test
  public void tombstonesExhaustSharedExactCountBudget() throws Exception {
    var deleted = new ArrayList<RecordIdInternal>();
    var vertices = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex>();
    for (int i = 0; i < 100; i++) {
      vertices.add(session.newVertex("NativeSource"));
    }
    session.commit();
    vertices.forEach(vertex -> deleted.add((RecordIdInternal) vertex.getIdentity()));
    session.begin();
    deleted.forEach(rid -> session.loadVertex(rid).delete());
    session.commit();
    session.begin();
    try (var logs = LogRecordCollector.attachTo(session.getStorage().getClass())) {
      var actual = parity(target, "", false);
      assertThat(actual.path).isEqualTo("rid");
      assertThat(actual.reason).isEqualTo("exact count work budget");
      assertThat(logs.messages()).noneMatch(message -> message.startsWith("SEVERE"));
    }
  }

  /** The same budget covers edge tombstones and aborts without logging a storage error. */
  @Test
  public void edgeTombstonesExhaustSharedExactCountBudget() throws Exception {
    var created = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.Edge>();
    for (int i = 0; i < 100; i++) {
      created.add(session.loadVertex(sources.get(2))
          .addEdge(session.loadVertex(sparse), "NativeSourceLink"));
    }
    session.commit();
    var deleted = created.stream().map(edge -> edge.getIdentity()).toList();
    session.begin();
    deleted.forEach(rid -> session.loadEdge(rid).delete());
    session.commit();
    session.begin();
    try (var logs = LogRecordCollector.attachTo(session.getStorage().getClass())) {
      var actual = parity(target, "", false);
      assertThat(actual.path).isEqualTo("rid");
      assertThat(actual.reason).isEqualTo("exact count work budget");
      assertThat(logs.messages()).noneMatch(message -> message.startsWith("SEVERE"));
    }
  }

  /** A losing edge estimate stops before the compatible edge class's exact scan. */
  @Test
  public void approximateEdgeGateSkipsExactEdgeCount() throws Exception {
    for (int i = 0; i < 32; i++) {
      session.loadVertex(sources.get(2)).addEdge(session.loadVertex(sparse), "NativeSourceLink");
    }
    session.commit();
    session.begin();
    var oracle = execute(NativeKnownEndpointStep.Path.RID, target, "", false);
    try (var injected = KnownEndpointExistsStep.injectFailure("native edge counts",
        new IllegalStateException("exact edge count must not run"))) {
      var actual = execute(NativeKnownEndpointStep.Path.AUTO, target, "", false);
      assertThat(actual.path).isEqualTo("rid");
      assertThat(actual.reason).isEqualTo("edge bound or margin unavailable");
      assertThat(actual.rows).isEqualTo(oracle.rows);
      assertThat(actual.error).isEqualTo(oracle.error);
    }
  }

  /** Exact forward work rejects a loss or equality, while one more target edge wins. */
  @Test
  public void exactEdgeWorkAndEqualityBoundariesDiscriminateAutoChoice() throws Exception {
    for (boolean edgeRecords : List.of(false, true)) {
      int equality = edgeRecords ? 34 : 26;
      for (int degree : List.of(equality - 1, equality, equality + 1)) {
        setTargetDegree(degree);
        var actual = parity(target, "", edgeRecords);
        assertThat(actual.rows).containsExactlyInAnyOrder(0, 0, 1);
        assertThat(actual.path).as("degree=%s, edgeRecords=%s", degree, edgeRecords)
            .isEqualTo(degree > equality ? "source" : "rid");
      }
    }
  }

  /** Source record work alone loses the margin even though target degree passes its first gate. */
  @Test
  public void sourceCountRejectsHubWithManyDisconnectedSources() throws Exception {
    for (int i = 0; i < 36; i++) {
      session.newVertex("NativeSource").setProperty("n", 100 + i);
    }
    session.commit();
    session.begin();
    var actual = parity(target, "", false);
    assertThat(actual.rows).containsExactlyInAnyOrder(0, 0, 1);
    assertThat(actual.path).isEqualTo("rid");
  }

  /** LIMIT 1 loses the AUTO first-row margin, but forced SOURCE may return any valid row. */
  @Test
  public void limitOneUsesCostRatherThanSliceGuard() throws Exception {
    for (boolean edgeRecords : List.of(false, true)) {
      var base = sql(target, "", edgeRecords);
      var full = executeQuery(NativeKnownEndpointStep.Path.RID, base);
      var query = base + " LIMIT 1";
      try (var injected = KnownEndpointExistsStep.injectFailure("native counts",
          new IllegalStateException("count must not run"))) {
        var actual = executeQuery(NativeKnownEndpointStep.Path.AUTO, query);
        assertValidSlice(actual, full.rows, 1);
        assertThat(actual.path).isEqualTo("rid");
        assertThat(actual.reason).isEqualTo("degree cannot meet margin");
      }
      var forced = executeQuery(NativeKnownEndpointStep.Path.SOURCE, query);
      assertValidSlice(forced, full.rows, 1);
      assertThat(forced.path).isEqualTo("source");
      assertThat(forced.reason).isEqualTo("test selector");
      assertThat(forced.properties).containsOnlyKeys("path", "reason");
    }
  }

  /** Sorting needs the full input, so SOURCE can win LIMIT and SKIP without changing the slice. */
  @Test
  public void orderedPagingUsesFullWorkAndKeepsRidRows() throws Exception {
    for (boolean edgeRecords : List.of(false, true)) {
      for (var suffix : List.of(" LIMIT 1", " SKIP 1", " SKIP 1 LIMIT 1")) {
        var query = sql(target, "", edgeRecords).replace("RETURN s.n", "RETURN DISTINCT s.n")
            + " ORDER BY n" + suffix;
        var oracle = executeQuery(NativeKnownEndpointStep.Path.RID, query);
        assertThat(oracle.error).isNull();
        for (var selected : List.of(NativeKnownEndpointStep.Path.AUTO,
            NativeKnownEndpointStep.Path.SOURCE)) {
          var actual = executeQuery(selected, query);
          assertThat(actual.rows).as(suffix).isEqualTo(oracle.rows);
          assertThat(actual.error).isEqualTo(oracle.error);
          assertThat(actual.path).isEqualTo("source");
          assertThat(actual.properties).containsOnlyKeys("path", "reason");
        }
      }
    }
  }

  /** Ordered LIMIT still requires a strict fixed-margin win, not merely a cheaper full scan. */
  @Test
  public void orderedLimitRetainsFixedMarginBoundary() throws Exception {
    for (int degree : List.of(26, 27)) {
      setTargetDegree(degree);
      var query = sql(target, "", false) + " ORDER BY n LIMIT 1";
      var oracle = executeQuery(NativeKnownEndpointStep.Path.RID, query);
      var actual = executeQuery(NativeKnownEndpointStep.Path.AUTO, query);
      assertThat(actual.rows).isEqualTo(oracle.rows);
      assertThat(actual.error).isEqualTo(oracle.error);
      assertThat(actual.path).isEqualTo(degree == 26 ? "rid" : "source");
    }
  }

  /** Unordered SKIP uses full or requested work and permits any correctly sized valid slice. */
  @Test
  public void unorderedSkipUsesCostAndValidSlices() throws Exception {
    for (boolean edgeRecords : List.of(false, true)) {
      var base = sql(target, "", edgeRecords);
      var full = executeQuery(NativeKnownEndpointStep.Path.RID, base);
      for (var suffix : List.of(" SKIP 0", " SKIP 1", " SKIP 1 LIMIT 1", " SKIP 40 LIMIT 1")) {
        int size = suffix.equals(" SKIP 0") ? 3 : suffix.equals(" SKIP 1") ? 2
            : suffix.equals(" SKIP 1 LIMIT 1") ? 1 : 0;
        for (var selected : List.of(NativeKnownEndpointStep.Path.AUTO,
            NativeKnownEndpointStep.Path.SOURCE)) {
          var actual = executeQuery(selected, base + suffix);
          assertValidSlice(actual, full.rows, size);
          assertThat(actual.path).as("%s %s", selected, suffix)
              .isEqualTo(selected == NativeKnownEndpointStep.Path.AUTO
                  && suffix.equals(" SKIP 1 LIMIT 1") ? "rid" : "source");
        }
      }
    }
  }

  /** DISTINCT consumes full input, so unordered LIMIT switches and selects valid distinct rows. */
  @Test
  public void unorderedDistinctPagingUsesFullInputCost() throws Exception {
    var base = sql(target, "", false).replace("RETURN s.n", "RETURN DISTINCT s.n");
    var full = executeQuery(NativeKnownEndpointStep.Path.RID, base);
    assertThat(full.error).isNull();
    assertThat(full.rows).containsExactlyInAnyOrder(0, 1);
    for (var suffix : List.of(" LIMIT 1", " SKIP 1 LIMIT 1")) {
      var actual = executeQuery(NativeKnownEndpointStep.Path.AUTO, base + suffix);
      assertValidSlice(actual, full.rows, 1);
      assertThat(actual.path).isEqualTo("source");
    }
  }

  /** ORDER BY ties permit either start's slice, but its rows must belong to the full multiset. */
  @Test
  public void orderedTiesPermitValidCostBasedSlice() throws Exception {
    var base = sql(target, "", false).replace("RETURN s.n as n", "RETURN s.n as n, 0 as rank");
    var full = executeQuery(NativeKnownEndpointStep.Path.RID, base);
    for (var suffix : List.of(" LIMIT 1", " SKIP 1 LIMIT 1")) {
      for (var selected : List.of(NativeKnownEndpointStep.Path.AUTO,
          NativeKnownEndpointStep.Path.SOURCE)) {
        var actual = executeQuery(selected, base + " ORDER BY rank" + suffix);
        assertValidSlice(actual, full.rows, 1);
        assertThat(actual.path).isEqualTo("source");
      }
    }
  }

  /** Copied cached-plan bounds resolve LIMIT and SKIP parameters anew, not at planning time. */
  @Test
  public void copiedPagingPlanResolvesCurrentParameters() throws Exception {
    var base = sql(target, "", false);
    var full = executeQuery(NativeKnownEndpointStep.Path.RID, base);
    var query = base + " SKIP :skip LIMIT :limit";
    var template = plan(query, Map.of("skip", 0, "limit", 1));
    try {
      for (var parameters : List.<Map<Object, Object>>of(Map.of("skip", 0, "limit", 1),
          Map.of("skip", 0, "limit", -1), Map.of("skip", 1, "limit", 1),
          Map.of("skip", 40, "limit", 1), Map.of("skip", 1, "limit", -1),
          Map.of("skip", -1, "limit", 1), Map.of("skip", 0, "limit", 0))) {
        Run oracle;
        try (var forced = NativeKnownEndpointStep.forcePath(NativeKnownEndpointStep.Path.RID)) {
          oracle = drain((SelectExecutionPlan) template.copy(context(parameters)));
        }
        var actual = drain((SelectExecutionPlan) template.copy(context(parameters)));
        assertValidSlice(actual, full.rows, oracle.rows.size());
        assertThat(actual.error).isEqualTo(oracle.error);
        assertThat(actual.path).isEqualTo(((Integer) parameters.get("limit")) < 0
            || ((Integer) parameters.get("skip")) == 40 ? "source" : "rid");
      }
      assertThat(nativeStep(template).path()).isEqualTo("not started");
    } finally {
      template.close();
    }
  }

  /** Paging never suppresses a target-predicate error or adds one after a source choice. */
  @Test
  public void pagingPredicateErrorsRetainRidOracle() throws Exception {
    session.loadVertex(target).setProperty("n", 0);
    session.commit();
    session.begin();
    var base = sql(target, "", false).replace("@rid=" + target,
        "@rid=" + target + " AND 1/n > 0");
    for (var suffix : List.of(" LIMIT 1", " SKIP 1", " ORDER BY n SKIP 1 LIMIT 1")) {
      var query = base + suffix;
      var oracle = executeQuery(NativeKnownEndpointStep.Path.RID, query);
      assertThat(oracle.error).isEqualTo(ArithmeticException.class.getName());
      var actual = executeQuery(NativeKnownEndpointStep.Path.AUTO, query);
      assertThat(actual.rows).isEqualTo(oracle.rows);
      assertThat(actual.error).isEqualTo(oracle.error);
      assertThat(actual.path).isEqualTo("rid");
    }
  }

  /** Missing collection ids raise the ordinary count error and leave subsequent counts usable. */
  @Test
  public void positionCountMissingCollectionMatchesOrdinaryCount() {
    var storage = session.getStorage();
    int[] missing = {Integer.MAX_VALUE};
    assertThatThrownBy(() -> storage.count(session, missing, false))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> storage.count(session, missing, false, () -> {
    }))
        .isInstanceOf(ConfigurationException.class);
    var ids = session.getMetadata().getSchema().getClass("NativeSource")
        .getPolymorphicCollectionIds();
    var positions = new AtomicInteger();
    assertThat(storage.count(session, ids, false, positions::incrementAndGet))
        .isEqualTo(storage.count(session, ids, false)).isEqualTo(4);
    assertThat(positions.get()).isGreaterThanOrEqualTo(4);
  }

  /** Position hooks hold the storage read lock, and hook failure releases it before RID fallback. */
  @Test
  public void positionCountHoldsStateLockAndReleasesItOnFailure() throws Exception {
    var storage = session.getStorage();
    var field = AbstractStorage.class.getDeclaredField("stateLock");
    field.setAccessible(true);
    var lock = (ScalableRWLock) field.get(storage);
    Callable<Boolean> tryWriter = () -> {
      boolean acquired = lock.writeLock().tryLock();
      if (acquired) {
        lock.writeLock().unlock();
      }
      return acquired;
    };
    var ids = session.getMetadata().getSchema().getClass("NativeSource").getCollectionIds();
    var failure = new IllegalStateException("count abort");
    try (var executor = Executors.newSingleThreadExecutor()) {
      Runnable hook = () -> {
        try {
          assertThat(executor.submit(tryWriter).get(10, TimeUnit.SECONDS)).isFalse();
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
        // Sticky failures also abort the locked fallback after an optimistic retry.
        throw failure;
      };
      assertThatThrownBy(() -> storage.count(session, ids, false, hook)).isSameAs(failure);
      assertThat(executor.submit(tryWriter).get(10, TimeUnit.SECONDS)).isTrue();
    }
    var oracle = execute(NativeKnownEndpointStep.Path.RID, target, "", false);
    try (var injected = KnownEndpointExistsStep.injectFailure("native count position", failure)) {
      var actual = execute(NativeKnownEndpointStep.Path.AUTO, target, "", false);
      assertThat(actual.path).isEqualTo("rid");
      assertThat(actual.rows).isEqualTo(oracle.rows);
      assertThat(actual.error).isEqualTo(oracle.error);
    }
  }

  /** Genuine position-hook failures still log a storage error and preserve exception identity. */
  @Test
  public void positionCountRealFailureStillLogsStorageError() {
    var storage = session.getStorage();
    var ids = session.getMetadata().getSchema().getClass("NativeSource").getCollectionIds();
    var failure = new IllegalStateException("genuine count failure");
    try (var logs = LogRecordCollector.attachTo(storage.getClass())) {
      assertThatThrownBy(() -> storage.count(session, ids, false, () -> {
        throw failure;
      })).isSameAs(failure);
      assertThat(logs.messages()).anyMatch(message -> message.startsWith("SEVERE")
          && message.contains("in storage"));
    }
    assertThat(storage.count(session, ids, false)).isEqualTo(4);
  }

  /** Target and compatible edge collection denials force RID before any exact edge count. */
  @Test
  public void deniedTargetAndEdgeCollectionsRetainRidOracle() throws Exception {
    for (var clazz : List.of("NativeTarget", "NativeSourceLink")) {
      session.commit();
      session.begin();
      var role = session.getMetadata().getSecurity().getRole("reader");
      for (int collection : session.getMetadata().getSchema().getClass(clazz).getCollectionIds()) {
        role.revoke(session, ResourceGeneric.COLLECTION, session.getCollectionNameById(collection),
            Role.PERMISSION_READ);
      }
      role.save(session);
      session.commit();
      session.begin();
      securedTemplate = plan(sql(target, "", false));
      assertThat(nativeStep(securedTemplate)).isNotNull();
      session.rollback();
      reOpen(readerUser, readerPassword);
      session.begin();
      assertFallback(clazz.equals("NativeTarget") ? "target collection denied"
          : "edge bound or margin unavailable");
      securedTemplate.close();
      securedTemplate = null;
      session.rollback();
      reOpen(adminUser, adminPassword);
      session.begin();
      role = session.getMetadata().getSecurity().getRole("reader");
      for (int collection : session.getMetadata().getSchema().getClass(clazz).getCollectionIds()) {
        role.grant(session, ResourceGeneric.COLLECTION, session.getCollectionNameById(collection),
            Role.PERMISSION_READ);
      }
      role.save(session);
      session.commit();
      session.begin();
    }
  }

  private void setTargetDegree(int desired) {
    var noise = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.Edge>();
    session.loadVertex(target).getEdges(Direction.IN, "NativeNoiseLink").forEach(noise::add);
    int desiredNoise = desired - 3;
    for (int i = desiredNoise; i < noise.size(); i++) {
      noise.get(i).delete();
    }
    for (int i = noise.size(); i < desiredNoise; i++) {
      session.newVertex("NativeNoise").addEdge(session.loadVertex(target), "NativeNoiseLink");
    }
    session.commit();
    session.begin();
  }

  /** A selective unindexed source still switches, but a real source index prevents wrapping. */
  @Test
  public void selectiveSourceAndIndexKeepTheirActualAccessPaths() throws Exception {
    var selective = parity(target, "n=1", false);
    assertThat(selective.rows).containsExactly(1);
    assertThat(selective.path).isEqualTo("source");
    session.rollback();
    session.execute("CREATE INDEX NativeSource.n ON NativeSource (n) NOTUNIQUE").close();
    session.begin();
    var indexed = execute(NativeKnownEndpointStep.Path.AUTO, target, "n=1", false);
    assertThat(indexed.rows).containsExactly(1);
    assertThat(indexed.path).isEqualTo("none");
    assertThat(indexed.plan).contains("INDEX");
    var forced = execute(NativeKnownEndpointStep.Path.SOURCE, target, "n=1", false);
    assertThat(forced.rows).isEqualTo(indexed.rows);
    assertThat(forced.path).isEqualTo("none");
  }

  /** EXPLAIN and PROFILE expose exactly path/reason and honestly show the plain source scan. */
  @Test
  public void planOutputHasNoPrivateCountsOrScanEliminationClaim() throws Exception {
    for (var endpoint : List.of(target, sparse)) {
      var direct = execute(NativeKnownEndpointStep.Path.AUTO, endpoint, "", false);
      assertThat(direct.properties).containsOnlyKeys("path", "reason");
      assertThat(direct.properties.get("path")).isEqualTo(direct.path);
      assertThat(direct.plan).contains("RID:", "SOURCE:", "NativeSource", "FETCH FROM CLASS")
          .doesNotContain("scan eliminated", "scan elimination", "degree=", "sourceCount=");
      for (var prefix : List.of("EXPLAIN ", "PROFILE ")) {
        try (var result = session.query(prefix + sql(endpoint, "", false))) {
          var row = result.next();
          Result plan = row.getProperty("executionPlan");
          assertThat(plan).isNotNull();
          var step = findPublishedNative(plan);
          assertThat(step).isNotNull();
          assertThat(properties(step)).containsOnlyKeys("path", "reason");
          if (prefix.startsWith("PROFILE")) {
            assertThat(step.<String>getProperty("path")).isEqualTo(direct.path);
          }
          assertThat(row.<String>getProperty("executionPlanAsString"))
              .contains("RID:", "SOURCE:", "FETCH FROM CLASS")
              .doesNotContain("scan eliminated", "sourceCount=", "degree=");
        }
      }
    }
  }

  /** Copies reset selected branches and prefetch bindings instead of inheriting prior decisions. */
  @Test
  public void copiedPlanHasFreshDecisionAndSameRows() throws Exception {
    var original = plan(sql(target, "", false));
    var choice = nativeStep(original);
    assertThat(choice).isNotNull();
    var copy = (SelectExecutionPlan) original.copy(context());
    var copyChoice = nativeStep(copy);
    assertThat(drain(copy).rows).containsExactlyInAnyOrder(0, 0, 1);
    assertThat(copyChoice.path()).isEqualTo("source");
    assertThat(choice.path()).isEqualTo("not started");
    try (var forced = NativeKnownEndpointStep.forcePath(NativeKnownEndpointStep.Path.RID)) {
      assertThat(drain(original).rows).containsExactlyInAnyOrder(0, 0, 1);
      assertThat(choice.path()).isEqualTo("rid");
    }
  }

  /** Collection denial on either the source or its subclass preserves RID rows and errors. */
  @Test
  public void deniedSourceAndSubclassCollectionsRetainRidOracle() throws Exception {
    for (var clazz : List.of("NativeSource", "NativeSub")) {
      session.commit();
      session.begin();
      var role = session.getMetadata().getSecurity().getRole("reader");
      for (int collection : session.getMetadata().getSchema().getClass(clazz).getCollectionIds()) {
        role.revoke(session, ResourceGeneric.COLLECTION, session.getCollectionNameById(collection),
            Role.PERMISSION_READ);
      }
      role.save(session);
      session.commit();
      session.begin();
      // Plan as an allowed principal, then execute the copied RID-rooted plan as the reader.
      // Permission-aware root estimates can otherwise choose a zero-count source root first.
      securedTemplate = plan(sql(target, "", false));
      assertThat(nativeStep(securedTemplate)).isNotNull();
      session.rollback();
      reOpen(readerUser, readerPassword);
      session.begin();
      assertFallback("source collection denied");
      gremlinSecurityParity(clazz.equals("NativeSource") ? "none" : "rid",
          "source collection denied");
      securedTemplate.close();
      securedTemplate = null;
      session.rollback();
      reOpen(adminUser, adminPassword);
      session.begin();
      role = session.getMetadata().getSecurity().getRole("reader");
      for (int collection : session.getMetadata().getSchema().getClass(clazz).getCollectionIds()) {
        role.grant(session, ResourceGeneric.COLLECTION, session.getCollectionNameById(collection),
            Role.PERMISSION_READ);
      }
      role.save(session);
      session.commit();
      session.begin();
    }
  }

  /** A forward edge-list field policy forbids a switch before either branch emits rows. */
  @Test
  public void edgeListReadPolicyRetainsRidOracle() throws Exception {
    policy("database.class.NativeSource.out_NativeSourceLink", "false");
    assertFallback("edge-list read policy");
    gremlinSecurityParity("rid", "edge-list read policy");
  }

  /** A policy-hidden target remains hidden and the optimization adds neither rows nor errors. */
  @Test
  public void hiddenTargetRetainsSecuredRidLoad() throws Exception {
    policy("database.class.NativeTarget", "false");
    var run = assertFallback("target is missing, hidden or not a vertex");
    assertThat(run.rows).isEmpty();
    gremlinSecurityParity("rid", "target is missing, hidden or not a vertex");
  }

  /** The first source is hidden, so a full secured preflight must still reach the visible one. */
  @Test
  public void hiddenCandidateBeforeVisibleCandidateDoesNotTruncateSource() throws Exception {
    policy("database.class.NativeSource", "n <> 0");
    var run = parity(target, "", false);
    assertThat(run.rows).containsExactly(1);
    assertThat(run.error).isNull();
    assertThat(run.path).isEqualTo("source");
    gremlinSecurityParity("source", "exact count margin");
  }

  /** Target and source LINKLIST values require fallback despite a current LINKBAG declaration. */
  @Test
  public void actualStorageFormWinsOverSchemaDeclaration() throws Exception {
    for (boolean source : List.of(false, true)) {
      var rid = source ? sources.get(0) : target;
      var clazz = source ? "NativeSource" : "NativeTarget";
      var field = source ? "out_NativeSourceLink" : "in_NativeSourceLink";
      var direction = source ? Direction.OUT : Direction.IN;
      session.rollback();
      var schemaClass = (SchemaClassInternal) session.getMetadata().getSchema().getClass(clazz);
      schemaClass.createProperty(field, PropertyTypeInternal.LINKLIST, (SchemaClass) null, true);
      session.begin();
      var vertex = (EntityImpl) session.loadVertex(rid);
      var edges = new ArrayList<Identifiable>();
      vertex.asVertex().getEdges(direction, "NativeSourceLink").forEach(edges::add);
      vertex.removePropertyInternal(field);
      vertex.setPropertyInternal(field, new EntityLinkListImpl(vertex, edges),
          PropertyTypeInternal.LINKLIST);
      session.commit();
      session.execute("DROP PROPERTY " + clazz + "." + field).close();
      schemaClass.createProperty(field, PropertyTypeInternal.LINKBAG, (SchemaClass) null, true);
      session.begin();
      assertThat((Object) ((EntityImpl) session.loadVertex(rid)).getPropertyInternal(field))
          .isInstanceOf(EntityLinkListImpl.class);
      assertFallback("edge-list storage form");
      gremlinSecurityParity("rid", "edge-list storage form");
      // Restore the original value so the second iteration exercises source preflight itself.
      session.rollback();
      session.execute("DROP PROPERTY " + clazz + "." + field).close();
      session.begin();
      vertex = (EntityImpl) session.loadVertex(rid);
      vertex.removePropertyInternal(field);
      var bag = new com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag(session);
      for (var edge : edges) {
        var entity = session.loadEdge(edge.getIdentity());
        bag.add(entity.getIdentity(),
            entity.getVertex(source ? Direction.IN : Direction.OUT).getIdentity());
      }
      vertex.setPropertyInternal(field, bag, PropertyTypeInternal.LINKBAG);
      session.commit();
      session.begin();
    }
  }

  /** Pending writes, temporary endpoint RIDs and deletions preserve the transaction RID oracle. */
  @Test
  public void transactionEditsNeverTriggerExactCountDecision() throws Exception {
    for (var scenario : List.of("write", "temporary", "edge-delete", "source-delete",
        "target-delete")) {
      var endpoint = target;
      switch (scenario) {
        case "write" -> session.newVertex("NativeSource").setProperty("n", 99);
        case "temporary" -> {
          var vertex = session.newVertex("NativeTarget");
          session.loadVertex(sources.get(0)).addEdge(vertex, "NativeSourceLink");
          endpoint = (RecordIdInternal) vertex.getIdentity();
        }
        case "edge-delete" -> session.loadVertex(sources.get(0))
            .getEdges(Direction.OUT, "NativeSourceLink").iterator().next().delete();
        case "source-delete" -> session.loadVertex(sources.get(0)).delete();
        case "target-delete" -> session.loadVertex(target).delete();
        default -> throw new AssertionError(scenario);
      }
      var oracle = execute(NativeKnownEndpointStep.Path.RID, endpoint, "", false);
      for (var selected : List.of(NativeKnownEndpointStep.Path.AUTO,
          NativeKnownEndpointStep.Path.SOURCE)) {
        var actual = execute(selected, endpoint, "", false);
        assertThat(actual.rows).as(scenario).isEqualTo(oracle.rows);
        assertThat(actual.error).as(scenario).isEqualTo(oracle.error);
        assertThat(actual.path).as(scenario).isEqualTo("rid");
        assertThat(actual.reason).as(scenario).isEqualTo("pending transaction writes");
      }
      session.rollback();
      session.begin();
    }
  }

  /** Recoverable preparation errors keep the RID result and do not publish private error text. */
  @Test
  public void guardedFailuresFallBackBeforeRows() throws Exception {
    var oracle = execute(NativeKnownEndpointStep.Path.RID, target, "", false);
    for (var stage : List.of("native decision", "native counts", "native count position",
        "native source copy")) {
      try (var injected = KnownEndpointExistsStep.injectFailure(stage,
          new IllegalStateException("private policy value"))) {
        var actual = execute(NativeKnownEndpointStep.Path.AUTO, target, "", false);
        assertThat(actual.rows).as(stage).isEqualTo(oracle.rows);
        assertThat(actual.error).as(stage).isEqualTo(oracle.error);
        assertThat(actual.path).isEqualTo("rid");
        assertThat(actual.reason).isEqualTo("source attempt failed: IllegalStateException");
        assertThat(actual.plan).doesNotContain("private policy value");
      }
    }
    try (var injected = KnownEndpointExistsStep.injectFailure("native plan",
        new IllegalStateException("private policy value"))) {
      var actual = execute(NativeKnownEndpointStep.Path.AUTO, target, "", false);
      assertThat(actual.rows).isEqualTo(oracle.rows);
      assertThat(actual.error).isEqualTo(oracle.error);
      assertThat(actual.path).isEqualTo("none");
    }
  }

  /** Wrapped interrupts escape with the flag set, and wrapped timeout/activation errors escape. */
  @Test
  public void nonRecoverableFailuresEscapeEveryGuard() throws Exception {
    for (var stage : List.of("native plan", "native decision", "native counts",
        "native count position", "native source copy")) {
      for (var cause : List.of(new ThreadInterruptedException("interrupted"),
          new TimeoutException("timeout"), new SessionNotActivatedException(databaseName))) {
        var failure = new IllegalStateException(cause);
        try (var injected = KnownEndpointExistsStep.injectFailure(stage, failure)) {
          if (stage.equals("native plan")) {
            assertThatThrownBy(() -> plan(sql(target, "", false))).isSameAs(failure);
          } else {
            var plan = plan(sql(target, "", false));
            try {
              assertThatThrownBy(plan::start).as(stage).isSameAs(failure);
              assertThat(nativeStep(plan).path()).isEqualTo("not started");
            } finally {
              plan.close();
            }
          }
          assertThat(Thread.currentThread().isInterrupted())
              .isEqualTo(cause instanceof ThreadInterruptedException);
        } finally {
          Thread.interrupted();
        }
      }
    }
  }

  /** A downstream inferred class must not reverse a recursive WHILE across its explicit root. */
  @Test
  public void inferredLeafCannotBecomeRecursiveRoot() throws Exception {
    session.rollback();
    session.createVertexClass("RecursiveRoot");
    var leaf = session.createVertexClass("RecursiveLeaf");
    session.createEdgeClass("RecursiveEdge");
    session.createEdgeClass("RecursiveTail").getProperty("in").setLinkedClass(leaf);
    session.begin();
    var root = session.newVertex("RecursiveRoot");
    root.setProperty("n", 10);
    var child = session.newVertex("RecursiveRoot");
    child.setProperty("n", 20);
    root.addEdge(child, "RecursiveEdge");
    child.addEdge(session.newVertex("RecursiveLeaf"), "RecursiveTail");
    for (int i = 0; i < 8; i++) {
      session.newVertex("RecursiveRoot").setProperty("n", 30 + i);
    }
    session.commit();
    session.begin();
    var plan = plan("MATCH {class:RecursiveRoot,as:r}"
        + ".out('RecursiveEdge'){as:w,while:($depth<2),where:($depth=1)}"
        + ".out('RecursiveTail'){as:l} RETURN r.n as n");
    var rootStep = plan.getSteps().stream().filter(MatchFirstStep.class::isInstance)
        .map(MatchFirstStep.class::cast).findFirst().orElseThrow();
    assertThat(rootStep.getAlias()).isEqualTo("r");
    assertThat(drain(plan).rows).containsExactly(10);
  }

  /** A positive Gremlin hop keeps multiplicity with translation off and reaches SOURCE when on. */
  @Test
  public void positiveGremlinTranslationModesKeepNativeAcceptance() throws Exception {
    session.commit();
    try (var graph = (YTDBGraphInternal) pool.asGraph()) {
      for (boolean translated : List.of(false, true)) {
        graph.tx().open();
        var db = graph.tx().getDatabaseSession();
        var configuration = db.getConfiguration();
        boolean previous = configuration.getValueAsBoolean(
            GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
        configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
            translated);
        try {
          for (var endpoint : List.of(target, sparse)) {
            for (var selected : NativeKnownEndpointStep.Path.values()) {
              try (var forced = NativeKnownEndpointStep.forcePath(selected);
                  var traversal = graph.traversal().V().hasLabel("NativeSource").as("s")
                      .out().hasId(endpoint).select("s")) {
                var admin = traversal.asAdmin();
                admin.applyStrategies();
                var boundaries = admin.getSteps().stream()
                    .filter(YTDBMatchPlanStep.class::isInstance)
                    .map(step -> (YTDBMatchPlanStep<?, ?>) step).toList();
                var rows = traversal.toList().stream()
                    .map(value -> ((org.apache.tinkerpop.gremlin.structure.Vertex) value)
                        .id().toString())
                    .toList();
                assertThat(rows).containsExactlyInAnyOrderElementsOf(endpoint.equals(target)
                    ? List.of(sources.get(0).toString(), sources.get(0).toString(),
                        sources.get(1).toString())
                    : List.of(sources.get(1).toString()));
                assertThat(boundaries).hasSize(translated ? 1 : 0);
                if (translated) {
                  var choice = nativeStep(boundaries.getFirst().getPlan());
                  assertThat(choice).as(boundaries.getFirst().getPlan().prettyPrint(0, 2))
                      .isNotNull();
                  boolean ridStart = selected == NativeKnownEndpointStep.Path.RID
                      || (selected == NativeKnownEndpointStep.Path.AUTO && endpoint.equals(sparse));
                  assertThat(choice.path()).isEqualTo(ridStart ? "rid" : "source");
                  assertThat(choice.reason())
                      .isEqualTo(selected != NativeKnownEndpointStep.Path.AUTO
                          ? "test selector" : endpoint.equals(target) ? "exact count margin"
                              : "degree cannot meet margin");
                  assertThat(properties(choice.toResult(db))).containsOnlyKeys("path", "reason");
                  assertThat(choice.prettyPrint(0, 2)).contains("SOURCE:", "FETCH FROM CLASS")
                      .doesNotContain("scan eliminated", "sourceCount=", "degree=");
                }
              }
            }
          }
        } finally {
          configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
              previous);
          graph.tx().rollback();
        }
      }
    }
    session.activateOnCurrentThread();
    session.begin();
  }

  /** Each translation mode supplies its own forced-RID row and error oracle under READ policies. */
  private void gremlinSecurityParity(String expectedPath, String expectedReason) throws Exception {
    var user = session.getCurrentUserName();
    session.commit();
    try (var graph = (YTDBGraphInternal) pool.asGraph()) {
      for (boolean translated : List.of(false, true)) {
        graph.tx().open();
        var db = graph.tx().getDatabaseSession();
        var configuration = db.getConfiguration();
        boolean previous = configuration.getValueAsBoolean(
            GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
        configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
            translated);
        try {
          List<String> oracleRows = null;
          String oracleError = null;
          for (var selected : List.of(NativeKnownEndpointStep.Path.RID,
              NativeKnownEndpointStep.Path.AUTO, NativeKnownEndpointStep.Path.SOURCE)) {
            try (var forced = NativeKnownEndpointStep.forcePath(selected);
                var traversal = graph.traversal().V().hasLabel("NativeSource").as("s")
                    .out().hasId(target).select("s")) {
              var admin = traversal.asAdmin();
              admin.applyStrategies();
              var boundaries = admin.getSteps().stream()
                  .filter(YTDBMatchPlanStep.class::isInstance)
                  .map(step -> (YTDBMatchPlanStep<?, ?>) step).toList();
              var rows = new ArrayList<String>();
              String error = null;
              try {
                while (traversal.hasNext()) {
                  rows.add(((org.apache.tinkerpop.gremlin.structure.Vertex) traversal.next())
                      .id().toString());
                }
              } catch (RuntimeException failure) {
                error = failure.getClass().getName();
              }
              if (selected == NativeKnownEndpointStep.Path.RID) {
                oracleRows = rows;
                oracleError = error;
              } else {
                assertThat(rows).as("translated=" + translated)
                    .containsExactlyInAnyOrderElementsOf(oracleRows);
                assertThat(error).as("translated=" + translated).isEqualTo(oracleError);
              }
              assertThat(boundaries).hasSize(translated ? 1 : 0);
              if (translated && expectedPath.equals("none")) {
                // Fresh permission-aware estimates select the source root before this choice.
                // The copied SQL template covers denial after a RID-rooted plan is built.
                assertThat(nativeStep(boundaries.getFirst().getPlan())).isNull();
                assertThat(boundaries.getFirst().getPlan().getSteps())
                    .anyMatch(MatchFirstStep.class::isInstance);
              } else if (translated) {
                var choice = nativeStep(boundaries.getFirst().getPlan());
                assertThat(choice).as(boundaries.getFirst().getPlan().prettyPrint(0, 2))
                    .isNotNull();
                assertThat(choice.path()).isEqualTo(
                    selected == NativeKnownEndpointStep.Path.RID ? "rid" : expectedPath);
                assertThat(choice.reason()).isEqualTo(selected == NativeKnownEndpointStep.Path.RID
                    || (selected == NativeKnownEndpointStep.Path.SOURCE
                        && expectedPath.equals("source")) ? "test selector" : expectedReason);
              }
            }
          }
        } finally {
          configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
              previous);
          graph.tx().rollback();
        }
      }
    }
    session.activateOnCurrentThread();
    // Graph close owns its pool. Reacquire it for later security cases in this fixture.
    pool = youTrackDB.cachedPool(databaseName, user,
        user.equals(adminUser) ? adminPassword : readerPassword);
    session.begin();
  }

  /** Slice membership includes multiplicity, not only set membership. */
  private static void assertValidSlice(Run actual, List<Integer> full, int size) {
    assertThat(actual.error).isNull();
    assertThat(actual.rows).hasSize(size);
    var remaining = new ArrayList<>(full);
    for (var row : actual.rows) {
      assertThat(remaining.remove(row)).as("row %s exceeds the full multiset %s", row, full)
          .isTrue();
    }
  }

  private Run assertFallback(String reason) throws Exception {
    var oracle = execute(NativeKnownEndpointStep.Path.RID, target, "", false);
    Run actual = null;
    for (var selected : List.of(NativeKnownEndpointStep.Path.AUTO,
        NativeKnownEndpointStep.Path.SOURCE)) {
      actual = execute(selected, target, "", false);
      assertThat(actual.path).as(actual.plan).isEqualTo("rid");
      assertThat(actual.reason).isEqualTo(reason);
      assertThat(actual.rows).isEqualTo(oracle.rows);
      assertThat(actual.error).isEqualTo(oracle.error);
    }
    return actual;
  }

  private Run parity(RecordIdInternal endpoint, String filter, boolean edgeRecords)
      throws Exception {
    var rid = execute(NativeKnownEndpointStep.Path.RID, endpoint, filter, edgeRecords);
    var source = execute(NativeKnownEndpointStep.Path.SOURCE, endpoint, filter, edgeRecords);
    var auto = execute(NativeKnownEndpointStep.Path.AUTO, endpoint, filter, edgeRecords);
    assertThat(rid.path).as(rid.plan).isEqualTo("rid");
    assertThat(source.path).as(source.reason + source.plan).isEqualTo("source");
    assertThat(rid.error).as(rid.plan).isNull();
    assertThat(source.error).as(source.plan).isNull();
    assertThat(auto.error).as(auto.plan).isNull();
    assertThat(source.rows).containsExactlyInAnyOrderElementsOf(rid.rows);
    assertThat(auto.rows).containsExactlyInAnyOrderElementsOf(rid.rows);
    return auto;
  }

  private void policy(String resource, String rule) {
    session.commit();
    session.begin();
    var security = session.getSharedContext().getSecurity();
    var policy = security.createSecurityPolicy(session, "nativePolicy");
    policy.setActive(true);
    policy.setReadRule(rule);
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session, security.getRole(session, "reader"), resource, policy);
    session.commit();
    reOpen(readerUser, readerPassword);
    session.begin();
  }

  private String sql(RecordIdInternal endpoint, String filter, boolean edgeRecords) {
    return "MATCH {class:NativeSource,as:s" + (filter.isEmpty() ? "" : ",where:(" + filter + ")")
        + "}." + (edgeRecords ? "outE('NativeLink'){as:e}.inV()" : "out('NativeLink')")
        + "{class:NativeTarget,as:t,where:(@rid=" + endpoint + ")} RETURN s.n as n";
  }

  private SelectExecutionPlan plan(String sql) throws Exception {
    return plan(sql, Map.of());
  }

  private SelectExecutionPlan plan(String sql, Map<Object, Object> parameters) throws Exception {
    var statement = (SQLMatchStatement) new YouTrackDBSql(new ByteArrayInputStream(
        sql.getBytes(StandardCharsets.UTF_8))).parse();
    return (SelectExecutionPlan) statement.createExecutionPlanNoCache(context(parameters), true);
  }

  private BasicCommandContext context() {
    return context(Map.of());
  }

  private BasicCommandContext context(Map<Object, Object> parameters) {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    context.setInputParameters(new HashMap<>(parameters));
    return context;
  }

  private static NativeKnownEndpointStep nativeStep(InternalExecutionPlan plan) {
    return plan.getSteps().stream().filter(NativeKnownEndpointStep.class::isInstance)
        .map(NativeKnownEndpointStep.class::cast).findFirst().orElse(null);
  }

  private Run execute(NativeKnownEndpointStep.Path selected, RecordIdInternal endpoint,
      String filter, boolean edgeRecords) throws Exception {
    try (var forced = NativeKnownEndpointStep.forcePath(selected)) {
      return drain(securedTemplate == null ? plan(sql(endpoint, filter, edgeRecords))
          : (SelectExecutionPlan) securedTemplate.copy(context()));
    }
  }

  private Run executeQuery(NativeKnownEndpointStep.Path selected, String query) throws Exception {
    return executeQuery(selected, query, Map.of());
  }

  private Run executeQuery(NativeKnownEndpointStep.Path selected, String query,
      Map<Object, Object> parameters) throws Exception {
    try (var forced = NativeKnownEndpointStep.forcePath(selected)) {
      return drain(plan(query, parameters));
    }
  }

  private Run drain(SelectExecutionPlan plan) {
    var rows = new ArrayList<Integer>();
    String error = null;
    com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream stream = null;
    try {
      stream = plan.start();
      while (stream.hasNext(plan.getContext())) {
        rows.add(stream.next(plan.getContext()).getProperty("n"));
      }
    } catch (RuntimeException failure) {
      error = failure.getClass().getName();
    } finally {
      if (stream != null) {
        stream.close(plan.getContext());
      }
      plan.close();
    }
    var choice = nativeStep(plan);
    return new Run(rows, error, choice == null ? "none" : choice.path(),
        choice == null ? "none" : choice.reason(), plan.prettyPrint(0, 2),
        choice == null ? Map.of() : properties(choice.toResult(session)));
  }

  private record Run(List<Integer> rows, String error, String path, String reason, String plan,
      Map<String, Object> properties) {
  }

  private static Map<String, Object> properties(Result step) {
    var framework = Set.of("name", "type", "targetNode", "javaType", "cost", "description",
        "subSteps", "subExecutionPlans");
    var result = new LinkedHashMap<String, Object>();
    step.getPropertyNames().stream().filter(key -> !framework.contains(key))
        .forEach(key -> result.put(key, step.getProperty(key)));
    return result;
  }

  private static Result findPublishedNative(Result plan) {
    List<Result> steps = plan.getProperty("steps");
    if (steps == null) {
      return null;
    }
    for (var step : steps) {
      if (NativeKnownEndpointStep.class.getName().equals(step.getProperty("javaType"))) {
        return step;
      }
    }
    return null;
  }
}
