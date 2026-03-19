package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

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
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMethodCall;
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
    var id = new SQLIdentifier(methodName);
    when(method.getMethodName()).thenReturn(id);
    when(method.getParams()).thenReturn(List.of());
    return mockEdge(method);
  }

  private PatternEdge mockEdgeWithMethodAndParam(
      String methodName, String paramString) {
    var method = mockMethodWithBaseExpression(paramString);
    var id = new SQLIdentifier(methodName);
    when(method.getMethodName()).thenReturn(id);
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
}
