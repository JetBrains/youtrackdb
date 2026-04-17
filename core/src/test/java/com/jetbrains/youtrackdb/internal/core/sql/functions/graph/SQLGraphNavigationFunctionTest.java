package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;

/**
 * Direct tests for the static helpers exposed by {@link SQLGraphNavigationFunction}: the
 * {@code propertiesForV2ENavigation} and {@code propertiesForV2VNavigation} utility methods, and
 * the {@code propertyNamesForIndexCandidates} contract implemented by subclasses. The goal is to
 * pin the branching behaviour (vertex vs non-vertex schema class, direction-specific property
 * names, BOTH fan-out).
 *
 * <p>A "vertex type" here means the schema class is-a V (the default vertex root class). A
 * "non-vertex type" is any class that does not inherit V, including the root E class and arbitrary
 * DOCUMENT classes. The helpers behave differently for these cases: vertex classes delegate to
 * {@link com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl
 * #getAllPossibleEdgePropertyNames} while non-vertex classes examine LINK-typed properties
 * directly.
 */
public class SQLGraphNavigationFunctionTest extends DbTestBase {

  private SchemaClass edgeClassKnows;
  private SchemaClass linkDocClass;

  @Before
  public void setUpSchema() {
    // Edge class "knows" (inherits E, hence non-vertex).
    edgeClassKnows = session.createEdgeClass("knows");

    // Plain document class with a LINK property used to drive the non-vertex
    // branches of propertiesForV2VNavigation.
    var docClass = session.createClass("LinkDoc");
    docClass.createProperty("owner", PropertyType.LINK);
    docClass.createProperty("nonLink", PropertyType.STRING);
    linkDocClass = docClass;

    // Also create a vertex subclass so vertex-type delegation exists.
    session.createVertexClass("Person");
  }

  // --- propertiesForV2ENavigation -----------------------------------------

  @Test
  public void v2eOnNonVertexReturnsNull() {
    // The root E class is non-vertex — the helper must return null outright.
    var eClass = session.getMetadata().getImmutableSchemaSnapshot().getClass("E");
    var result = SQLGraphNavigationFunction.propertiesForV2ENavigation(
        eClass, session, Direction.OUT, new String[] {"knows"});
    assertNull(result);
  }

  @Test
  public void v2eOnPlainDocumentReturnsNull() {
    // A regular document class (not a vertex) also must return null.
    var result = SQLGraphNavigationFunction.propertiesForV2ENavigation(
        linkDocClass, session, Direction.OUT, new String[] {"owner"});
    assertNull(result);
  }

  @Test
  public void v2eOnVertexReturnsNonNullDelegation() {
    // Vertex types yield a non-null list (possibly empty) delegated to
    // VertexEntityImpl.getAllPossibleEdgePropertyNames.
    var vClass = session.getMetadata().getImmutableSchemaSnapshot().getClass(
        SchemaClass.VERTEX_CLASS_NAME);
    var result = SQLGraphNavigationFunction.propertiesForV2ENavigation(
        vClass, session, Direction.OUT, new String[] {"knows"});
    // Must be non-null. We don't pin an exact value because it depends on the
    // edge class hierarchy; vertex-type delegation is the contract.
    assertNotNull(result);
  }

  // --- propertiesForV2VNavigation -----------------------------------------

  @Test
  public void v2vNonVertexOutCollectsLinkLabelsInInputOrder() {
    // For non-vertex OUT: the returned list contains each label whose property
    // is LINK-typed, in the SAME order as the input labels. Non-LINK and
    // missing labels are skipped.
    var result = SQLGraphNavigationFunction.propertiesForV2VNavigation(
        linkDocClass, session, Direction.OUT, new String[] {"owner", "nonLink", "missing"});

    assertNotNull(result);
    var list = new ArrayList<>(result);
    // "owner" is LINK → kept; "nonLink" is STRING → dropped; "missing" → dropped.
    assertEquals(1, list.size());
    assertEquals("owner", list.get(0));
  }

  @Test
  public void v2vNonVertexInReturnsOppositeLinkBagNames() {
    // For non-vertex IN: for each LINK-typed label, the helper substitutes the
    // reverse-pointer system property name (EntityImpl.getOppositeLinkBagPropertyName).
    var result = SQLGraphNavigationFunction.propertiesForV2VNavigation(
        linkDocClass, session, Direction.IN, new String[] {"owner"});

    assertNotNull(result);
    var list = new ArrayList<>(result);
    assertEquals(1, list.size());
    assertEquals(EntityImpl.getOppositeLinkBagPropertyName("owner"), list.get(0));
  }

  @Test
  public void v2vNonVertexBothCombinesOutAndIn() {
    // BOTH must concatenate the OUT result followed by the IN result (OUT
    // first, per the implementation).
    var result = SQLGraphNavigationFunction.propertiesForV2VNavigation(
        linkDocClass, session, Direction.BOTH, new String[] {"owner"});

    assertNotNull(result);
    var list = new ArrayList<>(result);
    // Exactly two entries: the OUT label, then the IN opposite system property.
    assertEquals(2, list.size());
    assertEquals("owner", list.get(0));
    assertEquals(EntityImpl.getOppositeLinkBagPropertyName("owner"), list.get(1));
  }

  @Test
  public void v2vNonVertexLabelsMissingFromClassAreSkipped() {
    // Labels that resolve to null properties must not appear in the result.
    var result = SQLGraphNavigationFunction.propertiesForV2VNavigation(
        linkDocClass, session, Direction.OUT, new String[] {"doesNotExist"});

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void v2vNonVertexNonLinkPropertyIsSkipped() {
    // STRING properties are not LINK-typed, so they must be filtered out.
    var result = SQLGraphNavigationFunction.propertiesForV2VNavigation(
        linkDocClass, session, Direction.OUT, new String[] {"nonLink"});

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void v2vOnVertexDelegatesToVertexEntityImpl() {
    // Vertex types take the delegation branch. The helper must return a
    // non-null collection regardless of direction (the result content is
    // whatever VertexEntityImpl decides; we only pin non-null-ness).
    var vClass = session.getMetadata().getImmutableSchemaSnapshot().getClass(
        SchemaClass.VERTEX_CLASS_NAME);
    var out = SQLGraphNavigationFunction.propertiesForV2VNavigation(
        vClass, session, Direction.OUT, new String[] {"knows"});
    var in = SQLGraphNavigationFunction.propertiesForV2VNavigation(
        vClass, session, Direction.IN, new String[] {"knows"});
    var both = SQLGraphNavigationFunction.propertiesForV2VNavigation(
        vClass, session, Direction.BOTH, new String[] {"knows"});

    assertNotNull("OUT on vertex must delegate", out);
    assertNotNull("IN on vertex must delegate", in);
    assertNotNull("BOTH on vertex must delegate", both);
  }

  // --- propertyNamesForIndexCandidates (dispatcher contract) --------------

  @Test
  public void outFunctionForVertexReturnsNonNullCandidates() {
    // Propagating the dispatcher contract: SQLFunctionOut's candidates come
    // from the V2V helper — for a vertex, the helper delegates.
    var fn = new SQLFunctionOut();
    var vClass = session.getMetadata().getImmutableSchemaSnapshot().getClass(
        SchemaClass.VERTEX_CLASS_NAME);
    var candidates = fn.propertyNamesForIndexCandidates(
        new String[] {"knows"}, vClass, false, session);
    assertNotNull(candidates);
  }

  @Test
  public void outFunctionForNonVertexOnlyReturnsLinkLabels() {
    // For a non-vertex, the dispatcher inherits V2V semantics — non-LINK
    // properties must be filtered out of the candidate list.
    var fn = new SQLFunctionOut();
    var candidates = fn.propertyNamesForIndexCandidates(
        new String[] {"owner", "nonLink"}, linkDocClass, false, session);
    assertNotNull(candidates);
    var list = new ArrayList<>(candidates);
    assertEquals(1, list.size());
    assertEquals("owner", list.get(0));
  }

  @Test
  public void inFunctionForNonVertexTransformsLabelsToReverseSystemNames() {
    var fn = new SQLFunctionIn();
    var candidates = fn.propertyNamesForIndexCandidates(
        new String[] {"owner"}, linkDocClass, false, session);
    assertNotNull(candidates);
    var list = new ArrayList<>(candidates);
    assertEquals(1, list.size());
    assertEquals(EntityImpl.getOppositeLinkBagPropertyName("owner"), list.get(0));
  }

  @Test
  public void bothFunctionForNonVertexReturnsOutThenInCombined() {
    var fn = new SQLFunctionBoth();
    var candidates = fn.propertyNamesForIndexCandidates(
        new String[] {"owner"}, linkDocClass, false, session);
    assertNotNull(candidates);
    var list = new ArrayList<>(candidates);
    assertEquals(2, list.size());
    assertEquals("owner", list.get(0));
    assertEquals(EntityImpl.getOppositeLinkBagPropertyName("owner"), list.get(1));
  }

  @Test
  public void outEFunctionForNonVertexReturnsNull() {
    // OutE delegates to V2E navigation which returns null for non-vertex.
    var fn = new SQLFunctionOutE();
    var candidates = fn.propertyNamesForIndexCandidates(
        new String[] {"knows"}, linkDocClass, false, session);
    assertNull(candidates);
  }
}
