package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import org.junit.Assert;
import org.junit.Test;

/**
 * DB-backed coverage for the default methods on {@link Result} that delegate
 * to {@link Result#asEntity} / {@link Result#asEntityOrNull}:
 * {@code asVertex}, {@code asVertexOrNull}, {@code getVertex}, {@code getEdge}.
 * The standalone {@code BasicResultSetDefaultMethodsTest} cannot exercise
 * these paths because they require a session-bound {@code ResultInternal}.
 *
 * <p>Extends {@link TestUtilsFixture} for the {@code @After rollbackIfLeftOpen}
 * safety net: any test that fails mid-transaction gets its transaction
 * rolled back before the database is dropped, preserving the original
 * failure for the test runner.
 */
public class ResultDefaultMethodsTest extends TestUtilsFixture {

  @Test
  public void testAsVertexOnVertexResult() {
    var schema = session.getMetadata().getSchema();
    var vClass = "V_AsVertex";
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    var vertex = session.newVertex(vClass);
    vertex.setProperty("name", "v1");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + vClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      Assert.assertTrue("Result backing a vertex must report isVertex()",
          row.isVertex());
      // asVertex() is a default method on Result that delegates through
      // asEntity().asVertex(). Exercising the result path ensures the
      // default itself is covered, not just ResultInternal's override.
      var asVertex = row.asVertex();
      Assert.assertNotNull(asVertex);
      Assert.assertEquals("v1", asVertex.getProperty("name"));
    }
    session.commit();
  }

  @Test
  public void testAsVertexOrNullOnVertexResultReturnsVertex() {
    var schema = session.getMetadata().getSchema();
    var vClass = "V_AsVertexOrNull";
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    session.newVertex(vClass).setProperty("name", "v");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + vClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      var asVertex = row.asVertexOrNull();
      Assert.assertNotNull("asVertexOrNull must return the vertex for an"
          + " entity-backed result", asVertex);
    }
    session.commit();
  }

  @Test
  public void testIsEntityIsVertexFlagsForVertex() {
    var schema = session.getMetadata().getSchema();
    var vClass = "V_IsFlags";
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    session.newVertex(vClass).setProperty("name", "v");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + vClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      Assert.assertTrue(row.isEntity());
      Assert.assertTrue(row.isVertex());
      Assert.assertFalse("Vertex result must not claim to be an edge",
          row.isEdge());
      Assert.assertFalse("Vertex result must not claim to be a blob",
          row.isBlob());
    }
    session.commit();
  }

  @Test
  public void testIsEdgeFlagForEdgeRow() {
    var schema = session.getMetadata().getSchema();
    var vClass = "V_ForEdgeFlag";
    var eClass = "E_Flag";
    schema.createClass(vClass, schema.getClass("V"));
    schema.createClass(eClass, schema.getClass("E"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    v1.addEdge(v2, eClass);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + eClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      Assert.assertTrue(row.isEntity());
      Assert.assertTrue(row.isEdge());
      Assert.assertFalse("Edge result must not claim to be a vertex",
          row.isVertex());
      Assert.assertFalse(row.isBlob());
      // asEdge() is abstract on Result and resolved through
      // ResultInternal#asEdge.
      Assert.assertNotNull(row.asEdge());
    }
    session.commit();
  }

  /**
   * Projection rows are not entities, vertices, edges, or blobs. Documents
   * that all four is* flags flip to false for an aggregate/projection.
   */
  @Test
  public void testProjectionRowHasNoEntityFlags() {
    var schema = session.getMetadata().getSchema();
    var vClass = "V_Projection";
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    session.newVertex(vClass);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT count(*) AS c FROM " + vClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      Assert.assertFalse("SELECT count(*) row must not be an entity",
          row.isEntity());
      Assert.assertFalse(row.isVertex());
      Assert.assertFalse(row.isEdge());
      Assert.assertFalse(row.isBlob());
      Assert.assertTrue("Projection result must report isProjection()",
          row.isProjection());
      Assert.assertEquals(1L, ((Number) row.getProperty("c")).longValue());
    }
    session.commit();
  }

  @Test
  public void testAsVertexOnProjectionThrowsIllegalState() {
    // asVertex() default method delegates to asEntity().asVertex(); asEntity
    // throws IllegalStateException with the message "Result is not an
    // entity" when the result is not an entity. Asserting the message
    // content pins the specific throw site — ResultInternal#asEntity —
    // rather than any arbitrary IllegalStateException (ResultInternal
    // also throws ISE from setProperty for mutation guards).
    var schema = session.getMetadata().getSchema();
    var vClass = "V_AsVertexProjection";
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    session.newVertex(vClass);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT count(*) AS c FROM " + vClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      var ise = Assert.assertThrows(IllegalStateException.class, row::asVertex);
      Assert.assertTrue(
          "Expected 'not an entity' message from asEntity(), got: "
              + ise.getMessage(),
          ise.getMessage() != null && ise.getMessage().contains("not an entity"));
    }
    // rollbackIfLeftOpen (inherited @After) handles the open read-only tx.
  }

  @Test
  public void testAsEdgeOrNullOnVertexRowReturnsNull() {
    // asEdgeOrNull on a vertex row must return null (the "wrong entity
    // kind" fall-through arm of asEdgeOrNull's default body).
    var schema = session.getMetadata().getSchema();
    var vClass = "V_AsEdgeOrNull";
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    session.newVertex(vClass);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + vClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      Assert.assertNull("asEdgeOrNull on a vertex row must return null",
          row.asEdgeOrNull());
    }
    session.commit();
  }

  @Test
  public void testAsBlobOrNullOnVertexRowReturnsNull() {
    // asBlobOrNull on a vertex row exercises the non-Blob fall-through arm.
    var schema = session.getMetadata().getSchema();
    var vClass = "V_AsBlobOrNull";
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    session.newVertex(vClass);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + vClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      Assert.assertNull(row.asBlobOrNull());
    }
    session.commit();
  }

  @Test
  public void testAsVertexOrNullOnEdgeRowReturnsNull() {
    // Default asVertexOrNull body: asEntityOrNull().asVertexOrNull() — on an
    // edge row, entity is non-null but asVertexOrNull() on the edge must
    // return null. Pins the cross-kind dispatch fall-through.
    var schema = session.getMetadata().getSchema();
    var vClass = "V_ForEdgeVertexOrNull";
    var eClass = "E_VertexOrNull";
    schema.createClass(vClass, schema.getClass("V"));
    schema.createClass(eClass, schema.getClass("E"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    v1.addEdge(v2, eClass);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + eClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      Assert.assertNull("asVertexOrNull on an edge row must return null",
          row.asVertexOrNull());
    }
    session.commit();
  }

  @Test
  public void testAsVertexOrNullOnProjectionReturnsNull() {
    // asVertexOrNull's default body: asEntityOrNull() → null when not an
    // entity → returns null without throwing.
    var schema = session.getMetadata().getSchema();
    var vClass = "V_AsVertexOrNullProjection";
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    session.newVertex(vClass);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT count(*) AS c FROM " + vClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      Assert.assertNull("asVertexOrNull on a projection must return null",
          row.asVertexOrNull());
    }
    session.commit();
  }

  /**
   * {@link Result#getVertex(String)} and {@link Result#getEdge(String)}
   * default bodies delegate to {@code getEntity(name)} and return null when
   * the property is missing. Exercising the happy-absence path on a vertex
   * row covers both defaults in one test.
   */
  @Test
  public void testGetVertexAndGetEdgeReturnNullForMissingProperty() {
    var schema = session.getMetadata().getSchema();
    var vClass = "V_GetVertexEdge";
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    session.newVertex(vClass).setProperty("name", "v");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + vClass)) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      Assert.assertNull(row.getVertex("missingLink"));
      Assert.assertNull(row.getEdge("missingLink"));
    }
    session.commit();
  }
}
