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

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * SQL round-trip tests that exercise uncovered branches of the small execution planners:
 * {@link UpdateExecutionPlanner}, {@link InsertExecutionPlanner}, {@link
 * CreateEdgeExecutionPlanner}, {@link DeleteEdgeExecutionPlanner}, and {@link
 * CreateVertexExecutionPlanner}. Track 8 Step 10.
 *
 * <p>These planners are small enough that a single focused class can cover their remaining
 * branches via {@code session.query(sql)} / {@code session.execute(sql)}. Each test targets a
 * specific branch (RETURN BEFORE, TIMEOUT, UPSERT without unique index, INSERT FROM SELECT, etc.)
 * rather than broadly exercising the SQL feature.
 */
public class SmallPlannerBranchTest extends TestUtilsFixture {

  // --- UpdateExecutionPlanner ---

  /**
   * {@code UPDATE ... RETURN AFTER @this}. Exercises the {@code returnAfter &&
   * returnProjection != null} branch of {@link UpdateExecutionPlanner#handleResultForReturnAfter}.
   * The planner must chain both {@code ConvertToResultInternalStep} and
   * {@code ProjectionCalculationStep}.
   */
  @Test
  public void update_returnAfter_withProjection() {
    var className = "UpdReturnAfterProj_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("v", 1);
    session.commit();

    session.begin();
    try (var result =
        session.execute("update " + className + " set v = 2 return after $current.v as newV")) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      Assert.assertEquals(Integer.valueOf(2), row.getProperty("newV"));
    }
    session.commit();
  }

  /**
   * {@code UPDATE ... RETURN BEFORE} is explicitly rejected at parse time by {@link
   * com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUpdateStatement#isReturnBefore()} which
   * always throws {@code DatabaseException("BEFORE is not supported")}. The RETURN-BEFORE
   * branches of {@link UpdateExecutionPlanner} ({@code handleReturnBefore},
   * {@code handleResultForReturnBefore}, {@code CopyRecordContentBeforeUpdateStep},
   * {@code UnwrapPreviousValueStep}) are therefore unreachable via valid SQL.
   *
   * <p>WHEN-FIXED: Track 22 — remove the {@code returnBefore} field and its dependent branches
   * from {@link UpdateExecutionPlanner} (plus the now-dead {@code UnwrapPreviousValueStep} and
   * {@code CopyRecordContentBeforeUpdateStep}) once the parser reject is preserved as the only
   * contract.
   */
  @Test
  public void update_returnBefore_rejectedAtParseTime() {
    var className = "UpdReturnBefore_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    session.newInstance(className).setProperty("v", 10);
    session.commit();

    session.begin();
    try {
      session.execute("update " + className + " set v = 20 return before").close();
      Assert.fail("Expected RETURN BEFORE to be rejected at parse/plan time");
    } catch (DatabaseException e) {
      Assert.assertEquals(
          "Production pins this exact message; a change here signals Track 22 may have removed "
              + "the parser reject",
          "BEFORE is not supported", e.getMessage());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code UPDATE ... TIMEOUT 60000}. Exercises {@link UpdateExecutionPlanner#handleTimeout} —
   * the {@code timeout.getVal() > 0} branch chains a {@code TimeoutStep}. Also hits the
   * {@code this.timeout = oUpdateStatement.getTimeout().copy()} branch in the constructor.
   */
  @Test
  public void update_withTimeoutClause() {
    var className = "UpdTimeoutClass_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    session.newInstance(className).setProperty("v", 0);
    session.commit();

    session.begin();
    try (var result =
        session.execute("update " + className + " set v = 1 timeout 60000")) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(Long.valueOf(1L), result.next().getProperty("count"));
    }
    session.commit();
  }

  /**
   * {@code UPDATE ... PUT map = 'k', 'v'}. Exercises the {@code TYPE_PUT} arm of {@link
   * UpdateExecutionPlanner#handleOperations} which throws {@link CommandExecutionException}
   * because the new executor does not support {@code PUT}/{@code ADD}/{@code INCREMENT}.
   */
  @Test
  public void update_putOperation_throws() {
    var className = "UpdPutClass_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    session.newInstance(className).setProperty("dummy", "x");
    session.commit();

    session.begin();
    try {
      session.execute("update " + className + " put m = 'k', 'v'").close();
      Assert.fail("Expected CommandExecutionException for UPDATE PUT (unsupported)");
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          "message must reference PUT/ADD/INCREMENT: " + e.getMessage(),
          e.getMessage().contains("PUT")
              || e.getMessage().contains("ADD")
              || e.getMessage().contains("INCREMENT"));
    } finally {
      session.rollback();
    }
  }

  // --- InsertExecutionPlanner ---

  /**
   * {@code INSERT INTO Target FROM (SELECT FROM Src)}. Exercises {@link
   * InsertExecutionPlanner#handleInsertSelect} — the {@code selectStatement != null} branch that
   * chains {@code SubQueryStep}, {@code CopyEntityStep}, and {@code RemoveEdgePointersStep}.
   */
  @Test
  public void insert_fromSelect_copiesToTargetClass() {
    var srcName = "InsertFromSelectSrc_" + uniqueSuffix();
    var tgtName = "InsertFromSelectTgt_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(srcName);
    session.getMetadata().getSchema().createClass(tgtName);

    session.begin();
    for (var i = 0; i < 3; i++) {
      var d = session.newInstance(srcName);
      d.setProperty("n", i);
    }
    session.commit();

    session.begin();
    try (var result =
        session.execute("insert into " + tgtName + " from (select from " + srcName + ")")) {
      // 3 inserts expected.
      var inserted = 0;
      while (result.hasNext()) {
        result.next();
        inserted++;
      }
      Assert.assertEquals(3, inserted);
    }
    session.commit();

    try (var q = session.query("select from " + tgtName)) {
      Assert.assertEquals(3, q.stream().toList().size());
    }
  }

  /**
   * {@code INSERT INTO Class CONTENT {...}} — single CONTENT entry exercises the CONTENT arm
   * of {@link InsertExecutionPlanner#handleSetFields} and {@link
   * InsertExecutionPlanner#handleCreateRecord}'s {@code body.getContent() != null} branch.
   *
   * <p><b>Pinned behaviour:</b> multi-CONTENT syntax ({@code CONTENT {a}, CONTENT {b}}) is
   * accepted by the grammar but the planner's for-loop over {@code body.getContent()} chains
   * every {@link UpdateContentStep} onto the same record stream, causing later CONTENT entries
   * to overwrite earlier ones. A single CONTENT block is the supported form; multi-CONTENT is
   * effectively "use the last one".
   *
   * <p>WHEN-FIXED: Track 22 — decide whether multi-CONTENT should produce N records (matching
   * the {@code tot} record-count that {@code handleCreateRecord} already computes) or be
   * rejected at parse time.
   */
  @Test
  public void insert_contentBlock_singleEntry() {
    var className = "InsertContentSingle_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    try (var result =
        session.execute("insert into " + className + " content {\"k\":42, \"name\":\"x\"}")) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      Assert.assertEquals(Integer.valueOf(42), row.getProperty("k"));
      Assert.assertEquals("x", row.getProperty("name"));
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  /**
   * {@code INSERT INTO Class SET k = 'v'}. Exercises {@link
   * InsertExecutionPlanner#handleSetFields}'s {@code getSetExpressions() != null} branch (the
   * final {@code else if} arm that converts SET expressions to update items).
   */
  @Test
  public void insert_setExpressions() {
    var className = "InsertSet_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    try (var result =
        session.execute("insert into " + className + " set k = 'v', n = 42")) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      Assert.assertEquals("v", row.getProperty("k"));
      Assert.assertEquals(Integer.valueOf(42), row.getProperty("n"));
    }
    session.commit();
  }

  /**
   * {@code INSERT INTO Class (a, b) VALUES (1, 2), (3, 4), (5, 6)} — multi-row VALUES exercises
   * {@link InsertExecutionPlanner#handleCreateRecord}'s {@code body.getValueExpressions().size()}
   * branch for computing record count.
   */
  @Test
  public void insert_multiRowValues() {
    var className = "InsertMultiValues_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    try (var result =
        session.execute(
            "insert into " + className + " (a, b) values (1, 2), (3, 4), (5, 6)")) {
      var rows = result.stream().toList();
      Assert.assertEquals(3, rows.size());
    }
    session.commit();

    try (var q = session.query("select a, b from " + className + " order by a asc")) {
      var all = q.stream().toList();
      Assert.assertEquals(3, all.size());
      Assert.assertEquals(Integer.valueOf(1), all.get(0).getProperty("a"));
      Assert.assertEquals(Integer.valueOf(6), all.get(2).getProperty("b"));
    }
  }

  /**
   * {@code INSERT INTO Class RETURN $current.k as result VALUES (...)}. Exercises {@link
   * InsertExecutionPlanner#handleReturn} — the {@code returnStatement != null} branch that
   * appends a {@code ProjectionCalculationStep}.
   */
  @Test
  public void insert_withReturnProjection() {
    var className = "InsertReturn_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    session.begin();
    try (var result =
        session.execute(
            "insert into " + className + " set k = 'hello' return $current.k as result")) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("hello", result.next().getProperty("result"));
    }
    session.commit();
  }

  // --- CreateEdgeExecutionPlanner ---

  /**
   * {@code CREATE EDGE Foo UPSERT FROM v1 TO v2} when class {@code Foo} has no unique index on
   * {@code out/in}. Exercises the {@code uniqueIndexName == null} guard inside {@link
   * CreateEdgeExecutionPlanner#createExecutionPlan} — must throw {@link
   * CommandExecutionException}.
   */
  @Test
  public void createEdge_upsert_withoutUniqueIndex_throws() {
    var eClass = "EdgeNoUpsertIdx_" + uniqueSuffix();
    var vClass = "VForEdgeNoIdx_" + uniqueSuffix();
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClass, schema.getClass("V"));
    schema.createClass(eClass, schema.getClass("E"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    session.commit();

    session.begin();
    try {
      session
          .execute(
              "create edge " + eClass + " upsert from " + v1.getIdentity() + " to "
                  + v2.getIdentity())
          .close();
      Assert.fail("Expected CommandExecutionException — UPSERT without unique(out,in) index");
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          "message must reference UPSERT/unique index: " + e.getMessage(),
          e.getMessage().toLowerCase().contains("upsert")
              && e.getMessage().toLowerCase().contains("unique"));
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code CREATE EDGE Missing UPSERT FROM v1 TO v2} where {@code Missing} is not a registered
   * schema class. Exercises the {@code clazz == null} branch — must throw {@link
   * CommandExecutionException} with "not found" in the message.
   */
  @Test
  public void createEdge_upsert_unknownClass_throws() {
    var vClass = "VForMissingEdge_" + uniqueSuffix();
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    session.commit();

    session.begin();
    try {
      session
          .execute(
              "create edge MissingEdgeClass_" + uniqueSuffix() + " upsert from "
                  + v1.getIdentity() + " to " + v2.getIdentity())
          .close();
      Assert.fail("Expected CommandExecutionException — unknown class with UPSERT");
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          "message must indicate the class was not found, got: " + e.getMessage(),
          e.getMessage().toLowerCase().contains("not found"));
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code CREATE EDGE} without a target class — the default target {@code "E"} must be used.
   * Exercises the {@code targetClass == null} branch that assigns {@code new SQLIdentifier("E")}.
   */
  @Test
  public void createEdge_defaultTargetClassE() {
    var vClass = "VDefaultEdgeTarget_" + uniqueSuffix();
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    session.commit();

    session.begin();
    try (var result =
        session.execute("create edge from " + v1.getIdentity() + " to " + v2.getIdentity())) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    session.commit();

    // Verify the edge exists and its class name is the default "E".
    try (var result =
        session.query("select @class as cls from (select expand(outE()) from "
            + v1.getIdentity() + ")")) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "CREATE EDGE without target class must default to class \"E\"",
          "E", result.next().getProperty("cls"));
    }
  }

  /**
   * {@code CREATE EDGE Foo FROM v1 TO v2 SET weight = 1.5}. Exercises the SET-expressions branch
   * of {@link CreateEdgeExecutionPlanner#handleSetFields}.
   */
  @Test
  public void createEdge_withSetProperties() {
    var vClass = "VForEdgeSet_" + uniqueSuffix();
    var eClass = "EForEdgeSet_" + uniqueSuffix();
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClass, schema.getClass("V"));
    schema.createClass(eClass, schema.getClass("E"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    session.commit();

    session.begin();
    try (var result =
        session.execute(
            "create edge " + eClass + " from " + v1.getIdentity() + " to " + v2.getIdentity()
                + " set weight = 1.5")) {
      Assert.assertTrue(result.hasNext());
    }
    session.commit();

    try (var result = session.query("select expand(outE()) from " + v1.getIdentity())) {
      Assert.assertTrue(result.hasNext());
      var edge = result.next();
      // SQL literal 1.5 is parsed as a Float by default; use numeric equality to stay robust
      // against future changes in literal promotion.
      Number weight = edge.getProperty("weight");
      Assert.assertNotNull("SET weight must populate the edge property", weight);
      Assert.assertEquals("SET weight must preserve the numeric value 1.5",
          1.5, weight.doubleValue(), 0.0);
    }
  }

  /**
   * {@code CREATE EDGE Foo UPSERT FROM v1 TO v2} when {@code Foo} has a correct composite
   * unique index on {@code (out, in)}. Exercises the {@code uniqueIndexName != null} happy path
   * of the UPSERT code — second CREATE must return the existing edge, not a new one.
   */
  @Test
  public void createEdge_upsert_happyPath_reusesEdge() {
    var vClass = "VForUpsertOK_" + uniqueSuffix();
    var eClass = "EForUpsertOK_" + uniqueSuffix();
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClass, schema.getClass("V"));
    var e = schema.createClass(eClass, schema.getClass("E"));
    e.createProperty("out", PropertyType.LINK);
    e.createProperty("in", PropertyType.LINK);
    e.createIndex(eClass + "_out_in_idx", SchemaClass.INDEX_TYPE.UNIQUE, "out", "in");

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    session.commit();

    session.begin();
    com.jetbrains.youtrackdb.internal.core.db.record.record.RID firstRid;
    try (var first =
        session.execute(
            "create edge " + eClass + " upsert from " + v1.getIdentity() + " to "
                + v2.getIdentity())) {
      Assert.assertTrue(first.hasNext());
      firstRid = first.next().getIdentity();
    }
    session.commit();

    session.begin();
    com.jetbrains.youtrackdb.internal.core.db.record.record.RID secondRid;
    try (var second =
        session.execute(
            "create edge " + eClass + " upsert from " + v1.getIdentity() + " to "
                + v2.getIdentity())) {
      Assert.assertTrue(second.hasNext());
      secondRid = second.next().getIdentity();
    }
    session.commit();

    // UPSERT must return the same edge record, not a fresh duplicate.
    Assert.assertEquals(
        "UPSERT on existing (out,in) must return the same edge RID, not insert a new one",
        firstRid, secondRid);

    // Exactly one edge must exist.
    try (var result = session.query("select expand(outE('" + eClass + "')) from "
        + v1.getIdentity())) {
      var edges = result.stream().toList();
      Assert.assertEquals("UPSERT must dedupe on (out,in)", 1, edges.size());
      Assert.assertEquals(firstRid, edges.getFirst().getIdentity());
    }
  }

  // --- DeleteEdgeExecutionPlanner ---

  /**
   * {@code DELETE EDGE Foo FROM v1}. Exercises {@link DeleteEdgeExecutionPlanner}'s branches
   * for FROM-only edge deletion (no {@code TO} clause).
   */
  @Test
  public void deleteEdge_fromOnly_removesAll() {
    var vClass = "VDelEdgeFrom_" + uniqueSuffix();
    var eClass = "EDelEdgeFrom_" + uniqueSuffix();
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClass, schema.getClass("V"));
    schema.createClass(eClass, schema.getClass("E"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    var v3 = session.newVertex(vClass);
    session.commit();

    session.begin();
    session.execute(
        "create edge " + eClass + " from " + v1.getIdentity() + " to " + v2.getIdentity())
        .close();
    session.execute(
        "create edge " + eClass + " from " + v1.getIdentity() + " to " + v3.getIdentity())
        .close();
    session.commit();

    session.begin();
    try (var result =
        session.execute("delete edge " + eClass + " from " + v1.getIdentity())) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    session.commit();

    try (var result = session.query("select expand(outE('" + eClass + "')) from "
        + v1.getIdentity())) {
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * {@code DELETE EDGE Foo TO v2}. Exercises the branch with only {@code TO} specified.
   */
  @Test
  public void deleteEdge_toOnly() {
    var vClass = "VDelEdgeTo_" + uniqueSuffix();
    var eClass = "EDelEdgeTo_" + uniqueSuffix();
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClass, schema.getClass("V"));
    schema.createClass(eClass, schema.getClass("E"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    session.commit();

    session.begin();
    session.execute(
        "create edge " + eClass + " from " + v1.getIdentity() + " to " + v2.getIdentity())
        .close();
    session.commit();

    session.begin();
    try (var result = session.execute("delete edge " + eClass + " to " + v2.getIdentity())) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    session.commit();

    try (var result = session.query("select expand(inE('" + eClass + "')) from "
        + v2.getIdentity())) {
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * {@code DELETE EDGE Foo FROM v1 TO v2 WHERE weight > 0}. Exercises the combined
   * {@code FROM}+{@code TO}+{@code WHERE} code path of {@link DeleteEdgeExecutionPlanner}.
   */
  @Test
  public void deleteEdge_fromToWithWhere() {
    var vClass = "VDelEdgeFromTo_" + uniqueSuffix();
    var eClass = "EDelEdgeFromTo_" + uniqueSuffix();
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClass, schema.getClass("V"));
    schema.createClass(eClass, schema.getClass("E"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    session.commit();

    session.begin();
    // Two edges — only one has weight > 0. Create via SQL so records are session-bound.
    session.execute(
        "create edge " + eClass + " from " + v1.getIdentity() + " to " + v2.getIdentity()
            + " set weight = 5")
        .close();
    session.execute(
        "create edge " + eClass + " from " + v1.getIdentity() + " to " + v2.getIdentity()
            + " set weight = 0")
        .close();
    session.commit();

    session.begin();
    try (var result =
        session.execute(
            "delete edge " + eClass + " from " + v1.getIdentity() + " to " + v2.getIdentity()
                + " where weight > 0")) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    session.commit();

    try (var result = session.query("select expand(outE('" + eClass + "')) from "
        + v1.getIdentity())) {
      var remaining = result.stream().toList();
      Assert.assertEquals("only the weight=0 edge must remain", 1, remaining.size());
      Assert.assertEquals(Integer.valueOf(0), remaining.getFirst().getProperty("weight"));
    }
  }

  /**
   * {@code DELETE EDGE Foo} (no endpoints, no WHERE) — exercises the no-scope deletion path which
   * enumerates all edges of the class.
   */
  @Test
  public void deleteEdge_noScope_deletesAll() {
    var vClass = "VDelEdgeAll_" + uniqueSuffix();
    var eClass = "EDelEdgeAll_" + uniqueSuffix();
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClass, schema.getClass("V"));
    schema.createClass(eClass, schema.getClass("E"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    session.newRegularEdge(eClass, v1, v2);
    session.newRegularEdge(eClass, v2, v1);
    session.commit();

    session.begin();
    try (var result = session.execute("delete edge " + eClass)) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    session.commit();

    try (var result = session.query("select count(*) as c from " + eClass)) {
      Assert.assertEquals(Long.valueOf(0L), result.next().getProperty("c"));
    }
  }

  // --- CreateVertexExecutionPlanner / DeleteVertexExecutionPlanner ---

  /**
   * {@code CREATE VERTEX} (no target class) — exercises the {@code targetClass == null} default
   * to {@code "V"}.
   */
  @Test
  public void createVertex_defaultTargetV() {
    session.begin();
    try (var result = session.execute("create vertex")) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      Assert.assertNotNull(row.getIdentity());
    }
    session.commit();
  }

  /**
   * {@code DELETE VERTEX #cluster:pos}. Exercises {@link DeleteVertexExecutionPlanner} on a RID
   * target.
   */
  @Test
  public void deleteVertex_byRid() {
    var vClass = "VForDelVertex_" + uniqueSuffix();
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    var v = session.newVertex(vClass);
    var rid = v.getIdentity();
    session.commit();

    session.begin();
    try (var result = session.execute("delete vertex " + rid)) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    session.commit();

    try (var result = session.query("select count(*) as c from " + vClass)) {
      Assert.assertEquals(Long.valueOf(0L), result.next().getProperty("c"));
    }
  }

  /**
   * {@code INSERT INTO Class CONTENT :p} — CONTENT sourced from a map-valued input parameter
   * exercises the {@code getContentInputParam()} arm of {@link
   * InsertExecutionPlanner#handleSetFields} (TC5), distinct from the literal-JSON CONTENT arm
   * covered by {@code insert_contentBlock_singleEntry}.
   */
  @Test
  public void insert_contentFromInputParam() {
    var className = "InsertContentParam_" + uniqueSuffix();
    session.getMetadata().getSchema().createClass(className);

    Map<String, Object> params = new HashMap<>();
    params.put("p", Map.of("k", 7, "name", "viaParam"));

    session.begin();
    try (var result =
        session.execute("insert into " + className + " content :p", params)) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      Assert.assertEquals(Integer.valueOf(7), row.getProperty("k"));
      Assert.assertEquals("viaParam", row.getProperty("name"));
    }
    session.commit();
  }

  /** Returns a unique suffix so class names never collide across test methods. */
  private static String uniqueSuffix() {
    return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
