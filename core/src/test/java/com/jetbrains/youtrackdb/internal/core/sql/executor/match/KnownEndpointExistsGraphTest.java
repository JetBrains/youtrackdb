package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Mode-local security oracles and transaction parity for both eligible execution branches. */
@Category(SequentialTest.class)
public class KnownEndpointExistsGraphTest extends DbTestBase {

  private YTDBGraphInternal graph;
  private RecordIdInternal target;
  private RecordIdInternal first;
  private RecordIdInternal second;
  private RecordIdInternal unconnected;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    session.createVertexClass("GraphSource").createProperty("n", PropertyType.INTEGER);
    session.execute("CREATE CLASS GraphSourceSub EXTENDS GraphSource").close();
    session.createVertexClass("GraphTarget");
    session.createEdgeClass("GraphLink");
    session.begin();
    var t = session.newVertex("GraphTarget");
    t.setProperty("visible", true);
    var sources = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex>();
    for (int i = 0; i < 512; i++) {
      var source = session.newVertex("GraphSource");
      source.setProperty("n", i);
      sources.add(source);
    }
    session.commit();
    target = (RecordIdInternal) t.getIdentity();
    var rids = sources.stream().map(v -> (RecordIdInternal) v.getIdentity()).sorted().toList();
    first = rids.get(7);
    second = rids.get(200);
    unconnected = rids.get(300);
    // Use permanent scan order rather than the order of temporary RID allocation.
    session.begin();
    for (int i = 0; i < rids.size(); i++) {
      session.loadVertex(rids.get(i)).setProperty("n", i);
    }
    var endpoint = session.loadVertex(target);
    session.loadVertex(second).addEdge(endpoint, "GraphLink");
    session.loadVertex(first).addEdge(endpoint, "GraphLink");
    session.loadVertex(first).addEdge(endpoint, "GraphLink");
    session.commit();
    graph = (YTDBGraphInternal) pool.asGraph();
  }

  @After
  public void closeGraph() {
    if (graph != null) {
      graph.close();
    }
  }

  /** Parallel edges, arrival order, explicit order and paging agree in both translation modes. */
  @Test
  public void modesAndForcedBranchesPreserveOrderAndPaging() throws Exception {
    for (String shape : List.of("all", "next", "limit", "page", "ordered")) {
      var off = run(false, KnownEndpointExistsStep.Path.SOURCE, target, shape);
      var source = run(true, KnownEndpointExistsStep.Path.SOURCE, target, shape);
      var reverse = run(true, KnownEndpointExistsStep.Path.TARGET, target, shape);
      assertThat(source.rows).as(shape).isEqualTo(off.rows).isNotEmpty();
      assertThat(reverse.rows).as(shape).isEqualTo(off.rows);
      assertThat(reverse.path).as(reverse.plan).isEqualTo("target");
      assertThat(reverse.candidates).isBetween(1L, 2L);
      assertThat(off.boundaries).isZero();
      assertThat(source.boundaries).isEqualTo(1);
      assertThat(reverse.boundaries).isEqualTo(1);
    }
  }

  /** A hidden target keeps each mode's own current result, including the known on/off difference. */
  @Test
  public void hiddenTargetFallsBackToTheModeLocalOracle() throws Exception {
    policy("database.class.GraphTarget", "visible = false");
    useReader();
    assertSecurityParity(true);
    assertThat(run(false, KnownEndpointExistsStep.Path.SOURCE, target, "all").rows).isEmpty();
  }

  /** Discovery cannot add a target policy error that a rejecting source filter never visits. */
  @Test
  public void failingTargetReadPolicyFallsBackBeforeSourceFiltering() throws Exception {
    session.begin();
    session.loadVertex(target).setProperty("denominator", 0);
    session.commit();
    policy("database.class.GraphTarget", "1 / denominator = 1");
    useReader();
    for (boolean translated : List.of(false, true)) {
      var baseline = run(translated, KnownEndpointExistsStep.Path.SOURCE, target, "negative");
      assertThat(baseline.rows).isEmpty();
      assertThat(baseline.error).as(baseline.plan).isNull();
      for (var path : KnownEndpointExistsStep.Path.values()) {
        var actual = run(translated, path, target, "negative");
        assertThat(actual.rows).isEqualTo(baseline.rows);
        assertThat(actual.error).as(actual.plan).isNull();
        assertThat(graphSession().isTxActive()).isTrue();
        if (translated) {
          assertThat(actual.path).as(actual.plan).isEqualTo("source");
          assertThat(actual.reason).isEqualTo(path == KnownEndpointExistsStep.Path.SOURCE
              ? "test selector" : "target attempt failed: SecurityException");
          assertThat(actual.candidates).isZero();
          assertThat(actual.edgeReads).isZero();
        }
      }
      var visited = run(translated, KnownEndpointExistsStep.Path.SOURCE, target, "all");
      assertThat(visited.error)
          .isEqualTo(com.jetbrains.youtrackdb.internal.core.exception.SecurityException.class
              .getName());
      for (var path : KnownEndpointExistsStep.Path.values()) {
        var actual = run(translated, path, target, "all");
        assertThat(actual.rows).isEqualTo(visited.rows);
        assertThat(actual.error).as(actual.plan).isEqualTo(visited.error);
        if (translated) {
          assertThat(actual.path).isEqualTo("source");
          assertThat(actual.reason).isEqualTo(path == KnownEndpointExistsStep.Path.SOURCE
              ? "test selector" : "target attempt failed: SecurityException");
        }
      }
    }
  }

  /**
   * A failing second candidate stays unguarded after the first row. With and without LIMIT, each
   * path must preserve the emitted rows and exception type of SOURCE in the same translation mode.
   */
  @Test
  public void candidatePolicyErrorsAfterTheFirstRowKeepScanParity() throws Exception {
    session.begin();
    session.loadVertex(second).setProperty("d", 0);
    session.commit();
    policy("database.class.GraphSource", "d IS NULL OR 1 / d = 1");
    useReader();
    for (boolean translated : List.of(false, true)) {
      for (var shape : List.of("all", "limit")) {
        var baseline = run(translated, KnownEndpointExistsStep.Path.SOURCE, target, shape);
        var baselineScenario = "SOURCE, translated=" + translated + ", shape=" + shape;
        assertThat(baseline.rows).as(baselineScenario + "\n" + baseline.plan).isNotEmpty();
        // Full consumption must reach the failing second candidate after emitting a row.
        // A translated LIMIT stops before that read. Native traversal can read ahead.
        if (translated && shape.equals("limit")) {
          assertThat(baseline.error).as(baselineScenario + "\n" + baseline.plan).isNull();
        } else {
          assertThat(baseline.error).as(baselineScenario + "\n" + baseline.plan)
              .isEqualTo(com.jetbrains.youtrackdb.internal.core.exception.SecurityException.class
                  .getName());
        }
        for (var path : List.of(KnownEndpointExistsStep.Path.AUTO,
            KnownEndpointExistsStep.Path.TARGET)) {
          var actual = run(translated, path, target, shape);
          var scenario = "translated=" + translated + ", shape=" + shape + ", path=" + path;
          assertThat(actual.rows).as(scenario + "\n" + actual.plan).isEqualTo(baseline.rows);
          assertThat(actual.error).as(scenario + "\n" + actual.plan).isEqualTo(baseline.error);
          if (translated) {
            assertThat(actual.path).isEqualTo("target");
            assertThat(actual.reason).isEqualTo(path == KnownEndpointExistsStep.Path.AUTO
                ? "cost and first-row margin" : "test selector");
          }
        }
      }
    }
  }

  /** The unreadable candidate sorts first. The later visible source must still be returned. */
  @Test
  public void hiddenSourceDoesNotTruncateTheCandidateStream() throws Exception {
    policy("database.class.GraphSource", "n <> 7");
    useReader();
    assertSecurityParity(false);
    var reverse = run(true, KnownEndpointExistsStep.Path.TARGET, target, "all");
    assertThat(reverse.rows).containsExactly(second.toString());
    assertThat(reverse.path).isEqualTo("target");
    assertThat(reverse.candidates).isEqualTo(1);
  }

  /** LinkBag vertex traversal compares identities without loading hidden edge records. */
  @Test
  public void hiddenEdgesKeepTheCurrentLinkBagBehavior() throws Exception {
    policy("database.class.GraphLink", "false");
    useReader();
    assertSecurityParity(false);
    assertThat(run(true, KnownEndpointExistsStep.Path.TARGET, target, "all").rows).hasSize(2);
  }

  /** A property policy on a forward edge-list field forces fallback even for a labeled hop. */
  @Test
  public void hiddenForwardEdgeFieldForcesFallback() throws Exception {
    policy("database.class.GraphSource.out_GraphLink", "false");
    useReader();
    assertSecurityParity(true);
  }

  /** A property policy on the reverse field also forces fallback before adjacency discovery. */
  @Test
  public void hiddenReverseEdgeFieldForcesFallback() throws Exception {
    policy("database.class.GraphTarget.in_GraphLink", "false");
    useReader();
    assertSecurityParity(true);
  }

  /** Catch-all policies use the same field resolution as enforcement, not the property index. */
  @Test
  public void catchAllFieldPolicyForcesFallback() throws Exception {
    session.begin();
    var security = session.getSharedContext().getSecurity();
    var role = security.createRole(session, "endpointCustom");
    for (var resource : List.of(ResourceGeneric.DATABASE, ResourceGeneric.SCHEMA,
        ResourceGeneric.COLLECTION, ResourceGeneric.CLASS, ResourceGeneric.COMMAND)) {
      role.grant(session, resource, null, Role.PERMISSION_READ);
    }
    role.save(session);
    var allow = security.createSecurityPolicy(session, "endpointAllowVertices");
    allow.setActive(true);
    allow.setReadRule("true");
    security.saveSecurityPolicy(session, allow);
    security.setSecurityPolicy(session, role, "database.class.V", allow);
    var deny = security.createSecurityPolicy(session, "endpointDenyFields");
    deny.setActive(true);
    deny.setReadRule("false");
    security.saveSecurityPolicy(session, deny);
    security.setSecurityPolicy(session, role, "*", deny);
    security.createUser(session, "endpointCustomUser", "custompwd",
        new String[] {"endpointCustom"});
    session.commit();
    useUser("endpointCustomUser", "custompwd");
    for (boolean translated : List.of(false, true)) {
      var baseline = run(translated, KnownEndpointExistsStep.Path.SOURCE, target, "all");
      var reverse = run(translated, KnownEndpointExistsStep.Path.TARGET, target, "all");
      assertThat(baseline.error).as(baseline.plan).isNull();
      assertThat(reverse.error).as(reverse.plan).isNull();
      assertThat(reverse.rows).isEqualTo(baseline.rows);
      if (translated) {
        assertThat(reverse.path).as(reverse.plan).isEqualTo("source");
        assertThat(reverse.reason).as(reverse.plan).isEqualTo("edge-list read policy");
        assertThat(reverse.candidates).isZero();
        assertThat(reverse.edgeReads).isZero();
      }
    }
  }

  /** A connected class-denied subclass is skipped while its parent and collections stay readable. */
  @Test
  public void deniedSubclassClassDoesNotExposeCandidateCounts() throws Exception {
    session.begin();
    var sub = session.newVertex("GraphSourceSub");
    sub.setProperty("n", 999);
    sub.addEdge(session.loadVertex(target), "GraphLink");
    session.commit();
    session.begin();
    var role = session.getMetadata().getSecurity().getRole("reader");
    role.revoke(session, ResourceGeneric.CLASS, "GraphSourceSub", Role.PERMISSION_READ);
    role.save(session);
    session.commit();
    useReader();
    var db = graphSession();
    var configuration = db.getConfiguration();
    boolean previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, true);
    try {
      for (var collection : db.getMetadata().getSchema().getClass("GraphSourceSub")
          .getCollectionIds()) {
        db.checkSecurity(ResourceGeneric.COLLECTION, Role.PERMISSION_READ,
            db.getCollectionNameById(collection));
      }
      for (boolean translated : List.of(false, true)) {
        var baseline = run(translated, KnownEndpointExistsStep.Path.SOURCE, target, "all");
        var reverse = run(translated, KnownEndpointExistsStep.Path.TARGET, target, "all");
        assertThat(baseline.error).as(baseline.plan).isNull();
        assertThat(reverse.error).as(reverse.plan).isNull();
        assertThat(baseline.rows).containsExactly(first.toString(), second.toString());
        assertThat(reverse.rows).isEqualTo(baseline.rows);
        assertThat(baseline.candidates).isZero();
        assertThat(reverse.candidates).isEqualTo(translated ? 2 : 0);
        assertThat(reverse.path).isEqualTo(translated ? "target" : "none");
      }
    } finally {
      configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }

  /** A denied subclass collection preserves the source scan's permission error in both modes. */
  @Test
  public void deniedSubclassCollectionKeepsTheCurrentPermissionBehavior() throws Exception {
    session.begin();
    var clazz = session.getMetadata().getSchema().getClass("GraphSourceSub");
    var role = session.getMetadata().getSecurity().getRole("reader");
    for (var collection : clazz.getCollectionIds()) {
      role.revoke(session, ResourceGeneric.COLLECTION, session.getCollectionNameById(collection),
          Role.PERMISSION_READ);
    }
    role.save(session);
    session.commit();
    useReader();
    assertSecurityParity(true);
  }

  /** A collection-denied known target cannot introduce a permission error into RID-only EXISTS. */
  @Test
  public void deniedTargetCollectionKeepsTheCurrentPermissionBehavior() throws Exception {
    session.begin();
    var role = session.getMetadata().getSecurity().getRole("reader");
    role.revoke(session, ResourceGeneric.COLLECTION,
        session.getCollectionNameById(target.getCollectionId()), Role.PERMISSION_READ);
    role.save(session);
    session.commit();
    useReader();
    assertSecurityParity(true);
  }

  /** Class-denied sources are skipped by secured reads instead of being exposed as candidates. */
  @Test
  public void deniedSourceClassCannotExposeCandidateCounts() throws Exception {
    session.begin();
    var role = session.getMetadata().getSecurity().getRole("reader");
    role.revoke(session, ResourceGeneric.CLASS, "GraphSource", Role.PERMISSION_READ);
    role.save(session);
    session.commit();
    useReader();
    for (boolean translated : List.of(false, true)) {
      var baseline = run(translated, KnownEndpointExistsStep.Path.SOURCE, target, "all");
      var reverse = run(translated, KnownEndpointExistsStep.Path.TARGET, target, "all");
      assertThat(reverse.rows).isEqualTo(baseline.rows).isEmpty();
      assertThat(reverse.error).isEqualTo(baseline.error);
      // The permission-aware zero count makes this a prefetched, ineligible source start.
      assertThat(reverse.path).as(reverse.plan).isEqualTo("none");
      assertThat(reverse.candidates).isZero();
      assertThat(reverse.boundaries).isEqualTo(translated ? 1 : 0);
    }
  }

  /** A stored reverse LINKLIST requires fallback even when its current declaration is LINKBAG. */
  @Test
  public void actualStorageFormOverridesTheClassDeclaration() throws Exception {
    ((SchemaClassInternal) session.getMetadata().getSchema().getClass("GraphTarget"))
        .createProperty("in_GraphLink", PropertyTypeInternal.LINKLIST, (SchemaClass) null, true);
    session.begin();
    var vertex = (EntityImpl) session.loadVertex(target);
    var edges =
        new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable>();
    session.loadVertex(first).getEdges(Direction.OUT, "GraphLink").forEach(edges::add);
    session.loadVertex(second).getEdges(Direction.OUT, "GraphLink").forEach(edges::add);
    vertex.removePropertyInternal("in_GraphLink");
    vertex.setPropertyInternal("in_GraphLink", new EntityLinkListImpl(vertex, edges),
        com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal.LINKLIST);
    session.commit();
    session.execute("DROP PROPERTY GraphTarget.in_GraphLink").close();
    // Unsafe schema creation changes metadata without rewriting the persisted field.
    ((SchemaClassInternal) session.getMetadata().getSchema().getClass("GraphTarget"))
        .createProperty("in_GraphLink", PropertyTypeInternal.LINKBAG, (SchemaClass) null, true);
    var db = graphSession();
    var stored = (EntityImpl) db.loadVertex(target);
    assertThat((Object) stored.getPropertyInternal("in_GraphLink"))
        .isInstanceOf(EntityLinkListImpl.class);
    assertThat(stored.getImmutableSchemaClass(db).getProperty("in_GraphLink").getType())
        .isEqualTo(PropertyType.LINKBAG);
    assertSecurityParity(true);
    graph.tx().rollback();
    policy("database.class.GraphLink", "false");
    useReader();
    assertSecurityParity(true);
    assertThat(run(true, KnownEndpointExistsStep.Path.SOURCE, target, "all").rows).hasSize(2);
  }

  /** Every transaction edit is checked before commit against both modes and both forced branches. */
  @Test
  public void transactionChangesKeepTheSameSources() throws Exception {
    for (String scenario : List.of("target-created", "source-created", "unsaved-edge",
        "moved-source", "moved-target", "deleted-edge", "deleted-source", "deleted-target",
        "missing-target")) {
      var db = graphSession();
      var endpoints = new ArrayList<RecordIdInternal>();
      RecordIdInternal endpoint = target;
      var expected = new ArrayList<>(List.of(first.toString(), second.toString()));
      try {
        switch (scenario) {
          case "target-created" -> {
            var vertex = db.newVertex("GraphTarget");
            db.loadVertex(first).addEdge(vertex, "GraphLink");
            endpoint = (RecordIdInternal) vertex.getIdentity();
            expected = new ArrayList<>(List.of(first.toString()));
          }
          case "source-created" -> {
            var vertex = db.newVertex("GraphSource");
            vertex.setProperty("n", 999);
            vertex.addEdge(db.loadVertex(target), "GraphLink");
            expected.add(vertex.getIdentity().toString());
          }
          case "unsaved-edge" -> {
            db.loadVertex(unconnected).addEdge(db.loadVertex(target), "GraphLink");
            expected.add(unconnected.toString());
          }
          case "moved-source" -> {
            var edge = db.loadVertex(second).getEdges(Direction.OUT, "GraphLink").iterator().next();
            db.execute("UPDATE EDGE " + edge.getIdentity() + " SET out = " + unconnected).close();
            expected = new ArrayList<>(List.of(first.toString(), unconnected.toString()));
          }
          case "moved-target" -> {
            var newTarget = db.newVertex("GraphTarget");
            var edge = db.loadVertex(second).getEdges(Direction.OUT, "GraphLink").iterator().next();
            db.execute("UPDATE EDGE " + edge.getIdentity() + " SET in = " + newTarget.getIdentity())
                .close();
            endpoints.add((RecordIdInternal) newTarget.getIdentity());
            expected = new ArrayList<>(List.of(first.toString()));
          }
          case "deleted-edge" -> {
            db.loadVertex(second).getEdges(Direction.OUT, "GraphLink").iterator().next().delete();
            expected = new ArrayList<>(List.of(first.toString()));
          }
          case "deleted-source" -> {
            db.loadVertex(first).delete();
            expected = new ArrayList<>(List.of(second.toString()));
          }
          case "deleted-target" -> {
            db.loadVertex(target).delete();
            expected.clear();
          }
          case "missing-target" -> {
            endpoint =
                RecordIdInternal.fromString("#" + target.getCollectionId() + ":999999", false);
            expected.clear();
          }
          default -> throw new AssertionError(scenario);
        }
        endpoints.addFirst(endpoint);
        for (var queried : endpoints) {
          var expectedRows = queried.equals(endpoint) ? expected : List.of(second.toString());
          for (boolean translated : List.of(false, true)) {
            var source = run(translated, KnownEndpointExistsStep.Path.SOURCE, queried, "all");
            var reverse = run(translated, KnownEndpointExistsStep.Path.TARGET, queried, "all");
            assertThat(source.error).as(scenario + " " + source.plan).isNull();
            assertThat(reverse.error).as(scenario + " " + reverse.plan).isNull();
            assertThat(source.rows).as(scenario).containsExactlyInAnyOrderElementsOf(expectedRows);
            assertThat(reverse.rows).as(scenario + " translated=" + translated)
                .isEqualTo(source.rows);
            boolean fallback =
                scenario.equals("deleted-target") || scenario.equals("missing-target");
            assertThat(source.path).as(scenario).isEqualTo(translated ? "source" : "none");
            assertThat(reverse.path).as(scenario)
                .isEqualTo(translated ? (fallback ? "source" : "target") : "none");
          }
        }
      } finally {
        graph.tx().rollback();
      }
    }
    // A middle-vertex policy is not applicable to a one-hop check.
  }

  /** Subclass sources and edges preserve polymorphic and exact-label filtering in both modes. */
  @Test
  public void sourceAndEdgeSubclassesKeepTheirExistingLabelRules() throws Exception {
    session.execute("CREATE CLASS GraphLinkSub EXTENDS GraphLink").close();
    session.begin();
    var sub = session.newVertex("GraphSourceSub");
    sub.setProperty("n", 999);
    sub.addEdge(session.loadVertex(target), "GraphLinkSub");
    session.commit();
    var config = graphSession().getConfiguration();
    boolean previous = config.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    try {
      for (boolean polymorphic : List.of(false, true)) {
        config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, polymorphic);
        var nativeRun = run(false, KnownEndpointExistsStep.Path.SOURCE, target, "all");
        var source = run(true, KnownEndpointExistsStep.Path.SOURCE, target, "all");
        var reverse = run(true, KnownEndpointExistsStep.Path.TARGET, target, "all");
        assertThat(source.rows).isEqualTo(nativeRun.rows).hasSize(polymorphic ? 3 : 2);
        assertThat(reverse.rows).isEqualTo(source.rows);
        assertThat(reverse.path).as(reverse.plan).isEqualTo("target");
        assertThat(reverse.boundaries).isEqualTo(1);
      }
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }

  /** Reverse IN and BOTH gather the same scan-ordered sources as their forward checks. */
  @Test
  public void incomingAndBidirectionalChecksKeepScanOrder() throws Exception {
    var db = graphSession();
    db.loadVertex(target).addEdge(db.loadVertex(second), "GraphLink");
    db.loadVertex(target).addEdge(db.loadVertex(first), "GraphLink");
    for (String direction : List.of("in", "both")) {
      for (String shape : List.of("all", "next", "limit", "page", "ordered")) {
        var nativeRun = run(false, KnownEndpointExistsStep.Path.SOURCE, target, shape, direction);
        var source = run(true, KnownEndpointExistsStep.Path.SOURCE, target, shape, direction);
        var reverse = run(true, KnownEndpointExistsStep.Path.TARGET, target, shape, direction);
        assertThat(source.rows).as(direction + " " + shape).isEqualTo(nativeRun.rows).isNotEmpty();
        assertThat(reverse.rows).isEqualTo(source.rows);
        assertThat(reverse.path).as(reverse.plan).isEqualTo("target");
      }
    }
    graph.tx().rollback();
  }

  /** A newly created, policy-hidden candidate is skipped with the same read check as the scan. */
  @Test
  public void transactionCreatedSourcesUseTheScanSecurityCheck() throws Exception {
    session.begin();
    session.getMetadata().getSecurity().createUser("endpointWriter", "writerpwd", "writer");
    session.commit();
    // The writer can change graph records, but its source read policy still hides the new record.
    policy("database.class.GraphSource", "n <> 999", true);
    useUser("endpointWriter", "writerpwd");
    var db = graphSession();
    var hidden = db.newVertex("GraphSource");
    hidden.setProperty("n", 999);
    hidden.addEdge(db.loadVertex(target), "GraphLink");
    for (boolean translated : List.of(false, true)) {
      var source = run(translated, KnownEndpointExistsStep.Path.SOURCE, target, "all");
      var reverse = run(translated, KnownEndpointExistsStep.Path.TARGET, target, "all");
      assertThat(source.error).as(source.plan).isNull();
      assertThat(reverse.error).as(reverse.plan).isNull();
      assertThat(reverse.rows).isEqualTo(source.rows).hasSize(2);
      if (translated) {
        assertThat(reverse.path).as(reverse.plan).isEqualTo("target");
        assertThat(reverse.candidates).isEqualTo(2);
      }
    }
    graph.tx().rollback();
  }

  private void policy(String resource, String read) {
    policy(resource, read, false);
  }

  private void policy(String resource, String read, boolean allowWrites) {
    session.begin();
    var security = session.getSharedContext().getSecurity();
    var policy = security.createSecurityPolicy(session, "endpointPolicy");
    policy.setActive(true);
    policy.setReadRule(read);
    if (allowWrites) {
      policy.setCreateRule("true");
      policy.setBeforeUpdateRule("true");
      policy.setAfterUpdateRule("true");
    }
    security.saveSecurityPolicy(session, policy);
    security.setSecurityPolicy(session,
        security.getRole(session, allowWrites ? "writer" : "reader"),
        resource, policy);
    session.commit();
  }

  private void useReader() {
    useUser(readerUser, readerPassword);
  }

  private void useUser(String user, String password) {
    graph.close();
    pool = youTrackDB.cachedPool(databaseName, user, password);
    graph = (YTDBGraphInternal) pool.asGraph();
  }

  private DatabaseSessionEmbedded graphSession() {
    if (!graph.tx().isOpen()) {
      graph.tx().open();
    }
    return graph.tx().getDatabaseSession();
  }

  private void assertSecurityParity(boolean fallback) throws Exception {
    for (boolean translated : List.of(false, true)) {
      var baseline = run(translated, KnownEndpointExistsStep.Path.SOURCE, target, "all");
      var reverse = run(translated, KnownEndpointExistsStep.Path.TARGET, target, "all");
      assertThat(reverse.rows).as("translation=" + translated).isEqualTo(baseline.rows);
      assertThat(reverse.error).as("translation=" + translated).isEqualTo(baseline.error);
      if (translated && reverse.error == null) {
        assertThat(reverse.path).as(reverse.plan).isEqualTo(fallback ? "source" : "target");
        if (fallback) {
          assertThat(reverse.candidates).isZero();
        }
      }
    }
  }

  private record Run(List<String> rows, String error, String path, String reason, long candidates,
      long edgeReads, int boundaries, String plan) {
  }

  private Run run(boolean translated, KnownEndpointExistsStep.Path path, RecordIdInternal endpoint,
      String shape) throws Exception {
    return run(translated, path, endpoint, shape, "out");
  }

  private Run run(boolean translated, KnownEndpointExistsStep.Path path, RecordIdInternal endpoint,
      String shape, String direction) throws Exception {
    var db = graphSession();
    var configuration = db.getConfiguration();
    boolean previous = configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
        translated);
    AutoCloseable cleanup = null;
    try (var forced = KnownEndpointExistsStep.forcePath(path)) {
      var hop = switch (direction) {
        case "out" -> __.out("GraphLink");
        case "in" -> __.in("GraphLink");
        case "both" -> __.both("GraphLink");
        default -> throw new AssertionError(direction);
      };
      var traversal = graph.traversal().V().hasLabel("GraphSource");
      if (shape.equals("negative")) {
        traversal.has("n", P.lt(0));
      }
      traversal.where(hop.hasId(endpoint));
      cleanup = traversal;
      if (shape.equals("ordered")) {
        traversal.order().by("n", Order.desc);
      }
      if (shape.equals("page")) {
        traversal.skip(1);
      }
      if (shape.equals("page") || shape.equals("limit") || shape.equals("ordered")) {
        traversal.limit(1);
      }
      var admin = traversal.asAdmin();
      admin.applyStrategies();
      var boundaries =
          admin.getSteps().stream().filter(YTDBMatchPlanStep.class::isInstance).toList();
      var rows = new ArrayList<String>();
      String error = null;
      String errorDetail = "";
      try {
        if (shape.equals("next")) {
          rows.add(traversal.next().id().toString());
        } else {
          // Keep rows already emitted when a later secured read fails.
          while (traversal.hasNext()) {
            rows.add(traversal.next().id().toString());
          }
        }
      } catch (RuntimeException failure) {
        error = failure.getClass().getName();
        errorDetail = failure.toString();
      }
      var chosen = boundaries.isEmpty() ? null
          : ((YTDBMatchPlanStep<?, ?>) boundaries.getFirst()).getPlan().getSteps().stream()
              .filter(KnownEndpointExistsStep.class::isInstance)
              .map(KnownEndpointExistsStep.class::cast).findFirst().orElse(null);
      var result = new Run(rows, error, chosen == null ? "none" : chosen.counters().path,
          chosen == null ? "none" : chosen.counters().reason,
          chosen == null ? 0 : chosen.counters().candidates,
          chosen == null ? 0 : chosen.counters().edgeReads, boundaries.size(),
          (boundaries.isEmpty() ? admin.toString()
              : ((YTDBMatchPlanStep<?, ?>) boundaries.getFirst()).getPlan().prettyPrint(0, 2))
              + "\n" + errorDetail);
      return result;
    } finally {
      try {
        if (cleanup != null) {
          cleanup.close();
        }
      } finally {
        configuration.setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED,
            previous);
      }
    }
  }
}
