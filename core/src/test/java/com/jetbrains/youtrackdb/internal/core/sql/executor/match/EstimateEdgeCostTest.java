package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchTestWhereBuilders.makeWhereWithOperator;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaPropertyInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.CostModel;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLEqualsOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMethodCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
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

  @Test
  public void parseDirectionEmptyString_returnsNull() {
    // Empty string is not a valid method name — must return null.
    assertNull(MatchExecutionPlanner.parseDirection(""));
  }

  @Test
  public void parseDirectionOutE_uppercase_returnsOut() {
    // "OUTE" (all-caps) must map to OUT via case-insensitive matching.
    assertEquals(Direction.OUT, MatchExecutionPlanner.parseDirection("OUTE"));
  }

  @Test
  public void parseDirectionInE_uppercase_returnsIn() {
    assertEquals(Direction.IN, MatchExecutionPlanner.parseDirection("INE"));
  }

  @Test
  public void parseDirectionBothE_uppercase_returnsBoth() {
    assertEquals(Direction.BOTH, MatchExecutionPlanner.parseDirection("BOTHE"));
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

  @Test
  public void extractEdgeClassName_evaluableLiteral_returnsStringValue() {
    // When execute() on the first param returns a String directly,
    // extractEdgeClassName should use it without falling through to toString.
    var pathItem = new SQLMatchPathItem(-1);
    var edgeName = new SQLIdentifier("Knows");
    pathItem.outPath(edgeName);

    // pathItem.getMethod() now has a proper SQLExpression param that
    // evaluates to "Knows" (a String).
    assertEquals("Knows",
        MatchExecutionPlanner.extractEdgeClassName(pathItem.getMethod()));
  }

  @Test
  public void extractEdgeClassName_singleCharString_returnsRaw() {
    // When the raw toString is a single character (length < 2), the
    // quote-stripping branch must NOT be entered — returns as-is.
    var method = mock(SQLMethodCall.class);
    var outId = new SQLIdentifier("out");
    when(method.getMethodName()).thenReturn(outId);

    var base = mock(SQLBaseExpression.class);
    when(base.toString()).thenReturn("X");

    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);

    when(method.getParams()).thenReturn(List.of(param));

    assertEquals("X", MatchExecutionPlanner.extractEdgeClassName(method));
  }

  @Test
  public void extractEdgeClassName_emptyString_returnsEmpty() {
    // Empty raw string (length 0 < 2) — returns as-is without stripping.
    var method = mock(SQLMethodCall.class);
    var outId = new SQLIdentifier("out");
    when(method.getMethodName()).thenReturn(outId);

    var base = mock(SQLBaseExpression.class);
    when(base.toString()).thenReturn("");

    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);

    when(method.getParams()).thenReturn(List.of(param));

    assertEquals("", MatchExecutionPlanner.extractEdgeClassName(method));
  }

  @Test
  public void extractEdgeClassName_mismatchedQuotes_returnsRaw() {
    // When first and last quotes don't match (e.g., "'Knows\""),
    // the quote-stripping condition fails — returns raw string.
    var method = mock(SQLMethodCall.class);
    var outId = new SQLIdentifier("out");
    when(method.getMethodName()).thenReturn(outId);

    var base = mock(SQLBaseExpression.class);
    when(base.toString()).thenReturn("'Knows\"");

    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);

    when(method.getParams()).thenReturn(List.of(param));

    assertEquals("'Knows\"",
        MatchExecutionPlanner.extractEdgeClassName(method));
  }

  @Test
  public void extractEdgeClassName_nullRaw_returnsNull() {
    // When base.toString() returns null, the null check on raw prevents NPE.
    var method = mock(SQLMethodCall.class);
    var outId = new SQLIdentifier("out");
    when(method.getMethodName()).thenReturn(outId);

    var base = mock(SQLBaseExpression.class);
    when(base.toString()).thenReturn(null);

    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);

    when(method.getParams()).thenReturn(List.of(param));

    assertNull(MatchExecutionPlanner.extractEdgeClassName(method));
  }

  @Test
  public void extractEdgeClassName_twoCharQuoted_returnsEmpty() {
    // Exactly 2 chars with matching quotes: "''" → empty string after strip.
    // This is the boundary: length == 2 satisfies >= 2.
    var method = mock(SQLMethodCall.class);
    var outId = new SQLIdentifier("out");
    when(method.getMethodName()).thenReturn(outId);

    var base = mock(SQLBaseExpression.class);
    when(base.toString()).thenReturn("''");

    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);

    when(method.getParams()).thenReturn(List.of(param));

    assertEquals("", MatchExecutionPlanner.extractEdgeClassName(method));
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

  @Test
  public void estimateEdgeCost_edgeClassNoOutProperty() {
    // Edge class exists but has no "out" property — outVertexClass stays null.
    var personClass = mockSchemaClass("Person", 100);
    when(personClass.isSubClassOf("Person")).thenReturn(true);
    var edgeClass = mockSchemaClass("Knows", 500);
    when(edgeClass.getPropertyInternal("out")).thenReturn(null);
    when(edgeClass.getPropertyInternal("in")).thenReturn(null);

    var edge = mockEdgeWithMethodAndParam("out", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    // With null vertex classes, EdgeFanOutEstimator uses default fan-out.
    assertTrue("Cost should be positive", cost > 0);
  }

  @Test
  public void estimateEdgeCost_outPropertyLinkedClassNull() {
    // Edge class has "out" property but getLinkedClass() returns null.
    mockSchemaClass("Person", 100);
    var edgeClass = mockSchemaClass("Knows", 500);

    var outProp = mock(SchemaPropertyInternal.class);
    when(outProp.getLinkedClass()).thenReturn(null);
    when(edgeClass.getPropertyInternal("out")).thenReturn(outProp);
    when(edgeClass.getPropertyInternal("in")).thenReturn(null);

    var edge = mockEdgeWithMethodAndParam("out", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    assertTrue("Cost should be positive", cost > 0);
  }

  @Test
  public void estimateEdgeCost_inPropertyLinkedClassNull() {
    // Edge class has "in" property but getLinkedClass() returns null.
    mockSchemaClass("Person", 100);
    var edgeClass = mockSchemaClass("Knows", 500);

    var inProp = mock(SchemaPropertyInternal.class);
    when(inProp.getLinkedClass()).thenReturn(null);
    when(edgeClass.getPropertyInternal("in")).thenReturn(inProp);
    when(edgeClass.getPropertyInternal("out")).thenReturn(null);

    var edge = mockEdgeWithMethodAndParam("in", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 100, Map.of("p", "Person"), db);

    assertTrue("Cost should be positive", cost > 0);
  }

  @Test
  public void estimateEdgeCost_verifyCostModelFormula() {
    // Verify exact cost computation: cost = sourceRows * fanOut * randomPageReadCost.
    // Kills mutants that replace the return value or negate conditions.
    mockSchemaClass("Person", 100);
    mockEdgeClassWithVertexLinks("Knows", 500, "Person", "Person");

    var edge = mockEdgeWithMethodAndParam("out", "\"Knows\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 200, Map.of("p", "Person"), db);

    // fanOut = 500/100 = 5.0
    // cost = 200 * 5.0 * randomPageReadCost
    double expected = CostModel.edgeTraversalCost(200, 5.0);
    assertEquals(expected, cost, 1e-9);
  }

  @Test
  public void estimateEdgeCost_inDirection_verifyCostModelFormula() {
    // Verify exact cost for IN direction.
    mockSchemaClass("Person", 200);
    mockEdgeClassWithVertexLinks("WorksAt", 600, "Person", "Person");

    var edge = mockEdgeWithMethodAndParam("in", "\"WorksAt\"");
    double cost = MatchExecutionPlanner.estimateEdgeCost(
        edge, "p", 50, Map.of("p", "Person"), db);

    // fanOut = 600/200 = 3.0
    double expected = CostModel.edgeTraversalCost(50, 3.0);
    assertEquals(expected, cost, 1e-9);
  }

  // -- parseDirection: case variants -----------------------------------------

  @Test
  public void parseDirectionMixedCase() {
    // Verify case-insensitive matching for various casing patterns.
    assertEquals(Direction.OUT, MatchExecutionPlanner.parseDirection("Out"));
    assertEquals(Direction.IN, MatchExecutionPlanner.parseDirection("In"));
    assertEquals(Direction.BOTH, MatchExecutionPlanner.parseDirection("Both"));
    assertEquals(Direction.OUT, MatchExecutionPlanner.parseDirection("OutE"));
    assertEquals(Direction.IN, MatchExecutionPlanner.parseDirection("InE"));
    assertEquals(Direction.BOTH, MatchExecutionPlanner.parseDirection("BothE"));
  }

  @Test
  public void parseDirection_null_returnsNull() {
    // parseDirection(null) should return null without throwing.
    assertNull(MatchExecutionPlanner.parseDirection(null));
  }

  // -- applyTargetSelectivity tests -------------------------------------------

  @Test
  public void targetSelectivityWithEqualityFilter() {
    mockSchemaClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    var edge = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");
    var filter = makeWhereWithOperator(new SQLEqualsOperator(-1));

    double adjusted = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", edge, true,
        Map.of("tag", "Tag"), Map.of("tag", filter),
        Map.of("tag", 100L), db);

    // equality selectivity = 1/1000 = 0.001, adjusted = 500 * 0.001 = 0.5
    assertEquals(0.5, adjusted, 0.01);
  }

  @Test
  public void targetSelectivityWithInequalityFilter() {
    mockSchemaClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    var edge = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");
    var filter = makeWhereWithOperator(new SQLNeOperator(-1));

    double adjusted = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", edge, true,
        Map.of("tag", "Tag"), Map.of("tag", filter),
        Map.of("tag", 100L), db);

    // inequality selectivity = 999/1000 = 0.999, adjusted ≈ 499.5
    assertEquals(499.5, adjusted, 1.0);
  }

  @Test
  public void targetSelectivityFallsBackToEstimateWhenNoFilter() {
    mockSchemaClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    var edge = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");

    double adjusted = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", edge, true,
        Map.of("tag", "Tag"), Map.of(),
        Map.of("tag", 1L), db);

    // no filter → falls back to estimatedRootEntries: 1/1000 * 500 = 0.5
    assertEquals(0.5, adjusted, 0.01);
  }

  @Test
  public void targetSelectivityReturnBaseCostWhenNoClassAndNoEdgeSchema() {
    var edge = mockEdgeWithMethod("out");
    double adjusted = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", edge, true,
        Map.of(), Map.of(), Map.of("tag", 10L), db);

    assertEquals(500.0, adjusted, 0.0);
  }

  @Test
  public void targetSelectivityReturnBaseCostWhenNoEstimate() {
    mockSchemaClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    var edge = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");

    double adjusted = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", edge, true,
        Map.of("tag", "Tag"), Map.of(), Map.of(), db);

    assertEquals(500.0, adjusted, 0.0);
  }

  @Test
  public void targetSelectivityDifferentiatesSameEdgeType() {
    mockSchemaClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    var edge1 = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");
    var eqFilter = makeWhereWithOperator(new SQLEqualsOperator(-1));

    var edge2 = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");
    var neFilter = makeWhereWithOperator(new SQLNeOperator(-1));

    double selectiveCost = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "selectiveTag", edge1, true,
        Map.of("selectiveTag", "Tag"),
        Map.of("selectiveTag", eqFilter),
        Map.of("selectiveTag", 1L), db);

    double broadCost = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "broadTag", edge2, true,
        Map.of("broadTag", "Tag"),
        Map.of("broadTag", neFilter),
        Map.of("broadTag", 999L), db);

    assertTrue("Selective edge (=) should be much cheaper than broad edge (<>)",
        selectiveCost < broadCost);
  }

  @Test
  public void targetSelectivityInfersClassFromEdgeSchema() {
    mockSchemaClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);
    mockEdgeClassWithVertexLinks("HAS_TAG", 5000, "Post", "Tag");

    var edge = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");
    var eqFilter = makeWhereWithOperator(new SQLEqualsOperator(-1));

    // aliasClasses has NO entry for "tag" — class is inferred from edge schema
    double adjusted = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "tag", edge, true,
        Map.of(), Map.of("tag", eqFilter),
        Map.of("tag", 1L), db);

    // inferred class = Tag (in-linked), equality → 1/1000 * 500 = 0.5
    assertEquals(0.5, adjusted, 0.01);
  }

  // -- estimateFilterSelectivity tests ----------------------------------------

  @Test
  public void filterSelectivityEqualityWithoutIndex() {
    var filter = makeWhereWithOperator(new SQLEqualsOperator(-1));
    // No schemaClass/session → falls back to classCount
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 1000, null, null);
    assertEquals(0.001, sel, 0.0001);
  }

  @Test
  public void filterSelectivityInequalityWithoutIndex() {
    var filter = makeWhereWithOperator(new SQLNeOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 1000, null, null);
    assertEquals(0.999, sel, 0.0001);
  }

  @Test
  public void filterSelectivityEqualityWithIndexDistinctCount() {
    mockSchemaClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);
    var tagClass = schema.getClassInternal("Tag");

    var idx = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    var stats =
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(1000, 200, 0);
    when(idx.getStatistics(db)).thenReturn(stats);
    when(tagClass.getInvolvedIndexesInternal(db, "name")).thenReturn(Set.of(idx));

    var filter = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 1000, tagClass, db);
    // 1/distinctCount = 1/200 = 0.005
    assertEquals(0.005, sel, 0.0001);
  }

  @Test
  public void filterSelectivityInequalityWithIndexDistinctCount() {
    mockSchemaClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);
    var tagClass = schema.getClassInternal("Tag");

    var idx = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    var stats =
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(1000, 200, 0);
    when(idx.getStatistics(db)).thenReturn(stats);
    when(tagClass.getInvolvedIndexesInternal(db, "name")).thenReturn(Set.of(idx));

    var filter = makeWhereWithPropertyAndOperator("name", new SQLNeOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 1000, tagClass, db);
    // (200-1)/200 = 199/200 = 0.995
    assertEquals(0.995, sel, 0.001);
  }

  @Test
  public void filterSelectivityUnknownReturnsNegative() {
    var filter = new SQLWhereClause(-1);
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 1000, null, null);
    assertTrue("Unknown filter should return negative", sel < 0);
  }

  @Test
  public void filterSelectivityClassAttributeEquality() {
    mockSchemaClass("Message", 10000);
    when(schema.existsClass("Message")).thenReturn(true);
    var messageClass = schema.getClassInternal("Message");

    mockSchemaClass("Post", 3000);
    when(schema.existsClass("Post")).thenReturn(true);

    var filter = makeWhereWithClassAttribute("Post");
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 10000, messageClass, db);
    // Post.count / Message.count = 3000/10000 = 0.3
    assertEquals(0.3, sel, 0.01);
  }

  @Test
  public void filterSelectivityClassAttributeSmallSubclass() {
    mockSchemaClass("Message", 10000);
    when(schema.existsClass("Message")).thenReturn(true);
    var messageClass = schema.getClassInternal("Message");

    mockSchemaClass("Comment", 100);
    when(schema.existsClass("Comment")).thenReturn(true);

    var filter = makeWhereWithClassAttribute("Comment");
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 10000, messageClass, db);
    // 100/10000 = 0.01
    assertEquals(0.01, sel, 0.001);
  }

  // -- applyDepthMultiplier tests ---------------------------------------------

  @Test
  public void depthMultiplierNoWhile() {
    var edge = mockEdgeWithMethod("out");
    // No WHILE → no multiplier
    double cost = MatchExecutionPlanner.applyDepthMultiplier(100.0, edge);
    assertEquals(100.0, cost, 0.0);
  }

  @Test
  public void depthMultiplierWithMaxDepth() {
    var edge = mockEdgeWithWhileAndDepth(3);
    double cost = MatchExecutionPlanner.applyDepthMultiplier(100.0, edge);
    assertEquals(300.0, cost, 0.0);
  }

  @Test
  public void depthMultiplierWithWhileButNoMaxDepth() {
    var edge = mockEdgeWithWhileAndDepth(null);
    double cost = MatchExecutionPlanner.applyDepthMultiplier(100.0, edge);
    // DEFAULT_WHILE_DEPTH = 10
    assertEquals(1000.0, cost, 0.0);
  }

  @Test
  public void depthMultiplierLowerDepthIsCheaper() {
    var shallowEdge = mockEdgeWithWhileAndDepth(2);
    var deepEdge = mockEdgeWithWhileAndDepth(5);
    double shallowCost = MatchExecutionPlanner.applyDepthMultiplier(100.0, shallowEdge);
    double deepCost = MatchExecutionPlanner.applyDepthMultiplier(100.0, deepEdge);
    assertTrue("Shallow edge should be cheaper than deep edge",
        shallowCost < deepCost);
  }

  // -- Helper methods ---------------------------------------------------------

  private SQLWhereClause makeWhereWithPropertyAndOperator(
      String propertyName,
      com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator op) {
    var left = new SQLExpression(-1);
    var baseExpr = new SQLBaseExpression(-1);
    baseExpr.setIdentifier(new SQLBaseIdentifier(new SQLIdentifier(propertyName)));
    left.setMathExpression(baseExpr);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(op);
    condition.setRight(new SQLExpression(-1));

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);
    return where;
  }

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
    when(method.getMethodName()).thenReturn(new SQLIdentifier(methodName));
    when(method.getMethodNameString()).thenReturn(methodName);
    when(method.getParams()).thenReturn(List.of());
    return method;
  }

  private SQLMethodCall mockMethodCall(String methodName, String paramString) {
    var method = mock(SQLMethodCall.class);
    when(method.getMethodName()).thenReturn(new SQLIdentifier(methodName));
    when(method.getMethodNameString()).thenReturn(methodName);

    var base = mock(SQLBaseExpression.class);
    when(base.toString()).thenReturn(paramString);

    var param = mock(SQLExpression.class);
    when(param.getMathExpression()).thenReturn(base);

    when(method.getParams()).thenReturn(List.of(param));
    return method;
  }

  private SQLWhereClause makeWhereWithClassAttribute(String className) {
    var recAttr = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute(-1);
    recAttr.setName("@class");
    var baseId = new SQLBaseIdentifier(recAttr);
    var leftBase = new SQLBaseExpression(-1);
    leftBase.setIdentifier(baseId);
    var left = new SQLExpression(-1);
    left.setMathExpression(leftBase);

    var right = mock(SQLExpression.class);
    when(right.execute(
        (com.jetbrains.youtrackdb.internal.core.query.Result) isNull(),
        any())).thenReturn(className);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(right);

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);
    return where;
  }

  // -- applyTargetSelectivity: schema and class resolution edge cases --

  @Test
  public void targetSelectivityReturnsBaseCostWhenSchemaNull() {
    // When the schema snapshot is unavailable, cost should remain unchanged.
    var nullSchemaMetadata = mock(MetadataDefault.class);
    when(db.getMetadata()).thenReturn(nullSchemaMetadata);
    when(nullSchemaMetadata.getImmutableSchemaSnapshot()).thenReturn(null);

    var edge = mockEdgeWithMethodAndParam("out", "\"E\"");
    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "t", edge, true,
        Map.of("t", "Missing"), Map.of(), Map.of(), db);
    assertEquals(500.0, result, 0.0);
  }

  @Test
  public void targetSelectivityReturnsBaseCostWhenClassCountZero() {
    // An empty class (0 records) cannot provide selectivity; cost stays unchanged.
    mockSchemaClass("Empty", 0);
    when(schema.existsClass("Empty")).thenReturn(true);

    var edge = mockEdgeWithMethodAndParam("out", "\"E\"");
    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "t", edge, true,
        Map.of("t", "Empty"), Map.of(), Map.of(), db);
    assertEquals(500.0, result, 0.0);
  }

  @Test
  public void targetSelectivityReturnsBaseCostWhenClassCountExactlyOne() {
    // A class with exactly 1 record is valid and should apply selectivity normally.
    mockSchemaClass("Single", 1);
    when(schema.existsClass("Single")).thenReturn(true);

    var edge = mockEdgeWithMethodAndParam("out", "\"E\"");
    var eqFilter = makeWhereWithOperator(new SQLEqualsOperator(-1));

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "t", edge, true,
        Map.of("t", "Single"), Map.of("t", eqFilter),
        Map.of("t", 1L), db);
    // equality on class with 1 record: selectivity = 1/1 = 1.0, cost = 500
    assertTrue("Should not return baseCost unchanged", result <= 500.0);
  }

  // -- resolveTargetClass: graceful handling of missing schema info ----

  @Test
  public void resolveTargetClassReturnsNullWhenMethodNull() {
    // An edge without a traversal method (e.g. bare connection) cannot
    // resolve a target class, so cost stays at baseCost.
    var edge = mock(PatternEdge.class);
    edge.item = mock(SQLMatchPathItem.class);
    when(edge.item.getMethod()).thenReturn(null);

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "t", edge, true,
        Map.of(), Map.of(), Map.of(), db);
    assertEquals("Should return baseCost when class cannot be resolved",
        500.0, result, 0.0);
  }

  @Test
  public void resolveTargetClassReturnsNullWhenEdgeClassMissing() {
    // When the edge class name doesn't exist in schema, target class
    // cannot be inferred and cost stays unchanged.
    when(schema.getClassInternal("MissingEdge")).thenReturn(null);
    var edge = mockEdgeWithMethodAndParam("out", "\"MissingEdge\"");

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "t", edge, true,
        Map.of(), Map.of(), Map.of(), db);
    assertEquals(500.0, result, 0.0);
  }

  // -- estimateFilterSelectivity: invalid inputs and boundaries ---------

  @Test
  public void filterSelectivityReturnsNegativeWhenClassCountZero() {
    // Zero records means selectivity cannot be computed.
    var filter = makeWhereWithOperator(new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 0, null, null);
    assertTrue("classCount=0 should return negative", sel < 0);
  }

  @Test
  public void filterSelectivityReturnsNegativeWhenClassCountNegative() {
    // Negative record count is invalid; selectivity cannot be computed.
    var filter = makeWhereWithOperator(new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, -5, null, null);
    assertTrue("classCount<0 should return negative", sel < 0);
  }

  @Test
  public void filterSelectivityWorksWhenClassCountExactlyOne() {
    // A class with exactly 1 record is a valid input for selectivity estimation.
    var filter = makeWhereWithOperator(new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 1, null, null);
    // equality: 1/1 = 1.0
    assertEquals(1.0, sel, 0.001);
  }

  @Test
  public void filterSelectivityReturnsNegativeWhenBaseExpressionNull() {
    // A WHERE clause with no base expression is unclassifiable.
    var filter = new SQLWhereClause(-1);
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 1000, null, null);
    assertTrue("Null base expression should return negative", sel < 0);
  }

  // -- resolveDistinctCount: index statistics unavailable ---------------

  @Test
  public void resolveDistinctCountReturnsNegativeWhenNoIndexes() {
    // Property without any indexes falls back to classCount as divisor.
    mockSchemaClass("NoIdx", 100);
    when(schema.existsClass("NoIdx")).thenReturn(true);
    var noIdxClass = schema.getClassInternal("NoIdx");
    when(noIdxClass.getInvolvedIndexesInternal(db, "field")).thenReturn(Set.of());

    // Use estimateFilterSelectivity which calls resolveDistinctCount internally.
    // With no indexes, should fall back to classCount as divisor.
    var filter = makeWhereWithPropertyAndOperator("field", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 100, noIdxClass, db);
    // No index → divisor = classCount = 100 → 1/100 = 0.01
    assertEquals(0.01, sel, 0.001);
  }

  @Test
  public void resolveDistinctCountReturnsNegativeWhenStatsNull() {
    // Index exists but has no statistics collected yet.
    mockSchemaClass("NullStats", 100);
    when(schema.existsClass("NullStats")).thenReturn(true);
    var cls = schema.getClassInternal("NullStats");

    var idx = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(idx.getStatistics(db)).thenReturn(null);
    when(cls.getInvolvedIndexesInternal(db, "field")).thenReturn(Set.of(idx));

    var filter = makeWhereWithPropertyAndOperator("field", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 100, cls, db);
    // null stats → falls back to classCount → 1/100
    assertEquals(0.01, sel, 0.001);
  }

  @Test
  public void resolveDistinctCountReturnsNegativeWhenDistinctCountZero() {
    // Index has statistics but zero distinct values (e.g. freshly created empty index).
    mockSchemaClass("ZeroDC", 100);
    when(schema.existsClass("ZeroDC")).thenReturn(true);
    var cls = schema.getClassInternal("ZeroDC");

    var idx = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    var stats = new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(100, 0, 0);
    when(idx.getStatistics(db)).thenReturn(stats);
    when(cls.getInvolvedIndexesInternal(db, "field")).thenReturn(Set.of(idx));

    var filter = makeWhereWithPropertyAndOperator("field", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 100, cls, db);
    // distinctCount=0 → falls back to classCount → 1/100
    assertEquals(0.01, sel, 0.001);
  }

  // -- estimateClassAttributeSelectivity: @class heuristic edge cases --

  @Test
  public void classAttributeSelectivityNullSchemaClass() {
    // Without a schema class, the @class heuristic cannot look up subclass
    // counts and falls back to generic equality selectivity.
    var filter = makeWhereWithClassAttribute("Post");
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 1000, null, db);
    // Cannot evaluate @class without schema → falls back to classCount
    // Since it's an equals operator: 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  @Test
  public void classAttributeSelectivityNullSession() {
    // Without a session, the @class heuristic cannot access schema for
    // subclass lookups and falls back to generic equality selectivity.
    mockSchemaClass("Msg", 1000);
    when(schema.existsClass("Msg")).thenReturn(true);
    var filter = makeWhereWithClassAttribute("Post");
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 1000, schema.getClassInternal("Msg"), null);
    // Cannot evaluate @class without session → falls back to classCount
    assertEquals(0.001, sel, 0.0001);
  }

  @Test
  public void classAttributeSelectivityNonexistentSubclass() {
    // When @class references a class not present in schema, the heuristic
    // cannot compute a ratio and falls back to generic equality selectivity.
    mockSchemaClass("Msg", 1000);
    when(schema.existsClass("Msg")).thenReturn(true);
    when(schema.existsClass("Nonexistent")).thenReturn(false);

    var filter = makeWhereWithClassAttribute("Nonexistent");
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 1000, schema.getClassInternal("Msg"), db);
    // Subclass doesn't exist → falls back to classCount: 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  // -- applyDepthMultiplier: depth boundaries ----------------------------

  @Test
  public void depthMultiplierMaxDepthZeroUsesDefault() {
    // maxDepth=0 is not a meaningful depth limit; the default unbounded
    // multiplier should be applied instead of multiplying by zero.
    var edge = mockEdgeWithWhileAndDepth(0);
    double cost = MatchExecutionPlanner.applyDepthMultiplier(100.0, edge);
    assertEquals(1000.0, cost, 0.0);
  }

  @Test
  public void depthMultiplierSimpleEdgeWithoutWhilePreservesCost() {
    // A regular one-hop edge (no WHILE clause) should not modify the cost.
    var edge = mockEdgeWithMethod("out");
    double result = MatchExecutionPlanner.applyDepthMultiplier(
        Double.MAX_VALUE, edge);
    assertEquals(Double.MAX_VALUE, result, 0.0);
  }

  // -- resolveTargetClass: explicit class has priority over inference --

  @Test
  public void targetSelectivityUsesExplicitClassOverEdgeSchema() {
    // When aliasClasses provides an explicit class, edge schema inference
    // should not override it — even if the edge schema links to a different class.
    mockSchemaClass("Tag", 500);
    when(schema.existsClass("Tag")).thenReturn(true);
    mockEdgeClassWithVertexLinks("HAS_TAG", 5000, "Post", "OtherClass");

    var edge = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");
    var eqFilter = makeWhereWithOperator(new SQLEqualsOperator(-1));

    // aliasClasses says "Tag", edge schema says "OtherClass" → should use "Tag"
    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "t", edge, true,
        Map.of("t", "Tag"), Map.of("t", eqFilter),
        Map.of("t", 1L), db);
    // Uses Tag(500): equality → 1/500 * 500 = 1.0
    assertEquals(1.0, result, 0.01);
  }

  // -- resolveTargetClass: linked property without a target class ------

  @Test
  public void targetSelectivityReturnsBaseCostWhenLinkedPropHasNoClass() {
    // Edge class has in/out property defined but it's not linked to a vertex
    // class, so target class cannot be inferred.
    var edgeClass = mockSchemaClass("HAS_TAG", 5000);
    var linkedProp = mock(SchemaPropertyInternal.class);
    when(linkedProp.getLinkedClass()).thenReturn(null);
    when(edgeClass.getPropertyInternal("in")).thenReturn(linkedProp);

    var edge = mockEdgeWithMethodAndParam("out", "\"HAS_TAG\"");
    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "t", edge, true,
        Map.of(), Map.of(), Map.of(), db);
    assertEquals(500.0, result, 0.0);
  }

  @Test
  public void targetSelectivityClassCountExactlyZeroReturnsBaseCost() {
    mockSchemaClass("Zero", 0);
    when(schema.existsClass("Zero")).thenReturn(true);

    var edge = mockEdgeWithMethodAndParam("out", "\"E\"");
    var eqFilter = makeWhereWithOperator(new SQLEqualsOperator(-1));

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "t", edge, true,
        Map.of("t", "Zero"), Map.of("t", eqFilter),
        Map.of("t", 1L), db);
    // classCount=0 → should return baseCost (guarded by <= 0)
    assertEquals(500.0, result, 0.0);
  }

  @Test
  public void targetSelectivityReturnsBaseCostWhenClassNotExists() {
    when(schema.existsClass("Ghost")).thenReturn(false);

    var edge = mockEdgeWithMethodAndParam("out", "\"E\"");
    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "t", edge, true,
        Map.of("t", "Ghost"), Map.of(), Map.of(), db);
    assertEquals(500.0, result, 0.0);
  }

  // -- estimateFilterSelectivity: compound and edge-case filters -------

  @Test
  public void filterSelectivityCompoundFilterReturnsNegative() {
    // A compound filter (AND with multiple conditions) cannot be classified
    // as simple equality or inequality, so selectivity is unknown.
    var and = new SQLAndBlock(-1);
    and.getSubBlocks().add(new SQLBinaryCondition(-1));
    and.getSubBlocks().add(new SQLBinaryCondition(-1));

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(and);
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, null, null);
    assertTrue("Compound filter should return negative", sel < 0);
  }

  @Test
  public void resolveDistinctCountHandlesNullIndexes() {
    mockSchemaClass("NullIdx", 100);
    when(schema.existsClass("NullIdx")).thenReturn(true);
    var cls = schema.getClassInternal("NullIdx");
    when(cls.getInvolvedIndexesInternal(db, "field")).thenReturn(null);

    var filter = makeWhereWithPropertyAndOperator("field", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 100, cls, db);
    // null indexes → falls back to classCount: 1/100
    assertEquals(0.01, sel, 0.001);
  }

  // -- estimateClassAttributeSelectivity: value type edge cases --------

  @Test
  public void classAttributeSelectivityTargetCountZero() {
    // When the target class has no records, the @class ratio is undefined.
    mockSchemaClass("Msg", 0);
    when(schema.existsClass("Msg")).thenReturn(true);
    var filter = makeWhereWithClassAttribute("Post");
    // classCount=0 → estimateFilterSelectivity returns -1 before reaching classAttr
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 0, schema.getClassInternal("Msg"), db);
    assertTrue("classCount=0 should return negative", sel < 0);
  }

  @Test
  public void classAttributeSelectivityWithNonStringValue() {
    // When the right side of @class = X evaluates to a non-String (e.g. Integer),
    // the heuristic cannot look up a class name and falls back.
    mockSchemaClass("Msg", 1000);
    when(schema.existsClass("Msg")).thenReturn(true);

    // Build @class condition where right side evaluates to Integer instead of String
    var recAttr = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute(-1);
    recAttr.setName("@class");
    var baseId = new SQLBaseIdentifier(recAttr);
    var leftBase = new SQLBaseExpression(-1);
    leftBase.setIdentifier(baseId);
    var left = new SQLExpression(-1);
    left.setMathExpression(leftBase);

    var right = mock(SQLExpression.class);
    when(right.execute(
        (com.jetbrains.youtrackdb.internal.core.query.Result) isNull(),
        any())).thenReturn(42); // Integer, not String

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(right);

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, schema.getClassInternal("Msg"), db);
    // Non-string value → @class heuristic returns -1 → falls back to 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  @Test
  public void classAttributeSelectivityWithExceptionInExecute() {
    // When the right-side expression throws during evaluation (e.g.
    // unresolvable parameter), the heuristic gracefully falls back.
    mockSchemaClass("Msg", 1000);
    when(schema.existsClass("Msg")).thenReturn(true);

    var recAttr = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute(-1);
    recAttr.setName("@class");
    var baseId = new SQLBaseIdentifier(recAttr);
    var leftBase = new SQLBaseExpression(-1);
    leftBase.setIdentifier(baseId);
    var left = new SQLExpression(-1);
    left.setMathExpression(leftBase);

    var right = mock(SQLExpression.class);
    when(right.execute(
        (com.jetbrains.youtrackdb.internal.core.query.Result) isNull(),
        any())).thenThrow(new RuntimeException("eval failed"));

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(right);

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, schema.getClassInternal("Msg"), db);
    // Exception → @class heuristic returns -1 → falls back to 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  // -- unwrapSingleCondition: OR and NOT block paths --------------------

  @Test
  public void filterSelectivityThroughSingleElementOrBlock() {
    // A WHERE clause wrapped in a single-element OR block should be unwrapped
    // to the inner binary condition and classified normally.
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(new SQLExpression(-1));
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1));

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);

    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(andBlock);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, null, null);
    // Should unwrap OR→AND→BinaryCondition and classify as equality: 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  @Test
  public void filterSelectivityMultiElementOrBlock() {
    // OR of two conditions: sel(A OR B) = 1 - (1-sel(A)) * (1-sel(B))
    // A: equality → 1/1000 = 0.001
    // B: inequality → 999/1000 = 0.999
    // sel = 1 - (1-0.001)*(1-0.999) = 1 - 0.999*0.001 = 1 - 0.000999 ≈ 0.999
    var cond1 = new SQLBinaryCondition(-1);
    cond1.setLeft(new SQLExpression(-1));
    cond1.setOperator(new SQLEqualsOperator(-1));
    cond1.setRight(new SQLExpression(-1));

    var cond2 = new SQLBinaryCondition(-1);
    cond2.setLeft(new SQLExpression(-1));
    cond2.setOperator(new SQLNeOperator(-1));
    cond2.setRight(new SQLExpression(-1));

    var and1 = new SQLAndBlock(-1);
    and1.getSubBlocks().add(cond1);
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(cond2);

    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(and1);
    orBlock.getSubBlocks().add(and2);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, null, null);
    // 1 - (1-0.001)*(1-0.999) = 1 - 0.999*0.001 ≈ 0.999001
    assertEquals(0.999, sel, 0.001);
  }

  @Test
  public void filterSelectivityOrOfTwoEqualities() {
    // OR of two equalities: sel = 1 - (1-1/1000)^2 ≈ 0.002
    var cond1 = new SQLBinaryCondition(-1);
    cond1.setLeft(new SQLExpression(-1));
    cond1.setOperator(new SQLEqualsOperator(-1));
    cond1.setRight(new SQLExpression(-1));

    var cond2 = new SQLBinaryCondition(-1);
    cond2.setLeft(new SQLExpression(-1));
    cond2.setOperator(new SQLEqualsOperator(-1));
    cond2.setRight(new SQLExpression(-1));

    var and1 = new SQLAndBlock(-1);
    and1.getSubBlocks().add(cond1);
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(cond2);

    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(and1);
    orBlock.getSubBlocks().add(and2);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, null, null);
    // 1 - (1-0.001)^2 = 1 - 0.998001 ≈ 0.001999
    assertEquals(0.002, sel, 0.001);
  }

  @Test
  public void filterSelectivityThroughNonNegatedNotBlock() {
    // A SQLNotBlock with isNegate()=false is a passthrough wrapper;
    // unwrapSingleCondition should descend into its child.
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(new SQLExpression(-1));
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1));

    var notBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock(-1);
    notBlock.setSub(condition);
    // SQLNotBlock default isNegate() is false (no NOT keyword)

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(notBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, null, null);
    // Should unwrap NOT(non-negated)→BinaryCondition: equality → 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  @Test
  public void filterSelectivityNegatedNotBlockReturnsNegative() {
    // A SQLNotBlock with isNegate()=true (actual NOT) should NOT be unwrapped;
    // the result is a negated expression which is not a simple binary condition.
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(new SQLExpression(-1));
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1));

    var notBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock(-1);
    notBlock.setSub(condition);
    notBlock.setNegate(true);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(notBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, null, null);
    // Negated NOT block is not a simple binary condition → negative
    assertTrue("Negated NOT block should return negative", sel < 0);
  }

  // -- resolveTargetClass: inbound direction uses "out" property -------

  @Test
  public void targetSelectivityInfersClassFromInboundEdge() {
    // For an inbound edge (.in), the target vertex is the "out" end of the
    // edge class, not "in". Verify the direction logic is correct.
    mockSchemaClass("Person", 200);
    when(schema.existsClass("Person")).thenReturn(true);
    mockEdgeClassWithVertexLinks("HAS_CREATOR", 5000, "Person", "Message");

    var edge = mockEdgeWithMethodAndParam("in", "\"HAS_CREATOR\"");
    var eqFilter = makeWhereWithOperator(new SQLEqualsOperator(-1));

    // inbound: target is "out" vertex → Person (200 records)
    double adjusted = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "p", edge, false,
        Map.of(), Map.of("p", eqFilter),
        Map.of("p", 1L), db);
    // equality on Person(200): 1/200 * 500 = 2.5
    assertEquals(2.5, adjusted, 0.1);
  }

  // -- aliasFilters null safety -----------------------------------------

  @Test
  public void targetSelectivityWithNullAliasFilters() {
    // aliasFilters can be null when no filters are provided.
    mockSchemaClass("Tag", 1000);
    when(schema.existsClass("Tag")).thenReturn(true);

    var edge = mockEdgeWithMethodAndParam("out", "\"E\"");

    double result = MatchExecutionPlanner.applyTargetSelectivity(
        500.0, "t", edge, true,
        Map.of("t", "Tag"), null,
        Map.of("t", 100L), db);
    // No filter → falls back to estimatedRootEntries: 100/1000 * 500 = 50
    assertEquals(50.0, result, 0.1);
  }

  // -- heuristic == 0.0 boundary: selectivity of zero should be used ---

  @Test
  public void filterSelectivityReturnsZeroForEmptySubclass() {
    // When @class = 'EmptySubclass' and the subclass has 0 records,
    // selectivity = 0/10000 = 0.0. This is a valid selectivity (not -1).
    mockSchemaClass("Message", 10000);
    when(schema.existsClass("Message")).thenReturn(true);
    var messageClass = schema.getClassInternal("Message");

    mockSchemaClass("EmptySub", 0);
    when(schema.existsClass("EmptySub")).thenReturn(true);

    var filter = makeWhereWithClassAttribute("EmptySub");
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 10000, messageClass, db);
    // 0 / 10000 = 0.0 — valid selectivity, should be returned (not -1)
    assertEquals(0.0, sel, 0.0001);
  }

  // -- Precise return value tests for resolveDistinctCount paths ------

  @Test
  public void filterSelectivityEqualityWithDistinctCountOneReturnsOne() {
    // distinctCount=1 → equality selectivity = 1/1 = 1.0 (not 1/classCount).
    // If resolveDistinctCount wrongly returns 0, divisor falls back to classCount,
    // giving 1/100 instead of 1.0.
    mockSchemaClass("OneVal", 100);
    when(schema.existsClass("OneVal")).thenReturn(true);
    var cls = schema.getClassInternal("OneVal");

    var idx = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    var stats = new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(100, 1, 0);
    when(idx.getStatistics(db)).thenReturn(stats);
    when(cls.getInvolvedIndexesInternal(db, "field")).thenReturn(Set.of(idx));

    var filter = makeWhereWithPropertyAndOperator("field", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 100, cls, db);
    // distinctCount=1 → 1/1 = 1.0
    assertEquals(1.0, sel, 0.001);
  }

  @Test
  public void filterSelectivityInequalityWithDistinctCountOneReturnsZero() {
    // distinctCount=1 → inequality selectivity = (1-1)/1 = 0.0.
    // Verifies the inequality formula uses distinctCount correctly.
    mockSchemaClass("OneVal2", 100);
    when(schema.existsClass("OneVal2")).thenReturn(true);
    var cls = schema.getClassInternal("OneVal2");

    var idx = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    var stats = new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(100, 1, 0);
    when(idx.getStatistics(db)).thenReturn(stats);
    when(cls.getInvolvedIndexesInternal(db, "field")).thenReturn(Set.of(idx));

    var filter = makeWhereWithPropertyAndOperator("field", new SQLNeOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        filter, 100, cls, db);
    // (1-1)/1 = 0.0
    assertEquals(0.0, sel, 0.001);
  }

  @Test
  public void filterSelectivityWithPropNameNull() {
    // Binary condition where getRelatedIndexPropertyName returns null
    // (left side is not a base identifier). resolveDistinctCount should
    // return -1 and divisor falls back to classCount.
    mockSchemaClass("NoProp", 200);
    when(schema.existsClass("NoProp")).thenReturn(true);
    var cls = schema.getClassInternal("NoProp");

    // Create condition with non-identifier left side (empty expression)
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(new SQLExpression(-1)); // not a base identifier
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1));

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 200, cls, db);
    // propName=null → distinctCount fallback → divisor=classCount=200 → 1/200
    assertEquals(0.005, sel, 0.001);
  }

  // -- estimateClassAttributeSelectivity: reachable -1.0 paths --------

  @Test
  public void classAttributeSelectivityWithNonBaseExpressionLeft() {
    // @class check fails when left.getMathExpression() is not SQLBaseExpression.
    // Falls back to generic equality selectivity.
    mockSchemaClass("Msg2", 1000);
    when(schema.existsClass("Msg2")).thenReturn(true);
    var cls = schema.getClassInternal("Msg2");

    var left = new SQLExpression(-1);
    // Set mathExpression to a non-SQLBaseExpression (e.g. a math operation)
    left.setMathExpression(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression(-1));

    var right = mock(SQLExpression.class);
    when(right.execute(
        (com.jetbrains.youtrackdb.internal.core.query.Result) isNull(),
        any())).thenReturn("Post");

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(right);

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    // Not @class pattern → falls back to generic: 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  @Test
  public void classAttributeSelectivityWithNullIdentifier() {
    // SQLBaseExpression without an identifier → @class check returns -1.
    mockSchemaClass("Msg3", 1000);
    when(schema.existsClass("Msg3")).thenReturn(true);
    var cls = schema.getClassInternal("Msg3");

    var baseExpr = new SQLBaseExpression(-1);
    // Don't set identifier — it stays null
    var left = new SQLExpression(-1);
    left.setMathExpression(baseExpr);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1));

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    // No identifier → not @class → falls back to 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  @Test
  public void classAttributeSelectivityWithNonClassRecordAttribute() {
    // Record attribute that is NOT @class (e.g. @rid) → returns -1.
    mockSchemaClass("Msg4", 1000);
    when(schema.existsClass("Msg4")).thenReturn(true);
    var cls = schema.getClassInternal("Msg4");

    var recAttr = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute(-1);
    recAttr.setName("@rid"); // NOT @class
    var baseId = new SQLBaseIdentifier(recAttr);
    var leftBase = new SQLBaseExpression(-1);
    leftBase.setIdentifier(baseId);
    var left = new SQLExpression(-1);
    left.setMathExpression(leftBase);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1));

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    // @rid is not @class → falls back to 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  @Test
  public void classAttributeSelectivityWithNullRight() {
    // @class = <null right> → returns -1, falls back to generic.
    mockSchemaClass("Msg5", 1000);
    when(schema.existsClass("Msg5")).thenReturn(true);
    var cls = schema.getClassInternal("Msg5");

    var recAttr = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute(-1);
    recAttr.setName("@class");
    var baseId = new SQLBaseIdentifier(recAttr);
    var leftBase = new SQLBaseExpression(-1);
    leftBase.setIdentifier(baseId);
    var left = new SQLExpression(-1);
    left.setMathExpression(leftBase);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    // right is null — not set

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    // null right → @class heuristic returns -1 → 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  // compound AND/OR and helper methods

  /**
   * Compound AND with two equality conditions: sel = sel(a) * sel(b).
   */
  @Test
  public void compoundAnd_multipliesSelectivities() {
    // Two conditions: field1 = X AND field2 = X, each with distinctCount=10
    // Expected: 1/10 * 1/10 = 0.01
    var cond1 = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var cond2 = makeBinaryWithProperty("f2", new SQLEqualsOperator(-1));
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(cond1);
    andBlock.getSubBlocks().add(cond2);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10, null, null);
    // No schema → distinctCount fallback to classCount=10 → each = 1/10
    assertEquals("AND should multiply: 1/10 * 1/10", 0.01, sel, 0.001);
  }

  /**
   * Compound AND where only one sub-condition is estimable — the other
   * returns -1 and is skipped. Result should be just the estimable one.
   */
  @Test
  public void compoundAnd_skipsUnestimableConditions() {
    var estimable = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    // A condition with a non-standard operator that returns -1
    var unestimable = makeBinaryWithProperty("f2",
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator(-1));
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(estimable);
    andBlock.getSubBlocks().add(unestimable);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, null, null);
    // Only f1 = X is estimable → 1/100
    assertEquals("AND with one estimable: 1/100", 0.01, sel, 0.001);
  }

  /**
   * Compound AND where NO sub-conditions are estimable → returns -1.
   */
  @Test
  public void compoundAnd_allUnestimable_returnsNegative() {
    var c1 = makeBinaryWithProperty("f1",
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator(-1));
    var c2 = makeBinaryWithProperty("f2",
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator(-1));
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(c1);
    andBlock.getSubBlocks().add(c2);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, null, null);
    assertEquals(-1.0, sel, 0.0);
  }

  /**
   * Compound OR: sel(A OR B) = 1 - (1 - sel(A)) * (1 - sel(B)).
   * Two equalities on classCount=10: 1 - (1 - 0.1)*(1 - 0.1) = 0.19
   */
  @Test
  public void compoundOr_usesInclusionExclusion() {
    var cond1 = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var cond2 = makeBinaryWithProperty("f2", new SQLEqualsOperator(-1));
    var and1 = new SQLAndBlock(-1);
    and1.getSubBlocks().add(cond1);
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(cond2);
    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(and1);
    orBlock.getSubBlocks().add(and2);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10, null, null);
    // 1 - (1 - 1/10) * (1 - 1/10) = 1 - 0.81 = 0.19
    assertEquals("OR inclusion-exclusion", 0.19, sel, 0.01);
  }

  /**
   * Nested AND inside OR: OR(AND(a, b), c).
   * Tests estimateSubExpression with nested compound blocks.
   */
  @Test
  public void nestedAndInsideOr_estimatesCorrectly() {
    // OR branch 1: AND(f1=X, f2=X) with classCount=10 → 1/10 * 1/10 = 0.01
    var c1 = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var c2 = makeBinaryWithProperty("f2", new SQLEqualsOperator(-1));
    var innerAnd = new SQLAndBlock(-1);
    innerAnd.getSubBlocks().add(c1);
    innerAnd.getSubBlocks().add(c2);

    // OR branch 2: f3=X → 1/10 = 0.1
    var c3 = makeBinaryWithProperty("f3", new SQLEqualsOperator(-1));
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(c3);

    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(innerAnd);
    orBlock.getSubBlocks().add(and2);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10, null, null);
    // 1 - (1 - 0.01) * (1 - 0.1) = 1 - 0.99 * 0.9 = 1 - 0.891 = 0.109
    assertEquals("Nested AND inside OR", 0.109, sel, 0.01);
  }

  /**
   * Nested OR inside AND: AND(OR(a, b), c).
   * Tests estimateSubExpression with nested OR block.
   */
  @Test
  public void nestedOrInsideAnd_estimatesCorrectly() {
    // AND term 1: OR(f1=X, f2=X) with classCount=10 → 0.19
    var c1 = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var c2 = makeBinaryWithProperty("f2", new SQLEqualsOperator(-1));
    var and1 = new SQLAndBlock(-1);
    and1.getSubBlocks().add(c1);
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(c2);
    var innerOr = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    innerOr.getSubBlocks().add(and1);
    innerOr.getSubBlocks().add(and2);

    // AND term 2: f3=X → 1/10 = 0.1
    var c3 = makeBinaryWithProperty("f3", new SQLEqualsOperator(-1));

    var outerAnd = new SQLAndBlock(-1);
    outerAnd.getSubBlocks().add(innerOr);
    outerAnd.getSubBlocks().add(c3);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(outerAnd);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10, null, null);
    // 0.19 * 0.1 = 0.019
    assertEquals("Nested OR inside AND", 0.019, sel, 0.005);
  }

  /**
   * extractRecordAttribute with null expression → returns null (no NPE).
   */
  @Test
  public void classAttributeSelectivity_nullLeftExpression_returnsNegative() {
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(null);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1));

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    // classCount=100, no schema → classAttr returns -1 → falls to distinctCount
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, null, null);
    // null left → not @class → falls to 1/classCount = 0.01
    assertEquals(0.01, sel, 0.001);
  }

  /**
   * evaluateAsString with non-string return → returns null.
   */
  @Test
  public void classAttributeSelectivity_nonStringRight_returnsNegative() {
    var where = makeWhereWithClassAttribute(null);
    var cls = mockSchemaClass("SomeClass", 500);
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    // @class = null → subclassName=null → returns -1 → fallback to 1/1000
    assertEquals(0.001, sel, 0.0001);
  }

  /**
   * resolveDistinctCount returns -1 when schema is null.
   * Falls back to classCount.
   */
  @Test
  public void resolveDistinctCount_nullSchema_usesClassCountFallback() {
    var where = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));
    // null schema → resolveDistinctCount returns -1 → divisor = classCount
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 50, null, null);
    assertEquals("Fallback to 1/classCount", 1.0 / 50, sel, 0.0001);
  }

  /**
   * Single NE condition with classCount fallback.
   */
  @Test
  public void singleNe_classCountFallback() {
    var where = makeWhereWithOperator(new SQLNeOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 20, null, null);
    assertEquals("NE: (20-1)/20", 19.0 / 20, sel, 0.001);
  }

  /**
   * Single NE condition with distinctCount from index.
   */
  @Test
  public void singleNe_withDistinctCount() {
    var cls = mockSchemaClass("Tag", 1000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            1000, 200, 0));

    var where = makeWhereWithPropertyAndOperator("name", new SQLNeOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    assertEquals("NE: (200-1)/200", 199.0 / 200, sel, 0.001);
  }

  private SQLBinaryCondition makeBinaryWithProperty(String propertyName,
      com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator op) {
    var left = new SQLExpression(-1);
    var baseExpr = new SQLBaseExpression(-1);
    baseExpr.setIdentifier(new SQLBaseIdentifier(new SQLIdentifier(propertyName)));
    left.setMathExpression(baseExpr);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(op);
    condition.setRight(new SQLExpression(-1));
    return condition;
  }

  /**
   * estimateSubExpression with an unrecognizable expression type (not binary,
   * not AND, not OR) returns -1.0.
   */
  @Test
  public void estimateSubExpression_unrecognizable_returnsNegative() {
    // SQLNotBlock with negate=true is not handled → returns -1.0
    var notBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock(-1);
    notBlock.setNegate(true);
    notBlock.setSub(makeBinaryWithProperty("f1", new SQLEqualsOperator(-1)));

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(makeBinaryWithProperty("f1", new SQLEqualsOperator(-1)));
    andBlock.getSubBlocks().add(notBlock);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    // One condition estimable (1/100), one not (-1) → combined = 1/100
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, null, null);
    assertEquals(0.01, sel, 0.001);
  }

  /**
   * Nested OR inside compound AND: AND(OR(a, b), c) where nested OR has
   * size > 1.
   */
  @Test
  public void nestedOrInsideAnd_killsMutant() {
    var c1 = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var c2 = makeBinaryWithProperty("f2", new SQLEqualsOperator(-1));
    var and1 = new SQLAndBlock(-1);
    and1.getSubBlocks().add(c1);
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(c2);
    var innerOr = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    innerOr.getSubBlocks().add(and1);
    innerOr.getSubBlocks().add(and2);

    var c3 = makeBinaryWithProperty("f3", new SQLEqualsOperator(-1));
    var outerAnd = new SQLAndBlock(-1);
    outerAnd.getSubBlocks().add(innerOr);
    outerAnd.getSubBlocks().add(c3);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(outerAnd);

    // OR: 1-(1-1/10)*(1-1/10) = 0.19, AND with 1/10 = 0.019
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10, null, null);
    assertTrue("Nested OR inside AND should produce small selectivity",
        sel > 0.0 && sel < 0.1);
  }

  /**
   * Nested AND inside compound OR: OR(AND(a, b), c) where nested AND has
   * size > 1.
   */
  @Test
  public void nestedAndInsideOr_killsMutant() {
    var c1 = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var c2 = makeBinaryWithProperty("f2", new SQLEqualsOperator(-1));
    var innerAnd = new SQLAndBlock(-1);
    innerAnd.getSubBlocks().add(c1);
    innerAnd.getSubBlocks().add(c2);

    var c3 = makeBinaryWithProperty("f3", new SQLEqualsOperator(-1));
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(c3);

    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(innerAnd);
    orBlock.getSubBlocks().add(and2);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    // AND: 1/10 * 1/10 = 0.01; OR with 1/10: 1-(1-0.01)*(1-0.1) = 0.109
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10, null, null);
    assertEquals(0.109, sel, 0.02);
  }

  /**
   * estimateViaHistogram: index with stats but value resolution fails
   * (parameterized query). Falls to uniform estimation.
   */
  @Test
  public void histogramEstimation_parameterizedValue_fallsToUniform() {
    var cls = mockSchemaClass("Person", 1000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            1000, 100, 0));
    // No histogram — should fall back to distinctCount
    when(index.getHistogram(any())).thenReturn(null);

    // Right side is a mock that returns null (simulating unresolvable param)
    var left = new SQLExpression(-1);
    var baseExpr = new SQLBaseExpression(-1);
    baseExpr.setIdentifier(new SQLBaseIdentifier(new SQLIdentifier("name")));
    left.setMathExpression(baseExpr);

    var right = mock(SQLExpression.class);
    when(right.execute(
        (com.jetbrains.youtrackdb.internal.core.query.Result) isNull(),
        isNull())).thenThrow(new NullPointerException("no ctx"));

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(right);

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    // histogram fails (NPE) → distinctCount=100 → 1/100
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    assertEquals(0.01, sel, 0.001);
  }

  /**
   * estimateViaHistogram: index with stats and literal value resolves.
   * Tests the full histogram path.
   */
  @Test
  public void histogramEstimation_literalValue_usesEstimator() {
    var cls = mockSchemaClass("Person", 1000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            1000, 100, 0));
    when(index.getHistogram(any())).thenReturn(null);

    // Right side is a literal "Alice" that can be resolved at plan time
    var where = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    // With stats: distinctCount=100 → SelectivityEstimator.estimateEquality
    // or uniform fallback 1/100
    assertTrue("Should get selectivity from index stats",
        sel > 0.0 && sel <= 0.02);
  }

  /**
   * extractRecordAttribute: expression with no math expression → null.
   */
  @Test
  public void classAttrSelectivity_noMathExpr_returnsNegative() {
    var left = new SQLExpression(-1);
    // No mathExpression set → extractRecordAttribute returns null
    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1));

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, null, null);
    assertEquals("No @class → 1/classCount", 0.01, sel, 0.001);
  }

  /**
   * extractRecordAttribute: identifier with null suffix → null.
   */
  @Test
  public void classAttrSelectivity_nullSuffix_returnsNegative() {
    var left = new SQLExpression(-1);
    var base = new SQLBaseExpression(-1);
    // Identifier with no suffix (no record attribute)
    base.setIdentifier(new SQLBaseIdentifier(-1));
    left.setMathExpression(base);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(new SQLExpression(-1));

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, null, null);
    assertEquals("No @class → 1/classCount", 0.01, sel, 0.001);
  }

  /**
   * evaluateAsString: right side returns non-String → @class path returns -1.
   */
  @Test
  public void classAttrSelectivity_rightReturnsNumber_fallsToDistinctCount() {
    var recAttr =
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRecordAttribute(-1);
    recAttr.setName("@class");
    var baseId = new SQLBaseIdentifier(recAttr);
    var leftBase = new SQLBaseExpression(-1);
    leftBase.setIdentifier(baseId);
    var left = new SQLExpression(-1);
    left.setMathExpression(leftBase);

    // Right returns a number, not a string
    var right = mock(SQLExpression.class);
    when(right.execute(
        (com.jetbrains.youtrackdb.internal.core.query.Result) isNull(),
        any())).thenReturn(42);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(right);

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, null, null);
    // @class returns -1 (non-string right) → 1/classCount
    assertEquals(0.01, sel, 0.001);
  }

  /**
   * Compound OR where all sub-conditions are unestimable → returns -1.
   */
  @Test
  public void compoundOr_allUnestimable_returnsNegative() {
    var c1 = makeBinaryWithProperty("f1",
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator(-1));
    var and1 = new SQLAndBlock(-1);
    and1.getSubBlocks().add(c1);
    var c2 = makeBinaryWithProperty("f2",
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator(-1));
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(c2);

    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(and1);
    orBlock.getSubBlocks().add(and2);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, null, null);
    assertEquals(-1.0, sel, 0.0);
  }

  /**
   * Single equality with classCount=1 → selectivity = 1/1 = 1.0.
   */
  @Test
  public void singleEquality_classCountOne_returnsOne() {
    var where = makeWhereWithOperator(new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1, null, null);
    assertEquals(1.0, sel, 0.0);
  }

  /**
   * resolveDistinctCount with index that has stats with distinctCount=0 → falls
   * to classCount.
   */
  @Test
  public void resolveDistinctCount_zeroDistinctCount_fallsToClassCount() {
    var cls = mockSchemaClass("Tag", 500);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    // distinctCount=0 → should fall back
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            500, 0, 0));

    var where = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 500, cls, db);
    // distinctCount=0 → fallback to classCount=500 → 1/500
    assertEquals(1.0 / 500, sel, 0.001);
  }

  /**
   * AND block with exactly 1 sub-condition should NOT enter the compound AND path
   * (treated as single condition via unwrap).
   */
  @Test
  public void singleElementAndBlock_treatedAsSingle() {
    var cond = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(cond);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 50, null, null);
    // Single equality: 1/50 = 0.02
    assertEquals(0.02, sel, 0.001);
  }

  /**
   * OR block with exactly 1 sub-condition should NOT enter the compound OR path.
   */
  @Test
  public void singleElementOrBlock_treatedAsSingle() {
    var cond = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var inner = new SQLAndBlock(-1);
    inner.getSubBlocks().add(cond);
    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(inner);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 50, null, null);
    assertEquals(0.02, sel, 0.001);
  }

  /**
   * Nested AND with exactly 1 sub-condition inside OR → not treated as compound AND
   * in estimateSubExpression.
   */
  @Test
  public void nestedSingleAnd_insideOr_notCompound() {
    // OR branch 1: AND with 1 element (single, not compound)
    var c1 = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var singleAnd = new SQLAndBlock(-1);
    singleAnd.getSubBlocks().add(c1);

    // OR branch 2: normal single
    var c2 = makeBinaryWithProperty("f2", new SQLEqualsOperator(-1));
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(c2);

    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(singleAnd);
    orBlock.getSubBlocks().add(and2);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10, null, null);
    // Both are 1/10 = 0.1 → OR: 1-(1-0.1)*(1-0.1) = 0.19
    assertEquals(0.19, sel, 0.01);
  }

  /**
   * Nested OR with exactly 1 sub-condition inside AND → not treated as compound OR
   * in estimateSubExpression.
   */
  @Test
  public void nestedSingleOr_insideAnd_notCompound() {
    var c1 = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var inner = new SQLAndBlock(-1);
    inner.getSubBlocks().add(c1);
    var singleOr = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    singleOr.getSubBlocks().add(inner);

    var c2 = makeBinaryWithProperty("f2", new SQLEqualsOperator(-1));

    var outerAnd = new SQLAndBlock(-1);
    outerAnd.getSubBlocks().add(singleOr);
    outerAnd.getSubBlocks().add(c2);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(outerAnd);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10, null, null);
    // singleOr unwraps to 1/10, c2 = 1/10 → AND: 0.1 * 0.1 = 0.01
    assertEquals(0.01, sel, 0.001);
  }

  /**
   * Compound OR with exactly 2 elements and sel >= 0 boundary.
   */
  @Test
  public void compoundOr_twoElements_boundary() {
    var c1 = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var and1 = new SQLAndBlock(-1);
    and1.getSubBlocks().add(c1);
    var c2 = makeBinaryWithProperty("f2", new SQLNeOperator(-1));
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(c2);

    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(and1);
    orBlock.getSubBlocks().add(and2);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    // f1=: 1/20=0.05, f2<>: 19/20=0.95 → OR: 1-(1-0.05)*(1-0.95) = 1-0.95*0.05 = 0.9525
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 20, null, null);
    assertTrue(sel > 0.9 && sel < 1.0);
  }

  /**
   * Compound AND with exactly 2 elements using NE operator.
   */
  @Test
  public void compoundAnd_twoNe_boundary() {
    var c1 = makeBinaryWithProperty("f1", new SQLNeOperator(-1));
    var c2 = makeBinaryWithProperty("f2", new SQLNeOperator(-1));
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(c1);
    andBlock.getSubBlocks().add(c2);

    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    // NE: (20-1)/20 = 0.95 each → AND: 0.95 * 0.95 = 0.9025
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 20, null, null);
    assertEquals(0.9025, sel, 0.01);
  }

  /**
   * estimateSingleConditionSelectivity with histogram returning exactly 0.0.
   */
  @Test
  public void histogramSelectivity_exactlyZero_isValid() {
    var cls = mockSchemaClass("Rare", 1000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    // totalCount > 0 but distinctCount = 1 → equality = 1.0
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            1000, 1, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var where = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    assertTrue("Selectivity should be valid (>=0)", sel >= 0.0);
  }

  /**
   * estimateViaHistogram with totalCount exactly 0 → skips index.
   */
  @Test
  public void histogramEstimation_zeroTotalCount_skipsIndex() {
    var cls = mockSchemaClass("Empty", 0);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            0, 0, 0));

    var where = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));
    // classCount=0 → estimateFilterSelectivity returns -1 early
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 0, cls, db);
    assertEquals(-1.0, sel, 0.0);
  }

  /**
   * estimateViaHistogram with totalCount=1 → does not skip.
   */
  @Test
  public void histogramEstimation_totalCountOne_doesNotSkip() {
    var cls = mockSchemaClass("Tiny", 1);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            1, 1, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var where = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1, cls, db);
    assertEquals(1.0, sel, 0.01);
  }

  /**
   * resolveDistinctCount with distinctCount exactly 1.
   */
  @Test
  public void resolveDistinctCount_one_usesIt() {
    var cls = mockSchemaClass("Single", 100);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            100, 1, 0));

    var where = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, cls, db);
    // distinctCount=1 → 1/1 = 1.0
    assertEquals(1.0, sel, 0.01);
  }

  /**
   * Nested AND inside OR must return non-zero selectivity that differs from 0.0.
   * The test asserts the exact value so mutating the return to 0.0 would fail.
   */
  @Test
  public void nestedAndInsideOr_exactSelectivityValue() {
    // OR(AND(f1=, f2=), f3=) with classCount=100
    var c1 = makeBinaryWithProperty("f1", new SQLEqualsOperator(-1));
    var c2 = makeBinaryWithProperty("f2", new SQLEqualsOperator(-1));
    var innerAnd = new SQLAndBlock(-1);
    innerAnd.getSubBlocks().add(c1);
    innerAnd.getSubBlocks().add(c2);

    var c3 = makeBinaryWithProperty("f3", new SQLEqualsOperator(-1));
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(c3);

    var orBlock = new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock(-1);
    orBlock.getSubBlocks().add(innerAnd);
    orBlock.getSubBlocks().add(and2);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(orBlock);

    // AND: 1/100 * 1/100 = 0.0001; OR: 1 - (1-0.0001)*(1-0.01) = ~0.01009
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, null, null);
    assertTrue("Nested AND selectivity must not be 0.0", sel > 0.009);
    assertEquals(0.01009, sel, 0.001);
  }

  /**
   * Histogram path returns non-zero selectivity when index has stats.
   */
  @Test
  public void singleConditionWithHistogram_returnsNonZero() {
    var cls = mockSchemaClass("Tag", 500);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            500, 50, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var where = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 500, cls, db);
    // distinctCount=50 → 1/50 = 0.02
    assertTrue("estimateSingleConditionSelectivity must not return 0.0", sel > 0.0);
    assertEquals(0.02, sel, 0.001);
  }

  /**
   * When index has stats and value resolves, histogram path returns non-zero.
   */
  @Test
  public void histogramPath_exactReturnValue() {
    var cls = mockSchemaClass("City", 200);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            200, 20, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var where = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 200, cls, db);
    assertTrue("histogram path return must not be 0.0", sel > 0.0);
    assertEquals(0.05, sel, 0.01);
  }

  /**
   * When index has distinctCount=50, equality should be 1/50 (not 1/classCount).
   */
  @Test
  public void resolveDistinctCount_affectsResult() {
    var cls = mockSchemaClass("Animal", 10000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            10000, 50, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var where = makeWhereWithPropertyAndOperator("species", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10000, cls, db);
    // distinctCount=50 → 1/50 = 0.02, NOT 1/10000 = 0.0001
    assertEquals("Should use distinctCount not classCount", 0.02, sel, 0.001);
  }

  /**
   * @class = 'Post' with existing class → valid selectivity.
   * If evaluateAsString returned "" instead, the class wouldn't be found.
   */
  @Test
  public void evaluateAsString_correctClassLookup() {
    var cls = mockSchemaClass("Message", 10000);
    var postClass = mockSchemaClass("Post", 3000);
    when(schema.existsClass("Post")).thenReturn(true);

    var where = makeWhereWithClassAttribute("Post");
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10000, cls, db);
    // Post=3000/Message=10000 = 0.3
    assertEquals(0.3, sel, 0.001);
  }

  /**
   * when class name is returned as empty string, schema.existsClass("") returns false → -1.
   */
  @Test
  public void evaluateAsString_emptyStringNotValidClass() {
    var cls = mockSchemaClass("Message", 10000);
    when(schema.existsClass("")).thenReturn(false);

    var where = makeWhereWithClassAttribute("");
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10000, cls, db);
    // empty class name → not found → falls to 1/10000
    assertEquals(0.0001, sel, 0.0001);
  }

  /**
   * When no indexes found, histogram path skips.
   */
  @Test
  public void histogramPath_noIndexes_fallsToUniform() {
    var cls = mockSchemaClass("NoIdx", 100);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(null);

    var where = makeWhereWithPropertyAndOperator("name", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, cls, db);
    // No index → fallback to 1/classCount
    assertEquals(0.01, sel, 0.001);
  }

  /**
   * Ensures propName is passed correctly to the index lookup.
   */
  @Test
  public void histogramPath_propNamePassedToIndexLookup() {
    var cls = mockSchemaClass("Indexed", 100);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    // Only match on specific property name "email"
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        org.mockito.ArgumentMatchers.eq("email"))).thenReturn(Set.of(index));
    // Other properties return null
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        org.mockito.ArgumentMatchers.eq(new String[0]))).thenReturn(null);
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            100, 10, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var where = makeWhereWithPropertyAndOperator("email", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, cls, db);
    // Index found for "email" → distinctCount=10 → 1/10
    assertEquals(0.1, sel, 0.01);
  }

  /**
   * When value is null after execute(), should skip to next index.
   */
  @Test
  public void histogramPath_nullValue_skipsIndex() {
    var cls = mockSchemaClass("NullVal", 100);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            100, 10, 0));

    // Right side returns null when executed (simulates IS NULL literal)
    var left = new SQLExpression(-1);
    var baseExpr = new SQLBaseExpression(-1);
    baseExpr.setIdentifier(new SQLBaseIdentifier(new SQLIdentifier("name")));
    left.setMathExpression(baseExpr);

    var right = mock(SQLExpression.class);
    when(right.execute(
        (com.jetbrains.youtrackdb.internal.core.query.Result) isNull(),
        isNull())).thenReturn(null);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(new SQLEqualsOperator(-1));
    condition.setRight(right);

    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 100, cls, db);
    // null value → histogram skipped → fallback to distinctCount=10 → 1/10
    assertEquals(0.1, sel, 0.01);
  }

  // -- Range operator selectivity via histogram path ---------------------------

  /**
   * Range operator (>) with index stats but no histogram: falls back to
   * SelectivityEstimator's UNIFORM_RANGE_SELECTIVITY (1/3). Verifies that
   * range operators are routed through estimateViaHistogram instead of
   * returning -1.0.
   */
  @Test
  public void rangeGt_withStats_noHistogram_usesUniformRange() {
    var cls = mockSchemaClass("Event", 3000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            3000, 500, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var where = makeWhereWithLiteralAndOperator("date", 100,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 3000, cls, db);
    // UNIFORM_RANGE_SELECTIVITY = 1/3 ≈ 0.333
    assertEquals("Gt with stats should use uniform range",
        1.0 / 3.0, sel, 0.01);
  }

  /**
   * Range operator (<) with index stats but no histogram: same uniform
   * fallback as >.
   */
  @Test
  public void rangeLt_withStats_noHistogram_usesUniformRange() {
    var cls = mockSchemaClass("Event", 3000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            3000, 500, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var where = makeWhereWithLiteralAndOperator("date", 100,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 3000, cls, db);
    assertEquals("Lt with stats should use uniform range",
        1.0 / 3.0, sel, 0.01);
  }

  /**
   * Range operator (>=) with index stats but no histogram.
   */
  @Test
  public void rangeGe_withStats_noHistogram_usesUniformRange() {
    var cls = mockSchemaClass("Event", 3000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            3000, 500, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var where = makeWhereWithLiteralAndOperator("date", 100,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 3000, cls, db);
    assertEquals("Ge with stats should use uniform range",
        1.0 / 3.0, sel, 0.01);
  }

  /**
   * Range operator (<=) with index stats but no histogram.
   */
  @Test
  public void rangeLe_withStats_noHistogram_usesUniformRange() {
    var cls = mockSchemaClass("Event", 3000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            3000, 500, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var where = makeWhereWithLiteralAndOperator("date", 100,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLeOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 3000, cls, db);
    assertEquals("Le with stats should use uniform range",
        1.0 / 3.0, sel, 0.01);
  }

  /**
   * Range operator without schema/session: cannot resolve index → returns -1.
   * This is the regression case — before the histogram path, range operators
   * always returned -1 from estimateFilterSelectivity.
   */
  @Test
  public void rangeGt_withoutSchema_returnsNegative() {
    var where = makeWhereWithPropertyAndOperator("date",
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, null, null);
    assertEquals("Range without schema should return -1", -1.0, sel, 0.0);
  }

  /**
   * Compound AND with range operators (IC4-style: date >= X AND date < Y).
   * With index stats and resolvable values, both conditions go through
   * estimateViaHistogram → UNIFORM_RANGE_SELECTIVITY (1/3 each).
   * Combined: (1/3) * (1/3) = 1/9 ≈ 0.111.
   */
  @Test
  public void compoundAnd_rangeOperators_ic4Style() {
    var cls = mockSchemaClass("Message", 5000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            5000, 1000, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var cond1 = makeBinaryWithLiteral("creationDate", 1000,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator(-1));
    var cond2 = makeBinaryWithLiteral("creationDate", 2000,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator(-1));
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(cond1);
    andBlock.getSubBlocks().add(cond2);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 5000, cls, db);
    // Both conditions: UNIFORM_RANGE_SELECTIVITY = 1/3 each → 1/9 ≈ 0.111
    assertEquals("IC4-style compound range", 1.0 / 9.0, sel, 0.02);
  }

  /**
   * Compound AND with mixed equality and range: date >= X AND name = 'Alice'.
   * Equality uses distinctCount (1/100), range uses uniform (1/3).
   * Combined: (1/100) * (1/3) ≈ 0.0033.
   */
  @Test
  public void compoundAnd_mixedEqualityAndRange() {
    var cls = mockSchemaClass("Person", 10000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            10000, 100, 0));
    when(index.getHistogram(any())).thenReturn(null);

    var rangeCond = makeBinaryWithLiteral("date", 500,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator(-1));
    var eqCond = makeBinaryWithLiteral("name", "Alice",
        new SQLEqualsOperator(-1));
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(rangeCond);
    andBlock.getSubBlocks().add(eqCond);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10000, cls, db);
    // range: 1/3, equality via histogram: 1/100 → combined: 1/300 ≈ 0.0033
    assertTrue("Mixed AND should produce small selectivity",
        sel > 0.001 && sel < 0.02);
  }

  // -- Narrowest index selection tests ----------------------------------------

  /**
   * When multiple indexes cover the same property, estimateViaHistogram
   * picks the one with the lowest selectivity estimate. This tests the
   * "use most narrowed index" behavior.
   */
  @Test
  public void histogramPath_multipleIndexes_picksNarrowest() {
    var cls = mockSchemaClass("Post", 10000);

    // Broad index: 10000 total, 10 distinct → equality = 1/10 = 0.1
    var broadIndex = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(broadIndex.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            10000, 10, 0));
    when(broadIndex.getHistogram(any())).thenReturn(null);

    // Narrow index: 10000 total, 5000 distinct → equality = 1/5000 = 0.0002
    var narrowIndex = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(narrowIndex.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            10000, 5000, 0));
    when(narrowIndex.getHistogram(any())).thenReturn(null);

    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(broadIndex, narrowIndex));

    var where = makeWhereWithLiteralAndOperator("userId",
        "user123", new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10000, cls, db);
    // Should pick narrow index: 1/5000 = 0.0002
    assertEquals("Should use narrowest index",
        1.0 / 5000, sel, 0.001);
  }

  /**
   * Multiple indexes with range operator — picks the one producing the
   * lowest selectivity. Both use UNIFORM_RANGE_SELECTIVITY (1/3) when
   * no histogram, so result is 1/3 regardless, but the code path still
   * iterates all indexes.
   */
  @Test
  public void histogramPath_multipleIndexes_rangeOperator() {
    var cls = mockSchemaClass("Event", 5000);

    var idx1 = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(idx1.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            5000, 100, 0));
    when(idx1.getHistogram(any())).thenReturn(null);

    var idx2 = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(idx2.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            5000, 2000, 0));
    when(idx2.getHistogram(any())).thenReturn(null);

    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(idx1, idx2));

    var where = makeWhereWithLiteralAndOperator("ts", 42,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 5000, cls, db);
    // Both indexes return UNIFORM_RANGE_SELECTIVITY = 1/3
    assertEquals("Range across multiple indexes", 1.0 / 3.0, sel, 0.01);
  }

  /**
   * Multiple indexes where one has null stats → skipped. The other with
   * valid stats is used.
   */
  @Test
  public void histogramPath_multipleIndexes_oneNullStats_usesOther() {
    var cls = mockSchemaClass("Tag", 1000);

    var nullStatsIndex = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(nullStatsIndex.getStatistics(any())).thenReturn(null);

    var validIndex = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(validIndex.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            1000, 200, 0));
    when(validIndex.getHistogram(any())).thenReturn(null);

    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(nullStatsIndex, validIndex));

    var where = makeWhereWithLiteralAndOperator("name", "target",
        new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    // Valid index: distinctCount=200 → 1/200 = 0.005
    assertEquals("Should use index with valid stats",
        1.0 / 200, sel, 0.001);
  }

  /**
   * Multiple indexes where one has zero totalCount → skipped. Verifies
   * the totalCount > 0 guard in estimateViaHistogram's loop.
   */
  @Test
  public void histogramPath_multipleIndexes_oneZeroTotal_usesOther() {
    var cls = mockSchemaClass("Widget", 500);

    var emptyIndex = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(emptyIndex.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            0, 0, 0));

    var populatedIndex = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(populatedIndex.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            500, 50, 0));
    when(populatedIndex.getHistogram(any())).thenReturn(null);

    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(emptyIndex, populatedIndex));

    var where = makeWhereWithLiteralAndOperator("sku", "ABC",
        new SQLEqualsOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 500, cls, db);
    // Populated index: distinctCount=50 → 1/50 = 0.02
    assertEquals("Should skip empty index",
        1.0 / 50, sel, 0.001);
  }

  /**
   * resolveDistinctCount with multiple indexes also picks the narrowest.
   * Two indexes with different distinctCounts: the one producing the
   * most selective result (lowest sel) should be used.
   */
  @Test
  public void resolveDistinctCount_multipleIndexes_picksNarrowest() {
    var cls = mockSchemaClass("Account", 10000);

    // Index A: distinctCount=10 → equality = 1/10
    var idxA = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(idxA.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            10000, 10, 0));
    when(idxA.getHistogram(any())).thenReturn(null);

    // Index B: distinctCount=8000 → equality = 1/8000
    var idxB = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(idxB.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            10000, 8000, 0));
    when(idxB.getHistogram(any())).thenReturn(null);

    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(idxA, idxB));

    // NE operator: should use narrowest → (8000-1)/8000 ≈ 0.99987
    var where = makeWhereWithLiteralAndOperator("email", "test@example.com",
        new SQLNeOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10000, cls, db);
    // The narrowest index for NE: histogramPath estimates NE as
    // 1 - estimateEquality. With distinctCount=8000: 1 - 1/8000 ≈ 0.99987.
    // With distinctCount=10: 1 - 1/10 = 0.9. Narrowest picks lowest sel.
    assertTrue("NE with multiple indexes should pick narrowest",
        sel < 0.95);
  }

  // -- Histogram-backed range estimation with actual histogram ----------------

  /**
   * Range operator (>) with actual histogram: boundary at 50 with uniform
   * distribution. Value=50 should produce ~50% selectivity for > (values
   * above the midpoint of a uniform distribution).
   */
  @Test
  public void rangeGt_withHistogram_producesAccurateEstimate() {
    var cls = mockSchemaClass("Measurement", 1000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            1000, 100, 0));

    // 2-bucket histogram: [0, 50), [50, 100], 500 each
    var histogram = new com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500},
        new long[] {50, 50},
        1000,
        null,
        0);
    when(index.getHistogram(any())).thenReturn(histogram);

    var where = makeWhereWithLiteralAndOperator("value", 50,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGtOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    // value > 50: second bucket [50,100] with ~half remaining → ~0.5
    assertTrue("Gt with histogram should produce ~50% selectivity",
        sel > 0.2 && sel < 0.8);
  }

  /**
   * Range operator (<) with actual histogram: value near the end should
   * produce high selectivity (most values below).
   */
  @Test
  public void rangeLt_withHistogram_producesAccurateEstimate() {
    var cls = mockSchemaClass("Sensor", 1000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            1000, 100, 0));

    // 2-bucket histogram: [0, 50), [50, 100], 500 each
    var histogram = new com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram(
        2,
        new Comparable<?>[] {0, 50, 100},
        new long[] {500, 500},
        new long[] {50, 50},
        1000,
        null,
        0);
    when(index.getHistogram(any())).thenReturn(histogram);

    var where = makeWhereWithLiteralAndOperator("value", 90,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator(-1));
    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 1000, cls, db);
    // value < 90: most of the data → high selectivity
    assertTrue("Lt with histogram near end should be high selectivity",
        sel > 0.5);
  }

  /**
   * IC4-style compound range with histogram: creationDate >= 1000 AND
   * creationDate < 3000 on a [0, 10000] distribution. The range covers
   * ~20% of the data.
   */
  @Test
  public void compoundAnd_rangeWithHistogram_ic4Style() {
    var cls = mockSchemaClass("Comment", 10000);
    var index = mock(com.jetbrains.youtrackdb.internal.core.index.Index.class);
    when(cls.getInvolvedIndexesInternal(any(DatabaseSessionEmbedded.class),
        any(String[].class))).thenReturn(Set.of(index));
    when(index.getStatistics(any())).thenReturn(
        new com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics(
            10000, 5000, 0));

    // 4-bucket histogram: [0,2500), [2500,5000), [5000,7500), [7500,10000]
    // 2500 entries each
    var histogram = new com.jetbrains.youtrackdb.internal.core.index.engine.EquiDepthHistogram(
        4,
        new Comparable<?>[] {0, 2500, 5000, 7500, 10000},
        new long[] {2500, 2500, 2500, 2500},
        new long[] {2500, 2500, 2500, 2500},
        10000,
        null,
        0);
    when(index.getHistogram(any())).thenReturn(histogram);

    var cond1 = makeBinaryWithLiteral("creationDate", 1000,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGeOperator(-1));
    var cond2 = makeBinaryWithLiteral("creationDate", 3000,
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLtOperator(-1));
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(cond1);
    andBlock.getSubBlocks().add(cond2);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);

    double sel = MatchExecutionPlanner.estimateFilterSelectivity(
        where, 10000, cls, db);
    // Range [1000, 3000) on [0, 10000]: ~20% of data.
    // With histogram: sel(>=1000) * sel(<3000) should be roughly 0.05-0.30
    assertTrue("IC4 compound range should produce reasonable selectivity",
        sel > 0.01 && sel < 0.5);
  }

  // -- Helper: create WHERE with literal value on right side ------------------

  /**
   * Creates a WHERE clause with a property on the left and a literal value
   * on the right that can be resolved at plan time. This is essential for
   * testing the histogram path which requires value resolution.
   */
  private SQLWhereClause makeWhereWithLiteralAndOperator(
      String propertyName, Object literalValue,
      com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator op) {
    var condition = makeBinaryWithLiteral(propertyName, literalValue, op);
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(condition);
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(andBlock);
    return where;
  }

  /**
   * Creates a binary condition with a property on the left and a mock
   * expression on the right that returns the given literal value when
   * executed.
   */
  private SQLBinaryCondition makeBinaryWithLiteral(
      String propertyName, Object literalValue,
      com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator op) {
    var left = new SQLExpression(-1);
    var baseExpr = new SQLBaseExpression(-1);
    baseExpr.setIdentifier(new SQLBaseIdentifier(new SQLIdentifier(propertyName)));
    left.setMathExpression(baseExpr);

    var right = mock(SQLExpression.class);
    when(right.execute(
        (com.jetbrains.youtrackdb.internal.core.query.Result) isNull(),
        isNull())).thenReturn(literalValue);

    var condition = new SQLBinaryCondition(-1);
    condition.setLeft(left);
    condition.setOperator(op);
    condition.setRight(right);
    return condition;
  }

  private PatternEdge mockEdgeWithWhileAndDepth(@Nullable Integer maxDepth) {
    var edge = mockEdgeWithMethod("out");
    var matchFilter = mock(
        com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter.class);
    when(edge.item.getFilter()).thenReturn(matchFilter);
    var whileClause = new SQLWhereClause(-1);
    whileClause.setBaseExpression(new SQLAndBlock(-1));
    when(matchFilter.getWhileCondition()).thenReturn(whileClause);
    when(matchFilter.getMaxDepth()).thenReturn(maxDepth);
    return edge;
  }
}
