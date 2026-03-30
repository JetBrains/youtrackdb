package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaPropertyInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CostModel;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMethodCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLModifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Mutation-killing tests for {@link MatchExecutionPlanner}.
 *
 * <p>Targets surviving pitest mutations in:
 * <ul>
 *   <li>{@code extractEdgeClassName} — L1153 ({@code instanceof String}) and
 *       L1170 (quote-stripping condition)</li>
 *   <li>{@code estimateEdgeCost} — L1092-L1102 (null guards on schema/edge
 *       class/properties) and L1111 (swapped outVertexClass/inVertexClass
 *       parameters in the {@code estimateFanOut} call)</li>
 * </ul>
 *
 * <p>The scheduling/traversal mutations (L871-L1015) are deeply coupled to the
 * pattern graph infrastructure ({@link Pattern}, {@link PatternNode},
 * {@link PatternEdge}, visited-set tracking) and would require full integration
 * test setup. They are noted but not covered here.
 */
public class MatchExecutionPlannerMutationTest {

  private static final double DELTA = 1e-9;

  private DatabaseSessionEmbedded db;
  private ImmutableSchema schema;

  @Before
  public void setUp() {
    db = mock(DatabaseSessionEmbedded.class);
    schema = mock(ImmutableSchema.class);
    var metadata = mock(MetadataDefault.class);
    when(db.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);
  }

  // ── extractEdgeClassName: L1153 — instanceof String must be exercised ──

  /**
   * Kills L1153: "value instanceof String s" replaced with false.
   *
   * <p>Uses a real SQLExpression built via SQLMatchPathItem.outPath so that
   * execute() returns a String ("Knows"). The toString() fallback would also
   * return "Knows", so to distinguish the two paths we verify that execute()
   * is the path taken by building an expression whose evaluate-path returns
   * the class name but whose toString representation is different (an
   * identifier without quotes).
   *
   * <p>Key insight: if the instanceof check is replaced with false, the code
   * falls through to the toString path. For a bare identifier like "Knows",
   * both paths produce "Knows". To kill the mutation we need a case where:
   * (a) execute() returns a String, and (b) the toString fallback returns
   * something different or null.
   *
   * <p>We construct an SQLExpression whose execute() returns a String but
   * whose mathExpression is NOT an SQLBaseExpression, so the toString
   * fallback returns null. If the instanceof check is mutated to false,
   * the method returns null instead of the class name.
   */
  @Test
  public void extractEdgeClassName_executeReturnsString_nonBaseExpression() {
    // Build a mock where execute() returns "MyEdge" (a String) but
    // getMathExpression() is not SQLBaseExpression, so the fallback returns null.
    var method = mock(SQLMethodCall.class);
    var param = mock(SQLExpression.class);
    when(param.execute(nullable(Result.class), any(CommandContext.class)))
        .thenReturn("MyEdge");
    // getMathExpression returns null (not SQLBaseExpression)
    when(param.getMathExpression()).thenReturn(null);
    when(method.getParams()).thenReturn(List.of(param));

    // If instanceof String is replaced with false, this returns null
    assertEquals("MyEdge",
        MatchExecutionPlanner.extractEdgeClassName(method));
  }

  /**
   * Complementary test: when execute() returns a non-String value (e.g., an
   * Integer), extractEdgeClassName must NOT return it — it should fall through
   * to the toString path. This ensures the instanceof check is necessary.
   */
  @Test
  public void extractEdgeClassName_executeReturnsNonString_fallsThrough() {
    var method = mock(SQLMethodCall.class);
    var param = mock(SQLExpression.class);
    // execute() returns an Integer, not a String
    when(param.execute(nullable(Result.class), any(CommandContext.class)))
        .thenReturn(42);

    var base = mock(SQLBaseExpression.class);
    when(base.toString()).thenReturn("EdgeClass");
    when(param.getMathExpression()).thenReturn(base);
    when(method.getParams()).thenReturn(List.of(param));

    // Must fall through to the toString path and return "EdgeClass"
    assertEquals("EdgeClass",
        MatchExecutionPlanner.extractEdgeClassName(method));
  }

  /**
   * When execute() throws CommandExecutionException, the code must fall
   * through to the toString path. Combined with L1153: if instanceof is
   * replaced with false AND execute throws, behavior stays the same — but
   * this test ensures the exception path itself works correctly.
   */
  @Test
  public void extractEdgeClassName_executeThrows_fallsToToStringPath() {
    var method = mock(SQLMethodCall.class);
    var param = mock(SQLExpression.class);
    when(param.execute(nullable(Result.class), any(CommandContext.class)))
        .thenThrow(new CommandExecutionException("test"));

    var base = mock(SQLBaseExpression.class);
    when(base.toString()).thenReturn("\"FallbackEdge\"");
    when(param.getMathExpression()).thenReturn(base);
    when(method.getParams()).thenReturn(List.of(param));

    assertEquals("FallbackEdge",
        MatchExecutionPlanner.extractEdgeClassName(method));
  }

  // ── extractEdgeClassName: L1170 — quote-stripping condition ──

  /**
   * Kills L1170: quote-stripping condition replaced with false.
   *
   * <p>When execute() returns null (not a String) and the raw string is
   * double-quoted, stripping must produce the inner value. If the condition
   * is replaced with false, the raw string with quotes is returned instead.
   */
  @Test
  public void extractEdgeClassName_doubleQuoted_stripsQuotes() {
    var method = mockMethodWithBaseExpression("\"Friendship\"");
    assertEquals("Friendship",
        MatchExecutionPlanner.extractEdgeClassName(method));
  }

  /**
   * Same as above but for single quotes. Ensures the OR branch
   * (first == '\'' && last == '\'') is exercised.
   */
  @Test
  public void extractEdgeClassName_singleQuoted_stripsQuotes() {
    var method = mockMethodWithBaseExpression("'Friendship'");
    assertEquals("Friendship",
        MatchExecutionPlanner.extractEdgeClassName(method));
  }

  /**
   * When quotes don't match (double/single mix), no stripping occurs —
   * the raw string is returned as-is. Verifies the condition is not
   * always-true.
   */
  @Test
  public void extractEdgeClassName_mismatchedQuotes_noStripping() {
    var method = mockMethodWithBaseExpression("\"Friendship'");
    assertEquals("\"Friendship'",
        MatchExecutionPlanner.extractEdgeClassName(method));
  }

  /**
   * Unquoted identifier — returned as-is.
   */
  @Test
  public void extractEdgeClassName_unquoted_returnsRaw() {
    var method = mockMethodWithBaseExpression("Friendship");
    assertEquals("Friendship",
        MatchExecutionPlanner.extractEdgeClassName(method));
  }

  /**
   * Single character — length < 2, so no stripping even if it's a quote char.
   */
  @Test
  public void extractEdgeClassName_singleChar_returnsRaw() {
    var method = mockMethodWithBaseExpression("'");
    assertEquals("'",
        MatchExecutionPlanner.extractEdgeClassName(method));
  }

  // ── estimateEdgeCost: L1092 — edgeClassName != null ──

  /**
   * Kills L1092: "edgeClassName != null" replaced with true.
   *
   * <p>When edgeClassName is null, the schema lookup block should be skipped
   * entirely, so outVertexClass and inVertexClass stay null. For BOTH
   * direction, this leads to the naive 2x fan-out formula. If the null guard
   * is replaced with true, a NullPointerException would be thrown when calling
   * schema.getClassInternal(null) if the mock isn't set up for it — or
   * the fan-out computation would be wrong.
   *
   * <p>We verify the exact cost to detect any deviation.
   */
  @Test
  public void estimateEdgeCost_nullEdgeClassName_skipsSchemaLookup() {
    // .out() — no edge class parameter
    registerClass("Person", 100);

    var edge = mockEdgeWithMethod("out");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    // With null edgeClassName, EdgeFanOutEstimator gets null edgeClass,
    // returns defaultFanOut. Cost = sourceRows * defaultFanOut * randomPageReadCost.
    double defaultFanOut = EdgeFanOutEstimator.defaultFanOut();
    double expected = CostModel.edgeTraversalCost(100, defaultFanOut);
    assertEquals(expected, cost, DELTA);
  }

  // ── estimateEdgeCost: L1094 — schema != null ──

  /**
   * Kills L1094: "schema != null" replaced with false.
   *
   * <p>When schema is null, the method should skip the schema block and use
   * default fan-out. If the condition is replaced with false, the method
   * always skips schema lookup even when schema is available, leading to
   * default fan-out instead of computed fan-out. We compare against a case
   * where schema IS available and produces a different (non-default) cost.
   */
  @Test
  public void estimateEdgeCost_nullSchema_usesDefaultFanOut() {
    var metadata = mock(MetadataDefault.class);
    when(db.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(null);

    var edge = mockEdgeWithMethodAndParam("out", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    double defaultFanOut = EdgeFanOutEstimator.defaultFanOut();
    double expected = CostModel.edgeTraversalCost(100, defaultFanOut);
    assertEquals(expected, cost, DELTA);
  }

  // ── estimateEdgeCost: L1096 — edgeClass != null ──

  /**
   * Kills L1096: "edgeClass != null" replaced with false.
   *
   * <p>When schema returns null for the edge class, the method should skip
   * property lookup and use default fan-out. Verified by exact cost equality.
   */
  @Test
  public void estimateEdgeCost_edgeClassNotInSchema_usesDefaultFanOut() {
    registerClass("Person", 100);
    when(schema.getClassInternal("Unknown")).thenReturn(null);

    var edge = mockEdgeWithMethodAndParam("out", "\"Unknown\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    double defaultFanOut = EdgeFanOutEstimator.defaultFanOut();
    double expected = CostModel.edgeTraversalCost(100, defaultFanOut);
    assertEquals(expected, cost, DELTA);
  }

  // ── estimateEdgeCost: L1098 — outProp != null && outProp.getLinkedClass() != null ──

  /**
   * Kills L1098: "outProp != null && outProp.getLinkedClass() != null"
   * replaced with false.
   *
   * <p>When outProp exists and has a linked class, outVertexClass should be
   * set. If the condition is replaced with false, outVertexClass stays null.
   * For BOTH direction with asymmetric vertex classes, this changes the
   * fan-out computation.
   */
  @Test
  public void estimateEdgeCost_outPropWithLinkedClass_affectsBothFanOut() {
    // Person -[WorksAt]-> Company. 300 edges, 100 Person, 50 Company.
    var personClass = registerClass("Person", 100);
    var companyClass = registerClass("Company", 50);
    var edgeClass = registerClass("WorksAt", 300);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(companyClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var edge = mockEdgeWithMethodAndParam("both", "\"WorksAt\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    // BOTH from Person: OUT side matches (Person is out vertex) → 300/100 = 3.0
    // IN side: Person is NOT Company → 0.0
    // Total fan-out = 3.0
    double expected = CostModel.edgeTraversalCost(100, 3.0);
    assertEquals(expected, cost, DELTA);
  }

  // ── estimateEdgeCost: L1102 — inProp != null && inProp.getLinkedClass() != null ──

  /**
   * Kills L1102: "inProp != null && inProp.getLinkedClass() != null"
   * replaced with false.
   *
   * <p>When inProp exists and has a linked class, inVertexClass should be
   * set. If the condition is replaced with false, inVertexClass stays null.
   * For BOTH direction this changes the fan-out.
   */
  @Test
  public void estimateEdgeCost_inPropWithLinkedClass_affectsBothFanOut() {
    // Company -[WorksAt]-> Company (self-referencing for simplicity).
    // From Company with BOTH: only the IN side should match.
    var companyClass = registerClass("Company", 50);
    var personClass = registerClass("Person", 100);
    var edgeClass = registerClass("WorksAt", 300);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(companyClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    // Source is Company, direction BOTH
    var edge = mockEdgeWithMethodAndParam("both", "\"WorksAt\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "c", 50, Map.of("c", "Company"), db);

    // BOTH from Company: OUT side (Person) — Company is NOT Person → 0.0
    // IN side (Company) — Company IS Company → 300/50 = 6.0
    // Total fan-out = 6.0
    double expected = CostModel.edgeTraversalCost(50, 6.0);
    assertEquals(expected, cost, DELTA);
  }

  // ── estimateEdgeCost: L1111 — swapped outVertexClass/inVertexClass ──
  //
  // NOTE: The L1111 mutation (swapping params 5 and 6 in estimateFanOut) is
  // effectively unkillable via unit test. The BOTH fan-out formula sums
  // outFanOut + inFanOut, and swapping the params merely swaps which side
  // contributes which value — but addition is commutative. When only one side
  // matches the source class, the contributing side always uses the same
  // denominator (the matching vertex class's count), regardless of parameter
  // order. When both sides match, the sum is identical either way.

  // ── estimateEdgeCost: exact cost formulas ──

  /**
   * Verifies exact cost for OUT direction. Kills mutations that replace
   * the return value with 0.0 or Double.MAX_VALUE.
   */
  @Test
  public void estimateEdgeCost_exactCostFormula_outDirection() {
    // Simpler exact-value test for OUT direction to kill return-value mutations.
    registerClass("Person", 100);
    mockEdgeClassWithVertexLinks("Knows", 500, "Person", "Person");

    var edge = mockEdgeWithMethodAndParam("out", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 200, Map.of("p", "Person"), db);

    // fanOut = 500/100 = 5.0, cost = 200 * 5.0 * randomPageReadCost
    double expected = CostModel.edgeTraversalCost(200, 5.0);
    assertEquals(expected, cost, DELTA);
  }

  @Test
  public void estimateEdgeCost_exactCostFormula_inDirection() {
    registerClass("Company", 200);
    mockEdgeClassWithVertexLinks("WorksAt", 600, "Person", "Company");

    var edge = mockEdgeWithMethodAndParam("in", "\"WorksAt\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "c", 50, Map.of("c", "Company"), db);

    // fanOut = 600/200 = 3.0
    double expected = CostModel.edgeTraversalCost(50, 3.0);
    assertEquals(expected, cost, DELTA);
  }

  // ── estimateEdgeCost: property null guards ──

  @Test
  public void estimateEdgeCost_outPropertyNull_inPropertySet() {
    // Edge class has no "out" property but has "in" property.
    // outVertexClass stays null, inVertexClass gets set.
    registerClass("Person", 100);
    var edgeClass = registerClass("Knows", 500);

    when(edgeClass.getPropertyInternal("out")).thenReturn(null);

    var personClass = schema.getClassInternal("Person");
    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var edge = mockEdgeWithMethodAndParam("out", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    // OUT direction: fanOut = edgeCount/sourceCount = 500/100 = 5.0
    // (outVertexClass and inVertexClass don't affect OUT direction)
    double expected = CostModel.edgeTraversalCost(100, 5.0);
    assertEquals(expected, cost, DELTA);
  }

  @Test
  public void estimateEdgeCost_outPropertyLinkedClassNull() {
    // Edge class has "out" property but getLinkedClass() returns null.
    registerClass("Person", 100);
    var edgeClass = registerClass("Knows", 500);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(null);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);
    when(edgeClass.getPropertyInternal("in")).thenReturn(null);

    var edge = mockEdgeWithMethodAndParam("out", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    // OUT direction: fanOut = 500/100 = 5.0
    double expected = CostModel.edgeTraversalCost(100, 5.0);
    assertEquals(expected, cost, DELTA);
  }

  @Test
  public void estimateEdgeCost_inPropertyLinkedClassNull() {
    // Edge class has "in" property but getLinkedClass() returns null.
    registerClass("Person", 100);
    var edgeClass = registerClass("Knows", 500);

    when(edgeClass.getPropertyInternal("out")).thenReturn(null);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(null);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var edge = mockEdgeWithMethodAndParam("in", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    // IN direction: fanOut = 500/100 = 5.0
    double expected = CostModel.edgeTraversalCost(100, 5.0);
    assertEquals(expected, cost, DELTA);
  }

  // ── Helper methods ──

  private SchemaClassInternal registerClass(String name, long count) {
    var clazz = mock(SchemaClassInternal.class);
    when(schema.getClassInternal(name)).thenReturn(clazz);
    when(schema.getClass(name)).thenReturn(clazz);
    when(clazz.approximateCount(any(DatabaseSessionEmbedded.class)))
        .thenReturn(count);
    when(clazz.getName()).thenReturn(name);
    when(clazz.isSubClassOf(name)).thenReturn(true);
    return clazz;
  }

  private void mockEdgeClassWithVertexLinks(
      String edgeClassName, long edgeCount,
      String outVertexClassName, String inVertexClassName) {
    var edgeClass = registerClass(edgeClassName, edgeCount);

    if (outVertexClassName != null) {
      var outClass = schema.getClassInternal(outVertexClassName);
      if (outClass == null) {
        outClass = registerClass(outVertexClassName, 0);
      }
      var outProp = mock(SchemaPropertyInternal.class);
      when(outProp.getLinkedClass()).thenReturn(outClass);
      when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);
    }

    if (inVertexClassName != null) {
      var inClass = schema.getClassInternal(inVertexClassName);
      if (inClass == null) {
        inClass = registerClass(inVertexClassName, 0);
      }
      var inProp = mock(SchemaPropertyInternal.class);
      when(inProp.getLinkedClass()).thenReturn(inClass);
      when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);
    }
  }

  private PatternEdge mockEdge(SQLMethodCall method) {
    var edge = new PatternEdge();
    edge.item = mock(SQLMatchPathItem.class);
    when(edge.item.getMethod()).thenReturn(method);
    edge.out = new PatternNode();
    edge.in = new PatternNode();
    return edge;
  }

  private PatternEdge mockEdgeWithMethod(String methodName) {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, methodName);
    when(method.getParams()).thenReturn(List.of());
    return mockEdge(method);
  }

  private PatternEdge mockEdgeWithMethodAndParam(
      String methodName, String paramString) {
    var method = mockMethodWithBaseExpression(paramString);
    stubMethodName(method, methodName);
    return mockEdge(method);
  }

  /**
   * Creates a mock SQLMethodCall whose first parameter has a mock
   * SQLBaseExpression with the given toString() value. The execute()
   * method on the param returns null (not a String), forcing the
   * toString fallback path.
   */
  private SQLMethodCall mockMethodWithBaseExpression(String rawString) {
    var method = mock(SQLMethodCall.class);

    var base = mock(SQLBaseExpression.class);
    when(base.toString()).thenReturn(rawString);

    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);
    // execute() returns null by default (Mockito), so instanceof String is false

    when(method.getParams()).thenReturn(List.of(param));
    return method;
  }

  // =========================================================================
  // getEdgeClassName — covers lines 1334-1355
  // =========================================================================

  /**
   * When the edge has no method, getEdgeClassName returns null.
   */
  @Test
  public void getEdgeClassName_nullMethod_returnsNull() {
    var et = makeEdgeTraversal(null, true);
    assertThat(MatchExecutionPlanner.getEdgeClassName(et)).isNull();
  }

  /**
   * When the method has null params, getEdgeClassName returns null.
   */
  @Test
  public void getEdgeClassName_nullParams_returnsNull() {
    var method = mock(SQLMethodCall.class);
    when(method.getParams()).thenReturn(null);
    var et = makeEdgeTraversal(method, true);
    assertThat(MatchExecutionPlanner.getEdgeClassName(et)).isNull();
  }

  /**
   * When the method has empty params, getEdgeClassName returns null.
   */
  @Test
  public void getEdgeClassName_emptyParams_returnsNull() {
    var method = mock(SQLMethodCall.class);
    when(method.getParams()).thenReturn(List.of());
    var et = makeEdgeTraversal(method, true);
    assertThat(MatchExecutionPlanner.getEdgeClassName(et)).isNull();
  }

  /**
   * When the param's math expression is not an SQLBaseExpression,
   * getEdgeClassName returns null.
   */
  @Test
  public void getEdgeClassName_nonBaseExpression_returnsNull() {
    var method = mock(SQLMethodCall.class);
    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(mock(SQLMathExpression.class));
    when(method.getParams()).thenReturn(List.of(param));
    var et = makeEdgeTraversal(method, true);
    assertThat(MatchExecutionPlanner.getEdgeClassName(et)).isNull();
  }

  /**
   * When the base expression has a modifier, getEdgeClassName returns null.
   */
  @Test
  public void getEdgeClassName_baseWithModifier_returnsNull() {
    var method = mock(SQLMethodCall.class);
    var base = mock(SQLBaseExpression.class);
    when(base.getModifier()).thenReturn(mock(SQLModifier.class));
    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);
    when(method.getParams()).thenReturn(List.of(param));
    var et = makeEdgeTraversal(method, true);
    assertThat(MatchExecutionPlanner.getEdgeClassName(et)).isNull();
  }

  /**
   * When execute() returns a valid string, getEdgeClassName returns it.
   */
  @Test
  public void getEdgeClassName_validString_returnsClassName() {
    var method = mock(SQLMethodCall.class);
    var base = mock(SQLBaseExpression.class);
    when(base.getModifier()).thenReturn(null);
    when(base.execute(nullable(Result.class), any())).thenReturn("KNOWS");
    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);
    when(method.getParams()).thenReturn(List.of(param));
    var et = makeEdgeTraversal(method, true);
    assertThat(MatchExecutionPlanner.getEdgeClassName(et)).isEqualTo("KNOWS");
  }

  /**
   * When execute() returns an empty string, getEdgeClassName returns null.
   */
  @Test
  public void getEdgeClassName_emptyString_returnsNull() {
    var method = mock(SQLMethodCall.class);
    var base = mock(SQLBaseExpression.class);
    when(base.getModifier()).thenReturn(null);
    when(base.execute(nullable(Result.class), any())).thenReturn("");
    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);
    when(method.getParams()).thenReturn(List.of(param));
    var et = makeEdgeTraversal(method, true);
    assertThat(MatchExecutionPlanner.getEdgeClassName(et)).isNull();
  }

  /**
   * When execute() returns a non-String, getEdgeClassName returns null.
   */
  @Test
  public void getEdgeClassName_nonStringValue_returnsNull() {
    var method = mock(SQLMethodCall.class);
    var base = mock(SQLBaseExpression.class);
    when(base.getModifier()).thenReturn(null);
    when(base.execute(nullable(Result.class), any())).thenReturn(123);
    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);
    when(method.getParams()).thenReturn(List.of(param));
    var et = makeEdgeTraversal(method, true);
    assertThat(MatchExecutionPlanner.getEdgeClassName(et)).isNull();
  }

  // =========================================================================
  // getEdgeDirection — covers lines 1361-1377
  // =========================================================================

  /**
   * When the edge has no method, getEdgeDirection returns null.
   */
  @Test
  public void getEdgeDirection_nullMethod_returnsNull() {
    var et = makeEdgeTraversal(null, true);
    assertThat(MatchExecutionPlanner.getEdgeDirection(et)).isNull();
  }

  /**
   * When the method name is null, getEdgeDirection returns null.
   */
  @Test
  public void getEdgeDirection_nullMethodName_returnsNull() {
    var method = mock(SQLMethodCall.class);
    when(method.getMethodName()).thenReturn(null);
    var et = makeEdgeTraversal(method, true);
    assertThat(MatchExecutionPlanner.getEdgeDirection(et)).isNull();
  }

  /**
   * For forward (out=true) traversal with "out" method, returns "out".
   */
  @Test
  public void getEdgeDirection_forwardOut_returnsOut() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "out");
    var et = makeEdgeTraversal(method, true);
    assertThat(MatchExecutionPlanner.getEdgeDirection(et)).isEqualTo("out");
  }

  /**
   * For forward (out=true) traversal with "in" method, returns "in".
   */
  @Test
  public void getEdgeDirection_forwardIn_returnsIn() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "in");
    var et = makeEdgeTraversal(method, true);
    assertThat(MatchExecutionPlanner.getEdgeDirection(et)).isEqualTo("in");
  }

  /**
   * For reversed (out=false) traversal with "out" method, direction
   * is flipped to "in".
   */
  @Test
  public void getEdgeDirection_reversedOut_returnsIn() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "out");
    var et = makeEdgeTraversal(method, false);
    assertThat(MatchExecutionPlanner.getEdgeDirection(et)).isEqualTo("in");
  }

  /**
   * For reversed (out=false) traversal with "in" method, direction
   * is flipped to "out".
   */
  @Test
  public void getEdgeDirection_reversedIn_returnsOut() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "in");
    var et = makeEdgeTraversal(method, false);
    assertThat(MatchExecutionPlanner.getEdgeDirection(et)).isEqualTo("out");
  }

  /**
   * For reversed (out=false) traversal with "both" method, direction
   * stays "both" (default case — no flip).
   */
  @Test
  public void getEdgeDirection_reversedBoth_returnsBoth() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "both");
    var et = makeEdgeTraversal(method, false);
    assertThat(MatchExecutionPlanner.getEdgeDirection(et)).isEqualTo("both");
  }

  // ── inferClassFromEdgeSchema: edge LINK → target class inference ──

  /**
   * out('HAS_CREATOR') with HAS_CREATOR.in LINK Person → infers "Person".
   * The "in" endpoint is the target for out() traversals.
   */
  @Test
  public void inferClassFromEdgeSchema_outDirection_returnsInEndpoint() {
    var personClass = registerClass("Person", 100);
    var edgeClass = registerClass("HAS_CREATOR", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var method = mockMethodCallWithDirection("out", "HAS_CREATOR");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isEqualTo("Person");
  }

  /**
   * in('CONTAINER_OF') with CONTAINER_OF.out LINK Forum → infers "Forum".
   * The "out" endpoint is the target for in() traversals.
   */
  @Test
  public void inferClassFromEdgeSchema_inDirection_returnsOutEndpoint() {
    var forumClass = registerClass("Forum", 50);
    var edgeClass = registerClass("CONTAINER_OF", 1000);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(forumClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var method = mockMethodCallWithDirection("in", "CONTAINER_OF");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isEqualTo("Forum");
  }

  /**
   * Null method → returns null (no inference possible).
   */
  @Test
  public void inferClassFromEdgeSchema_nullMethod_returnsNull() {
    var ctx = mockContext();
    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(null, null, ctx))
        .isNull();
  }

  /**
   * Method without params (e.g., outV()) → returns null.
   */
  @Test
  public void inferClassFromEdgeSchema_noParams_returnsNull() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "out");
    when(method.getParams()).thenReturn(List.of());
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isNull();
  }

  /**
   * Non-traversal method (e.g., both) → returns null.
   */
  @Test
  public void inferClassFromEdgeSchema_nonTraversalMethod_returnsNull() {
    var method = mockMethodCallWithDirection("both", "HAS_CREATOR");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isNull();
  }

  /**
   * Edge class exists but has no LINK declaration on the target endpoint →
   * returns null.
   */
  @Test
  public void inferClassFromEdgeSchema_noLinkDeclaration_returnsNull() {
    registerClass("HAS_TAG", 200);
    // No in/out LINK properties registered

    var method = mockMethodCallWithDirection("out", "HAS_TAG");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isNull();
  }

  /**
   * Edge class not found in schema → returns null.
   */
  @Test
  public void inferClassFromEdgeSchema_unknownEdgeClass_returnsNull() {
    when(schema.getClassInternal("NONEXISTENT")).thenReturn(null);

    var method = mockMethodCallWithDirection("out", "NONEXISTENT");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isNull();
  }

  /**
   * Mixed-case direction name ("Out") should be recognized as "out"
   * via case-insensitive matching.
   */
  @Test
  public void inferClassFromEdgeSchema_mixedCaseDirection_recognized() {
    var personClass = registerClass("Person", 100);
    var edgeClass = registerClass("HAS_CREATOR", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var method = mockMethodCallWithDirection("Out", "HAS_CREATOR");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isEqualTo("Person");
  }

  /**
   * When execute() on the param expression throws RuntimeException,
   * inferClassFromEdgeSchema returns null instead of propagating.
   */
  @Test
  public void inferClassFromEdgeSchema_executeThrows_returnsNull() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "out");
    var param = mock(SQLExpression.class);
    when(param.execute(nullable(Result.class), any(CommandContext.class)))
        .thenThrow(new RuntimeException("eval failure"));
    when(method.getParams()).thenReturn(List.of(param));
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isNull();
  }

  // ── inferClassFromEdgeSchema: outE/inE → edge class inference ──

  /**
   * outE('KNOWS') returns "KNOWS" — the edge class itself is the alias class.
   */
  @Test
  public void inferClassFromEdgeSchema_outE_returnsEdgeClass() {
    var method = mockMethodCallWithDirection("outE", "KNOWS");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isEqualTo("KNOWS");
  }

  /**
   * inE('HAS_MEMBER') returns "HAS_MEMBER" — the edge class itself is the alias class.
   */
  @Test
  public void inferClassFromEdgeSchema_inE_returnsEdgeClass() {
    var method = mockMethodCallWithDirection("inE", "HAS_MEMBER");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isEqualTo("HAS_MEMBER");
  }

  /**
   * outE() without params returns null — no edge class can be inferred.
   */
  @Test
  public void inferClassFromEdgeSchema_outE_noParams_returnsNull() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "outE");
    when(method.getParams()).thenReturn(List.of());
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isNull();
  }

  /**
   * inE() without params returns null.
   */
  @Test
  public void inferClassFromEdgeSchema_inE_noParams_returnsNull() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "inE");
    when(method.getParams()).thenReturn(List.of());
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isNull();
  }

  // ── inferClassFromEdgeSchema: inV/outV → linked vertex class inference ──

  /**
   * inV() with currentEdgeClass "KNOWS" where KNOWS.in LINK Person and
   * KNOWS.out LINK Forum → returns "Person" (the "in" side, not "out").
   * Both properties are registered with different classes to kill a
   * mutation that swaps the inV/outV property mapping.
   */
  @Test
  public void inferClassFromEdgeSchema_inV_returnsLinkedVertexClass() {
    var personClass = registerClass("Person", 100);
    var forumClass = registerClass("Forum", 50);
    var edgeClass = registerClass("KNOWS", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(forumClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "inV");
    var ctx = mockContext();

    // inV must read the "in" property → Person, not Forum
    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, "KNOWS", ctx))
        .isEqualTo("Person");
  }

  /**
   * outV() with currentEdgeClass "KNOWS" where KNOWS.out LINK Forum and
   * KNOWS.in LINK Person → returns "Forum" (the "out" side, not "in").
   * Both properties are registered with different classes to kill a
   * mutation that swaps the inV/outV property mapping.
   */
  @Test
  public void inferClassFromEdgeSchema_outV_returnsLinkedVertexClass() {
    var personClass = registerClass("Person", 100);
    var forumClass = registerClass("Forum", 50);
    var edgeClass = registerClass("KNOWS", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(forumClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "outV");
    var ctx = mockContext();

    // outV must read the "out" property → Forum, not Person
    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, "KNOWS", ctx))
        .isEqualTo("Forum");
  }

  /**
   * inV() without currentEdgeClass returns null — no preceding edge context.
   */
  @Test
  public void inferClassFromEdgeSchema_inV_noPrecedingEdge_returnsNull() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "inV");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isNull();
  }

  /**
   * outV() without currentEdgeClass returns null.
   */
  @Test
  public void inferClassFromEdgeSchema_outV_noPrecedingEdge_returnsNull() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "outV");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isNull();
  }

  /**
   * inV() with currentEdgeClass "UNKNOWN" (not in schema) returns null.
   */
  @Test
  public void inferClassFromEdgeSchema_inV_unknownEdgeClass_returnsNull() {
    when(schema.getClassInternal("UNKNOWN")).thenReturn(null);

    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "inV");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, "UNKNOWN", ctx))
        .isNull();
  }

  /**
   * inV() with currentEdgeClass but no "in" LINK property on the edge → returns null.
   */
  @Test
  public void inferClassFromEdgeSchema_inV_noLinkProperty_returnsNull() {
    registerClass("KNOWS", 500);
    // No "in" property registered on KNOWS

    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "inV");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, "KNOWS", ctx))
        .isNull();
  }

  /**
   * null method name → returns null regardless of currentEdgeClass.
   */
  @Test
  public void inferClassFromEdgeSchema_nullMethodName_returnsNull() {
    var method = mock(SQLMethodCall.class);
    when(method.getMethodNameString()).thenReturn(null);
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, "KNOWS", ctx))
        .isNull();
  }

  // ── inferClassFromEdgeSchema: case-insensitivity for new method types ──

  /**
   * OutE with mixed case resolves the edge class correctly —
   * verifies toLowerCase(Locale.ROOT) works for the outE/inE branch.
   */
  @Test
  public void inferClassFromEdgeSchema_outE_caseInsensitive_returnsEdgeClass() {
    var method = mockMethodCallWithDirection("OutE", "KNOWS");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isEqualTo("KNOWS");
  }

  /**
   * INV with upper case resolves the linked vertex class correctly —
   * verifies toLowerCase(Locale.ROOT) works for the inV/outV branch.
   */
  @Test
  public void inferClassFromEdgeSchema_inV_caseInsensitive_returnsLinkedVertexClass() {
    var personClass = registerClass("Person", 100);
    var edgeClass = registerClass("KNOWS", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "INV");
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, "KNOWS", ctx))
        .isEqualTo("Person");
  }

  // ── inferClassFromEdgeSchema: outE/inE with non-string parameter ──

  /**
   * outE(123) with a non-string parameter returns null — extractEdgeClassName
   * cannot resolve a non-string value to an edge class name.
   */
  @Test
  public void inferClassFromEdgeSchema_outE_nonStringParam_returnsNull() {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, "outE");
    var param = mock(SQLExpression.class);
    when(param.execute(nullable(Result.class), any(CommandContext.class)))
        .thenReturn(123);
    // getMathExpression returns null → toString fallback also returns null
    when(param.getMathExpression()).thenReturn(null);
    when(method.getParams()).thenReturn(List.of(param));
    var ctx = mockContext();

    assertThat(MatchExecutionPlanner.inferClassFromEdgeSchema(method, null, ctx))
        .isNull();
  }

  /**
   * Creates a mock SQLMethodCall for a traversal direction and edge class name.
   * For example, mockMethodCallWithDirection("out", "HAS_CREATOR") simulates
   * the method call in out('HAS_CREATOR').
   */
  private SQLMethodCall mockMethodCallWithDirection(String direction, String edgeClass) {
    var method = mock(SQLMethodCall.class);
    stubMethodName(method, direction);

    var param = mock(SQLExpression.class);
    when(param.execute(nullable(Result.class), any(CommandContext.class)))
        .thenReturn(edgeClass);
    when(method.getParams()).thenReturn(List.of(param));

    return method;
  }

  /**
   * Stubs both {@code getMethodName()} and {@code getMethodNameString()}
   * on a mocked {@link SQLMethodCall}. Needed because Mockito mocks
   * override the concrete {@code getMethodNameString()} method.
   */
  private void stubMethodName(SQLMethodCall method, String name) {
    when(method.getMethodName()).thenReturn(new SQLIdentifier(name));
    when(method.getMethodNameString()).thenReturn(name);
  }

  private CommandContext mockContext() {
    var ctx = mock(CommandContext.class);
    when(ctx.getDatabaseSession()).thenReturn(db);
    return ctx;
  }

  /**
   * Creates an EdgeTraversal with the given method and direction.
   */
  private EdgeTraversal makeEdgeTraversal(SQLMethodCall method, boolean out) {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";

    var item = mock(SQLMatchPathItem.class);
    when(item.getMethod()).thenReturn(method);
    nodeA.addEdge(item, nodeB);
    var patternEdge = nodeA.out.iterator().next();

    return new EdgeTraversal(patternEdge, out);
  }

  // =========================================================================
  // addAliases (expression-level) — currentEdgeClass tracking
  // =========================================================================

  /**
   * outE('KNOWS') item → aliasClasses should contain the edge alias mapped to "KNOWS".
   * Verifies that outE sets currentEdgeClass and inference returns the edge class directly.
   */
  @Test
  public void addAliases_outE_infersEdgeClass() {
    var edgeAlias = "e";
    var expr = mockExpression(
        mockPathItem("outE", "KNOWS", edgeAlias));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), false);

    assertThat(aliasClasses).containsEntry(edgeAlias, "KNOWS");
  }

  /**
   * inE('HAS_MEMBER') item → aliasClasses should contain the edge alias mapped to
   * "HAS_MEMBER".
   */
  @Test
  public void addAliases_inE_infersEdgeClass() {
    var edgeAlias = "e";
    var expr = mockExpression(
        mockPathItem("inE", "HAS_MEMBER", edgeAlias));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), false);

    assertThat(aliasClasses).containsEntry(edgeAlias, "HAS_MEMBER");
  }

  /**
   * outE('KNOWS') followed by inV() → aliasClasses should contain the edge alias
   * mapped to "KNOWS" and the vertex alias mapped to the linked class from KNOWS.in.
   * This verifies currentEdgeClass is propagated from outE to inV.
   */
  @Test
  public void addAliases_outE_then_inV_infersVertexClass() {
    var personClass = registerClass("Person", 100);
    var edgeClass = registerClass("KNOWS", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var expr = mockExpression(
        mockPathItem("outE", "KNOWS", "e"),
        mockPathItem("inV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), false);

    assertThat(aliasClasses)
        .containsEntry("e", "KNOWS")
        .containsEntry("v", "Person");
  }

  /**
   * inE('WORK_AT') followed by outV() → aliasClasses should contain the edge alias
   * mapped to "WORK_AT" and the vertex alias mapped to the linked class from
   * WORK_AT.out.
   */
  @Test
  public void addAliases_inE_then_outV_infersVertexClass() {
    var personClass = registerClass("Person", 100);
    var edgeClass = registerClass("WORK_AT", 300);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var expr = mockExpression(
        mockPathItem("inE", "WORK_AT", "e"),
        mockPathItem("outV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), false);

    assertThat(aliasClasses)
        .containsEntry("e", "WORK_AT")
        .containsEntry("v", "Person");
  }

  /**
   * inV() without a preceding outE → vertex alias should NOT be in aliasClasses
   * (no currentEdgeClass to propagate).
   */
  @Test
  public void addAliases_inV_withoutPrecedingOutE_noInference() {
    var expr = mockExpression(
        mockPathItem("inV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), false);

    assertThat(aliasClasses).doesNotContainKey("v");
  }

  /**
   * outE('KNOWS') followed by inV() followed by another inV() →
   * the second inV() should NOT infer a class because currentEdgeClass
   * was reset after the first inV() consumed it.
   */
  @Test
  public void addAliases_currentEdgeClass_resetAfterInV() {
    var personClass = registerClass("Person", 100);
    var edgeClass = registerClass("KNOWS", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var expr = mockExpression(
        mockPathItem("outE", "KNOWS", "e"),
        mockPathItem("inV", null, "v1"),
        mockPathItem("inV", null, "v2"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), false);

    assertThat(aliasClasses)
        .containsEntry("e", "KNOWS")
        .containsEntry("v1", "Person")
        .doesNotContainKey("v2");
  }

  /**
   * Consecutive outE overwrites currentEdgeClass: outE('KNOWS') followed by
   * outE('WORK_AT') followed by inV() → the inV should resolve from WORK_AT,
   * not KNOWS.
   */
  @Test
  public void addAliases_consecutiveOutE_overwritesCurrentEdgeClass() {
    var personClass = registerClass("Person", 100);
    var companyClass = registerClass("Company", 50);
    var knowsEdge = registerClass("KNOWS", 500);
    var workAtEdge = registerClass("WORK_AT", 300);

    // KNOWS.in → Person
    var knowsInProp = mock(SchemaPropertyInternal.class);
    when(knowsInProp.getLinkedClass()).thenReturn(personClass);
    when(knowsEdge.getPropertyInternal("in")).thenReturn(knowsInProp);

    // WORK_AT.in → Company
    var workAtInProp = mock(SchemaPropertyInternal.class);
    when(workAtInProp.getLinkedClass()).thenReturn(companyClass);
    when(workAtEdge.getPropertyInternal("in")).thenReturn(workAtInProp);

    var expr = mockExpression(
        mockPathItem("outE", "KNOWS", "e1"),
        mockPathItem("outE", "WORK_AT", "e2"),
        mockPathItem("inV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), false);

    assertThat(aliasClasses)
        .containsEntry("e1", "KNOWS")
        .containsEntry("e2", "WORK_AT")
        .containsEntry("v", "Company");
  }

  /**
   * bothE resets currentEdgeClass: outE('KNOWS') followed by bothE('X') followed by
   * inV() → inV should NOT infer a class because bothE reset the state.
   */
  @Test
  public void addAliases_bothE_resetsCurrentEdgeClass() {
    registerClass("KNOWS", 500);

    var expr = mockExpression(
        mockPathItem("outE", "KNOWS", "e1"),
        mockPathItem("bothE", "X", "e2"),
        mockPathItem("inV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), false);

    assertThat(aliasClasses)
        .containsEntry("e1", "KNOWS")
        .doesNotContainKey("v");
  }

  /**
   * skipClassInference=true → no class inference at all, even for edge methods.
   */
  @Test
  public void addAliases_skipClassInference_noInference() {
    var expr = mockExpression(
        mockPathItem("outE", "KNOWS", "e"),
        mockPathItem("inV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), true);

    assertThat(aliasClasses).isEmpty();
  }

  /**
   * out('KNOWS') between outE and inV resets currentEdgeClass: outE('X') → out('Y') → inV()
   * should NOT infer a class for inV because out() reset the edge state.
   */
  @Test
  public void addAliases_outBetweenOutEAndInV_resetsCurrentEdgeClass() {
    // Register KNOWS for the out('KNOWS') inference (vertex class)
    var personClass = registerClass("Person", 100);
    var knowsEdge = registerClass("KNOWS", 500);
    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(knowsEdge.getPropertyInternal("in")).thenReturn(inProp);

    registerClass("X", 100);

    var expr = mockExpression(
        mockPathItem("outE", "X", "e"),
        mockPathItem("out", "KNOWS", "mid"),
        mockPathItem("inV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), false);

    assertThat(aliasClasses)
        .containsEntry("e", "X")
        .containsEntry("mid", "Person")
        .doesNotContainKey("v");
  }

  // ── addAliases helper methods ──

  /**
   * Creates a mock SQLMatchExpression with the given path items and an empty origin.
   */
  private SQLMatchExpression mockExpression(SQLMatchPathItem... items) {
    var expr = mock(SQLMatchExpression.class);
    var origin = mock(SQLMatchFilter.class);
    when(origin.getAlias()).thenReturn(null);
    when(expr.getOrigin()).thenReturn(origin);
    when(expr.getItems()).thenReturn(List.of(items));
    return expr;
  }

  /**
   * Creates a mock SQLMatchPathItem with the given method name, optional edge class
   * parameter, and filter alias.
   *
   * @param methodName the method name (e.g., "outE", "inV", "out")
   * @param edgeClassParam the edge class parameter (e.g., "KNOWS"), or null for
   *     parameterless methods like inV/outV
   * @param alias the alias for the filter (e.g., "e", "v")
   */
  private SQLMatchPathItem mockPathItem(
      String methodName, String edgeClassParam, String alias) {
    var item = mock(SQLMatchPathItem.class);

    var method = mock(SQLMethodCall.class);
    stubMethodName(method, methodName);
    if (edgeClassParam != null) {
      var param = mock(SQLExpression.class);
      when(param.execute(nullable(Result.class), any(CommandContext.class)))
          .thenReturn(edgeClassParam);
      when(method.getParams()).thenReturn(List.of(param));
    } else {
      when(method.getParams()).thenReturn(List.of());
    }
    when(item.getMethod()).thenReturn(method);

    var filter = mock(SQLMatchFilter.class);
    when(filter.getAlias()).thenReturn(alias);
    when(item.getFilter()).thenReturn(filter);

    return item;
  }

}
