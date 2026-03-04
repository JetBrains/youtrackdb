package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link EdgeFanOutEstimator} — verifies fan-out estimation for all
 * direction modes (OUT, IN, BOTH), edge cases (zero counts, missing classes,
 * null schema), and subclass-aware BOTH computation.
 */
public class EdgeFanOutEstimatorTest {

  private static final double DELTA = 1e-9;

  private DatabaseSessionEmbedded session;
  private ImmutableSchema schema;

  @Before
  public void setUp() {
    session = mock(DatabaseSessionEmbedded.class);
    var metadata = mock(MetadataDefault.class);
    schema = mock(ImmutableSchema.class);
    when(session.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);
  }

  // ── Helper ─────────────────────────────────────────────────

  private SchemaClassInternal registerClass(String name, long count) {
    var clazz = mock(SchemaClassInternal.class);
    when(clazz.approximateCount(session)).thenReturn(count);
    when(clazz.getName()).thenReturn(name);
    // isSubClassOf returns true for itself (matching real behavior)
    when(clazz.isSubClassOf(name)).thenReturn(true);
    when(schema.getClassInternal(name)).thenReturn(clazz);
    when(schema.getClass(name)).thenReturn(clazz);
    return clazz;
  }

  // ── OUT direction ──────────────────────────────────────────

  @Test
  public void outDirection_returnsEdgeCountDividedBySourceCount() {
    // Given: 500 Knows edges, 100 Person vertices
    registerClass("Knows", 500);
    registerClass("Person", 100);

    // When: estimating OUT fan-out from Person via Knows
    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Person", Direction.OUT,
        "Person", "Person");

    // Then: fan-out = 500 / 100 = 5.0
    assertEquals(5.0, fanOut, DELTA);
  }

  @Test
  public void inDirection_returnsEdgeCountDividedBySourceCount() {
    // Given: 600 Lives edges, 200 City vertices
    registerClass("Lives", 600);
    registerClass("City", 200);

    // When: estimating IN fan-out to City via Lives
    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Lives", "City", Direction.IN,
        "Person", "City");

    // Then: fan-out = 600 / 200 = 3.0
    assertEquals(3.0, fanOut, DELTA);
  }

  // ── BOTH direction ─────────────────────────────────────────

  @Test
  public void bothDirection_selfLoop_combinesOutAndInFanOut() {
    // Given: Knows is Person→Knows→Person (self-loop)
    registerClass("Knows", 500);
    registerClass("Person", 100);

    // When
    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Person", Direction.BOTH,
        "Person", "Person");

    // Then: OUT=500/100=5.0 + IN=500/100=5.0 = 10.0
    assertEquals(10.0, fanOut, DELTA);
  }

  @Test
  public void bothDirection_directedEdge_onlyOutContributes() {
    // Given: Works is Person→Works→Company
    registerClass("Works", 300);
    var person = registerClass("Person", 100);
    registerClass("Company", 50);
    when(person.isSubClassOf("Company")).thenReturn(false);

    // When: BOTH from Person via Works
    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Works", "Person", Direction.BOTH,
        "Person", "Company");

    // Then: OUT=300/100=3.0, IN=0 (Person!=Company) → 3.0
    assertEquals(3.0, fanOut, DELTA);
  }

  @Test
  public void bothDirection_directedEdge_onlyInContributes() {
    // Given: Works is Person→Works→Company
    registerClass("Works", 300);
    registerClass("Person", 100);
    var company = registerClass("Company", 50);
    when(company.isSubClassOf("Person")).thenReturn(false);

    // When: BOTH from Company via Works
    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Works", "Company", Direction.BOTH,
        "Person", "Company");

    // Then: OUT=0 (Company!=Person), IN=300/50=6.0 → 6.0
    assertEquals(6.0, fanOut, DELTA);
  }

  @Test
  public void bothDirection_subclassMatchesOutVertex() {
    // Given: Works (Employee→Works→Company), Student extends Employee
    registerClass("Works", 300);
    registerClass("Employee", 200);
    registerClass("Company", 50);
    var student = registerClass("Student", 80);
    when(student.isSubClassOf("Employee")).thenReturn(true);
    when(student.isSubClassOf("Company")).thenReturn(false);

    // When: BOTH from Student via Works
    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Works", "Student", Direction.BOTH,
        "Employee", "Company");

    // Then: Student isSubClassOf Employee → OUT=300/200=1.5
    //       Student NOT isSubClassOf Company → IN=0
    assertEquals(1.5, fanOut, DELTA);
  }

  // ── Zero and empty counts ──────────────────────────────────

  @Test
  public void sourceCountZero_returnsZero() {
    // Given: 500 Knows edges but 0 Person vertices
    registerClass("Knows", 500);
    registerClass("Person", 0);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Person", Direction.OUT,
        "Person", "Person");

    assertEquals(0.0, fanOut, DELTA);
  }

  @Test
  public void edgeCountZero_returnsZero() {
    // Given: 0 Knows edges, 100 Person vertices
    registerClass("Knows", 0);
    registerClass("Person", 100);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Person", Direction.OUT,
        "Person", "Person");

    assertEquals(0.0, fanOut, DELTA);
  }

  @Test
  public void bothDirection_edgeCountZero_returnsZero() {
    // Given: 0 Knows edges, 100 Person vertices
    registerClass("Knows", 0);
    registerClass("Person", 100);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Person", Direction.BOTH,
        "Person", "Person");

    // Then: OUT=0/100=0 + IN=0/100=0 = 0.0
    assertEquals(0.0, fanOut, DELTA);
  }

  @Test
  public void bothDirection_outVertexCountZero_outFanOutIsZero() {
    // Given: source "Animal" is subclass of both "PersonOut" and
    // "PersonIn", but PersonOut has 0 vertices
    registerClass("Knows", 500);
    var animal = registerClass("Animal", 100);
    registerClass("PersonOut", 0);
    registerClass("PersonIn", 50);
    when(animal.isSubClassOf("PersonOut")).thenReturn(true);
    when(animal.isSubClassOf("PersonIn")).thenReturn(true);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Animal", Direction.BOTH,
        "PersonOut", "PersonIn");

    // Then: OUT=0 (outCount=0), IN=500/50=10.0 → 10.0
    assertEquals(10.0, fanOut, DELTA);
  }

  // ── Missing schema metadata → DEFAULT_FAN_OUT ─────────────

  @Test
  public void nullEdgeClassName_returnsDefaultFanOut() {
    registerClass("Person", 100);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, null, "Person", Direction.OUT, null, null);

    assertEquals(EdgeFanOutEstimator.DEFAULT_FAN_OUT, fanOut, DELTA);
  }

  @Test
  public void nullSourceClassName_returnsDefaultFanOut() {
    registerClass("Knows", 500);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", null, Direction.OUT, null, null);

    assertEquals(EdgeFanOutEstimator.DEFAULT_FAN_OUT, fanOut, DELTA);
  }

  @Test
  public void edgeClassNotInSchema_returnsDefaultFanOut() {
    registerClass("Person", 100);
    when(schema.getClassInternal("Missing")).thenReturn(null);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Missing", "Person", Direction.OUT, null, null);

    assertEquals(EdgeFanOutEstimator.DEFAULT_FAN_OUT, fanOut, DELTA);
  }

  @Test
  public void sourceClassNotInSchema_returnsDefaultFanOut() {
    registerClass("Knows", 500);
    when(schema.getClassInternal("Unknown")).thenReturn(null);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Unknown", Direction.OUT, null, null);

    assertEquals(EdgeFanOutEstimator.DEFAULT_FAN_OUT, fanOut, DELTA);
  }

  @Test
  public void nullSchemaSnapshot_returnsDefaultFanOut() {
    var metadata = mock(MetadataDefault.class);
    when(session.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(null);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Person", Direction.OUT, null, null);

    assertEquals(EdgeFanOutEstimator.DEFAULT_FAN_OUT, fanOut, DELTA);
  }

  // ── BOTH with null vertex class names ──────────────────────

  @Test
  public void bothDirection_nullOutVertexClass_onlyInContributes() {
    registerClass("Edge", 200);
    registerClass("Vertex", 50);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Edge", "Vertex", Direction.BOTH,
        null, "Vertex");

    // OUT skipped (null), IN=200/50=4.0
    assertEquals(4.0, fanOut, DELTA);
  }

  @Test
  public void bothDirection_nullInVertexClass_onlyOutContributes() {
    registerClass("Edge", 200);
    registerClass("Vertex", 50);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Edge", "Vertex", Direction.BOTH,
        "Vertex", null);

    // OUT=200/50=4.0, IN skipped (null)
    assertEquals(4.0, fanOut, DELTA);
  }

  @Test
  public void bothDirection_bothVertexClassesNull_returnsZero() {
    registerClass("Edge", 200);
    registerClass("Vertex", 50);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Edge", "Vertex", Direction.BOTH, null, null);

    assertEquals(0.0, fanOut, DELTA);
  }

  // ── BOTH: vertex class passes isSubclassOrEqual but ────────
  // ── getClassInternal returns null (defensive null guard) ────

  @Test
  public void bothDirection_outVertexPassesSubclassButMissingInternal() {
    // Given: source "A" isSubClassOf "B" via schema.getClass("A"),
    // but schema.getClassInternal("B") returns null
    registerClass("Edge", 200);
    registerClass("A", 50);
    var bClass = mock(SchemaClass.class);
    when(schema.getClass("B")).thenReturn(bClass);
    when(schema.getClassInternal("B")).thenReturn(null);
    var aClass = schema.getClass("A");
    when(aClass.isSubClassOf("B")).thenReturn(true);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Edge", "A", Direction.BOTH, "B", null);

    // OUT side: isSubclassOrEqual passes but getClassInternal("B")
    // returns null → OUT contribution = 0. IN skipped (null).
    assertEquals(0.0, fanOut, DELTA);
  }

  @Test
  public void bothDirection_inVertexPassesSubclassButMissingInternal() {
    // Same as above, but for the IN side
    registerClass("Edge", 200);
    registerClass("A", 50);
    var bClass = mock(SchemaClass.class);
    when(schema.getClass("B")).thenReturn(bClass);
    when(schema.getClassInternal("B")).thenReturn(null);
    var aClass = schema.getClass("A");
    when(aClass.isSubClassOf("B")).thenReturn(true);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Edge", "A", Direction.BOTH, null, "B");

    // IN side: isSubclassOrEqual passes but getClassInternal("B")
    // returns null → IN contribution = 0. OUT skipped (null).
    assertEquals(0.0, fanOut, DELTA);
  }

  // ── Fractional results ─────────────────────────────────────

  @Test
  public void fractionalFanOut_preservesPrecision() {
    // Given: 7 Knows edges, 3 Person vertices → 7/3 ≈ 2.333
    registerClass("Knows", 7);
    registerClass("Person", 3);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", "Person", Direction.OUT,
        "Person", "Person");

    assertEquals(7.0 / 3.0, fanOut, DELTA);
  }

  @Test
  public void singleEdgeSingleVertex_returnsOne() {
    registerClass("E", 1);
    registerClass("V", 1);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "E", "V", Direction.OUT, "V", "V");

    assertEquals(1.0, fanOut, DELTA);
  }

  // ── BOTH: source matches neither side ──────────────────────

  @Test
  public void bothDirection_sourceMatchesNeitherSide_returnsZero() {
    // Given: Works (Person→Works→Company), source is "Animal"
    // which is neither Person nor Company
    registerClass("Works", 300);
    registerClass("Person", 100);
    registerClass("Company", 50);
    var animal = registerClass("Animal", 80);
    when(animal.isSubClassOf("Person")).thenReturn(false);
    when(animal.isSubClassOf("Company")).thenReturn(false);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Works", "Animal", Direction.BOTH,
        "Person", "Company");

    assertEquals(0.0, fanOut, DELTA);
  }
}
