package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMonitoringMode;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.apache.tinkerpop.gremlin.process.traversal.DT;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.CardinalityValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GremlinProcessRunner.class)
public class YTDBQueryMetricsStrategyTest extends YTDBAbstractGremlinTest {

  private static long TICKER_POSSIBLE_LAG_NANOS;
  private static long TICKER_POSSIBLE_LAG_MILLIS;

  @BeforeClass
  public static void beforeClass() {
    var granularity =
        YouTrackDBEnginesManager.instance().getTicker().getGranularity();
    var granularityMillis = granularity / 1_000_000;

    TICKER_POSSIBLE_LAG_NANOS = (long) (granularity * 1.5);
    TICKER_POSSIBLE_LAG_MILLIS = (long) (granularityMillis * 1.5);
  }

  @Before
  public void warmup() throws InterruptedException {
    g().executeInTx(s -> s.V().hasLabel("person").toList());
    Thread.sleep(100);
  }

  @Test
  @LoadGraphWith(MODERN)
  public void testQueryMonitoringLightweight() throws Exception {
    final var seed = System.nanoTime();
    final var random = new Random(seed);
    final var listener = new RememberingListener();
    try {
      for (var i = 0; i < 100; i++) {
        testQuery(QueryMonitoringMode.LIGHTWEIGHT, listener, random);
      }
    } catch (Exception | Error e) {
      System.err.println("testQueryMonitoringLightweight seed: " + seed);
      throw e;
    }

    g.tx().open();
    g.V().hasLabel("software").toList();
    g.tx().commit();

    assertThat(listener.query).isNull();
  }

  @Test
  @LoadGraphWith(MODERN)
  public void testQueryMonitoringExact() throws Exception {
    final var seed = System.nanoTime();
    final var random = new Random(seed);
    final var listener = new RememberingListener();
    try {
      for (var i = 0; i < 100; i++) {
        testQuery(QueryMonitoringMode.EXACT, listener, random);
      }
    } catch (Exception | Error e) {
      System.err.println("testQueryMonitoringExact seed: " + seed);
      throw e;
    }

    g.tx().open();
    g.V().hasLabel("software").toList();
    g.tx().commit();

    assertThat(listener.query).isNull();
  }

  @Test
  @LoadGraphWith(MODERN)
  public void testDurationExcludesDelayBeforeClose() throws Exception {
    // Both modes should exclude idle time between the last iteration call and close().
    // LIGHTWEIGHT captures endNano during the last hasNext()/next() call.
    // EXACT only accumulates System.nanoTime() deltas inside hasNext()/next() calls.
    for (var mode : QueryMonitoringMode.values()) {
      final var listener = new RememberingListener();
      final long delayMillis = 200;

      ((YTDBTransaction) g.tx())
          .withQueryMonitoringMode(mode)
          .withQueryListener(listener);

      g.tx().open();

      try (var q = g.V().hasLabel("person")) {
        q.toList(); // consume all results

        final var afterLastCallNanos = System.nanoTime();

        Thread.sleep(delayMillis);

        // close() is called here by try-with-resources;
        // the reported duration should NOT include the sleep
        assertThat(System.nanoTime() - afterLastCallNanos)
            .as("sanity check: sleep actually elapsed")
            .isGreaterThanOrEqualTo(delayMillis * 1_000_000 / 2);
      }
      g.tx().commit();

      assertThat(listener.executionTimeNanos)
          .as("duration should exclude sleep delay in %s mode", mode)
          .isLessThan(delayMillis * 1_000_000);
    }
  }

  @Test
  @LoadGraphWith(MODERN)
  public void testListenerNotNotifiedWhenTraversalNeverIterated() throws Exception {
    final var listener = new RememberingListener();

    ((YTDBTransaction) g.tx())
        .withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT)
        .withQueryListener(listener);

    g.tx().open();

    //noinspection EmptyTryBlock
    try (var ignored = g.V().hasLabel("person")) {
      // never call hasNext/next
    }
    g.tx().commit();

    assertThat(listener.query).isNull();
  }

  @SuppressWarnings({"unchecked", "resource"})
  @Test
  @LoadGraphWith(MODERN)
  public void testQueryStringRepresentation() throws Exception {
    // Labels, property names, and step structure are preserved in the query string
    // reported to the listener. Actual values (numbers, strings used as values) are
    // replaced with bind variable placeholders.
    //
    // In the expected patterns below:
    //   - single quotes stand for double quotes (replaced at runtime for readability)
    //   - <arg> matches a parameterized value placeholder (_args_N)

    // --- Basic vertex/edge queries ---
    assertQueryString(
        g.V(),
        "g.V()"
    );
    assertQueryString(
        g.E(),
        "g.E()"
    );

    // --- hasLabel — labels preserved ---
    assertQueryString(
        g.V().hasLabel("person"),
        "g.V().hasLabel('person')"
    );
    assertQueryString(
        g.V().hasLabel("software"),
        "g.V().hasLabel('software')"
    );
    assertQueryString(
        g.E().hasLabel("knows"),
        "g.E().hasLabel('knows')"
    );
    assertQueryString(
        g.E().hasLabel("created"),
        "g.E().hasLabel('created')"
    );

    // --- has(key, value) — key preserved, value parameterized ---
    assertQueryString(
        g.V().has("name", "marko"),
        "g.V().has('name',<arg>)"
    );
    assertQueryString(
        g.V().has("age", 29),
        "g.V().has('age',<arg>)"
    );
    assertQueryString(
        g.V().has("lang", "java"),
        "g.V().has('lang',<arg>)"
    );
    assertQueryString(
        g.E().has("weight", 0.5),
        "g.E().has('weight',<arg>)"
    );

    // --- has(label, key, value) — label and key preserved, value parameterized ---
    assertQueryString(
        g.V().has("person", "name", "marko"),
        "g.V().has('person','name',<arg>)"
    );
    assertQueryString(
        g.V().has("person", "age", 29),
        "g.V().has('person','age',<arg>)"
    );

    // --- has with P predicates — key preserved, predicate value parameterized ---
    assertQueryString(
        g.V().has("age", P.gt(27)),
        "g.V().has('age',P.gt(<arg>))"
    );
    assertQueryString(
        g.V().has("age", P.lt(30)),
        "g.V().has('age',P.lt(<arg>))"
    );
    assertQueryString(
        g.V().has("age", P.gte(29)),
        "g.V().has('age',P.gte(<arg>))"
    );
    assertQueryString(
        g.V().has("age", P.lte(32)),
        "g.V().has('age',P.lte(<arg>))"
    );
    assertQueryString(
        g.V().has("age", P.eq(29)),
        "g.V().has('age',P.eq(<arg>))"
    );
    assertQueryString(
        g.V().has("age", P.neq(29)),
        "g.V().has('age',P.neq(<arg>))"
    );
    // P.between/inside/outside are decomposed into ConnectiveP by TinkerPop
    assertQueryString(
        g.V().has("age", P.between(27, 35)),
        "g.V().has('age',P.gte(<arg>).and(P.lt(<arg>)))"
    );
    assertQueryString(
        g.V().has("age", P.inside(27, 35)),
        "g.V().has('age',P.gt(<arg>).and(P.lt(<arg>)))"
    );
    assertQueryString(
        g.V().has("age", P.outside(27, 35)),
        "g.V().has('age',P.lt(<arg>).or(P.gt(<arg>)))"
    );
    assertQueryString(
        g.V().has("age", P.within(27, 29, 32)),
        "g.V().has('age',P.within([<arg>, <arg>, <arg>]))"
    );
    assertQueryString(
        g.V().has("age", P.without(27, 35)),
        "g.V().has('age',P.without([<arg>, <arg>]))"
    );

    // --- ConnectiveP — compound predicate ---
    assertQueryString(
        g.V().has("age", P.gt(27).and(P.lt(35))),
        "g.V().has('age',P.gt(<arg>).and(P.lt(<arg>)))"
    );
    assertQueryString(
        g.V().has("age", P.lt(27).or(P.gt(32))),
        "g.V().has('age',P.lt(<arg>).or(P.gt(<arg>)))"
    );

    // --- hasNot — key preserved ---
    assertQueryString(
        g.V().hasNot("lang"),
        "g.V().hasNot('lang')"
    );

    // --- is — value parameterized ---
    assertQueryString(
        g.V().values("age").is(29),
        "g.V().values('age').is(<arg>)"
    );
    assertQueryString(
        g.V().values("age").is(P.gt(27)),
        "g.V().values('age').is(P.gt(<arg>))"
    );

    // --- Navigation — edge labels preserved ---
    assertQueryString(
        g.V().out(),
        "g.V().out()"
    );
    assertQueryString(
        g.V().out("knows"),
        "g.V().out('knows')"
    );
    assertQueryString(
        g.V().out("created"),
        "g.V().out('created')"
    );
    assertQueryString(
        g.V().in("knows"),
        "g.V().in('knows')"
    );
    assertQueryString(
        g.V().in("created"),
        "g.V().in('created')"
    );
    assertQueryString(
        g.V().both("knows"),
        "g.V().both('knows')"
    );
    assertQueryString(
        g.V().outE("knows"),
        "g.V().outE('knows')"
    );
    assertQueryString(
        g.V().outE("created"),
        "g.V().outE('created')"
    );
    assertQueryString(
        g.V().inE("knows"),
        "g.V().inE('knows')"
    );
    assertQueryString(
        g.V().inE("created"),
        "g.V().inE('created')"
    );
    assertQueryString(
        g.V().bothE("knows"),
        "g.V().bothE('knows')"
    );

    // --- Edge vertex steps ---
    assertQueryString(
        g.V().outE().inV(),
        "g.V().outE().inV()"
    );
    assertQueryString(
        g.V().outE().outV(),
        "g.V().outE().outV()"
    );
    assertQueryString(
        g.V().outE().bothV(),
        "g.V().outE().bothV()"
    );

    // --- Property access — property names preserved ---
    assertQueryString(
        g.V().values("name"),
        "g.V().values('name')"
    );
    assertQueryString(
        g.V().values("name", "age"),
        "g.V().values('name','age')"
    );
    assertQueryString(
        g.V().valueMap("name"),
        "g.V().valueMap('name')"
    );
    assertQueryString(
        g.V().valueMap("name", "age"),
        "g.V().valueMap('name','age')"
    );
    assertQueryString(
        g.V().elementMap("name"),
        "g.V().elementMap('name')"
    );
    assertQueryString(
        g.V().elementMap("name", "age"),
        "g.V().elementMap('name','age')"
    );
    assertQueryString(
        g.V().properties("name"),
        "g.V().properties('name')"
    );

    // --- Aggregation ---
    assertQueryString(
        g.V().count(),
        "g.V().count()"
    );
    assertQueryString(
        g.E().count(),
        "g.E().count()"
    );
    assertQueryString(
        g.V().values("age").sum(),
        "g.V().values('age').sum()"
    );
    assertQueryString(
        g.V().values("age").min(),
        "g.V().values('age').min()"
    );
    assertQueryString(
        g.V().values("age").max(),
        "g.V().values('age').max()"
    );
    assertQueryString(
        g.V().values("age").mean(),
        "g.V().values('age').mean()"
    );
    assertQueryString(
        g.V().fold(),
        "g.V().fold()"
    );

    // --- Filtering — values parameterized ---
    assertQueryString(
        g.V().dedup(),
        "g.V().dedup()"
    );
    assertQueryString(
        g.V().limit(2),
        "g.V().limit(<arg>)"
    );
    assertQueryString(
        g.V().range(1, 3),
        "g.V().range(<arg>,<arg>)"
    );
    assertQueryString(
        g.V().tail(2),
        "g.V().tail(<arg>)"
    );
    assertQueryString(
        g.V().coin(0.5),
        "g.V().coin(<arg>)"
    );
    assertQueryString(
        g.V().sample(2),
        "g.V().sample(<arg>)"
    );

    // --- identity ---
    assertQueryString(
        g.V().identity(),
        "g.V().identity()"
    );

    // --- Step labels — preserved ---
    assertQueryString(
        g.V().as("a"),
        "g.V().as('a')"
    );
    assertQueryString(
        g.V().as("a").out("knows").as("b"),
        "g.V().as('a').out('knows').as('b')"
    );

    // --- select — step labels preserved ---
    assertQueryString(
        g.V().as("a").out().as("b").select("a", "b"),
        "g.V().as('a').out().as('b').select('a','b')"
    );
    assertQueryString(
        g.V().as("a").select("a"),
        "g.V().as('a').select('a')"
    );

    // --- project — projection keys and by() property names preserved ---
    assertQueryString(
        g.V().project("name", "age").by("name").by("age"),
        "g.V().project('name','age').by('name').by('age')"
    );

    // --- path ---
    assertQueryString(
        g.V().out().path(),
        "g.V().out().path()"
    );

    // --- id and label ---
    assertQueryString(
        g.V().id(),
        "g.V().id()"
    );
    assertQueryString(
        g.V().label(),
        "g.V().label()"
    );

    // --- order ---
    assertQueryString(
        g.V().order(),
        "g.V().order()"
    );
    assertQueryString(
        g.V().order().by("name"),
        "g.V().order().by('name')"
    );
    assertQueryString(
        g.V().order().by("age", Order.desc),
        "g.V().order().by('age',Order.desc)"
    );

    // --- group / groupCount ---
    assertQueryString(
        g.V().groupCount(),
        "g.V().groupCount()"
    );
    assertQueryString(
        g.V().groupCount().by("label"),
        "g.V().groupCount().by('label')"
    );

    // --- constant — value parameterized ---
    assertQueryString(
        g.V().constant("x"),
        "g.V().constant(<arg>)"
    );
    assertQueryString(
        g.V().constant(42),
        "g.V().constant(<arg>)"
    );

    // --- discard ---
    assertQueryString(
        g.V().hasLabel("person").discard(),
        "g.V().hasLabel('person').discard()"
    );

    // --- inject — values parameterized ---
    assertQueryString(
        g.inject(1, 2, 3),
        "g.inject(<arg>,<arg>,<arg>)"
    );
    assertQueryString(
        g.inject("a", "b"),
        "g.inject(<arg>,<arg>)"
    );
    assertQueryString(
        g.V().hasLabel("person").values("name").inject("extra"),
        "g.V().hasLabel('person').values('name').inject(<arg>)"
    );

    // --- all / any — predicate value parameterized ---
    assertQueryString(
        g.V().values("age").fold().all(P.gt(20)),
        "g.V().values('age').fold().all(P.gt(<arg>))"
    );
    assertQueryString(
        g.V().values("age").fold().any(P.gt(30)),
        "g.V().values('age').fold().any(P.gt(<arg>))"
    );

    // --- sideEffect — traversal argument preserved ---
    assertQueryString(
        g.V().hasLabel("person").sideEffect(__.count()),
        "g.V().hasLabel('person').sideEffect(__.count())"
    );

    // --- aggregate / cap — side-effect keys preserved ---
    assertQueryString(
        g.V().hasLabel("person").aggregate("x").cap("x"),
        "g.V().hasLabel('person').aggregate('x').cap('x')"
    );

    // --- coalesce — traversal arguments preserved ---
    assertQueryString(
        g.V().coalesce(__.values("name"), __.values("lang")),
        "g.V().coalesce(__.values('name'),__.values('lang'))"
    );

    // --- combine — value parameterized ---
    assertQueryString(
        g.V().values("name").fold().combine(List.of("extra")),
        "g.V().values('name').fold().combine([<arg>])"
    );

    // --- difference — value parameterized, traversal preserved ---
    assertQueryString(
        g.V().values("name").fold().difference(List.of("marko")),
        "g.V().values('name').fold().difference([<arg>])"
    );
    assertQueryString(
        g.V().values("name").fold().difference(__.V().values("name").fold()),
        "g.V().values('name').fold().difference(__.V().values('name').fold())"
    );

    // --- disjunct — value parameterized ---
    assertQueryString(
        g.V().values("name").fold().disjunct(List.of("marko", "extra")),
        "g.V().values('name').fold().disjunct([<arg>, <arg>])"
    );

    // --- intersect — value parameterized, traversal preserved ---
    assertQueryString(
        g.V().values("name").fold().intersect(List.of("marko", "josh")),
        "g.V().values('name').fold().intersect([<arg>, <arg>])"
    );
    assertQueryString(
        g.V().values("name").fold().intersect(__.V().values("name").fold()),
        "g.V().values('name').fold().intersect(__.V().values('name').fold())"
    );

    // --- concat — string values parameterized, traversal arguments preserved ---
    assertQueryString(
        g.V().values("name").concat(" suffix"),
        "g.V().values('name').concat(<arg>)"
    );
    assertQueryString(
        g.V().values("name").concat(__.constant(" test")),
        "g.V().values('name').concat(__.constant(<arg>))"
    );

    // --- conjoin — delimiter parameterized ---
    assertQueryString(
        g.V().values("name").fold().conjoin(","),
        "g.V().values('name').fold().conjoin(<arg>)"
    );

    // --- dateAdd — enum preserved, value parameterized ---
    assertQueryString(
        g.inject(java.time.OffsetDateTime.now()).dateAdd(DT.day, 1),
        "g.inject(<arg>).dateAdd(DT.day,<arg>)"
    );

    // --- dateDiff — value parameterized ---
    assertQueryString(
        g.inject(java.time.OffsetDateTime.now()).dateDiff(java.time.OffsetDateTime.now()),
        "g.inject(<arg>).dateDiff(<arg>)"
    );
    assertQueryString(
        g.inject(java.time.OffsetDateTime.now())
            .dateDiff(__.constant(java.time.OffsetDateTime.now())),
        "g.inject(<arg>).dateDiff(__.constant(<arg>))"
    );

    // --- cyclicPath ---
    assertQueryString(
        g.V().out().out().cyclicPath(),
        "g.V().out().out().cyclicPath()"
    );

    // --- repeat + emit — traversal arguments preserved ---
    assertQueryString(
        g.V().hasLabel("person").repeat(__.out("knows")).emit().times(2),
        "g.V().hasLabel('person').repeat(__.out('knows')).emit().times(<arg>)"
    );
    assertQueryString(
        g.V().hasLabel("person").repeat(__.out("knows")).emit(__.hasLabel("person")),
        "g.V().hasLabel('person').repeat(__.out('knows')).emit(__.hasLabel('person'))"
    );

    // --- repeat + loops — loop count used in until predicate ---
    assertQueryString(
        g.V().hasLabel("person").repeat(__.out("knows")).until(__.loops().is(P.gte(2))),
        "g.V().hasLabel('person').repeat(__.out('knows')).until(__.loops().is(P.gte(<arg>)))"
    );
    assertQueryString(
        g.V().hasLabel("person").repeat("r", __.out("knows")).until(__.loops("r").is(3)),
        "g.V().hasLabel('person').repeat(<arg>,__.out('knows')).until(__.loops(<arg>).is(<arg>))"
    );

    // --- union — nested traversals with mixed structural and value arguments ---
    assertQueryString(
        g.V().union(__.hasLabel("person").has("age", P.gt(27)).values("name"),
            __.hasLabel("software").values("lang")),
        "g.V().union(__.hasLabel('person').has('age',P.gt(<arg>)).values('name'),__.hasLabel('software').values('lang'))"
    );
    assertQueryString(
        g.V().union(__.out("knows").has("name", "josh"), __.out("created").has("lang", "java"))
            .values("name"),
        "g.V().union(__.out('knows').has('name',<arg>),__.out('created').has('lang',<arg>)).values('name')"
    );
    assertQueryString(
        g.union(__.V().hasLabel("person").has("age", P.gt(27)), __.V().hasLabel("software")),
        "g.union(__.V().hasLabel('person').has('age',P.gt(<arg>)),__.V().hasLabel('software'))"
    );

    // --- match — traversal arguments preserved ---
    assertQueryString(
        g.V().match(__.as("a").out("knows").as("b"), __.as("b").has("age", P.gt(27)))
            .select("a", "b"),
        "g.V().match(__.as('a').out('knows').as('b'),__.as('b').has('age',P.gt(<arg>))).select('a','b')"
    );

    // --- math — expression parameterized ---
    assertQueryString(
        g.V().hasLabel("person").values("age").math("_ + 10"),
        "g.V().hasLabel('person').values('age').math(<arg>)"
    );

    // --- merge (list) — value parameterized ---
    assertQueryString(
        g.V().values("name").fold().merge(List.of("extra")),
        "g.V().values('name').fold().merge([<arg>])"
    );

    // --- none — predicate value parameterized ---
    assertQueryString(
        g.V().values("age").fold().none(P.gt(100)),
        "g.V().values('age').fold().none(P.gt(<arg>))"
    );

    // --- element ---
    assertQueryString(
        g.V().hasLabel("person").properties("age").element(),
        "g.V().hasLabel('person').properties('age').element()"
    );

    // --- fail — message parameterized ---
    assertQueryString(
        g.V().hasLabel("person").choose(__.has("age"), __.values("name"), __.fail("no age")),
        "g.V().hasLabel('person').choose(__.has('age'),__.values('name'),__.fail(<arg>))"
    );

    // --- choose — traversal arguments preserved ---
    assertQueryString(
        g.V().choose(__.hasLabel("person"), __.values("name"), __.values("lang")),
        "g.V().choose(__.hasLabel('person'),__.values('name'),__.values('lang'))"
    );
    assertQueryString(
        g.V().choose(__.hasLabel("person"), __.out("knows")),
        "g.V().choose(__.hasLabel('person'),__.out('knows'))"
    );

    // --- and / or / not — traversal arguments preserved ---
    assertQueryString(
        g.V().and(__.hasLabel("person"), __.has("age", P.gt(27))),
        "g.V().and(__.hasLabel('person'),__.has('age',P.gt(<arg>)))"
    );
    assertQueryString(
        g.V().or(__.hasLabel("person"), __.hasLabel("software")),
        "g.V().or(__.hasLabel('person'),__.hasLabel('software'))"
    );
    assertQueryString(
        g.V().not(__.hasLabel("software")),
        "g.V().not(__.hasLabel('software'))"
    );

    // --- product — value parameterized ---
    assertQueryString(
        g.V().values("name").fold().product(List.of("x", "y")),
        "g.V().values('name').fold().product([<arg>, <arg>])"
    );

    // --- property — key preserved, value parameterized ---
    assertQueryString(
        g.V().hasLabel("person").property("nickname", "test"),
        "g.V().hasLabel('person').property('nickname',<arg>)"
    );

    // --- property with meta-properties — key preserved, value and meta-property
    //     key/values are parameterized
    assertQueryString(
        g.addV("test").property("name", "marko", "since", 2020),
        "g.addV('test').property('name',<arg>,<arg>,<arg>)"
    );

    // --- property with Cardinality — cardinality and key preserved, value parameterized
    assertQueryString(
        g.addV("test")
            .property(VertexProperty.Cardinality.single, "name", "marko"),
        "g.addV('test').property(VertexProperty.Cardinality.single,'name',<arg>)"
    );

    // --- property(Map) — decomposes into individual property() calls per entry;
    //     each key is preserved, each value is parameterized
    final var props = new LinkedHashMap<>();
    props.put("name", "marko");
    props.put("age", 29);
    assertQueryString(
        g.addV("test").property(props),
        "g.addV('test').property('name',<arg>).property('age',<arg>)"
    );

    // --- property(Cardinality, key, value, metaKey, metaValue) — cardinality and key
    //     preserved, value and meta-property key/values are parameterized
    assertQueryString(
        g.addV("test")
            .property(VertexProperty.Cardinality.single, "name", "marko", "since", 2020),
        "g.addV('test')"
            + ".property(VertexProperty.Cardinality.single,'name',<arg>,<arg>,<arg>)"
    );

    // --- property(Cardinality, Map) — decomposes into individual
    //     property(Cardinality, key, value) calls per entry
    final var cardProps = new LinkedHashMap<>();
    cardProps.put("name", "marko");
    cardProps.put("age", 29);
    assertQueryString(
        g.addV("test")
            .property(VertexProperty.Cardinality.single, cardProps),
        "g.addV('test')"
            + ".property(VertexProperty.Cardinality.single,'name',<arg>)"
            + ".property(VertexProperty.Cardinality.single,'age',<arg>)"
    );

    // --- property(Cardinality, Map) with CardinalityValueTraversal — per-entry
    //     cardinality override; each entry decomposes with its own cardinality
    final var cvtProps = new LinkedHashMap<>();
    cvtProps.put("name",
        new CardinalityValueTraversal(VertexProperty.Cardinality.set, "marko"));
    cvtProps.put("age",
        new CardinalityValueTraversal(VertexProperty.Cardinality.list, 29));
    assertQueryString(
        g.addV("test")
            .property(VertexProperty.Cardinality.single, cvtProps),
        "g.addV('test')"
            + ".property(VertexProperty.Cardinality.set,'name',<arg>)"
            + ".property(VertexProperty.Cardinality.list,'age',<arg>)"
    );

    // --- propertyMap ---
    assertQueryString(
        g.V().propertyMap("name", "age"),
        "g.V().propertyMap('name','age')"
    );

    // --- replace — both args parameterized ---
    assertQueryString(
        g.V().values("name").replace("a", "x"),
        "g.V().values('name').replace(<arg>,<arg>)"
    );

    // --- reverse ---
    assertQueryString(
        g.V().values("name").reverse(),
        "g.V().values('name').reverse()"
    );

    // --- trim / lTrim / rTrim ---
    assertQueryString(
        g.V().values("name").trim(),
        "g.V().values('name').trim()"
    );
    assertQueryString(
        g.V().values("name").lTrim(),
        "g.V().values('name').lTrim()"
    );
    assertQueryString(
        g.V().values("name").rTrim(),
        "g.V().values('name').rTrim()"
    );

    // --- skip — value parameterized ---
    assertQueryString(
        g.V().skip(2),
        "g.V().skip(<arg>)"
    );

    // --- split — separator parameterized ---
    assertQueryString(
        g.V().values("name").split("a"),
        "g.V().values('name').split(<arg>)"
    );

    // --- subgraph + cap — side effect key preserved ---
    assertQueryString(
        g.V().outE("knows").subgraph("sg").cap("sg"),
        "g.V().outE('knows').subgraph('sg').cap('sg')"
    );

    // --- substring — index values parameterized ---
    assertQueryString(
        g.V().values("name").substring(0, 3),
        "g.V().values('name').substring(<arg>,<arg>)"
    );
    assertQueryString(
        g.V().values("name").substring(1),
        "g.V().values('name').substring(<arg>)"
    );

    // --- timeLimit — value parameterized ---
    assertQueryString(
        g.V().out().timeLimit(1000),
        "g.V().out().timeLimit(<arg>)"
    );

    // --- toLower / toUpper ---
    assertQueryString(
        g.V().values("name").toLower(),
        "g.V().values('name').toLower()"
    );
    assertQueryString(
        g.V().values("name").toLower(Scope.local),
        "g.V().values('name').toLower(Scope.local)"
    );
    assertQueryString(
        g.V().values("name").toUpper(),
        "g.V().values('name').toUpper()"
    );
    assertQueryString(
        g.V().values("name").toUpper(Scope.local),
        "g.V().values('name').toUpper(Scope.local)"
    );

    // --- unfold ---
    assertQueryString(
        g.V().values("name").fold().unfold(),
        "g.V().values('name').fold().unfold()"
    );

    // --- value ---
    assertQueryString(
        g.V().hasLabel("person").properties("age").value(),
        "g.V().hasLabel('person').properties('age').value()"
    );

    // --- Chained traversals ---
    assertQueryString(
        g.V().hasLabel("person").out("knows").hasLabel("person"),
        "g.V().hasLabel('person').out('knows').hasLabel('person')"
    );
    assertQueryString(
        g.V().hasLabel("person").out("created").values("name"),
        "g.V().hasLabel('person').out('created').values('name')"
    );
    assertQueryString(
        g.V().hasLabel("person").outE("created").inV().hasLabel("software"),
        "g.V().hasLabel('person').outE('created').inV().hasLabel('software')"
    );
    assertQueryString(
        g.V().hasLabel("person").has("age", P.gt(27)).out("knows").values("name"),
        "g.V().hasLabel('person').has('age',P.gt(<arg>)).out('knows').values('name')"
    );
    assertQueryString(
        g.V().hasLabel("person").has("age", P.between(27, 35)).count(),
        "g.V().hasLabel('person').has('age',P.gte(<arg>).and(P.lt(<arg>))).count()"
    );
    assertQueryString(
        g.V().hasLabel("person").order().by("age", Order.desc).values("name").limit(2),
        "g.V().hasLabel('person').order().by('age',Order.desc).values('name').limit(<arg>)"
    );

    // --- filter — traversal argument preserved ---
    assertQueryString(
        g.V().filter(__.hasLabel("person")),
        "g.V().filter(__.hasLabel('person'))"
    );
    assertQueryString(
        g.V().filter(__.has("age", P.gt(27))),
        "g.V().filter(__.has('age',P.gt(<arg>)))"
    );

    // --- where ---
    assertQueryString(
        g.V().as("a").out("knows").where(__.out("created").as("a")),
        "g.V().as('a').out('knows').where(__.out('created').as('a'))"
    );

    // --- format — format string parameterized ---
    assertQueryString(
        g.V().hasLabel("person").format("%{name} is %{age}"),
        "g.V().hasLabel('person').format(<arg>)"
    );

    // --- length ---
    assertQueryString(
        g.V().values("name").length(),
        "g.V().values('name').length()"
    );
    assertQueryString(
        g.V().values("name").length(Scope.local),
        "g.V().values('name').length(Scope.local)"
    );

    // --- index ---
    assertQueryString(
        g.V().hasLabel("person").values("name").fold().index(),
        "g.V().hasLabel('person').values('name').fold().index()"
    );

    // --- key ---
    assertQueryString(
        g.V().hasLabel("person").properties("name").key(),
        "g.V().hasLabel('person').properties('name').key()"
    );

    // --- map — traversal argument preserved ---
    assertQueryString(
        g.V().map(__.values("name")),
        "g.V().map(__.values('name'))"
    );

    // --- flatMap — traversal argument preserved ---
    assertQueryString(
        g.V().flatMap(__.out("knows")),
        "g.V().flatMap(__.out('knows'))"
    );

    // --- branch — traversal argument preserved ---
    assertQueryString(
        g.V().branch(__.label()).option("person", __.out("knows"))
            .option("software", __.in("created")),
        "g.V().branch(__.label()).option(<arg>,__.out('knows')).option(<arg>,__.in('created'))"
    );

    // --- Mutating steps (must be last since they modify the graph) ---

    // --- addV — label preserved, property values parameterized ---
    assertQueryString(
        g.addV("test"),
        "g.addV('test')"
    );
    assertQueryString(
        g.addV("test").property("name", "foo").property("age", 25),
        "g.addV('test').property('name',<arg>).property('age',<arg>)"
    );

    // --- addE — label preserved, from/to step labels preserved ---
    assertQueryString(
        g.V().hasLabel("person").as("a").V().hasLabel("software").as("b")
            .addE("uses").from("a").to("b"),
        "g.V().hasLabel('person').as('a').V().hasLabel('software').as('b').addE('uses').from('a').to('b')"
    );

    // --- addE from source — label preserved, from/to traversals preserved ---
    assertQueryString(
        g.addE("knows").from(__.V().has("name", "marko")).to(__.V().has("name", "josh")),
        "g.addE('knows').from(__.V().has('name',<arg>)).to(__.V().has('name',<arg>))"
    );

  }

  /// Executes the traversal with query monitoring enabled and asserts that the query string
  /// reported to the listener matches the expected pattern.
  ///
  /// Pattern conventions:
  /// - Single quotes stand for double quotes (for readability in a Java source).
  /// - `<arg>` matches a parameterized value placeholder (`_args_N`).
  private void assertQueryString(Traversal<?, ?> traversal, String pattern) throws Exception {
    final var listener = new RememberingListener();
    ((YTDBTransaction) g.tx())
        .withQueryMonitoringMode(QueryMonitoringMode.LIGHTWEIGHT)
        .withQueryListener(listener);
    g.tx().open();
    try (traversal) {
      traversal.toList();
    }
    g.tx().commit();

    final var regex = pattern
        .replace("'", "\"")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("(", "\\(")
        .replace(")", "\\)")
        .replace(".", "\\.")
        .replace("\"", "\\\"")
        .replace("<arg>", "_args_\\d+");
    assertThat(listener.query).matches(regex);
  }

  private void testQuery(
      QueryMonitoringMode mode, RememberingListener listener, Random random
  ) throws Exception {
    final var withTxId = random.nextBoolean();
    final var txId = "tx_" + random.nextInt(1000);
    final var withSummary = random.nextBoolean();
    final var summary = "test_" + random.nextInt(1000);

    final var tx = ((YTDBTransaction) g.tx())
        .withQueryMonitoringMode(mode)
        .withQueryListener(listener);

    if (withTxId) {
      tx.withTrackingId(txId);
    }

    tx.open();

    final long beforeMillis;
    final long beforeNanos;
    final long afterMillis;
    final long afterNanos;

    var gs = g();
    if (withSummary) {
      gs = gs.with(YTDBQueryConfigParam.querySummary, summary);
    }

    try (var q = gs.V().hasLabel("person")) {

      beforeMillis = System.currentTimeMillis();
      beforeNanos = System.nanoTime();

      assertThat(q.hasNext()).isTrue(); // query has started

      Thread.sleep(random.nextInt(50));
      q.iterate(); // query has finished

      afterNanos = System.nanoTime();
      afterMillis = System.currentTimeMillis();
    }
    tx.commit();

    final var duration = afterNanos - beforeNanos;

    assertThat(listener.query).isNotNull().contains("hasLabel");
    if (withSummary) {
      assertThat(listener.querySummary).isEqualTo(summary);
    } else {
      assertThat(listener.querySummary).isNull();
    }
    if (withTxId) {
      assertThat(listener.transactionTrackingId).isEqualTo(txId);
    } else {
      assertThat(listener.transactionTrackingId).isNotNull();
    }

    if (mode == QueryMonitoringMode.LIGHTWEIGHT) {
      assertThat(listener.startedAtMillis)
          .isGreaterThanOrEqualTo(beforeMillis - TICKER_POSSIBLE_LAG_MILLIS)
          .isLessThanOrEqualTo(afterMillis + TICKER_POSSIBLE_LAG_MILLIS);
      // The ticker-measured window [nano, endNano] sits inside the System.nanoTime
      // window [beforeNanos, afterNanos], so the measured duration is at most the real
      // elapsed time plus ticker lag.
      assertThat(listener.executionTimeNanos)
          .isGreaterThanOrEqualTo(0)
          .isLessThanOrEqualTo(duration + TICKER_POSSIBLE_LAG_NANOS);
    } else {

      assertThat(listener.startedAtMillis)
          .isGreaterThanOrEqualTo(beforeMillis)
          .isLessThanOrEqualTo(afterMillis);
      assertThat(listener.executionTimeNanos)
          .isLessThanOrEqualTo(duration)
          .isGreaterThan(0);
    }

    listener.reset();
  }

  static class RememberingListener implements QueryMetricsListener {

    String query;
    String querySummary;
    String transactionTrackingId;
    long startedAtMillis;
    long executionTimeNanos;

    @Override
    public void queryFinished(
        QueryDetails queryDetails,
        long startedAtMillis,
        long executionTimeNanos
    ) {

      this.startedAtMillis = startedAtMillis;
      this.executionTimeNanos = executionTimeNanos;
      this.query = queryDetails.getQuery();
      this.querySummary = queryDetails.getQuerySummary();
      this.transactionTrackingId = queryDetails.getTransactionTrackingId();
    }

    public void reset() {
      query = null;
      querySummary = null;
      transactionTrackingId = null;
      startedAtMillis = 0;
      executionTimeNanos = 0;
    }
  }

}
