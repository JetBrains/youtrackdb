package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaPropertyInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMethodCall;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the edge cost estimation helpers added in Step 18:
 * {@link MatchExecutionPlanner#estimateEdgeCost},
 * {@link MatchExecutionPlanner#parseDirection}, and
 * {@link MatchExecutionPlanner#extractEdgeClassName}.
 */
public class EstimateEdgeCostTest {

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

  // -- parseDirection tests ---------------------------------------------------

  @Test
  public void parseDirectionOut() {
    assertEquals(Direction.OUT, MatchExecutionPlanner.parseDirection("out"));
  }

  @Test
  public void parseDirectionIn() {
    assertEquals(Direction.IN, MatchExecutionPlanner.parseDirection("in"));
  }

  @Test
  public void parseDirectionBoth() {
    assertEquals(Direction.BOTH, MatchExecutionPlanner.parseDirection("both"));
  }

  @Test
  public void parseDirectionOutE() {
    assertEquals(Direction.OUT, MatchExecutionPlanner.parseDirection("outE"));
  }

  @Test
  public void parseDirectionInE() {
    assertEquals(Direction.IN, MatchExecutionPlanner.parseDirection("inE"));
  }

  @Test
  public void parseDirectionBothE() {
    assertEquals(Direction.BOTH, MatchExecutionPlanner.parseDirection("bothE"));
  }

  @Test
  public void parseDirectionCaseInsensitive() {
    assertEquals(Direction.OUT, MatchExecutionPlanner.parseDirection("OUT"));
    assertEquals(Direction.IN, MatchExecutionPlanner.parseDirection("IN"));
    assertEquals(Direction.BOTH, MatchExecutionPlanner.parseDirection("BOTH"));
  }

  @Test
  public void parseDirectionNull() {
    assertNull(MatchExecutionPlanner.parseDirection(null));
  }

  @Test
  public void parseDirectionUnrecognized() {
    assertNull(MatchExecutionPlanner.parseDirection("bothV"));
    assertNull(MatchExecutionPlanner.parseDirection("outV"));
    assertNull(MatchExecutionPlanner.parseDirection("inV"));
    assertNull(MatchExecutionPlanner.parseDirection("unknown"));
  }

  // -- extractEdgeClassName tests ---------------------------------------------

  @Test
  public void extractEdgeClassNameFromQuotedString() {
    // Simulate .out('Knows') — mock's execute() returns null (not a String), so
    // extractEdgeClassName falls through to the toString path which strips quotes.
    var method = mockMethodCall("out", "\"Knows\"");
    assertEquals("Knows", MatchExecutionPlanner.extractEdgeClassName(method));
  }

  @Test
  public void extractEdgeClassNameFromSingleQuotedString() {
    var method = mockMethodCall("out", "'Knows'");
    assertEquals("Knows", MatchExecutionPlanner.extractEdgeClassName(method));
  }

  @Test
  public void extractEdgeClassNameNoParams() {
    var method = mockMethodCallNoParams("out");
    assertNull(MatchExecutionPlanner.extractEdgeClassName(method));
  }

  @Test
  public void extractEdgeClassNameNullParams() {
    var method = mock(SQLMethodCall.class);
    when(method.getParams()).thenReturn(null);
    assertNull(MatchExecutionPlanner.extractEdgeClassName(method));
  }

  @Test
  public void extractEdgeClassNameUnquotedIdentifier() {
    // Identifier without quotes — returned as-is
    var method = mockMethodCall("out", "Knows");
    assertEquals("Knows", MatchExecutionPlanner.extractEdgeClassName(method));
  }

  @Test
  public void extractEdgeClassNameNonBaseExpression() {
    // When the first parameter's mathExpression is not SQLBaseExpression
    var method = mock(SQLMethodCall.class);
    var param = mock(SQLExpression.class);
    // getMathExpression() returns something that's not SQLBaseExpression
    when(param.getMathExpression()).thenReturn(null);
    when(method.getParams()).thenReturn(List.of(param));
    assertNull(MatchExecutionPlanner.extractEdgeClassName(method));
  }

  // -- estimateEdgeCost tests -------------------------------------------------

  @Test
  public void estimateEdgeCostReturnsMaxValueWhenMethodIsNull() {
    var edge = mockEdge(null);
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);
    assertEquals(Double.MAX_VALUE, cost, 0.0);
  }

  @Test
  public void estimateEdgeCostReturnsMaxValueForUnrecognizedMethod() {
    var edge = mockEdgeWithMethod("bothV");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);
    assertEquals(Double.MAX_VALUE, cost, 0.0);
  }

  @Test
  public void estimateEdgeCostWithSchemaEdgeClass() {
    // Setup: .out('Knows') with edge class having out→Person, in→Person
    mockSchemaClass("Person", 100);
    mockEdgeClassWithVertexLinks("Knows", 500, "Person", "Person");

    var edge = mockEdgeWithMethodAndParam("out", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    // fanOut = 500/100 = 5.0, cost = 100 * 5.0 * randomPageReadCost
    assertTrue("Cost should be positive and finite", cost > 0 && cost < Double.MAX_VALUE);
  }

  @Test
  public void estimateEdgeCostWithoutEdgeClassParam() {
    // .out() with no parameter — edgeClassName is null, falls back to default fan-out
    mockSchemaClass("Person", 100);

    var edge = mockEdgeWithMethod("out");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    // With null edgeClassName, EdgeFanOutEstimator returns defaultFanOut
    assertTrue("Cost should be positive and finite", cost > 0 && cost < Double.MAX_VALUE);
  }

  @Test
  public void estimateEdgeCostInDirection() {
    mockSchemaClass("Person", 200);
    mockEdgeClassWithVertexLinks("WorksAt", 300, "Person", "Company");

    var edge = mockEdgeWithMethodAndParam("in", "\"WorksAt\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 50, Map.of("p", "Person"), db);

    assertTrue("Cost should be positive", cost > 0);
  }

  @Test
  public void estimateEdgeCostBothDirection() {
    mockSchemaClass("Person", 100);
    mockEdgeClassWithVertexLinks("Knows", 400, "Person", "Person");

    var edge = mockEdgeWithMethodAndParam("both", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    assertTrue("Cost should be positive", cost > 0);
  }

  @Test
  public void estimateEdgeCostWithZeroSourceRows() {
    mockSchemaClass("Person", 100);

    var edge = mockEdgeWithMethod("out");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 0, Map.of("p", "Person"), db);

    // 0 source rows → 0 cost
    assertEquals(0.0, cost, 0.001);
  }

  @Test
  public void estimateEdgeCostMissingSourceClass() {
    // Source alias not in aliasClasses → sourceClassName is null
    var edge = mockEdgeWithMethod("out");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of(), db);

    // Falls back to default fan-out (null sourceClassName)
    assertTrue("Cost should be positive", cost > 0);
  }

  @Test
  public void estimateEdgeCostEdgeClassNotInSchema() {
    // Edge class name provided but not found in schema
    when(schema.getClassInternal("Unknown")).thenReturn(null);
    mockSchemaClass("Person", 100);

    var edge = mockEdgeWithMethodAndParam("out", "\"Unknown\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    // Falls back to default fan-out
    assertTrue("Cost should be positive", cost > 0);
  }

  @Test
  public void estimateEdgeCostNullSchema() {
    var metadata = mock(MetadataDefault.class);
    when(db.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(null);

    var edge = mockEdgeWithMethodAndParam("out", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    // Null schema → default fan-out
    assertTrue("Cost should be positive", cost > 0);
  }

  // -- Helper methods ---------------------------------------------------------

  private SchemaClassInternal mockSchemaClass(String name, long count) {
    var clazz = mock(SchemaClassInternal.class);
    when(schema.getClassInternal(name)).thenReturn(clazz);
    when(schema.getClass(name)).thenReturn(clazz);
    when(clazz.approximateCount(any(DatabaseSessionEmbedded.class))).thenReturn(count);
    when(clazz.getName()).thenReturn(name);
    when(clazz.isSubClassOf(name)).thenReturn(true);
    return clazz;
  }

  private void mockEdgeClassWithVertexLinks(
      String edgeClassName, long edgeCount,
      String outVertexClass, String inVertexClass) {
    var edgeClass = mockSchemaClass(edgeClassName, edgeCount);

    if (outVertexClass != null) {
      var outProp = mock(SchemaPropertyInternal.class);
      var outClass = schema.getClassInternal(outVertexClass);
      if (outClass == null) {
        outClass = mockSchemaClass(outVertexClass, 0);
      }
      when(outProp.getLinkedClass()).thenReturn(outClass);
      when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);
    }

    if (inVertexClass != null) {
      var inProp = mock(SchemaPropertyInternal.class);
      var inClass = schema.getClassInternal(inVertexClass);
      if (inClass == null) {
        inClass = mockSchemaClass(inVertexClass, 0);
      }
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
    var method = mockMethodCallNoParams(methodName);
    return mockEdge(method);
  }

  private PatternEdge mockEdgeWithMethodAndParam(
      String methodName, String paramString) {
    var method = mockMethodCall(methodName, paramString);
    return mockEdge(method);
  }

  private SQLMethodCall mockMethodCallNoParams(String methodName) {
    var method = mock(SQLMethodCall.class);
    when(method.getMethodName()).thenReturn(methodName);
    when(method.getParams()).thenReturn(List.of());
    return method;
  }

  private SQLMethodCall mockMethodCall(String methodName, String paramString) {
    var method = mock(SQLMethodCall.class);
    when(method.getMethodName()).thenReturn(methodName);

    var base = mock(SQLBaseExpression.class);
    when(base.toString()).thenReturn(paramString);

    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);

    when(method.getParams()).thenReturn(List.of(param));
    return method;
  }
}
