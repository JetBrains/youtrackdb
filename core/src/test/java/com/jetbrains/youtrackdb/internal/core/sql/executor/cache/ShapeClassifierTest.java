package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlStatementCache;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that {@link ShapeClassifier#classify} maps each query AST to the correct {@link
 * CacheableShape}. The two shapes this foundation reconciles (RECORD and K0_NONE) are asserted
 * precisely; the AGGREGATE_* and MATCH branches are asserted to return their final enum values (their
 * delta paths land in later tracks). The most safety-critical case — an {@code ORDER BY} + {@code
 * LIMIT} query must classify as K0_NONE and never as RECORD — has a dedicated test that also confirms
 * the SKIP/LIMIT gate runs before the RECORD branch.
 */
public class ShapeClassifierTest extends DbTestBase {

  private SQLStatement parse(String sql) {
    return YqlStatementCache.get(sql, session);
  }

  /**
   * A plain {@code SELECT FROM Class WHERE simple-predicate} with no pagination, grouping, LET, or
   * aggregate is the canonical RECORD shape the per-record delta builder reconciles.
   */
  @Test
  public void plainSelectClassifiesAsRecord() {
    Assert.assertEquals(
        CacheableShape.RECORD, ShapeClassifier.classify(parse("select from OUser where name = ?")));
  }

  /**
   * A RECORD-shaped query with a plain {@code ORDER BY} (no SKIP/LIMIT) stays RECORD: ORDER BY alone
   * does not make the result delta-irreconcilable, because the full result is still materialised.
   */
  @Test
  public void selectWithOrderByButNoLimitStaysRecord() {
    Assert.assertEquals(
        CacheableShape.RECORD, ShapeClassifier.classify(parse("select from OUser order by name")));
  }

  /**
   * The load-bearing classify-ordering guard (I10 depends on it): an {@code ORDER BY} + {@code LIMIT}
   * query must classify as K0_NONE, never RECORD. {@code OrderByStep} + LIMIT is a bounded-heap
   * materialiser that discards rows past the top-N, so a cached top-N prefix could not promote row
   * N+1 after an in-tx delete. A future reorder that ran the RECORD branch before the SKIP/LIMIT gate
   * would silently break this; the assertion pins the ordering.
   */
  @Test
  public void orderByPlusLimitClassifiesAsK0NoneNotRecord() {
    var shape = ShapeClassifier.classify(parse("select from OUser order by name limit 10"));
    Assert.assertEquals(
        "ORDER BY + LIMIT must classify as K0_NONE so a bounded top-N prefix is never treated as a"
            + " complete delta-reconcilable RECORD result",
        CacheableShape.K0_NONE,
        shape);
    Assert.assertNotEquals(CacheableShape.RECORD, shape);
  }

  /** A bare SKIP routes to K0_NONE for the same paginated-prefix reason as LIMIT. */
  @Test
  public void skipClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE, ShapeClassifier.classify(parse("select from OUser skip 5")));
  }

  /** GROUP BY is not record-by-record reconcilable; routes to K0_NONE. */
  @Test
  public void groupByClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select name, count(*) from OUser group by name")));
  }

  /** A LET binding routes to K0_NONE; its computed aliases are not delta-reconcilable. */
  @Test
  public void letClauseClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select $a from OUser let $a = name")));
  }

  /** A subquery target routes to K0_NONE; the inner result is opaque to the per-record delta. */
  @Test
  public void subqueryTargetClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select from (select from OUser)")));
  }

  /**
   * Bare {@code COUNT(*) FROM C} (no WHERE) classifies K0_NONE, not AGGREGATE_COUNT. The planner
   * hardwires this shape to an O(1) {@code CountFromClassStep} built before any aggregation step
   * exists, so the aggregate side-tap can never reach it; routing it K0_NONE keeps the untappable shape
   * out of the aggregate replay path entirely. (It is already O(1) and tx-aware, so caching adds
   * nothing.)
   */
  @Test
  public void bareCountStarClassifiesAsK0None() {
    Assert.assertEquals(
        "bare COUNT(*) FROM C is hardwired and untappable; it must classify K0_NONE, not"
            + " AGGREGATE_COUNT",
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select count(*) from OUser")));
  }

  /**
   * {@code COUNT(*)} with a (non-indexed) WHERE predicate stays AGGREGATE_COUNT: unlike the bare and
   * single-field-indexed forms, this shape builds a real {@code AggregateProjectionCalculationStep}, so
   * the side-tap can observe its contributing records and the aggregate cache path applies. The
   * classifier cannot see indexes (it runs on the AST alone), so it keeps every WHERE-carrying
   * {@code COUNT(*)} tappable; the indexed-WHERE residual is caught at the splice fallback instead.
   */
  @Test
  public void countStarWithWhereStaysAggregateCount() {
    Assert.assertEquals(
        CacheableShape.AGGREGATE_COUNT,
        ShapeClassifier.classify(parse("select count(*) from OUser where name = ?")));
  }

  /**
   * An aggregate buried under arithmetic ({@code count(*) + 1}) classifies K0_NONE, not
   * AGGREGATE_COUNT. The cached aggregate replay produces the bare scalar, so caching the arithmetic
   * result as an AGGREGATE_* shape would replay the wrong value; the K0 version gate serves it safely
   * instead. This is the tightening that matters now that aggregates actually cache — the earlier
   * looser match (return the inner call regardless of surrounding arithmetic) was harmless only while
   * aggregates were uncached.
   */
  @Test
  public void aggregateUnderArithmeticClassifiesAsK0None() {
    Assert.assertEquals(
        "count(*) + 1 is an expression over an aggregate, not a bare aggregate; it must classify"
            + " K0_NONE so the aggregate replay never returns the un-incremented scalar",
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select count(*) + 1 from OUser")));
  }

  /**
   * SUM under arithmetic ({@code sum(age) * 2}) likewise classifies K0_NONE, confirming the
   * arithmetic-rejection rule is not specific to COUNT.
   */
  @Test
  public void sumUnderArithmeticClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select sum(age) * 2 from OUser")));
  }

  /** {@code SUM(prop)} maps to the sum aggregate shape. */
  @Test
  public void sumClassifiesAsAggregateSum() {
    Assert.assertEquals(
        CacheableShape.AGGREGATE_SUM,
        ShapeClassifier.classify(parse("select sum(age) from OUser")));
  }

  /**
   * {@code COUNT(DISTINCT(prop))} classifies K0_NONE, not a distinct-count aggregate shape. The engine
   * has no native distinct-count and computes {@code count(distinct(prop))} as a plain row count, so the
   * K0 version gate (re-execute on any mutation) reproduces the engine's result exactly; modelling a
   * true distinct-count in the aggregate replay would diverge from that native semantics. The DISTINCT
   * keyword inside a function call parses as a nested {@code distinct(...)} call, which is what the
   * classifier detects to make this routing decision.
   */
  @Test
  public void countDistinctClassifiesAsK0None() {
    Assert.assertEquals(
        "count(distinct(prop)) must classify K0_NONE so the K0 version gate keeps it matching the"
            + " engine's native row-count semantics",
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select count(distinct(name)) from OUser")));
  }

  /**
   * A projection mixing an aggregate with another item is not a clean single-aggregate shape and is
   * not a plain RECORD either, so it falls to K0_NONE.
   */
  @Test
  public void mixedAggregateAndFieldProjectionClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("select name, count(*) from OUser")));
  }

  /** A non-aggregate scalar function over a field keeps the query at RECORD shape. */
  @Test
  public void scalarFunctionProjectionStaysRecord() {
    Assert.assertEquals(
        CacheableShape.RECORD,
        ShapeClassifier.classify(parse("select name.toLowerCase() from OUser")));
  }

  // ===========================================================================
  // aggregateMetadata — the side-tap populate path's per-shape facts
  // ===========================================================================

  private ShapeClassifier.AggregateMetadata metadata(String sql) {
    return ShapeClassifier.aggregateMetadata((SQLSelectStatement) parse(sql));
  }

  /**
   * A value aggregate over a bare property yields metadata carrying that property name and the matching
   * kind, so the side-tap reads the right field from each contributing record. The alias is the
   * projection alias the aggregation step emits ({@code sum(age)}).
   */
  @Test
  public void aggregateMetadataForSumCarriesPropertyAndKind() {
    var md = metadata("select sum(age) from OUser");
    Assert.assertEquals(CacheableShape.AGGREGATE_SUM, md.kind());
    Assert.assertEquals("age", md.propertyName());
    Assert.assertEquals("sum(age)", md.alias());
  }

  /** MIN/MAX/AVG likewise carry the bare property name they read from each contributing record. */
  @Test
  public void aggregateMetadataForMinMaxAvgCarryProperty() {
    Assert.assertEquals("age", metadata("select min(age) from OUser").propertyName());
    Assert.assertEquals("age", metadata("select max(age) from OUser").propertyName());
    Assert.assertEquals("age", metadata("select avg(age) from OUser").propertyName());
  }

  /**
   * {@code COUNT(*)} with a WHERE (the tappable count shape) yields metadata with a {@code null} property
   * name: it observes membership only and reads no value. The kind is AGGREGATE_COUNT.
   */
  @Test
  public void aggregateMetadataForCountStarHasNullProperty() {
    var md = metadata("select count(*) from OUser where name = ?");
    Assert.assertEquals(CacheableShape.AGGREGATE_COUNT, md.kind());
    Assert.assertNull("COUNT(*) reads no per-row value", md.propertyName());
  }

  /**
   * {@code count(distinct(prop))} returns null metadata: it classifies K0_NONE and must never reach the
   * aggregate populate path. A null here is the signal the session uses to bypass the splice entirely.
   */
  @Test
  public void aggregateMetadataForCountDistinctIsNull() {
    Assert.assertNull(
        "count(distinct(prop)) is K0_NONE; the aggregate path must not derive metadata for it",
        metadata("select count(distinct(name)) from OUser"));
  }

  /** A non-aggregate (plain RECORD) statement yields null metadata. */
  @Test
  public void aggregateMetadataForRecordShapeIsNull() {
    Assert.assertNull(metadata("select from OUser where name = ?"));
  }

  // ===========================================================================
  // MATCH classify routing — the K0_NONE gate and the non-gated MATCH_TUPLE_MULTI
  // residual. classify is schema-free (AST only), so these queries reference OUser
  // and arbitrary edge labels without the classes needing to exist.
  // ===========================================================================

  /**
   * A multi-alias MATCH with statically-resolvable vertex and edge labels, alias-keyed RETURN, and
   * no pagination/grouping/distinct/cross-alias/link-deref WHERE is the canonical non-gated shape:
   * it stays MATCH_TUPLE_MULTI for the per-tuple delta floor.
   */
  @Test
  public void multiAliasMatchClassifiesAsTupleMulti() {
    Assert.assertEquals(
        CacheableShape.MATCH_TUPLE_MULTI,
        ShapeClassifier.classify(
            parse("match {as:i, class:OUser}.out('member'){as:p, class:OUser} return i, p")));
  }

  /**
   * A single-alias MATCH (one bound alias, no traversal edge) folds onto the RECORD delta path: the
   * Etap-A path stores the raw bound records and replays them through a returnProjector. {@code RETURN
   * u} binds exactly one alias with no edge, so it classifies RECORD rather than MATCH_TUPLE_MULTI.
   */
  @Test
  public void singleAliasMatchClassifiesAsRecord() {
    Assert.assertEquals(
        CacheableShape.RECORD,
        ShapeClassifier.classify(parse("match {as:u, class:OUser} return u")));
  }

  /**
   * A single-alias MATCH with a multi-item RETURN ({@code RETURN u, u.name}) still folds onto RECORD:
   * it binds one alias with no traversal edge, and the multi-property RETURN tuple is reproduced by
   * the stored returnProjector at the view emit boundary. This is the projected-tuple case the
   * RID-addressable cache must still serve.
   */
  @Test
  public void singleAliasMatchWithMultiItemReturnClassifiesAsRecord() {
    Assert.assertEquals(
        CacheableShape.RECORD,
        ShapeClassifier.classify(parse("match {as:u, class:OUser} return u, u.name")));
  }

  /**
   * A single-alias MATCH with a WHERE and a record-local ORDER BY ({@code ORDER BY u.name}, whose
   * head is the bound alias) folds onto RECORD: the WHERE re-evaluates against the mutated record and
   * the ORDER BY reads a property of the single bound record after projection.
   */
  @Test
  public void singleAliasMatchWithWhereAndAliasLocalOrderByClassifiesAsRecord() {
    Assert.assertEquals(
        CacheableShape.RECORD,
        ShapeClassifier.classify(
            parse("match {as:u, class:OUser, where:(name = ?)} return u, u.name order by u.name")));
  }

  /**
   * A single-alias MATCH whose ORDER BY references a foreign alias head is NOT record-local, so it
   * cannot fold onto the RECORD path (the raw single record cannot resolve another alias's column).
   * It stays MATCH_TUPLE_MULTI for the per-tuple delta floor rather than producing a mis-sorted RECORD
   * merge.
   */
  @Test
  public void singleAliasMatchWithForeignOrderByStaysTupleMulti() {
    Assert.assertEquals(
        CacheableShape.MATCH_TUPLE_MULTI,
        ShapeClassifier.classify(
            parse("match {as:u, class:OUser} return u, u.name order by other.name")));
  }

  /**
   * A single-alias MATCH whose ORDER BY is a bare record attribute ({@code @rid}) must NOT fold onto
   * the RECORD path. The RECORD fold sorts by projecting both merge heads to the RETURN tuple ({@code
   * u}, {@code u.name}) and comparing them, but that projected tuple is non-identifiable, so reading
   * {@code @rid} on it returns null for every head — the comparator would treat all rows as equal and
   * emit in cache/inject order, mis-sorting vs a fresh MATCH that orders on the raw record's @rid. The
   * statement must stay MATCH_TUPLE_MULTI instead.
   */
  @Test
  public void singleAliasMatchWithRecordAttrOrderByStaysTupleMulti() {
    Assert.assertEquals(
        CacheableShape.MATCH_TUPLE_MULTI,
        ShapeClassifier.classify(
            parse("match {as:u, class:OUser} return u, u.name order by @rid")));
  }

  /**
   * MATCH + LIMIT routes to K0_NONE: a cached top-N tuple set cannot promote tuple N+1 after an
   * in-transaction drop, so the paginated prefix is structurally incomplete (the same reason as the
   * SELECT SKIP/LIMIT gate). The SKIP/LIMIT check runs first, before any other MATCH gate.
   */
  @Test
  public void matchWithLimitClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("match {as:u, class:OUser} return u limit 10")));
  }

  /** MATCH + SKIP routes to K0_NONE for the same paginated-prefix reason as LIMIT. */
  @Test
  public void matchWithSkipClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("match {as:u, class:OUser} return u skip 5")));
  }

  /** MATCH + GROUP BY routes to K0_NONE: a per-tuple skip/inject delta cannot reconcile grouping. */
  @Test
  public void matchWithGroupByClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(
            parse("match {as:u, class:OUser} return u.name, count(*) group by u.name")));
  }

  /** MATCH + UNWIND routes to K0_NONE: the unwound fan-out is not per-tuple reconcilable. */
  @Test
  public void matchWithUnwindClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(
            parse("match {as:u, class:OUser} return u.name as x unwind x")));
  }

  /** MATCH RETURN DISTINCT routes to K0_NONE: a DISTINCT collapse is not per-tuple reconcilable. */
  @Test
  public void matchReturnDistinctClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("match {as:u, class:OUser} return distinct u")));
  }

  /**
   * A MATCH carrying a NOT MATCH anti-join routes to K0_NONE: the anti-join membership cannot be
   * maintained by a per-tuple skip/inject delta.
   */
  @Test
  public void matchWithNotMatchClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(
            parse(
                "match {as:i, class:OUser}, not {as:i}.out('member'){as:p, class:OUser} return i")));
  }

  /**
   * RETURN $elements routes to K0_NONE: it flattens the row to one element with no alias keys,
   * breaking the alias-keyed tuple assumption the per-tuple delta and the Etap-A projector rely on.
   */
  @Test
  public void matchReturnElementsClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(
            parse("match {as:i, class:OUser}, not {as:i}.out('member'){as:p, class:OUser}"
                + " return $elements")));
  }

  /**
   * A pattern node with no class: routes to K0_NONE: an unconstrained alias cannot seed a class
   * filter for the delta build, so its mutations could not be scoped.
   */
  @Test
  public void matchNodeMissingClassClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE, ShapeClassifier.classify(parse("match {as:u} return u")));
  }

  /**
   * A non-statically-resolvable (parameterized) edge label routes to K0_NONE: the edge-class closure
   * cannot be built from the AST alone, so seeding it would risk a wrong or empty closure.
   */
  @Test
  public void matchParameterizedEdgeLabelClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(
            parse("match {as:i, class:OUser}.out(:edgeType){as:p, class:OUser} return i, p")));
  }

  /**
   * A non-statically-resolvable (parameterized) class label routes to K0_NONE for the same
   * AST-only-closure reason as the parameterized edge label.
   */
  @Test
  public void matchParameterizedClassLabelClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(parse("match {as:u, class: :cls} return u")));
  }

  /**
   * A cross-alias-state pattern WHERE (a $matched.<otherAlias> reference) routes to K0_NONE: the
   * per-tuple delta cannot re-evaluate a predicate spanning two aliases against a single mutated
   * record.
   */
  @Test
  public void matchCrossAliasStateWhereClassifiesAsK0None() {
    Assert.assertEquals(
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(
            parse("match {as:a, class:OUser}.out('member'){as:b, class:OUser,"
                + " where:($matched.a is not null)} return a, b")));
  }

  /**
   * A link-path-dereference pattern WHERE (where:(assignee.name = ?), a dotted path whose head is a
   * link rather than the bound alias) routes to K0_NONE: a mutation of the dereferenced
   * out-of-pattern record would otherwise be dropped by the delta build's class filter.
   */
  @Test
  public void matchLinkPathDerefWhereClassifiesAsK0None() {
    Assert.assertEquals(
        "a WHERE dereferencing a link into an out-of-pattern class must classify K0_NONE so the"
            + " mutation of the dereferenced record is served correctly under the version gate",
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(
            parse("match {as:i, class:OUser, where:(assignee.name = ?)} return i")));
  }

  /**
   * The negative companion to the link-deref gate: a plain qualified own-property WHERE
   * (where:(i.title = ?), whose dotted head i IS the bound alias) is not a link dereference and must
   * NOT route to K0_NONE. Being a single-alias edge-free pattern with {@code return i}, it folds onto
   * RECORD (Etap A) rather than the per-tuple floor. This pins that the link-deref walk keys on a
   * foreign head, not on the mere presence of a dot — a foreign head would route K0_NONE instead.
   */
  @Test
  public void matchQualifiedOwnPropertyWhereFoldsToRecord() {
    var shape =
        ShapeClassifier.classify(parse("match {as:i, class:OUser, where:(i.title = ?)} return i"));
    Assert.assertEquals(
        "a qualified own-property WHERE (i.title on bound alias i) is not a link dereference and must"
            + " not route to K0_NONE; the single-alias fold makes it RECORD",
        CacheableShape.RECORD,
        shape);
    Assert.assertNotEquals(CacheableShape.K0_NONE, shape);
  }

  /**
   * A bare own-property WHERE (where:(title = ?), with no dotted path at all) likewise does not route
   * to K0_NONE: it references the bound record's own property and reaches nothing outside the pattern.
   * As a single-alias edge-free pattern it folds onto RECORD (Etap A).
   */
  @Test
  public void matchBareOwnPropertyWhereFoldsToRecord() {
    Assert.assertEquals(
        CacheableShape.RECORD,
        ShapeClassifier.classify(parse("match {as:i, class:OUser, where:(title = ?)} return i")));
  }

  /**
   * A bounded variable-depth traversal (a {@code maxDepth:} node) routes to K0_NONE. The bound
   * produces a transitive-closure tuple set whose membership depends on multi-hop reachability; the
   * direct-membership reverseIndex floor cannot reconcile a deletion of an intermediate vertex on a
   * multi-hop path (the downstream tuples never contained the deleted RID), so the shape must ride
   * the version gate rather than the per-tuple floor.
   */
  @Test
  public void matchMaxDepthClassifiesAsK0None() {
    Assert.assertEquals(
        "a maxDepth: traversal is a transitive closure the per-tuple floor cannot reconcile; it must"
            + " classify K0_NONE",
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(
            parse("match {as:i, class:OUser}.out('member'){as:p, class:OUser, maxDepth:3}"
                + " return i, p")));
  }

  /**
   * A {@code while:} traversal node routes to K0_NONE. Beyond the same transitive-closure
   * unreconcilability as maxDepth:, gating on the while: node's presence also closes the escape hatch
   * where a link-deref / cross-alias / subquery predicate inside the while: condition would bypass the
   * where:-side gates (which only inspect getFilter(), never getWhileCondition()). The while:
   * condition here is deterministic (a plain property predicate), so it survives the upstream
   * non-deterministic-query detector and reaches classify.
   */
  @Test
  public void matchWhileConditionClassifiesAsK0None() {
    Assert.assertEquals(
        "a while: traversal node must classify K0_NONE: its transitive closure is unreconcilable and"
            + " its while: predicate escapes the where:-side gates",
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(
            parse("match {as:i, class:OUser}.out('member'){as:p, class:OUser, while:(title = ?)}"
                + " return i, p")));
  }

  /**
   * An {@code optional:} node routes to K0_NONE. An optional node that fails to match binds a null
   * alias value into the tuple, and the alias-keyed per-tuple delta path and reverseIndex have no RID
   * to index for a null binding; gating it to K0_NONE conservatively keeps the unreconcilable
   * null-binding shape off the floor.
   */
  @Test
  public void matchOptionalNodeClassifiesAsK0None() {
    Assert.assertEquals(
        "an optional: node binds a null alias value with no RID to index; it must classify K0_NONE",
        CacheableShape.K0_NONE,
        ShapeClassifier.classify(
            parse("match {as:i, class:OUser}.out('member'){as:p, class:OUser, optional:true}"
                + " return i, p")));
  }
}
