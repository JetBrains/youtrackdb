package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactory;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Checks the invocation data and mutable objects on actual Gremlin cache publication paths. */
@Category(SequentialTest.class)
public class RuntimeRidStartPublicationTest extends GraphBaseTest {

  private DatabaseSessionEmbedded db(YTDBGraph current) {
    var tx = (YTDBTransaction) current.tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private YTDBGraph readerGraph() {
    var config = getBaseConfiguration();
    config.setProperty(YTDBGraphFactory.CONFIG_USER_NAME, readerUser);
    config.setProperty(YTDBGraphFactory.CONFIG_USER_PWD, readerPassword);
    config.setProperty(YTDBGraphFactory.CONFIG_CREATE_IF_NOT_EXISTS, false);
    return (YTDBGraph) GraphFactory.open(config);
  }

  /** Excluded root and child shapes never send a runtime descriptor to the real planner. */
  @Test
  public void excludedShapesAndNestedVStayLiteralThroughStrategies() {
    var a = graph.addVertex(T.label, "NegativePerson", "name", "A");
    var b = graph.addVertex(T.label, "NegativePerson", "name", "B");
    a.addEdge("negativeLink", b);
    b.addEdge("negativeLink", a);
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(db(graph));
    var observed = new ArrayList<MatchPlanInputs>();
    var currentArm = new AtomicInteger();
    cache.beforeUncachedPlanning = (input, context) -> {
      observed.add(input);
      assertThat(input.runtimeRidStarts()).as("negative arm %s", currentArm.get()).isEmpty();
    };
    try {
      for (int arm = 0; arm < 17; arm++) {
        cache.invalidate();
        observed.clear();
        currentArm.set(arm);
        var query = negative(arm, a.id(), b.id(), false).asAdmin();
        if (arm == 11 || arm == 12) {
          // Inspect the grammar before provider optimizations remove the explicit barrier.
          query.setStrategies(query.getStrategies().clone());
          query.getStrategies().removeStrategies(GremlinToMatchStrategy.class);
          GremlinToMatchStrategy.instance().apply(query);
        }
        query.applyStrategies();
        var translatedRows = TranslatorEquivalenceSupport.sortedIdsOrValues(query.toList());
        var nativeQuery = negative(arm, a.id(), b.id(), true).asAdmin();
        nativeQuery.applyStrategies();
        assertThat(TranslatorEquivalenceSupport.countBoundarySteps(nativeQuery)).isZero();
        assertThat(translatedRows).as("negative arm %s", arm)
            .isEqualTo(TranslatorEquivalenceSupport.sortedIdsOrValues(nativeQuery.toList()));
        // Any literal RID plan may translate, but it must never publish a runtime source.
        assertThat(observed).allSatisfy(input -> assertThat(input.runtimeRidStarts()).isEmpty());
      }
    } finally {
      cache.beforeUncachedPlanning = null;
    }
  }

  private GraphTraversal<?, ?> negative(int arm, Object a, Object b, boolean nativeOnly) {
    var g = nativeOnly ? graph.traversal().withoutStrategies(GremlinToMatchStrategy.class)
        : graph.traversal();
    return switch (arm) {
      case 0 -> g.V(a, b).out("negativeLink");
      case 1 -> g.V().hasId(P.within(a)).out("negativeLink");
      case 2 -> g.V(a).hasId(b).out("negativeLink");
      case 3 -> g.V(a).out("negativeLink").hasId(b);
      case 4 -> g.V(a).hasLabel("NegativePerson", "V").out("negativeLink");
      case 5 -> g.V(a).has("name", "A").out("negativeLink");
      case 6 -> g.V(a).out("negativeLink").has("name", "B");
      case 7 -> g.V(a).out("negativeLink").values("name", "other");
      case 8 -> g.V(a).out("negativeLink").out("negativeLink");
      case 9 -> g.V(a).outE("negativeLink");
      case 10 -> g.V(a).out("negativeLink").order().by("name").limit(1);
      case 11 -> g.V(a).barrier(1).as("kept").out("negativeLink");
      case 12 -> g.V(a).out("negativeLink").barrier(1).as("kept");
      case 13 -> g.V(a).union(__.out("negativeLink"), __.in("negativeLink")).count();
      case 14 -> g.V(a).where(__.out("negativeLink").hasId(b)).out("negativeLink");
      case 15 -> g.V(b).map(__.V(a).out("negativeLink"));
      default -> g.V(b).group().by(__.V(a).out("negativeLink").values("name"));
    };
  }

  /** Runtime shape keys omit RID values while preserving structural class, mode and hop facts. */
  @Test
  public void runtimeShapeKeysSeparateLiteralAndStructuralVariants() {
    graph.addVertex(T.label, "KeyPerson");
    graph.tx().commit();
    var session = db(graph);
    var a = new RecordId(25, 7);
    var b = new RecordId(25, 8);
    for (boolean hasId : List.of(false, true)) {
      var keys = new ArrayList<String>();
      for (var id : List.of(a, b, a.toString())) {
        var query = (hasId ? graph.traversal().V().hasId(id) : graph.traversal().V(id))
            .hasLabel("KeyPerson").out("keyLink").asAdmin();
        var decision = RuntimeRidStartEligibility.evaluate(query, true);
        var nulls = YTDBStrategyUtil.orderByNullsPlacements(query);
        var runtime = GremlinStepWalker.extractShape(query, session, false, nulls, true, decision);
        assertThat(runtime.bindings()).isEqualTo(Map.of(0, id.equals(b) ? b : a));
        keys.add(runtime.key());
        assertThat(runtime.key()).contains("3:rrs").doesNotContain(a.toString(), b.toString());
        assertThat(GremlinStepWalker.extractShape(query, session, false, nulls, true, null).key())
            .isNotEqualTo(runtime.key());
        assertThat(
            GremlinStepWalker.extractShape(query, session, false, nulls, false, decision).key())
            .isNotEqualTo(runtime.key());
      }
      assertThat(keys).allSatisfy(key -> assertThat(key).isEqualTo(keys.getFirst()));
      var variants = List.of(graph.traversal().V(a).hasLabel("KeyPerson").in("keyLink"),
          graph.traversal().V(a).hasLabel("KeyPerson").out("rrs"),
          graph.traversal().V(a).out("keyLink"),
          graph.traversal().V(new RecordId(25, -2)).hasLabel("KeyPerson").out("keyLink"),
          graph.traversal().V().hasId(P.within(a)).hasLabel("KeyPerson").out("keyLink"));
      for (var variant : variants) {
        var q = variant.asAdmin();
        var decision = RuntimeRidStartEligibility.evaluate(q, true);
        var key = GremlinStepWalker.extractShape(q, session, false,
            YTDBStrategyUtil.orderByNullsPlacements(q), true, decision).key();
        assertThat(key).isNotEqualTo(keys.getFirst());
      }
    }
  }

  /** Strategy normalization admits equivalent property/RID order but rejects reverse RID ties. */
  @Test
  public void normalizedOrderSpellingsKeepStructuralAdmission() {
    var a = graph.addVertex(T.label, "OrderStart");
    var b = graph.addVertex(T.label, "OrderStart", "name", "B");
    a.addEdge("orderLink", b);
    graph.tx().commit();
    for (int arm = 0; arm < 10; arm++) {
      var source = graph.traversal().withoutStrategies(GremlinToMatchStrategy.class,
          com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBGraphStepStrategy.class);
      if (arm == 6) {
        source = source.withoutStrategies(
            com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization.YTDBOrderRidTieBreakStrategy.class);
      }
      if (arm == 7 || arm == 8) {
        source = source.withStrategies(arm == 7
            ? org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy
                .instance()
            : org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy
                .build().productiveKeys("name").create());
      } else if (arm == 9) {
        source = source.withStrategies(
            org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy
                .instance());
      }
      var order = source.V(a.id()).out("orderLink").order();
      switch (arm) {
        case 0, 6, 7, 8, 9 -> order.by("name");
        case 1 -> order.by("name").by(T.id);
        case 2 -> order.by(__.values("name")).by(__.id());
        case 3 -> order.by("name", org.apache.tinkerpop.gremlin.process.traversal.Order.desc);
        case 4 -> order.by("name", org.apache.tinkerpop.gremlin.process.traversal.Order.desc)
            .by(T.id, org.apache.tinkerpop.gremlin.process.traversal.Order.desc);
        default -> order.by("name").by(T.id,
            org.apache.tinkerpop.gremlin.process.traversal.Order.desc);
      }
      var admin = order.asAdmin();
      admin.applyStrategies();
      var result = RuntimeRidStartEligibility.evaluate(admin, true);
      assertThat(result != null).as("normalized order arm %s: %s", arm, admin).isEqualTo(arm != 5);
      var keys = ((org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep<?,
          ?>) admin.getSteps().getLast()).getComparators();
      assertThat(keys).hasSize(arm == 6 ? 1 : 2);
    }
  }

  /** Three comparators stay literal and uncached on repeated runs through real strategies. */
  @Test
  public void threeComparatorOrderNeverPublishesRuntimeTemplate() {
    var a = graph.addVertex(T.label, "ThreeKeyStart");
    var b = graph.addVertex(T.label, "ThreeKeyTarget", "name", "B", "age", 1);
    a.addEdge("threeKeyLink", b);
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(db(graph));
    cache.invalidate();
    var runs = new AtomicInteger();
    cache.beforeUncachedPlanning = (input, context) -> {
      runs.incrementAndGet();
      assertThat(input.runtimeRidStarts()).isEmpty();
    };
    try {
      for (int attempt = 0; attempt < 2; attempt++) {
        var hits = cache.getHits();
        var translationHits = cache.getTranslationHits();
        var query = graph.traversal().V(a.id()).out("threeKeyLink")
            .order().by("name").by(T.id).by("age");
        assertThat(query.toList()).containsExactly(b);
        assertThat(runs.get()).isEqualTo(attempt + 1);
        assertThat(cache.getHits()).isEqualTo(hits);
        assertThat(cache.getTranslationHits()).isEqualTo(translationHits);
      }
    } finally {
      cache.beforeUncachedPlanning = null;
    }
  }

  /** Admin and reader use the same template, but each live copy checks its own row policy. */
  @Test
  public void readerAndAdminKeepRowPermissionsOnSharedRuntimeHits() throws Exception {
    var allowed = graph.addVertex(T.label, "SecurityPerson", "name", "allowed");
    var denied = graph.addVertex(T.label, "SecurityPerson", "name", "denied");
    var target = graph.addVertex(T.label, "SecurityTarget");
    allowed.addEdge("securityLink", target);
    denied.addEdge("securityLink", target);
    graph.tx().commit();
    var admin = db(graph);
    var security = admin.getSharedContext().getSecurity();
    var policy = security.createSecurityPolicy(admin, "runtimeReadPolicy");
    policy.setActive(true);
    policy.setReadRule("name = 'allowed'");
    security.saveSecurityPolicy(admin, policy);
    security.setSecurityPolicy(admin, security.getRole(admin, "reader"),
        "database.class.SecurityPerson", policy);
    graph.tx().commit();
    var cache = GremlinPlanCache.instance(db(graph));
    var runs = new AtomicInteger();
    cache.beforeUncachedPlanning = (input, context) -> runs.incrementAndGet();
    try (var reader = readerGraph()) {
      assertThat(GremlinPlanCache.instance(db(reader))).isSameAs(cache);
      // Count uses the same row policy, but empty input must emit a scalar zero rather than no row.
      var counted = graph.traversal().V(allowed.id()).out("securityLink").count().asAdmin();
      counted.applyStrategies();
      var countTemplate = ((YTDBMatchPlanStep<?, ?>) counted.getStartStep()).getPlan();
      assertThat(counted.toList()).containsExactly(1L);
      var deniedCount = reader.traversal().V(denied.id()).out("securityLink").count().asAdmin();
      deniedCount.applyStrategies();
      assertThat(((YTDBMatchPlanStep<?, ?>) deniedCount.getStartStep()).getPlan())
          .isSameAs(countTemplate);
      assertThat(deniedCount.toList()).containsExactly(0L);
      assertThat(reader.traversal().withoutStrategies(GremlinToMatchStrategy.class)
          .V(denied.id()).out("securityLink").count().toList()).containsExactly(0L);
      for (boolean readerFirst : List.of(false, true)) {
        cache.invalidate();
        runs.set(0);
        InternalExecutionPlan template = null;
        for (var current : readerFirst ? List.of(reader, graph) : List.of(graph, reader)) {
          for (var id : List.of(allowed.id(), denied.id())) {
            var query = current.traversal().V(id).out("securityLink").asAdmin();
            query.applyStrategies();
            var boundary = (YTDBMatchPlanStep<?, ?>) query.getStartStep();
            if (template == null) {
              template = boundary.getPlan();
            }
            assertThat(boundary.getPlan()).isSameAs(template);
            var rows = TranslatorEquivalenceSupport.sortedIds(query.toList());
            assertThat(rows).hasSize(current == reader && id.equals(denied.id()) ? 0 : 1);
            assertThat(rows).isEqualTo(TranslatorEquivalenceSupport.sortedIds(current.traversal()
                .withoutStrategies(GremlinToMatchStrategy.class).V(id).out("securityLink")
                .toList()));
            assertThat(boundary.getPlan()).isNotSameAs(template);
            assertThat(boundary.getPlan().getContext().getDatabaseSession()).isSameAs(db(current));
            assertThat(boundary.getPlan().getContext().getInputParameters())
                .isEqualTo(Map.of(0, id));
          }
        }
        assertThat(runs.get()).isEqualTo(1);
        assertThat(((BasicCommandContext) template.getContext()).hasLocalVariableKeys()).isFalse();
      }
      reader.tx().rollback();
    } finally {
      cache.beforeUncachedPlanning = null;
    }
  }

  /** Class denial is empty and collection denial propagates on an already shared runtime hit. */
  @Test
  public void classAndCollectionDenialsUseCurrentReaderOnWarmHits() throws Exception {
    for (boolean collection : List.of(false, true)) {
      String label = collection ? "CollectionDeniedPerson" : "ClassDeniedPerson";
      var a = graph.addVertex(T.label, label);
      var b = graph.addVertex(T.label, "DenialTarget");
      a.addEdge("denialLink", b);
      graph.tx().commit();
      var admin = db(graph);
      var resource = collection
          ? com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric.COLLECTION
          : com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric.CLASS;
      var rid = (com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal) a.id();
      var resourceName = collection ? admin.getCollectionNameById(rid.getCollectionId()) : label;
      var role = admin.getMetadata().getSecurity().getRole("reader");
      role.grant(admin, resource, resourceName,
          com.jetbrains.youtrackdb.internal.core.metadata.security.Role.PERMISSION_NONE);
      role.revoke(admin, resource, resourceName,
          com.jetbrains.youtrackdb.internal.core.metadata.security.Role.PERMISSION_READ);
      role.save(admin);
      graph.tx().commit();
      var cache = GremlinPlanCache.instance(db(graph));
      cache.invalidate();
      var runs = new AtomicInteger();
      cache.beforeUncachedPlanning = (input, context) -> runs.incrementAndGet();
      try (var reader = readerGraph()) {
        var warm = graph.traversal().V(a.id()).out("denialLink").asAdmin();
        warm.applyStrategies();
        var template = ((YTDBMatchPlanStep<?, ?>) warm.getStartStep()).getPlan();
        assertThat(warm.toList()).hasSize(1);
        assertThat(GremlinPlanCache.instance(db(reader))).isSameAs(cache);
        var denied = reader.traversal().V(a.id()).out("denialLink").asAdmin();
        denied.applyStrategies();
        var boundary = (YTDBMatchPlanStep<?, ?>) denied.getStartStep();
        assertThat(boundary.getPlan()).isSameAs(template);
        if (collection) {
          org.assertj.core.api.Assertions.assertThatThrownBy(denied::toList)
              .isInstanceOf(
                  com.jetbrains.youtrackdb.internal.core.exception.SecurityException.class);
        } else {
          assertThat(denied.toList()).isEmpty();
        }
        assertThat(boundary.getPlan()).isNotSameAs(template);
        assertThat(runs.get()).isEqualTo(1);
        reader.tx().rollback();
      } finally {
        cache.beforeUncachedPlanning = null;
      }
    }
  }

  private static Object field(Object owner, String name) throws Exception {
    var type = owner.getClass();
    while (type != null) {
      try {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
      } catch (NoSuchFieldException missing) {
        type = type.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }

  /**
   * Cold publication, plan hits and translation hits own immutable RID bindings. Mutating the
   * caller's identity after apply must not change rows. Live plans detach execution state while
   * retaining the captured translator inputs' structural path ASTs and runtime descriptor.
   */
  @Test
  public void publicationAuditPinsOwnedAndSharedObjects() throws Exception {
    var a = graph.addVertex(T.label, "AuditPerson");
    var b = graph.addVertex(T.label, "AuditPerson");
    a.addEdge("auditLink", b);
    b.addEdge("auditLink", a);
    graph.tx().commit();
    var aId = new RecordId((com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal) a.id());
    var bId = new RecordId((com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal) b.id());
    var nativeSource = graph.traversal().withoutStrategies(GremlinToMatchStrategy.class);
    var aRows = TranslatorEquivalenceSupport.sortedIds(nativeSource.V(aId).out("auditLink")
        .toList());
    var bRows = TranslatorEquivalenceSupport.sortedIds(nativeSource.V(bId).out("auditLink")
        .toList());
    assertThat(aRows).hasSize(1).isNotEqualTo(bRows);
    assertThat(bRows).hasSize(1);
    var cache = GremlinPlanCache.instance(db(graph));
    cache.invalidate();
    var inputs = new ArrayList<MatchPlanInputs>();
    cache.beforeUncachedPlanning = (input, context) -> {
      inputs.add(input);
      assertThat(context.getInputParameters()).isNullOrEmpty();
    };
    try {
      InternalExecutionPlan template = null;
      InternalExecutionPlan first = null;
      Object previousBindings = null;
      Object previousRid = null;
      for (int route = 0; route < 3; route++) {
        // Publish once, force a walker/plan hit, then use the published translation directly.
        var id = route == 1 ? bId : aId;
        var changedId = route == 1 ? aId : bId;
        var callerId = new ChangeableRecordId(id);
        var query = graph.traversal().withStrategies(
            GremlinToMatchStrategy.withTranslationCache(route != 1))
            .V(callerId).out("auditLink").asAdmin();
        var planHits = cache.getHits();
        var translationHits = cache.getTranslationHits();
        query.applyStrategies();
        assertThat(cache.getHits()).isEqualTo(planHits + (route == 1 ? 1 : 0));
        assertThat(cache.getTranslationHits())
            .isEqualTo(translationHits + (route == 2 ? 1 : 0));
        assertThat(inputs).hasSize(1);
        var boundary = (YTDBMatchPlanStep<?, ?>) query.getStartStep();
        if (template == null) {
          template = boundary.getPlan();
        }
        assertThat(boundary.getPlan()).isSameAs(template);
        var decisionQuery = graph.traversal().V(id).out("auditLink").asAdmin();
        var decision = RuntimeRidStartEligibility.evaluate(decisionQuery, true);
        var extraction = GremlinStepWalker.extractShape(decisionQuery, db(graph),
            YTDBStrategyUtil.orderIncludesMissingKey(decisionQuery),
            YTDBStrategyUtil.orderByNullsPlacements(decisionQuery),
            YTDBStrategyUtil.isPolymorphic(decisionQuery), decision);
        var publication = (GremlinTranslationTemplate.Translate) GremlinPlanCache
            .getTranslation(extraction.key(), db(graph));
        assertThat(publication.planTemplate()).isSameAs(template);
        assertThat(publication.bindingCount()).isEqualTo(1);
        // Develop's withPropertyGroupCount(null) returns an equal new record.
        var shaping = publication.shaping();
        assertThat(shaping).isEqualTo(
            com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping.NONE);
        assertThat(shaping.propertyGroupCount()).isNull();
        for (var component : List.of(shaping.presencePropertyKeys(),
            shaping.aliasPropertyPresences(), shaping.mapEmitColumnOrder(),
            shaping.recordIdMapKeys(), shaping.edgeMapKeys(), shaping.listShapingOps(),
            shaping.orderedSourceKeyColumns())) {
          assertThat(component).isEmpty();
          org.assertj.core.api.Assertions.assertThatThrownBy(component::clear)
              .isInstanceOf(UnsupportedOperationException.class);
        }
        // Inspect the map held by the executed boundary, not a second shape extraction.
        var bindings = (Map<?, ?>) field(boundary, "inputParameters");
        var boundRid = bindings.get(0);
        assertThat(bindings).isEqualTo(Map.of(0, id)).isNotSameAs(previousBindings);
        assertThat(boundRid).isExactlyInstanceOf(RecordId.class).isNotSameAs(callerId)
            .isNotSameAs(previousRid);
        org.assertj.core.api.Assertions.assertThatThrownBy(bindings::clear)
            .isInstanceOf(UnsupportedOperationException.class);
        callerId.setCollectionAndPosition(changedId.getCollectionId(),
            changedId.getCollectionPosition());
        assertThat(callerId).isEqualTo(changedId).isNotEqualTo(id);
        assertThat(bindings).isEqualTo(Map.of(0, id));
        assertThat(TranslatorEquivalenceSupport.sortedIds(query.toList()))
            .isEqualTo(route == 1 ? bRows : aRows);
        previousBindings = bindings;
        previousRid = boundRid;
        var live = boundary.getPlan();
        assertThat(live).isNotSameAs(template).isNotSameAs(first);
        assertThat(live.getContext()).isNotSameAs(template.getContext());
        assertThat(live.getContext().getParent()).isNull();
        assertThat(live.getContext().getInputParameters()).isEqualTo(Map.of(0, id));
        assertThat(live.getContext().getInputParameters().get(0)).isSameAs(boundRid);
        assertThat(field(boundary, "inputParameters")).isSameAs(bindings);
        var storedSteps = template.getSteps();
        var liveSteps = live.getSteps();
        assertThat(liveSteps).hasSameSizeAs(storedSteps);
        for (int i = 0; i < storedSteps.size(); i++) {
          var storedStep = storedSteps.get(i);
          var liveStep = liveSteps.get(i);
          assertThat(liveStep).isNotSameAs(storedStep);
          if (storedStep instanceof com.jetbrains.youtrackdb.internal.core.sql.executor.match.RuntimeRidStartStep) {
            assertThat(field(liveStep, "start")).isSameAs(field(storedStep, "start"))
                .isSameAs(inputs.getFirst().runtimeRidStarts().values().iterator().next());
          } else if (storedStep instanceof com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchStep) {
            var storedEdge = field(storedStep, "edge");
            var liveEdge = field(liveStep, "edge");
            assertThat(liveEdge).isNotSameAs(storedEdge);
            assertThat(field(liveEdge, "edge")).isSameAs(field(storedEdge, "edge"));
            assertThat(field(liveEdge, "acceptedCollectionIds"))
                .isSameAs(field(storedEdge, "acceptedCollectionIds"));
            assertThat(field(storedEdge, "intersectionDescriptor")).isNull();
            assertThat(field(storedEdge, "semiJoinDescriptor")).isNull();
            assertThat(field(storedEdge, "consumedPredecessor")).isNull();
            // PatternEdge.item includes shared lazy SQLMethodCall resolution. It is not immutable.
            var pattern =
                (com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternEdge) field(
                    storedEdge, "edge");
            assertThat(pattern.item).isNotNull();
            var inputEdges = inputs.getFirst().pattern().aliasToNode.values().stream()
                .flatMap(node -> node.out.stream()).toList();
            assertThat(inputEdges).anySatisfy(edge -> assertThat(pattern.item).isSameAs(edge.item));
          } else if (storedStep instanceof com.jetbrains.youtrackdb.internal.core.sql.executor.ProjectionCalculationStep) {
            assertThat(field(liveStep, "projection")).isNotSameAs(field(storedStep, "projection"));
          }
        }
        first = live;
        query.close();
        assertThat(template.getContext().getInputParameters()).isNullOrEmpty();
        assertThat(((BasicCommandContext) template.getContext()).hasLocalVariableKeys()).isFalse();
      }
      assertThat(inputs).hasSize(1);
      assertThat(inputs.getFirst().runtimeRidStarts()).hasSize(1);
      assertThat(inputs.getFirst().aliasFilters()).isEmpty();
    } finally {
      cache.beforeUncachedPlanning = null;
    }
  }

  /** Monitoring receives bound execution copies, not templates, and renders no current RID value. */
  @Test
  public void monitoringReportsBoundRuntimeCopiesWithoutRidValues() throws Exception {
    var a = graph.addVertex(T.label, "MonitorPerson");
    var b = graph.addVertex(T.label, "MonitorPerson");
    a.addEdge("monitorLink", b);
    b.addEdge("monitorLink", a);
    graph.tx().commit();
    var tx = (YTDBTransaction) graph.tx();
    var calls = new AtomicInteger();
    var plans = new ArrayList<InternalExecutionPlan>();
    var ids = List.of(a.id(), b.id());
    tx.withQueryMonitoringMode(QueryMonitoringMode.EXACT)
        .withQueryListener((details, start, duration) -> {
          var plan = (InternalExecutionPlan) details.getExecutionPlan();
          assertThat(plan).isNotNull();
          assertThat(plan.getContext().getDatabaseSession()).isSameAs(db(graph));
          assertThat(plan.getContext().getInputParameters())
              .isEqualTo(Map.of(0, ids.get(calls.get())));
          assertThat(plan.prettyPrint(0, 2)).contains("FETCH FROM RID PARAMETER")
              .doesNotContain(a.id().toString(), b.id().toString());
          plans.add(plan);
          calls.incrementAndGet();
        });
    try {
      InternalExecutionPlan template = null;
      for (var id : ids) {
        try (var query = graph.traversal().V(id).out("monitorLink")) {
          query.asAdmin().applyStrategies();
          var boundary = (YTDBMatchPlanStep<?, ?>) query.asAdmin().getStartStep();
          if (template == null) {
            template = boundary.getPlan();
          }
          assertThat(boundary.getPlan()).isSameAs(template);
          assertThat(query.toList()).hasSize(1);
          assertThat(plans.getLast()).isSameAs(boundary.getPlan()).isNotSameAs(template);
        }
      }
      assertThat(calls.get()).isEqualTo(2);
      assertThat(plans.getFirst()).isNotSameAs(plans.getLast());
    } finally {
      tx.withQueryListener(
          com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener.NO_OP);
    }
  }
}
