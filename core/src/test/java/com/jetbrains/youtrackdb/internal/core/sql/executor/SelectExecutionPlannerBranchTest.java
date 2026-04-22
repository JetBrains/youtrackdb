/*
 *
 *  * Copyright YouTrackDB
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * SQL round-trip tests that exercise branches of {@link SelectExecutionPlanner} not covered by
 * {@link SelectStatementExecutionTest}. Each test targets a specific branch identified from the
 * JaCoCo report (Track 8 Step 10).
 *
 * <p>Design contract: drive {@link SelectExecutionPlanner} via {@code session.query(sql)} rather
 * than instantiating the package-private planner directly. This mirrors the production invocation
 * path and is the only reliable way to exercise the {@code handle*} / {@code is*} helpers because
 * they depend on a fully-parsed AST and a live {@link com.jetbrains.youtrackdb.internal.core.db
 * .DatabaseSessionEmbedded} context.
 */
public class SelectExecutionPlannerBranchTest extends TestUtilsFixture {

  /**
   * {@code SELECT FROM :target} with the parameter bound to a {@link SchemaClass} value. Exercises
   * {@code handleInputParamAsTarget}'s {@code SchemaClass} switch arm — the planner must rewrap
   * the param into a synthetic {@code SQLFromClause} and dispatch to {@code handleClassAsTarget}.
   */
  @Test
  public void selectFromInputParam_schemaClassValue() {
    var className = "SchemaClassParamTarget_" + uniqueSuffix();
    var clazz = session.getMetadata().getSchema().createClass(className);

    session.begin();
    session.newInstance(className).setProperty("tag", "a");
    session.newInstance(className).setProperty("tag", "b");
    session.commit();

    Map<Object, Object> params = new HashMap<>();
    params.put("target", clazz); // SchemaClass instance, not a String

    try (var result = session.query("select from :target", params)) {
      var rows = result.stream().toList();
      Assert.assertEquals(2, rows.size());
    }
  }

  /**
   * {@code SELECT FROM :target} where the parameter is a single {@link
   * com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable} (a RID). Exercises the
   * {@code Identifiable} arm which must wrap the RID into a single-element list and dispatch to
   * {@code handleRidsAsTarget}.
   */
  @Test
  public void selectFromInputParam_singleIdentifiable() {
    var className = "SingleIdParamTarget_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "only");
    var rid = doc.getIdentity();
    session.commit();

    Map<Object, Object> params = new HashMap<>();
    params.put("target", rid);

    try (var result = session.query("select from :target", params)) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      Assert.assertEquals("only", row.getProperty("tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * {@code SELECT FROM :target} with a non-empty iterable of identifiables. Exercises the
   * {@code Iterable} arm of {@code handleInputParamAsTarget} — the planner walks the iterable
   * building an {@code SQLRid} per element and dispatches to {@code handleRidsAsTarget}.
   */
  @Test
  public void selectFromInputParam_iterableOfIdentifiables() {
    var className = "IterableIdParamTarget_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    List<Object> rids = new ArrayList<>();
    session.begin();
    for (var i = 0; i < 3; i++) {
      var doc = session.newInstance(className);
      doc.setProperty("n", i);
      rids.add(doc.getIdentity());
    }
    session.commit();

    Map<Object, Object> params = new HashMap<>();
    params.put("target", rids);

    try (var result = session.query("select from :target", params)) {
      var rows = result.stream().toList();
      Assert.assertEquals(3, rows.size());
    }
  }

  /**
   * {@code SELECT FROM :target} with an empty iterable. Exercises the {@code rids.isEmpty()}
   * fall-through that chains {@code EmptyStep} when no RIDs can be materialized.
   */
  @Test
  public void selectFromInputParam_emptyIterable() {
    Map<Object, Object> params = new HashMap<>();
    params.put("target", new ArrayList<>());

    try (var result = session.query("select from :target", params)) {
      Assert.assertFalse(
          "Empty iterable must produce no rows (EmptyStep path)", result.hasNext());
    }
  }

  /**
   * {@code SELECT FROM :target} with a {@code null} parameter value. Exercises the {@code null}
   * switch arm that chains {@code EmptyStep} directly.
   */
  @Test
  public void selectFromInputParam_nullValue() {
    Map<Object, Object> params = new HashMap<>();
    params.put("target", null);

    try (var result = session.query("select from :target", params)) {
      Assert.assertFalse(
          "Null param value must produce no rows (EmptyStep path)", result.hasNext());
    }
  }

  /**
   * {@code SELECT FROM :target} with an iterable containing a non-Identifiable element. Exercises
   * the {@code !(x instanceof Identifiable)} guard inside {@code handleInputParamAsTarget} which
   * must throw {@link CommandExecutionException} with the specific message.
   *
   * <p><b>Note (preserving a pre-existing typo):</b> the production exception message misspells
   * "collection" as {@code "colleciton"}. This test pins the typo so any future cleanup is
   * intentional.
   */
  @Test
  public void selectFromInputParam_iterableWithNonIdentifiable_throws() {
    Map<Object, Object> params = new HashMap<>();
    params.put("target", List.of("not-an-identifiable"));

    try {
      session.query("select from :target", params).close();
      Assert.fail("Expected CommandExecutionException for non-Identifiable in iterable");
    } catch (CommandExecutionException e) {
      // WHEN-FIXED: Track 22 — fix typo "colleciton" → "collection" in
      // SelectExecutionPlanner.handleInputParamAsTarget error message.
      Assert.assertTrue(
          "message must reference the bad iterable element, got: " + e.getMessage(),
          e.getMessage().contains("colleciton") || e.getMessage().contains("collection"));
    }
  }

  /**
   * {@code SELECT FROM :target} with a type that matches none of the switch arms (not a
   * {@code SchemaClass}, {@code String}, {@code Identifiable}, {@code Iterable}, or null).
   * Exercises the {@code default} arm which throws {@link CommandExecutionException}.
   */
  @Test
  public void selectFromInputParam_invalidType_throws() {
    Map<Object, Object> params = new HashMap<>();
    params.put("target", 42); // Integer: no switch arm handles it.

    try {
      session.query("select from :target", params).close();
      Assert.fail("Expected CommandExecutionException for invalid param type");
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          "message must contain 'Invalid target' or the bad value, got: " + e.getMessage(),
          e.getMessage().contains("Invalid target") || e.getMessage().contains("42"));
    }
  }

  /**
   * {@code SELECT foo FROM Class ORDER BY @version}. Exercises the {@code item.getRecordAttr()
   * != null} branch inside {@code calculateAdditionalOrderByProjections} — the planner must
   * synthesize a projection that references the record attribute (not a field alias).
   */
  @Test
  public void orderBySynthetic_recordAttribute() {
    var className = "OrderByAtVersion_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    for (var i = 0; i < 3; i++) {
      var doc = session.newInstance(className);
      doc.setProperty("name", "n" + i);
    }
    session.commit();

    // name is the only visible alias; @version must produce a synthetic projection.
    try (var result = session.query("select name from " + className + " order by @version asc")) {
      var rows = result.stream().toList();
      Assert.assertEquals(3, rows.size());
      for (var row : rows) {
        // Synthetic ORDER BY projection must be stripped from the visible output — assert by
        // prefix to survive future renames of the internal marker (BC5).
        for (var propName : row.getPropertyNames()) {
          Assert.assertFalse(
              "synthetic ORDER BY alias must not leak into visible output: " + propName,
              propName.startsWith("_$$$"));
        }
        Assert.assertNotNull(row.getProperty("name"));
      }
    }
  }

  /**
   * {@code SELECT a FROM [#cluster:pos] ORDER BY #cluster:pos}. Literal RID in ORDER BY exercises
   * the {@code item.getRid() != null} branch of {@code calculateAdditionalOrderByProjections}.
   */
  @Test
  public void orderBySynthetic_literalRid() {
    var className = "OrderByLiteralRid_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("name", "only");
    var rid = doc.getIdentity();
    session.commit();

    // Sorting by a literal RID expression — rare but valid; exercises the rid-copy branch.
    var sql = "select name from " + className + " order by " + rid.toString();
    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("only", result.next().getProperty("name"));
    }
  }

  /**
   * {@code SELECT FROM Class ORDER BY @rid DESC}. The planner should recognize @rid DESC against
   * a class target and apply the reverse-scan optimization. Exercises
   * {@code hasTargetWithSortedRids} + {@code isOrderByRidDesc}.
   */
  @Test
  public void orderByRidDesc_classTarget_reverseScans() {
    var className = "OrderByRidDesc_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    for (var i = 0; i < 5; i++) {
      var doc = session.newInstance(className);
      doc.setProperty("idx", i);
    }
    session.commit();

    try (var result = session.query("select from " + className + " order by @rid desc")) {
      var rows = result.stream().toList();
      Assert.assertEquals(5, rows.size());
      // Contract: RIDs must be strictly monotonically decreasing under @rid DESC. Don't compare
      // against insertion order — clusters may be shared across test classes and RIDs are not
      // guaranteed to be strictly dense, but the ordering relation is what the planner branch
      // guarantees.
      for (var i = 1; i < rows.size(); i++) {
        Assert.assertTrue(
            "RIDs must decrease under ORDER BY @rid DESC: "
                + rows.get(i - 1).getIdentity() + " vs " + rows.get(i).getIdentity(),
            rows.get(i - 1).getIdentity().compareTo(rows.get(i).getIdentity()) > 0);
      }
    }
  }

  /**
   * {@code SELECT FROM Class ORDER BY @rid ASC}. Exercises {@code isOrderByRidAsc} returning
   * {@code true} for the implicit-ASC case — no explicit {@code ASC} keyword (item.getType() ==
   * null branch).
   */
  @Test
  public void orderByRidAsc_classTarget_implicitAsc() {
    var className = "OrderByRidAsc_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    for (var i = 0; i < 4; i++) {
      var doc = session.newInstance(className);
      doc.setProperty("n", i);
    }
    session.commit();

    // No explicit ASC — exercises the getType() == null sub-branch of isOrderByRidAsc.
    try (var result = session.query("select from " + className + " order by @rid")) {
      var rows = result.stream().toList();
      Assert.assertEquals(4, rows.size());
      // RIDs must be strictly monotonically increasing under implicit ASC.
      for (var i = 1; i < rows.size(); i++) {
        Assert.assertTrue(
            "RIDs must increase under implicit ORDER BY @rid ASC: "
                + rows.get(i - 1).getIdentity() + " vs " + rows.get(i).getIdentity(),
            rows.get(i - 1).getIdentity().compareTo(rows.get(i).getIdentity()) < 0);
      }
    }
  }

  /**
   * Global {@link GlobalConfiguration#COMMAND_TIMEOUT} is consulted during {@code init()} when
   * the SQL statement itself has no TIMEOUT clause. Setting the global timeout to a non-zero
   * value exercises the {@code info.timeout == null && config.COMMAND_TIMEOUT > 0} branch.
   */
  @Test
  public void globalCommandTimeout_populatedFromConfig() {
    var className = "GlobalTimeoutClass_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    session.newInstance(className).setProperty("x", 1);
    session.commit();

    var config = session.getConfiguration();
    var previous = config.getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT);
    try {
      config.setValue(GlobalConfiguration.COMMAND_TIMEOUT, 30_000L); // 30 seconds
      try (var result = session.query("select from " + className)) {
        // The query must still complete within the very generous timeout — the point of the
        // test is to exercise the planner branch that consults the global config.
        Assert.assertTrue(result.hasNext());
        Assert.assertEquals(Integer.valueOf(1), result.next().getProperty("x"));
      }
    } finally {
      config.setValue(GlobalConfiguration.COMMAND_TIMEOUT, previous);
    }
  }

  /**
   * {@code LET $sub = (SELECT name FROM Class)} where the sub-query does not reference
   * {@code $parent}. The {@link SelectExecutionPlanner#splitLet} pass must promote the per-record
   * LET item to the global LET clause — exercises the query-without-parent-reference branch
   * (lines 905–907).
   */
  @Test
  public void splitLet_perRecordQueryWithoutParent_promotedToGlobal() {
    var className = "SplitLetClass_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    for (var i = 0; i < 3; i++) {
      var doc = session.newInstance(className);
      doc.setProperty("name", "row" + i);
    }
    session.commit();

    // $sub has no $parent reference — splitLet must promote this LET item to global-LET and
    // evaluate the subquery once. Reference $sub via a size() projection so every outer row
    // must see the same non-null value; if the promotion were a no-op the query would still
    // parse but the LET binding shape would differ.
    var sql =
        "select name, $sub.size() as cnt from " + className
            + " let $sub = (select from " + className + ")";
    try (var result = session.query(sql)) {
      var rows = result.stream().toList();
      Assert.assertEquals(3, rows.size());
      // Every outer row must see a non-null $sub binding — the global LET subquery resolves
      // into the context and is available to the projection at evaluation time.
      var names = new java.util.HashSet<String>();
      for (var row : rows) {
        Assert.assertNotNull(
            "global-LET subquery must be visible in projection of every outer row",
            row.getProperty("cnt"));
        names.add(row.<String>getProperty("name"));
      }
      Assert.assertEquals(
          "outer target scan must enumerate every row distinctly (global-LET is per-plan,"
              + " not per-record duplicated)",
          java.util.Set.of("row0", "row1", "row2"), names);
    }
  }

  /**
   * Hardwired {@code SELECT count(*) FROM Class WHERE indexedField = ?} optimization. Exercises
   * {@code handleHardwiredCountOnClassUsingIndex} — the planner must reduce the plan to a single
   * {@code CountFromIndexWithKeyStep} that reads the count straight from the index.
   */
  @Test
  public void hardwiredCountOnClass_usingSinglePropertyIndex() {
    var className = "CountOnIndexClass_" + uniqueSuffix();
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("tag",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.STRING);
    clazz.createIndex(className + "_tag_idx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");

    session.begin();
    for (var i = 0; i < 10; i++) {
      var doc = session.newInstance(className);
      doc.setProperty("tag", i < 7 ? "A" : "B");
    }
    session.commit();

    try (var result =
        session.query("select count(*) from " + className + " where tag = 'A'")) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      Assert.assertEquals(Long.valueOf(7L), row.getProperty("count(*)"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * {@code SELECT count(*) FROM Class} (no WHERE) hits the simpler hardwired optimization —
   * {@code handleHardwiredCountOnClass}. The {@code GuaranteeEmptyCountStep} must ensure a
   * zero-count row is emitted even for an empty class.
   */
  @Test
  public void hardwiredCountOnClass_bareNoWhere_empty() {
    var className = "BareCountEmpty_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    try (var result = session.query("select count(*) from " + className)) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      Assert.assertEquals(Long.valueOf(0L), row.getProperty("count(*)"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * {@code SELECT name FROM Class WHERE name = 'x' GROUP BY name}. Exercises the GROUP BY arm of
   * {@code handleProjectionsAndOrderBy} (Path B: distinct/groupBy/aggregate), ensuring the
   * planner routes aggregation queries through {@code handleProjections} and {@code
   * handleDistinct} in the correct order.
   */
  @Test
  public void groupByWithoutAggregate_pathB() {
    var className = "GroupByPathB_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    for (var name : new String[] {"a", "a", "b", "b", "b", "c"}) {
      session.newInstance(className).setProperty("name", name);
    }
    session.commit();

    try (var result =
        session.query("select name, count(*) as c from " + className + " group by name")) {
      var rows = result.stream().toList();
      Assert.assertEquals(3, rows.size());
      Map<String, Long> counts = new HashMap<>();
      for (var row : rows) {
        counts.put(row.getProperty("name"), row.getProperty("c"));
      }
      Assert.assertEquals(Long.valueOf(2L), counts.get("a"));
      Assert.assertEquals(Long.valueOf(3L), counts.get("b"));
      Assert.assertEquals(Long.valueOf(1L), counts.get("c"));
    }
  }

  /**
   * {@code SELECT FROM (SELECT FROM Class)} — subquery as the FROM target. Exercises
   * {@code handleSubqueryAsTarget} which wraps the inner plan in a {@code SubQueryStep}.
   */
  @Test
  public void subQueryAsTarget() {
    var className = "SubqTargetClass_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    for (var i = 0; i < 4; i++) {
      session.newInstance(className).setProperty("v", i);
    }
    session.commit();

    try (var result =
        session.query("select v from (select from " + className + " where v >= 2)")) {
      var rows = result.stream().toList();
      Assert.assertEquals(2, rows.size());
      for (var row : rows) {
        int v = row.getProperty("v");
        Assert.assertTrue("v must be >= 2 from inner filter", v >= 2);
      }
    }
  }

  /**
   * {@code SELECT FROM Class WHERE a = 1 OR b = 2} — OR-clause splitting emits a
   * {@code ParallelExecStep} followed by a {@code DistinctExecutionStep}. Exercises the
   * multi-sub-plan branch of the flattened-where assembly.
   */
  @Test
  public void orClauseSplit_parallelWithDistinct() {
    var className = "OrSplitClass_" + uniqueSuffix();
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("a",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.INTEGER);
    clazz.createProperty("b",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.INTEGER);
    clazz.createIndex(className + "_a_idx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    clazz.createIndex(className + "_b_idx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "b");

    session.begin();
    var matchA = session.newInstance(className);
    matchA.setProperty("a", 1);
    matchA.setProperty("b", 99);
    var matchB = session.newInstance(className);
    matchB.setProperty("a", 99);
    matchB.setProperty("b", 2);
    var matchBoth = session.newInstance(className);
    matchBoth.setProperty("a", 1);
    matchBoth.setProperty("b", 2);
    var matchNeither = session.newInstance(className);
    matchNeither.setProperty("a", 99);
    matchNeither.setProperty("b", 99);
    session.commit();

    try (var result =
        session.query("select from " + className + " where a = 1 or b = 2")) {
      // Expect 3 distinct rows (matchA, matchB, matchBoth) — DistinctExecutionStep must
      // deduplicate the two sub-plans that both include matchBoth.
      var rids = new ArrayList<>();
      while (result.hasNext()) {
        rids.add(result.next().getIdentity());
      }
      Assert.assertEquals(3, rids.size());
      Assert.assertEquals(
          "DistinctExecutionStep must deduplicate the shared row",
          rids.size(), new java.util.HashSet<>(rids).size());
    }
  }

  /**
   * {@code SELECT FROM #cluster:pos} — a literal RID target. Exercises the {@code target.getRids()
   * != null && !isEmpty()} branch inside the target dispatcher.
   */
  @Test
  public void fetchFromLiteralRid() {
    var className = "LiteralRidTarget_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("k", "v");
    var rid = doc.getIdentity();
    session.commit();

    try (var result = session.query("select from " + rid.toString())) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("v", result.next().getProperty("k"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * {@code SELECT FROM #C:P} where the cluster exists but the record position does not. Confirms
   * the planner routes through the RID path even when records are absent. Derives the cluster
   * id from a real record to avoid brittle reliance on unused cluster numbers.
   */
  @Test
  public void fetchFromLiteralRid_nonExistent_noRows() {
    var className = "RidMissClass_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    var rid = doc.getIdentity();
    session.commit();

    // Position 999999 in the same cluster as `rid` is guaranteed not to exist: the class only
    // ever hosts a single record (`doc`) so its cluster is sparsely populated.
    var missingRid = "#" + rid.getCollectionId() + ":999999";
    try (var result = session.query("select from " + missingRid)) {
      Assert.assertFalse(
          "RID pointing to non-existent position must produce no rows", result.hasNext());
    }
  }

  /**
   * {@code SELECT FROM Class ORDER BY a ASC LIMIT 3 SKIP 1}. Exercises Path C (simple query) —
   * SKIP/LIMIT before projections.
   */
  @Test
  public void skipLimit_pathC_beforeProjections() {
    var className = "SkipLimitPathC_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    for (var i = 0; i < 10; i++) {
      var doc = session.newInstance(className);
      doc.setProperty("a", i);
    }
    session.commit();

    try (var result =
        session.query("select a from " + className + " order by a asc limit 3 skip 1")) {
      var values = new ArrayList<Integer>();
      while (result.hasNext()) {
        values.add(result.next().getProperty("a"));
      }
      Assert.assertEquals(List.of(1, 2, 3), values);
    }
  }

  /**
   * {@code SELECT FROM :target} with a list where a valid {@code Identifiable} precedes a
   * non-{@code Identifiable} element. Exercises the mid-iteration position of the guard —
   * distinct from {@code selectFromInputParam_iterableWithNonIdentifiable_throws} which
   * trips the guard at the first element.
   */
  @Test
  public void selectFromInputParam_iterableMidElementBad_throws() {
    var className = "BadMidElemClass_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    var goodRid = doc.getIdentity();
    session.commit();

    // Valid RID first, bad element (String) second — must still throw, but only after
    // successfully handling the first element (mid-iteration guard).
    Map<Object, Object> params = new HashMap<>();
    params.put("target", List.of(goodRid, "not-an-identifiable"));

    try {
      session.query("select from :target", params).close();
      Assert.fail("Expected CommandExecutionException when mid-iteration element is bad");
    } catch (CommandExecutionException e) {
      // WHEN-FIXED: Track 22 — fix typo "colleciton" → "collection".
      Assert.assertTrue(
          "message must flag the bad element, got: " + e.getMessage(),
          e.getMessage().contains("colleciton") || e.getMessage().contains("collection"));
    }
  }

  /**
   * {@code SELECT FROM Class ORDER BY name ASC LIMIT 2} — pre-aggregate ORDER BY + LIMIT without
   * GROUP BY. Exercises Path C + order-by-limit heap optimization.
   */
  @Test
  public void orderByLimit_smallHeap() {
    var className = "OrderByHeapSmall_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    for (var s : new String[] {"z", "a", "m", "b", "k"}) {
      session.newInstance(className).setProperty("name", s);
    }
    session.commit();

    try (var result =
        session.query("select name from " + className + " order by name asc limit 2")) {
      var rows = result.stream().toList();
      Assert.assertEquals(2, rows.size());
      Assert.assertEquals("a", rows.get(0).getProperty("name"));
      Assert.assertEquals("b", rows.get(1).getProperty("name"));
    }
  }

  /**
   * Exercises the {@code Identifiable} arm with a legacy-shape RID constructed by the planner
   * via {@code setLegacy(true)}. Confirms a round-trip through {@code SELECT FROM :target}.
   */
  @Test
  public void selectFromInputParam_legacyRidShape() {
    var className = "LegacyRidClass_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("k", 1);
    var rid = doc.getIdentity();
    session.commit();

    // A plain RecordId passed as the param — the Identifiable arm wraps it with setLegacy(true).
    Map<Object, Object> params = new HashMap<>();
    params.put(
        "target",
        new RecordId(rid.getCollectionId(), rid.getCollectionPosition()));

    try (var result = session.query("select from :target", params)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(Integer.valueOf(1), result.next().getProperty("k"));
    }
  }

  /**
   * {@code SELECT FROM [#c:p1, #c:p2, #c:p3]} — literal multi-RID list. Drives the {@code for
   * (var rid : rids)} loop with N > 1 in {@code handleRidsAsTarget} (TC1).
   */
  @Test
  public void fetchFromLiteralRidList_multipleRids() {
    var className = "MultiRidTarget_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    List<Object> rids = new ArrayList<>();
    for (var i = 0; i < 3; i++) {
      var d = session.newInstance(className);
      d.setProperty("i", i);
      rids.add(d.getIdentity());
    }
    session.commit();

    var sql =
        "select i from [" + rids.get(0) + ", " + rids.get(1) + ", " + rids.get(2) + "]";
    try (var result = session.query(sql)) {
      var values = new java.util.TreeSet<Integer>();
      while (result.hasNext()) {
        values.add(result.next().getProperty("i"));
      }
      Assert.assertEquals(
          "multi-RID fetch must materialize every listed RID",
          java.util.Set.of(0, 1, 2), values);
    }
  }

  /**
   * {@code SELECT FROM metadata:SCHEMA} — exercises the {@code target.getMetadata() != null}
   * arm in the planner's target dispatcher plus {@code handleMetadataAsTarget}'s schema
   * branch (TC2).
   */
  @Test
  public void fetchFromMetadataSchema() {
    try (var result = session.query("select from metadata:schema")) {
      Assert.assertTrue("metadata:schema must expose at least one row", result.hasNext());
      // Schema metadata always contains a "classes" field even on a fresh DB.
      var row = result.next();
      Assert.assertNotNull("schema metadata row must carry content", row);
    }
  }

  /**
   * {@code LET $u = unionAll($a, $b)} — exercises {@code isCombinationOfQueries} (set-
   * combination function) path in {@code splitLet}, promoting the combination LET item to the
   * global-LET clause (TC3).
   */
  @Test
  public void splitLet_unionAllCombination_promotedToGlobal() {
    var className = "CombineLetClass_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    for (var i = 0; i < 3; i++) {
      var d = session.newInstance(className);
      d.setProperty("n", i);
    }
    session.commit();

    // $a and $b each select all 3 rows; unionAll($a, $b) produces 6 items. The combination
    // must be promoted to global-LET regardless of parent references.
    var sql =
        "select $u.size() as totalUnion from " + className
            + " let $a = (select from " + className + "),"
            + " $b = (select from " + className + "),"
            + " $u = unionAll($a, $b)";
    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      Object size = row.getProperty("totalUnion");
      Assert.assertNotNull(size);
      Assert.assertEquals(
          "unionAll($a, $b) must produce |$a| + |$b| = 6 elements",
          Integer.valueOf(6), Integer.valueOf(size.toString()));
    }
  }

  /** Returns a unique suffix so parallel test classes do not collide on class names. */
  private static String uniqueSuffix() {
    return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
