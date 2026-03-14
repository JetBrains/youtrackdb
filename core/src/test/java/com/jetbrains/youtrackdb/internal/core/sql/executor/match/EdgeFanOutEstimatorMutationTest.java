package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import org.junit.Before;
import org.junit.Test;

/**
 * Mutation-killing tests for {@link EdgeFanOutEstimator} targeting
 * specific pitest mutations that survived:
 * <ul>
 *   <li>L89: removed conditional (edgeClassName != null → true)</li>
 *   <li>L91: removed conditional (sourceClassName != null → true)</li>
 *   <li>L106: swapped parameters 6 and 7 in estimateBothFanOut</li>
 *   <li>L169: removed conditional (clazz != null → true)</li>
 * </ul>
 */
public class EdgeFanOutEstimatorMutationTest {

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

  private SchemaClassInternal registerClass(String name, long count) {
    var clazz = mock(SchemaClassInternal.class);
    when(clazz.approximateCount(session)).thenReturn(count);
    when(clazz.getName()).thenReturn(name);
    when(clazz.isSubClassOf(name)).thenReturn(true);
    when(schema.getClassInternal(name)).thenReturn(clazz);
    when(schema.getClass(name)).thenReturn(clazz);
    return clazz;
  }

  // ═══════════════════════════════════════════════════════════════
  // L89: edgeClassName != null → replaced with true
  // If the null check is removed, getClassInternal(null) would return null,
  // causing edgeClass == null → defaultFanOut(). Same result as correct code.
  // We need a test where removing the null check causes a different behavior.
  // Actually, the mutation replaces "edgeClassName != null ? schema.getClassInternal(edgeClassName) : null"
  // with "true ? schema.getClassInternal(edgeClassName) : null", i.e., always calls
  // getClassInternal(edgeClassName) even when edgeClassName is null.
  // If getClassInternal(null) returns a class by accident, the result changes.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void edgeClassNull_classInternalReturnsNonNull_stillReturnsDefault() {
    // Setup: schema.getClassInternal(null) returns a class (unlikely but possible)
    var weirdClass = mock(SchemaClassInternal.class);
    when(weirdClass.approximateCount(session)).thenReturn(1000L);
    when(schema.getClassInternal(null)).thenReturn(weirdClass);
    registerClass("Person", 100);

    // With edgeClassName=null, should return defaultFanOut regardless
    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, null, "Person", Direction.OUT, null, null);

    assertEquals("null edgeClassName → defaultFanOut",
        EdgeFanOutEstimator.defaultFanOut(), fanOut, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // L91: sourceClassName != null → replaced with true
  // Same reasoning as above.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void sourceClassNull_classInternalReturnsNonNull_stillReturnsDefault() {
    var weirdClass = mock(SchemaClassInternal.class);
    when(weirdClass.approximateCount(session)).thenReturn(1000L);
    when(schema.getClassInternal(null)).thenReturn(weirdClass);
    registerClass("Knows", 500);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Knows", null, Direction.OUT, null, null);

    assertEquals("null sourceClassName → defaultFanOut",
        EdgeFanOutEstimator.defaultFanOut(), fanOut, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // L106: swapped parameters 6 and 7 (outVertexClass, inVertexClass)
  // in call to estimateBothFanOut. If swapped, OUT and IN sides are
  // reversed, giving wrong fan-out for asymmetric edges.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void bothDirection_paramSwap_asymmetricEdge_detectsSwap() {
    // Given: Works is Person→Works→Company
    // OUT vertex = Person (100), IN vertex = Company (50)
    // Source = Person
    registerClass("Works", 300);
    var person = registerClass("Person", 100);
    var company = registerClass("Company", 50);
    when(person.isSubClassOf("Company")).thenReturn(false);
    when(company.isSubClassOf("Person")).thenReturn(false);

    // BOTH from Person:
    // Correct: OUT side → Person isSubClassOf Person? yes → 300/100=3.0
    //          IN side → Person isSubClassOf Company? no → 0
    //          Total = 3.0
    // If params swapped (outVertexClass and inVertexClass exchanged):
    //          OUT side → Person isSubClassOf Company? no → 0
    //          IN side → Person isSubClassOf Person? yes → 300/100=3.0
    //          Total = 3.0 (same result due to symmetry!)
    // We need different counts on the vertex classes to detect the swap.
    // OUT: correct → edgeCount/outClassCount = 300/100 = 3.0
    // Swapped OUT → edgeCount/inClassCount = 300/50 = 6.0 (different!)

    // But wait - estimateBothFanOut receives outVertexClass and inVertexClass,
    // then looks up their classes. If swapped, it looks up Company for OUT
    // and Person for IN.

    // Let's verify the correct result:
    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Works", "Person", Direction.BOTH,
        "Person", "Company");

    // Person isSubClassOf Person → OUT = 300/100 = 3.0
    // Person isSubClassOf Company → false → IN = 0
    assertEquals("BOTH from Person with asymmetric edge",
        3.0, fanOut, DELTA);

    // Now from Company:
    double fanOutCompany = EdgeFanOutEstimator.estimateFanOut(
        session, "Works", "Company", Direction.BOTH,
        "Person", "Company");

    // Company isSubClassOf Person → false → OUT = 0
    // Company isSubClassOf Company → true → IN = 300/50 = 6.0
    assertEquals("BOTH from Company with asymmetric edge",
        6.0, fanOutCompany, DELTA);
  }

  // ═══════════════════════════════════════════════════════════════
  // L169: removed conditional (clazz != null in isSubclassOrEqual)
  // If clazz is null (className not in schema), isSubClassOf would NPE.
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void bothDirection_sourceClassNotInSchemaViaGetClass_returnsDefault() {
    // Register edge class in schema, but source class only via getClassInternal
    // (not getClass). The isSubclassOrEqual method uses schema.getClass(),
    // so it should return null and fail gracefully.
    registerClass("Edge", 200);
    var sourceClass = mock(SchemaClassInternal.class);
    when(sourceClass.approximateCount(session)).thenReturn(100L);
    when(schema.getClassInternal("Source")).thenReturn(sourceClass);
    // schema.getClass("Source") returns null → isSubclassOrEqual returns false
    when(schema.getClass("Source")).thenReturn(null);

    double fanOut = EdgeFanOutEstimator.estimateFanOut(
        session, "Edge", "Source", Direction.BOTH,
        "OutV", "InV");

    // Both OUT and IN sides fail isSubclassOrEqual (source class not in schema)
    // → fan-out = 0 + 0 = 0
    assertEquals("Source class not findable via getClass → 0 fan-out",
        0.0, fanOut, DELTA);
  }
}
