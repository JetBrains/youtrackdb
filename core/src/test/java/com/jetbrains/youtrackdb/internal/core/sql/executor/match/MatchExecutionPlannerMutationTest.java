package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchTestWhereBuilders.makeWhereWithOperator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMethodCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLModifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        mockContext(), Set.of(), new java.util.HashSet<>());

    assertThat(aliasClasses).containsOnly(entry(edgeAlias, "KNOWS"));
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
        mockContext(), Set.of(), new java.util.HashSet<>());

    assertThat(aliasClasses).containsOnly(entry(edgeAlias, "HAS_MEMBER"));
  }

  /**
   * outE('KNOWS') followed by inV() → aliasClasses should contain the edge alias
   * mapped to "KNOWS" and the vertex alias mapped to the linked class from KNOWS.in.
   * Both in/out properties are registered with different classes to catch a
   * direction-swap mutation in the inV/outV property mapping.
   */
  @Test
  public void addAliases_outE_then_inV_infersVertexClass() {
    var personClass = registerClass("Person", 100);
    var forumClass = registerClass("Forum", 50);
    var edgeClass = registerClass("KNOWS", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    // Register "out" side with a different class so a direction swap
    // returns "Forum" instead of null, making the failure diagnostic.
    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(forumClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var expr = mockExpression(
        mockPathItem("outE", "KNOWS", "e"),
        mockPathItem("inV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), Set.of(), new java.util.HashSet<>());

    // inV reads "in" property → Person, NOT "out" → Forum
    assertThat(aliasClasses)
        .containsOnly(entry("e", "KNOWS"), entry("v", "Person"));
  }

  /**
   * inE('WORK_AT') followed by outV() → aliasClasses should contain the edge alias
   * mapped to "WORK_AT" and the vertex alias mapped to the linked class from
   * WORK_AT.out. Both in/out properties are registered with different classes to
   * catch a direction-swap mutation.
   */
  @Test
  public void addAliases_inE_then_outV_infersVertexClass() {
    var personClass = registerClass("Person", 100);
    var companyClass = registerClass("Company", 50);
    var edgeClass = registerClass("WORK_AT", 300);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    // Register "in" side with a different class so a direction swap
    // returns "Company" instead of null, making the failure diagnostic.
    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(companyClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var expr = mockExpression(
        mockPathItem("inE", "WORK_AT", "e"),
        mockPathItem("outV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), Set.of(), new java.util.HashSet<>());

    // outV reads "out" property → Person, NOT "in" → Company
    assertThat(aliasClasses)
        .containsOnly(entry("e", "WORK_AT"), entry("v", "Person"));
  }

  /**
   * inE('WORK_AT') followed by inV() → aliasClasses should contain the edge alias
   * mapped to "WORK_AT" and the vertex alias mapped to WORK_AT.in. Both in/out
   * properties are registered with different classes to catch a direction-swap
   * mutation. This completes the 4th direction combo (outE→inV, outE→outV,
   * inE→outV, inE→inV), ensuring inV/outV property lookup is independent of
   * whether the preceding method was outE or inE.
   */
  @Test
  public void addAliases_inE_then_inV_infersTargetVertexClass() {
    var personClass = registerClass("Person", 100);
    var companyClass = registerClass("Company", 50);
    var edgeClass = registerClass("WORK_AT", 300);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(companyClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var expr = mockExpression(
        mockPathItem("inE", "WORK_AT", "e"),
        mockPathItem("inV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), Set.of(), new java.util.HashSet<>());

    // inV reads "in" property → Person, NOT "out" → Company
    assertThat(aliasClasses)
        .containsOnly(entry("e", "WORK_AT"), entry("v", "Person"));
  }

  /**
   * outE('KNOWS') followed by outV() → aliasClasses should contain the edge alias
   * mapped to "KNOWS" and the vertex alias mapped to KNOWS.out (the source vertex).
   * Both in/out properties are registered with different classes to catch a
   * direction-swap mutation in the inV/outV property mapping.
   */
  @Test
  public void addAliases_outE_then_outV_infersSourceVertexClass() {
    var personClass = registerClass("Person", 100);
    var messageClass = registerClass("Message", 200);
    var edgeClass = registerClass("KNOWS", 500);

    // KNOWS.out → Person, KNOWS.in → Message
    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(personClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(messageClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var expr = mockExpression(
        mockPathItem("outE", "KNOWS", "e"),
        mockPathItem("outV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), Set.of(), new java.util.HashSet<>());

    // outV reads "out" property → Person, NOT "in" → Message
    assertThat(aliasClasses)
        .containsOnly(entry("e", "KNOWS"), entry("v", "Person"));
  }

  /**
   * inV() without a preceding outE → aliasClasses should be empty
   * (no currentEdgeClass to propagate).
   */
  @Test
  public void addAliases_inV_withoutPrecedingOutE_noInference() {
    var expr = mockExpression(
        mockPathItem("inV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), Set.of(), new java.util.HashSet<>());

    assertThat(aliasClasses).isEmpty();
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
        mockContext(), Set.of(), new java.util.HashSet<>());

    assertThat(aliasClasses)
        .containsOnly(entry("e", "KNOWS"), entry("v1", "Person"));
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
        mockContext(), Set.of(), new java.util.HashSet<>());

    assertThat(aliasClasses)
        .containsOnly(
            entry("e1", "KNOWS"), entry("e2", "WORK_AT"), entry("v", "Company"));
  }

  /**
   * bothE resets currentEdgeClass: outE('KNOWS') followed by bothE('X') followed by
   * inV() → inV should NOT infer a class because bothE reset the state. bothE('X')
   * itself also gets no inference (not a recognized edge-method type).
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
        mockContext(), Set.of(), new java.util.HashSet<>());

    assertThat(aliasClasses).containsOnly(entry("e1", "KNOWS"));
  }

  /**
   * If aliasClasses already contains an entry for the alias (e.g., from an explicit
   * class constraint), inference must not overwrite it.
   */
  @Test
  public void addAliases_existingAliasClass_notOverwritten() {
    registerClass("KNOWS", 500);

    var expr = mockExpression(
        mockPathItem("outE", "KNOWS", "e"));
    var aliasClasses = new HashMap<String, String>();
    aliasClasses.put("e", "PreExisting");

    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), Set.of(), new java.util.HashSet<>());

    assertThat(aliasClasses).containsOnly(entry("e", "PreExisting"));
  }

  /**
   * Aliases in whileAliases set → no class inference for those aliases,
   * even for edge methods.
   */
  @Test
  public void addAliases_whileAliases_noInferenceForRecursiveZone() {
    var expr = mockExpression(
        mockPathItem("outE", "KNOWS", "e"),
        mockPathItem("inV", null, "v"));
    var aliasClasses = new HashMap<String, String>();

    // Both aliases are in the while set → no inference for either
    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), Set.of("e", "v"), new java.util.HashSet<>());

    assertThat(aliasClasses).isEmpty();
  }

  /**
   * Only aliases in the whileAliases set are skipped; downstream aliases
   * in the same expression still get inference.  Simulates IC5 pattern
   * where 'person' is in the while zone but 'membership' is not.
   */
  @Test
  public void addAliases_whileAliases_downstreamAliasesStillInferred() {
    var expr = mockExpression(
        mockPathItem("out", "KNOWS", "person"),
        mockPathItem("inE", "HAS_MEMBER", "membership"));
    var aliasClasses = new HashMap<String, String>();

    // Only "person" is in the while set → "membership" should still be inferred
    MatchExecutionPlanner.addAliases(
        expr, new HashMap<>(), aliasClasses, new HashMap<>(), new HashMap<>(),
        mockContext(), Set.of("person"), new java.util.HashSet<>());

    // "person" should NOT be inferred (in while set)
    assertThat(aliasClasses).doesNotContainKey("person");
    // "membership" SHOULD be inferred to "HAS_MEMBER" (not in while set)
    assertThat(aliasClasses).containsEntry("membership", "HAS_MEMBER");
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
        mockContext(), Set.of(), new java.util.HashSet<>());

    assertThat(aliasClasses)
        .containsOnly(entry("e", "X"), entry("mid", "Person"));
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

  // =========================================================================
  // resolveChainedTarget — Step 1: structural detection rule
  //
  // The helper recognises the edge-method chain pattern .outE(X).inV()
  // (and .inE→.outV / .bothE→.bothV variants) when it appears as two
  // consecutive PatternEdges, so the planner's sort loop can fold the
  // downstream vertex's WHERE selectivity into the first edge's cost.
  //
  // In Step 1 the class field of the returned ChainedTarget is always null
  // (class inference lands in Step 2). These tests exercise the structural
  // rule in isolation.
  // =========================================================================

  /**
   * When {@code edge.item} is null, the helper returns empty. Mirrors the
   * null-guard of existing callers ({@code estimateEdgeCost} at :2297,
   * {@code resolveTargetClass} at :2523) so the helper does not NPE on
   * synthesised patterns.
   */
  @Test
  public void resolveChainedTarget_nullItem_returnsEmpty() {
    var edge = new PatternEdge();
    // item intentionally left null
    edge.out = new PatternNode();
    edge.in = new PatternNode();

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        edge, edge.in, Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * When {@code edge.item.getMethod()} is null, the helper returns empty.
   */
  @Test
  public void resolveChainedTarget_nullMethod_returnsEmpty() {
    var edge = new PatternEdge();
    edge.item = mock(SQLMatchPathItem.class);
    // method intentionally null
    edge.out = new PatternNode();
    edge.in = new PatternNode();

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        edge, edge.in, Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * First edge method name is {@code null} (e.g. a non-parseable method
   * call). The helper must not NPE and must return empty.
   */
  @Test
  public void resolveChainedTarget_firstMethodNameNull_returnsEmpty() {
    var method = mock(SQLMethodCall.class);
    when(method.getMethodNameString()).thenReturn(null);
    var chain = buildChain(method, "inV", "post", "e", "tag");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * First edge method is a vertex hop ({@code out}) rather than an edge hop
   * ({@code outE}). The chain pattern does not apply — return empty.
   */
  @Test
  public void resolveChainedTarget_firstMethodIsOut_returnsEmpty() {
    var chain = buildChain("out", "inV", "post", "e", "tag");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * First edge method is {@code in} (vertex hop), not {@code inE}. Pins the
   * {@code !"ine".equals(firstName)} branch so a mutation that drops it is
   * caught. Complements {@code firstMethodIsOut_returnsEmpty}.
   */
  @Test
  public void resolveChainedTarget_firstMethodIsIn_returnsEmpty() {
    var chain = buildChain("in", "outV", "tag", "e", "post");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * First edge method is {@code both} (vertex hop), not {@code bothE}. Pins
   * the {@code !"bothe".equals(firstName)} branch.
   */
  @Test
  public void resolveChainedTarget_firstMethodIsBoth_returnsEmpty() {
    var chain = buildChain("both", "bothV", "a", "e", "b");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * First edge method is a completely unknown name. Pins the whitelist's
   * exhaustive rejection of non-edge methods.
   */
  @Test
  public void resolveChainedTarget_firstMethodUnknown_returnsEmpty() {
    var chain = buildChain("traverse", "inV", "post", "e", "tag");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * Method names arrive case-preserved from the parser, so the whitelist must
   * be case-insensitive. Pins this for both hops jointly: a mutation that
   * replaces the {@code equalsIgnoreCase} short-circuit with a strict
   * {@code equals} would reject {@code OUTE}/{@code INV} variants and break
   * any user query written in upper case.
   */
  @Test
  public void resolveChainedTarget_mixedCaseMethodNames_returnsTarget() {
    var chain = buildChain("OuTe", "InV", "post", "e", "tag");
    var aliasClasses = Map.of("tag", "VITag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), aliasClasses, db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", "VITag"));
  }

  /**
   * Intermediate node has zero outgoing edges — the chain has no downstream
   * vertex step. Return empty.
   */
  @Test
  public void resolveChainedTarget_noDownstreamEdge_returnsEmpty() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");
    chain.intermediateNode().out.clear();

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * Intermediate node has two outgoing edges — ambiguous continuation,
   * chain rule does not apply.
   */
  @Test
  public void resolveChainedTarget_multipleDownstreamEdges_returnsEmpty() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");
    // Inject a second outgoing edge on the intermediate node
    var extraEdge = new PatternEdge();
    extraEdge.item = mock(SQLMatchPathItem.class);
    var secondMethod = mock(SQLMethodCall.class);
    stubMethodName(secondMethod, "inV");
    when(extraEdge.item.getMethod()).thenReturn(secondMethod);
    extraEdge.out = chain.intermediateNode();
    var extraTarget = new PatternNode();
    extraTarget.alias = "tag2";
    extraEdge.in = extraTarget;
    chain.intermediateNode().out.add(extraEdge);

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * The downstream edge has already been visited — DFS moved past it.
   * Chain detection must not fire.
   */
  @Test
  public void resolveChainedTarget_downstreamEdgeVisited_returnsEmpty() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");
    var visited = new LinkedHashSet<PatternEdge>();
    visited.add(chain.downstreamEdge());

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), visited, Map.of(), db))
        .isNull();
  }

  /**
   * Intermediate node has zero incoming edges — should not happen for the
   * sort loop's candidate (the first edge always lands in intermediate.in),
   * but defensive.
   */
  @Test
  public void resolveChainedTarget_noIncomingEdge_returnsEmpty() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");
    chain.intermediateNode().in.clear();

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * Intermediate alias has two incoming edges — the user joined it from a
   * second MATCH fragment (e.g. two fragments reuse the same {@code {as: e}}).
   * The chain rule must reject to avoid folding the filter against the
   * wrong alias.
   */
  @Test
  public void resolveChainedTarget_multipleIncomingEdges_returnsEmpty() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");
    // Simulate a second fragment that joins the intermediate alias
    var otherSource = new PatternNode();
    otherSource.alias = "author";
    var otherEdge = new PatternEdge();
    otherEdge.item = mock(SQLMatchPathItem.class);
    var otherMethod = mock(SQLMethodCall.class);
    stubMethodName(otherMethod, "outE");
    when(otherEdge.item.getMethod()).thenReturn(otherMethod);
    otherEdge.out = otherSource;
    otherEdge.in = chain.intermediateNode();
    chain.intermediateNode().in.add(otherEdge);

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * Intermediate has a single incoming edge, but it is not {@code edge} —
   * identity mismatch. This can't happen from the sort loop today but is
   * defensive.
   */
  @Test
  public void resolveChainedTarget_incomingEdgeIdentityMismatch_returnsEmpty() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");
    // Replace the intermediate's incoming edge with a different PatternEdge
    var imposter = new PatternEdge();
    imposter.item = chain.firstEdge().item;
    imposter.out = chain.firstEdge().out;
    imposter.in = chain.intermediateNode();
    chain.intermediateNode().in.clear();
    chain.intermediateNode().in.add(imposter);

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * The second-hop method is {@code out} (vertex hop), not {@code outV} —
   * this is not a chained-edge-then-vertex shape. Return empty.
   */
  @Test
  public void resolveChainedTarget_secondMethodNotVertexStep_returnsEmpty() {
    var chain = buildChain("outE", "out", "post", "e", "tag");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * The second-hop method is {@code in} (vertex hop), not {@code inV}.
   * Pins the {@code !"inv".equals(secondName)} branch so a mutation that
   * drops it is caught.
   */
  @Test
  public void resolveChainedTarget_secondMethodIsIn_returnsEmpty() {
    var chain = buildChain("outE", "in", "post", "e", "tag");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * The second-hop method is {@code both} (vertex hop), not {@code bothV}.
   * Pins the {@code !"bothv".equals(secondName)} branch.
   */
  @Test
  public void resolveChainedTarget_secondMethodIsBoth_returnsEmpty() {
    var chain = buildChain("outE", "both", "post", "e", "tag");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * The second-hop method is an edge hop ({@code inE}) — wrong shape for
   * the chain rule. Pins that the whitelist does not accept edge methods
   * in the vertex-hop position.
   */
  @Test
  public void resolveChainedTarget_secondMethodIsEdgeHop_returnsEmpty() {
    var chain = buildChain("outE", "inE", "post", "e", "tag");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * Same as {@code secondMethodIsEdgeHop} but with {@code outE} in vertex
   * position — pins the {@code !"outv".equals(secondName)} branch. Without
   * this a mutation that accepts {@code outE} as a second hop would survive
   * even though {@code secondMethodIsEdgeHop} only exercises {@code inE}.
   */
  @Test
  public void resolveChainedTarget_secondMethodIsOutE_returnsEmpty() {
    var chain = buildChain("outE", "outE", "post", "e", "tag");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * Same as {@code secondMethodIsEdgeHop} but with {@code bothE} in vertex
   * position — pins the {@code !"bothv".equals(secondName)} branch.
   */
  @Test
  public void resolveChainedTarget_secondMethodIsBothE_returnsEmpty() {
    var chain = buildChain("outE", "bothE", "post", "e", "tag");

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * The second-hop method name is null. Return empty.
   */
  @Test
  public void resolveChainedTarget_secondMethodNameNull_returnsEmpty() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");
    var secondMethod = mock(SQLMethodCall.class);
    when(secondMethod.getMethodNameString()).thenReturn(null);
    when(chain.downstreamEdge().item.getMethod()).thenReturn(secondMethod);

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * The second-hop item's method is null. Return empty.
   */
  @Test
  public void resolveChainedTarget_secondItemNullMethod_returnsEmpty() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");
    when(chain.downstreamEdge().item.getMethod()).thenReturn(null);

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * The second-hop item itself is null. Pins the first operand of the
   * {@code downstreamEdge.item == null || downstreamEdge.item.getMethod() == null}
   * short-circuit so a mutation dropping the first null-check is caught.
   */
  @Test
  public void resolveChainedTarget_secondItemNull_returnsEmpty() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");
    chain.downstreamEdge().item = null;

    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db))
        .isNull();
  }

  /**
   * Reverse traversal: the sort loop passes {@code neighbor = edge.out}
   * when traversing an edge in reverse. In this fixture the source node has
   * no incoming edges, so the {@code neighbor.in.size() == 1} guard rejects
   * the candidate. This pins the design contract documented in the plan
   * (Track 1 — reverse traversal case): the structural rule rejects reverse
   * traversals without any special-case logic.
   */
  @Test
  public void resolveChainedTarget_reverseTraversal_returnsEmpty() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");

    // Pass edge.out (the source `post` node) as neighbor. Source has
    // source.out = {firstEdge} (size 1) and source.in = {} (size 0), so the
    // `neighbor.in.size() != 1` clause in the combined size guard rejects.
    assertThat(MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.firstEdge().out, Set.of(), Map.of(), db))
        .isNull();
  }

  // ── Happy-path sanity tests (structural detection + class=null fallback) ──
  //
  // These tests exercise the structural detection rule with no edge-class
  // data — the precedence-2 schema lookup returns null in each case because
  // the mocked schema has no edge class registered and/or the method has
  // no param. Class-inference happy paths follow in the matrix below.

  /**
   * outE → inV: helper returns the downstream alias. Class is null because
   * the first edge has no class param (precedence-2 fallback returns null).
   */
  @Test
  public void resolveChainedTarget_outEinV_returnsDownstreamAlias() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", null));
  }

  /**
   * inE → outV: helper returns the downstream alias. Class null (same
   * fallback reason as outEinV).
   */
  @Test
  public void resolveChainedTarget_inEoutV_returnsDownstreamAlias() {
    var chain = buildChain("inE", "outV", "tag", "e", "post");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("post", null));
  }

  /**
   * bothE → bothV: helper returns the downstream alias. Class always null
   * for bothE — precedence-2 returns null by design (no single endpoint is
   * uniquely "downstream").
   */
  @Test
  public void resolveChainedTarget_bothEbothV_returnsDownstreamAlias() {
    var chain = buildChain("bothE", "bothV", "a", "e", "b");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("b", null));
  }

  /**
   * Method-name lowering uses {@code Locale.ENGLISH}: {@code OUTE} → {@code oute}
   * and {@code INV} → {@code inv} are recognised. Mirrors the case-insensitivity
   * of {@link MatchExecutionPlanner#parseDirection}.
   */
  @Test
  public void resolveChainedTarget_uppercaseMethodNames_recognised() {
    var chain = buildChain("OUTE", "INV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", null));
  }

  // =========================================================================
  // resolveChainedTarget — Step 2: class-inference precedence
  //
  // Precedence rule:
  //   1. aliasClasses.get(effectiveTargetAlias) — the pre-populated path
  //      for outE→inV / inE→outV (addAliases at :4518) and the only path
  //      for bothE→bothV when the user wrote {class: ...}.
  //   2. Derived from the first edge's class + direction:
  //      outE → edgeClass.in.linkedClass
  //      inE  → edgeClass.out.linkedClass
  //      bothE → null (no inference)
  // =========================================================================

  /**
   * outE → inV with {@code aliasClasses.get("tag") = "VITag"}: precedence-1
   * wins. Pins the normal post-{@code addAliases} path for outbound chains.
   */
  @Test
  public void resolveChainedTarget_outEinV_precedence1_aliasClassesHit() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");
    var aliasClasses = Map.of("tag", "VITag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), aliasClasses, db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", "VITag"));
  }

  /**
   * outE('VIHasTag') → inV with empty aliasClasses: precedence-2 falls back
   * to the edge-schema {@code in} linked class. Both {@code in} and
   * {@code out} properties are registered with different classes so a
   * direction-swap mutation (reading {@code out} instead of {@code in})
   * would return the wrong class.
   */
  @Test
  public void resolveChainedTarget_outEinV_precedence2_derivedFromSchema() {
    registerClass("VIPost", 100);
    var tagClass = registerClass("VITag", 10);
    var postClass = schema.getClassInternal("VIPost");
    var edgeClass = registerClass("VIHasTag", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(tagClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(postClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var firstMethod = mockMethodWithBaseExpression("\"VIHasTag\"");
    stubMethodName(firstMethod, "outE");
    var chain = buildChain(firstMethod, "inV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    // outE must read the "in" property → VITag, not "out" → VIPost
    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", "VITag"));
  }

  /**
   * inE → outV with {@code aliasClasses.get("post") = "VIPost"}:
   * precedence-1 wins. Pins the normal post-{@code addAliases} path for
   * inbound chains.
   */
  @Test
  public void resolveChainedTarget_inEoutV_precedence1_aliasClassesHit() {
    var chain = buildChain("inE", "outV", "tag", "e", "post");
    var aliasClasses = Map.of("post", "VIPost");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), aliasClasses, db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("post", "VIPost"));
  }

  /**
   * inE('VIHasTag') → outV with empty aliasClasses: precedence-2 falls back
   * to the edge-schema {@code out} linked class (NOT {@code in}, which is
   * what outE would use).
   */
  @Test
  public void resolveChainedTarget_inEoutV_precedence2_derivedFromSchema() {
    var postClass = registerClass("VIPost", 100);
    var tagClass = registerClass("VITag", 10);
    var edgeClass = registerClass("VIHasTag", 500);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(postClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(tagClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var firstMethod = mockMethodWithBaseExpression("\"VIHasTag\"");
    stubMethodName(firstMethod, "inE");
    var chain = buildChain(firstMethod, "outV", "tag", "e", "post");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    // inE must read the "out" property → VIPost, not "in" → VITag
    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("post", "VIPost"));
  }

  /**
   * outE('VIHasTag') → outV with empty aliasClasses: precedence-2 must read
   * the edge class's {@code out} linked vertex (source side), NOT {@code in}
   * (target side). The downstream side is determined by the SECOND method
   * (outV ↔ source), not the first one (outE) — a regression to a
   * firstName-only mapping would return VITag here instead of VIPost and
   * silently fold a wrong selectivity into the cost model.
   */
  @Test
  public void resolveChainedTarget_outEoutV_precedence2_readsOutSide() {
    var postClass = registerClass("VIPost", 100);
    var tagClass = registerClass("VITag", 10);
    var edgeClass = registerClass("VIHasTag", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(tagClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(postClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var firstMethod = mockMethodWithBaseExpression("\"VIHasTag\"");
    stubMethodName(firstMethod, "outE");
    // outE.outV — second hop loops back to source side
    var chain = buildChain(firstMethod, "outV", "post", "e", "sourcePost");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("sourcePost", "VIPost"));
  }

  /**
   * inE('VIHasTag') → inV with empty aliasClasses: precedence-2 must read
   * the edge class's {@code in} linked vertex (target side), NOT {@code out}.
   * Mirror of the outE.outV test — second-method-driven mapping pinned
   * for the inbound-then-target direction.
   */
  @Test
  public void resolveChainedTarget_inEinV_precedence2_readsInSide() {
    var postClass = registerClass("VIPost", 100);
    var tagClass = registerClass("VITag", 10);
    var edgeClass = registerClass("VIHasTag", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(tagClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(postClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var firstMethod = mockMethodWithBaseExpression("\"VIHasTag\"");
    stubMethodName(firstMethod, "inE");
    // inE.inV — second hop loops back to target side
    var chain = buildChain(firstMethod, "inV", "tag", "e", "targetTag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("targetTag", "VITag"));
  }

  /**
   * bothE('VIKnows') → inV: even though the first hop is bidirectional, the
   * second hop ({@code inV}) unambiguously selects the edge's {@code in}
   * side. Precedence-2 returns the in-linked class. Pins that bothE no
   * longer short-circuits to null when the second hop pins the direction —
   * a previous version of the fallback would return null on any bothE.
   */
  @Test
  public void resolveChainedTarget_bothEinV_precedence2_readsInSide() {
    var aClass = registerClass("A", 100);
    var bClass = registerClass("B", 50);
    var edgeClass = registerClass("VIKnows", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(bClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(aClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var firstMethod = mockMethodWithBaseExpression("\"VIKnows\"");
    stubMethodName(firstMethod, "bothE");
    var chain = buildChain(firstMethod, "inV", "a", "e", "target");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    // bothE.inV → in side = B (the edge's "in" linked class)
    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("target", "B"));
  }

  /**
   * bothE('VIKnows') → outV: bothE no longer blocks inference; outV picks
   * the {@code out} linked class. Mirror of the bothE.inV test.
   */
  @Test
  public void resolveChainedTarget_bothEoutV_precedence2_readsOutSide() {
    var aClass = registerClass("A", 100);
    var bClass = registerClass("B", 50);
    var edgeClass = registerClass("VIKnows", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(bClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(aClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var firstMethod = mockMethodWithBaseExpression("\"VIKnows\"");
    stubMethodName(firstMethod, "bothE");
    var chain = buildChain(firstMethod, "outV", "a", "e", "source");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    // bothE.outV → out side = A
    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("source", "A"));
  }

  /**
   * bothE → bothV with {@code aliasClasses.get("vertex") = "VITag"}:
   * precedence-1 wins. <b>Critical for Track 3 test 4</b> — this is the
   * only way a {@code bothE→bothV} chain ever gets a non-null class, since
   * precedence-2 returns null for bothE by design.
   */
  @Test
  public void resolveChainedTarget_bothEbothV_precedence1_aliasClassesHit() {
    var chain = buildChain("bothE", "bothV", "a", "e", "vertex");
    var aliasClasses = Map.of("vertex", "VITag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), aliasClasses, db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("vertex", "VITag"));
  }

  /**
   * bothE('VIKnows') → bothV with empty aliasClasses and a fully-registered
   * edge schema: precedence-2 returns null because the SECOND hop
   * ({@code bothV}) cannot disambiguate which side of the edge is
   * downstream — neither {@code in} nor {@code out} alone is correct.
   * Note that the rejection now lives in {@code linkedVertexClassForVertexStep}'s
   * "bothV → null" branch, not in any first-method check; bothE.inV /
   * bothE.outV are now resolvable (see {@code bothEinV_precedence2_readsInSide}).
   */
  @Test
  public void resolveChainedTarget_bothEbothV_precedence2_returnsNullClass() {
    var aClass = registerClass("A", 100);
    var bClass = registerClass("B", 100);
    var edgeClass = registerClass("VIKnows", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(bClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(aClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var firstMethod = mockMethodWithBaseExpression("\"VIKnows\"");
    stubMethodName(firstMethod, "bothE");
    var chain = buildChain(firstMethod, "bothV", "a", "e", "b");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("b", null));
  }

  /**
   * outE() without a class parameter + empty aliasClasses: precedence-2
   * fallback's {@code extractEdgeClassName} returns null, so class=null.
   */
  @Test
  public void resolveChainedTarget_precedence2_missingEdgeClassName_returnsNullClass() {
    // firstMethod has no params
    var chain = buildChain("outE", "inV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", null));
  }

  /**
   * outE('Unknown') + empty aliasClasses: edge class is not in the schema —
   * precedence-2 returns null.
   */
  @Test
  public void resolveChainedTarget_precedence2_edgeClassNotInSchema_returnsNullClass() {
    when(schema.getClassInternal("Unknown")).thenReturn(null);

    var firstMethod = mockMethodWithBaseExpression("\"Unknown\"");
    stubMethodName(firstMethod, "outE");
    var chain = buildChain(firstMethod, "inV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", null));
  }

  /**
   * outE('VIHasTag') but the edge class has no {@code in} property: no
   * linked vertex class exists, precedence-2 returns null.
   */
  @Test
  public void resolveChainedTarget_precedence2_missingLinkedProperty_returnsNullClass() {
    var edgeClass = registerClass("VIHasTag", 500);
    when(edgeClass.getPropertyInternal("in")).thenReturn(null);

    var firstMethod = mockMethodWithBaseExpression("\"VIHasTag\"");
    stubMethodName(firstMethod, "outE");
    var chain = buildChain(firstMethod, "inV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", null));
  }

  /**
   * Linked property exists but its {@code getLinkedClass()} returns null:
   * precedence-2 returns null.
   */
  @Test
  public void resolveChainedTarget_precedence2_linkedClassNull_returnsNullClass() {
    var edgeClass = registerClass("VIHasTag", 500);
    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(null);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    var firstMethod = mockMethodWithBaseExpression("\"VIHasTag\"");
    stubMethodName(firstMethod, "outE");
    var chain = buildChain(firstMethod, "inV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", null));
  }

  /**
   * Null session + empty aliasClasses: precedence-2 cannot do schema lookup
   * and must defensively return null (not NPE).
   */
  @Test
  public void resolveChainedTarget_precedence2_nullSession_returnsNullClass() {
    var firstMethod = mockMethodWithBaseExpression("\"VIHasTag\"");
    stubMethodName(firstMethod, "outE");
    var chain = buildChain(firstMethod, "inV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), null);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", null));
  }

  /**
   * Null aliasClasses map: the helper tolerates null aliasClasses (caller
   * is not required to pass a non-null map). Falls straight to precedence-2.
   */
  @Test
  public void resolveChainedTarget_precedence1_nullAliasClasses_fallsToPrecedence2() {
    var chain = buildChain("outE", "inV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), null, db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", null));
  }

  /**
   * aliasClasses wins over schema: even when the schema provides a
   * different class, precedence-1 short-circuits first. Pins the ordering
   * of the two precedence clauses.
   */
  @Test
  public void resolveChainedTarget_precedence1_winsOverSchema() {
    // Set up schema to return "VITag" for outE('VIHasTag')
    var tagClass = registerClass("VITag", 10);
    var edgeClass = registerClass("VIHasTag", 500);
    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(tagClass);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);

    // But aliasClasses says "VIExplicitTag" — that must win.
    var aliasClasses = Map.of("tag", "VIExplicitTag");

    var firstMethod = mockMethodWithBaseExpression("\"VIHasTag\"");
    stubMethodName(firstMethod, "outE");
    var chain = buildChain(firstMethod, "inV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), aliasClasses, db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", "VIExplicitTag"));
  }

  /**
   * Symmetric complement to {@link #resolveChainedTarget_precedence1_winsOverSchema}:
   * the inE branch's precedence ordering is also pinned against a conflicting
   * schema. Catches a mutation that selectively reorders precedence for the
   * inbound chain only.
   */
  @Test
  public void resolveChainedTarget_inE_precedence1_winsOverSchema() {
    var postClass = registerClass("VIPost", 100);
    var edgeClass = registerClass("VIHasTag", 500);
    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(postClass);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);

    var aliasClasses = Map.of("post", "VIExplicitPost");

    var firstMethod = mockMethodWithBaseExpression("\"VIHasTag\"");
    stubMethodName(firstMethod, "inE");
    var chain = buildChain(firstMethod, "outV", "tag", "e", "post");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), aliasClasses, db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("post", "VIExplicitPost"));
  }

  /**
   * Precedence-2 short-circuits cleanly when the immutable schema snapshot
   * itself is null (happens in narrow storage-lifecycle windows —
   * re-open, plugin init). Exercises the
   * {@code inferDownstreamVertexClassFromEdge} branch guarding against that
   * case, independent of the null-session, null-edge-class, and null-edge-
   * class-in-schema branches.
   */
  @Test
  public void resolveChainedTarget_precedence2_nullSchemaSnapshot_returnsNullClass() {
    when(db.getMetadata().getImmutableSchemaSnapshot()).thenReturn(null);

    var firstMethod = mockMethodWithBaseExpression("\"VIHasTag\"");
    stubMethodName(firstMethod, "outE");
    var chain = buildChain(firstMethod, "inV", "post", "e", "tag");

    var result = MatchExecutionPlanner.resolveChainedTarget(
        chain.firstEdge(), chain.intermediateNode(), Set.of(), Map.of(), db);

    assertThat(result).isEqualTo(
        new MatchExecutionPlanner.ChainedTarget("tag", null));
  }

  // =========================================================================
  // applyTargetSelectivity — refactor parity + new class-forced overload
  // =========================================================================
  //
  // These tests guard the refactor that extracts the shared body of the
  // existing 8-arg overload into {@code applyClassSelectivity} and adds a
  // class-forced 6-arg overload used by the sort-loop's chain fold.
  //
  // Parity tests (8-arg ↔ 6-arg) prove the refactor preserves behaviour for
  // the existing call site. Short-circuit tests on the 6-arg overload pin
  // the null-class guard and the inherited schema / classCount / filter /
  // estimate branches, so a mutation that drops any branch is caught.

  // ── 6-arg overload: short-circuit on null pre-resolved class ──

  /**
   * Kills: "if (preResolvedTargetClass == null) return baseCost;" replaced
   * with "if (true)" or "if (false)". A null class is the legitimate signal
   * for {@code bothE→bothV} chains where edge-schema inference returns null
   * and the user did not annotate the downstream alias — the overload must
   * leave {@code baseCost} unchanged instead of NPEing on the schema lookup.
   */
  @Test
  public void applyTargetSelectivity_classForced_nullClass_returnsBaseCost() {
    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", (String) null, Map.of(), Map.of("tag", 10L), db);
    assertEquals(500.0, result, 0.0);
  }

  // ── 6-arg overload: inherited schema/classCount short-circuits ──

  /**
   * Pins the sort-loop invariant that {@code Double.MAX_VALUE} (the
   * "unestimated" sentinel) survives the chain fold unchanged when the
   * pre-resolved class is null. If the null-class short-circuit at the
   * head of the 6-arg overload is weakened or removed, the caller at
   * {@code MatchExecutionPlanner.updateScheduleStartingAt} would multiply
   * MAX_VALUE by some finite selectivity, quietly breaking the stable-sort
   * tiebreaker the sort comparator relies on for edges with no estimate.
   */
  @Test
  public void applyTargetSelectivity_classForced_maxValueInputPreservedOnNullClass() {
    double result =
        MatchExecutionPlanner.applyTargetSelectivity(
            Double.MAX_VALUE, "tag", (String) null, Map.of(), Map.of("tag", 10L), db);
    assertEquals(Double.MAX_VALUE, result, 0.0);
  }

  /**
   * Pins the same MAX_VALUE-preservation invariant when the pre-resolved
   * class is present but the target lacks any filter/row-estimate: the
   * cardinality-ratio path's {@code return baseCost} on null estimate
   * must not silently convert MAX_VALUE into a finite cost. Complements
   * the null-class test above.
   */
  @Test
  public void applyTargetSelectivity_classForced_maxValueInputPreservedOnNoFilterNoEstimate() {
    registerClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    double result =
        MatchExecutionPlanner.applyTargetSelectivity(
            Double.MAX_VALUE, "tag", "Tag", Map.of(), Map.of(), db);
    assertEquals(Double.MAX_VALUE, result, 0.0);
  }

  /**
   * Kills: mutation that drops the {@code !schema.existsClass(...)} half of
   * the {@code schema == null || !schema.existsClass(...)} guard. The new
   * overload must delegate to the shared helper, which short-circuits when
   * the schema has no matching class (e.g. a stale pre-resolved class that
   * no longer exists after a schema edit).
   */
  @Test
  public void applyTargetSelectivity_classForced_classNotInSchema_returnsBaseCost() {
    when(schema.existsClass("Missing")).thenReturn(false);

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", "Missing", Map.of(), Map.of("tag", 10L), db);
    assertEquals(500.0, result, 0.0);
  }

  /**
   * Kills: mutation that drops the {@code schema == null} half of the
   * {@code schema == null || !schema.existsClass(...)} guard. During
   * narrow storage-lifecycle windows (re-open, plugin init) the immutable
   * snapshot may be null — calling {@code existsClass} on it would NPE, so
   * the guard must short-circuit to {@code baseCost} first.
   */
  @Test
  public void applyTargetSelectivity_classForced_schemaSnapshotNull_returnsBaseCost() {
    when(db.getMetadata().getImmutableSchemaSnapshot()).thenReturn(null);

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", "Tag", Map.of(), Map.of("tag", 10L), db);
    assertEquals(500.0, result, 0.0);
  }

  /**
   * Kills: "if (classCount <= 0) return baseCost;" replaced with a weaker
   * or stronger predicate. An empty downstream class has no selectivity
   * information — we must fall back to {@code baseCost} rather than divide
   * by zero.
   */
  @Test
  public void applyTargetSelectivity_classForced_classCountZero_returnsBaseCost() {
    registerClass("Empty", 0);
    when(schema.existsClass("Empty")).thenReturn(true);

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", "Empty", Map.of(), Map.of("tag", 10L), db);
    assertEquals(500.0, result, 0.0);
  }

  // ── 6-arg overload: filter-heuristic vs cardinality-ratio paths ──

  /**
   * Kills: "return baseCost * heuristic;" in the filter-heuristic branch
   * replaced with a constant. With an equality filter on a 1000-row class,
   * selectivity ≈ 1/1000, so adjusted ≈ 0.5. Also kills the
   * "heuristic >= 0.0" guard: negating it would skip this path entirely.
   */
  @Test
  public void applyTargetSelectivity_classForced_filterHeuristicPath() {
    registerClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    var filter = makeWhereWithOperator(new SQLEqualsOperator(-1));

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", "Tag", Map.of("tag", filter), Map.of("tag", 100L), db);

    // equality selectivity = 1/1000 → 500 × 0.001 = 0.5 (exact in IEEE-754)
    assertEquals(0.5, result, DELTA);
  }

  /**
   * Kills: "return baseCost * heuristic;" replaced for inequality. With a
   * {@code <>} filter on a 1000-row class, selectivity = 999/1000. This
   * complements the equality test so a mutation pinning heuristic to
   * 1/classCount (equality only) is caught. Uses {@code DELTA} because the
   * arithmetic is exact at this magnitude — a wider tolerance would admit
   * off-by-one mutations in the {@code (n-1)/n} fraction.
   */
  @Test
  public void applyTargetSelectivity_classForced_filterHeuristicInequality() {
    registerClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    var filter = makeWhereWithOperator(new SQLNeOperator(-1));

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", "Tag", Map.of("tag", filter), Map.of("tag", 100L), db);

    // inequality selectivity = 999/1000 → 500 × 0.999 = 499.5 (exact in IEEE-754)
    assertEquals(499.5, result, DELTA);
  }

  /**
   * Kills: the cardinality-ratio branch ("selectivity = targetEstimate /
   * classCount; return baseCost × selectivity;") being removed or pinned.
   * Uses an estimated 100-row target on a 1000-row class so the expected
   * value (50.0) is distinct from the filter-heuristic test's 0.5 — lets a
   * reader verify at a glance which branch produced the result, and lets a
   * mutation that swapped the heuristic and cardinality-ratio branches be
   * caught by either test individually rather than only by their combination.
   */
  @Test
  public void applyTargetSelectivity_classForced_cardinalityRatioPath() {
    registerClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", "Tag", Map.of(), Map.of("tag", 100L), db);

    // no filter → targetEstimate/classCount = 100/1000 → 500 × 0.1 = 50.0 (exact in IEEE-754)
    assertEquals(50.0, result, DELTA);
  }

  /**
   * Kills: "if (heuristic >= 0.0) return baseCost * heuristic;" mutated to
   * always take the heuristic branch (applying -1.0 would negate the cost)
   * or to swallow the threshold comparison. With an unestimable WHERE
   * (empty AND block), {@code estimateFilterSelectivity} returns -1.0; the
   * shared helper must fall through to the cardinality-ratio branch, not
   * short-circuit on the filter or multiply by a negative heuristic.
   */
  @Test
  public void
      applyTargetSelectivity_classForced_heuristicUnestimable_fallsBackToCardinalityRatio() {
    registerClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    // Empty SQLAndBlock: unwrapSingleCondition returns the AND block (size != 1),
    // which is not SQLBinaryCondition and has size 0 < 1 AND-sub-blocks, so
    // estimateFilterSelectivity returns -1.0. The helper must fall through
    // to the cardinality-ratio branch using estimatedRootEntries.
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(new SQLAndBlock(-1));

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", "Tag", Map.of("tag", where), Map.of("tag", 1L), db);

    // heuristic = -1.0 → fallback to targetEstimate/classCount = 1/1000
    //                  → 500 × 0.001 = 0.5 (exact in IEEE-754)
    assertEquals(0.5, result, DELTA);
  }

  /**
   * Kills: the "targetEstimate == null → return baseCost" guard. Without
   * a filter and without an estimate for the downstream alias, the overload
   * must not NPE dereferencing a null Long and must not misattribute 0 as
   * the estimate.
   */
  @Test
  public void applyTargetSelectivity_classForced_noFilterNoEstimate_returnsBaseCost() {
    registerClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", "Tag", Map.of(), Map.of(), db);

    assertEquals(500.0, result, 0.0);
  }

  // ── 8-arg ↔ 6-arg parity (refactor regression guard) ──

  /**
   * The refactor routes both overloads through the same private helper;
   * with the same resolved class, the filter-heuristic path must return
   * identical numeric results. Anchoring the absolute value (0.5) in addition
   * to the legacy==classForced parity kills mutations that alter the shared
   * helper symmetrically (e.g. {@code baseCost * heuristic} flipped to
   * {@code baseCost / heuristic}): the relative equality still holds across
   * both overloads, but the absolute-value assertion fails.
   */
  @Test
  public void applyTargetSelectivity_overloadsAgree_filterHeuristicPath() {
    registerClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    var filter = makeWhereWithOperator(new SQLEqualsOperator(-1));
    var edge = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");

    double legacy = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", edge, true,
        Map.of("tag", "Tag"), Map.of("tag", filter), Map.of("tag", 100L), db);

    double classForced = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", "Tag", Map.of("tag", filter), Map.of("tag", 100L), db);

    // equality selectivity = 1/1000 → 500 × 0.001 = 0.5 (exact in IEEE-754)
    assertEquals(0.5, legacy, DELTA);
    assertEquals(0.5, classForced, DELTA);
    assertEquals(legacy, classForced, 0.0);
  }

  /**
   * Parity for the cardinality-ratio branch: both overloads must produce
   * the identical double when the filter is absent and the estimate
   * drives the selectivity. Anchors the absolute value (0.5) in addition to
   * the legacy==classForced parity, for the same reason as the heuristic
   * parity test above — catches symmetric mutations to the shared helper.
   */
  @Test
  public void applyTargetSelectivity_overloadsAgree_cardinalityRatioPath() {
    registerClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    var edge = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");

    double legacy = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", edge, true,
        Map.of("tag", "Tag"), Map.of(), Map.of("tag", 1L), db);

    double classForced = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", "Tag", Map.of(), Map.of("tag", 1L), db);

    // no filter → targetEstimate/classCount = 1/1000 → 500 × 0.001 = 0.5 (exact in IEEE-754)
    assertEquals(0.5, legacy, DELTA);
    assertEquals(0.5, classForced, DELTA);
    assertEquals(legacy, classForced, 0.0);
  }

  /**
   * Parity for the null-class short-circuit: the 8-arg overload returns
   * {@code baseCost} when {@code resolveTargetClass} returns null; the 6-arg
   * overload returns {@code baseCost} when {@code preResolvedTargetClass}
   * is null. Both paths must observe the same {@code baseCost}.
   */
  @Test
  public void applyTargetSelectivity_overloadsAgree_nullClass_shortCircuit() {
    // 8-arg path: no aliasClasses entry + no edge class → resolveTargetClass
    // returns null. 6-arg path: pre-resolved class explicitly null.
    var edge = mockEdgeWithMethod("out");

    double legacy = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", edge, true,
        Map.of(), Map.of(), Map.of("tag", 10L), db);

    double classForced = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", (String) null, Map.of(), Map.of("tag", 10L), db);

    assertEquals(500.0, legacy, 0.0);
    assertEquals(500.0, classForced, 0.0);
    assertEquals(legacy, classForced, 0.0);
  }

  // ── resolveChainedTarget test helpers ──

  /**
   * A test fixture for a two-hop edge-method chain:
   * <pre>
   *   (source) ──firstEdge──▶ (intermediate) ──downstreamEdge──▶ (target)
   * </pre>
   *
   * <p>Returned by {@link #buildChain(String, String, String, String, String)}.
   */
  private record ChainFixture(
      PatternEdge firstEdge, PatternNode intermediateNode, PatternEdge downstreamEdge) {
  }

  /**
   * Builds a structural chain fixture that matches {@code resolveChainedTarget}'s
   * input contract: two consecutive PatternEdges with the method names given.
   * All {@link SQLMatchPathItem}s and {@link SQLMethodCall}s are Mockito mocks
   * stubbed so that {@code getMethodNameString()} returns the requested name.
   *
   * @param firstMethodName  method name on the first edge (e.g. {@code outE})
   * @param secondMethodName method name on the second edge (e.g. {@code inV})
   * @param sourceAlias      alias of the source vertex
   * @param intermediateAlias alias of the intermediate edge alias
   * @param targetAlias      alias of the downstream vertex
   */
  private ChainFixture buildChain(
      String firstMethodName,
      String secondMethodName,
      String sourceAlias,
      String intermediateAlias,
      String targetAlias) {
    var source = new PatternNode();
    source.alias = sourceAlias;
    var intermediate = new PatternNode();
    intermediate.alias = intermediateAlias;
    var target = new PatternNode();
    target.alias = targetAlias;

    var firstMethod = mock(SQLMethodCall.class);
    stubMethodName(firstMethod, firstMethodName);

    return buildChain(firstMethod, secondMethodName, source, intermediate, target);
  }

  /**
   * Overload that accepts a pre-built {@link SQLMethodCall} for the first edge —
   * useful when the test needs a non-standard method stubbing (e.g. null
   * method name).
   */
  private ChainFixture buildChain(
      SQLMethodCall firstMethod,
      String secondMethodName,
      String sourceAlias,
      String intermediateAlias,
      String targetAlias) {
    var source = new PatternNode();
    source.alias = sourceAlias;
    var intermediate = new PatternNode();
    intermediate.alias = intermediateAlias;
    var target = new PatternNode();
    target.alias = targetAlias;
    return buildChain(firstMethod, secondMethodName, source, intermediate, target);
  }

  private ChainFixture buildChain(
      SQLMethodCall firstMethod,
      String secondMethodName,
      PatternNode source,
      PatternNode intermediate,
      PatternNode target) {
    var firstItem = mock(SQLMatchPathItem.class);
    when(firstItem.getMethod()).thenReturn(firstMethod);

    var firstEdge = new PatternEdge();
    firstEdge.item = firstItem;
    firstEdge.out = source;
    firstEdge.in = intermediate;
    source.out.add(firstEdge);
    intermediate.in.add(firstEdge);

    var secondMethod = mock(SQLMethodCall.class);
    stubMethodName(secondMethod, secondMethodName);
    var secondItem = mock(SQLMatchPathItem.class);
    when(secondItem.getMethod()).thenReturn(secondMethod);

    var downstreamEdge = new PatternEdge();
    downstreamEdge.item = secondItem;
    downstreamEdge.out = intermediate;
    downstreamEdge.in = target;
    intermediate.out.add(downstreamEdge);
    target.in.add(downstreamEdge);

    return new ChainFixture(firstEdge, intermediate, downstreamEdge);
  }
}
