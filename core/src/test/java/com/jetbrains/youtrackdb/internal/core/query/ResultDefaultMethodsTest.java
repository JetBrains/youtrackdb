/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import java.util.function.Consumer;
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

  /**
   * Creates a single-row vertex class, inserts one vertex with {@code name="v"}, queries it
   * back, and hands the result row to {@code assertions}. Wraps the whole round-trip in a
   * begin/commit pair; the {@code @After rollbackIfLeftOpen} safety net catches any test that
   * throws mid-transaction.
   */
  private void withSingleVertexResult(String vClassName, Consumer<Result> assertions) {
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClassName, schema.getClass("V"));

    session.begin();
    session.newVertex(vClassName).setProperty("name", "v");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + vClassName)) {
      Assert.assertTrue(rs.hasNext());
      assertions.accept(rs.next());
    }
    session.commit();
  }

  /**
   * Creates vertex + edge classes, wires an edge between two vertices, queries the single edge
   * row back, and hands the result to {@code assertions}. Used by the edge-specific tests.
   */
  private void withSingleEdgeResult(String vClassName, String eClassName,
      Consumer<Result> assertions) {
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClassName, schema.getClass("V"));
    schema.createClass(eClassName, schema.getClass("E"));

    session.begin();
    var v1 = session.newVertex(vClassName);
    var v2 = session.newVertex(vClassName);
    v1.addEdge(v2, eClassName);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + eClassName)) {
      Assert.assertTrue(rs.hasNext());
      assertions.accept(rs.next());
    }
    session.commit();
  }

  /**
   * Projection row helper: creates {@code vClassName}, inserts one vertex, runs a
   * {@code SELECT count(*) AS c FROM <class>} query, and passes the projection row to
   * {@code assertions}. Separate from the entity helper because the commit/rollback semantics
   * for tests that deliberately throw differ.
   */
  private void withProjectionRow(String vClassName, Consumer<Result> assertions) {
    var schema = session.getMetadata().getSchema();
    schema.createClass(vClassName, schema.getClass("V"));

    session.begin();
    session.newVertex(vClassName);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT count(*) AS c FROM " + vClassName)) {
      Assert.assertTrue(rs.hasNext());
      assertions.accept(rs.next());
    }
    // Trailing commit is omitted deliberately when the caller already rolled back — callers
    // that throw assertions mid-body rely on the @After rollbackIfLeftOpen net so the
    // exception propagates unmasked. Here we commit only if the tx is still active.
    if (session.isTxActive()) {
      session.commit();
    }
  }

  @Test
  public void testAsVertexOnVertexResult() {
    // Override the helper's default "name":"v" to keep the original test's observable; create
    // the vertex inline so we can set "name":"v1" while reusing the rest of the query round-trip.
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
    withSingleVertexResult("V_AsVertexOrNull", row -> {
      var asVertex = row.asVertexOrNull();
      Assert.assertNotNull("asVertexOrNull must return the vertex for an"
          + " entity-backed result", asVertex);
    });
  }

  @Test
  public void testIsEntityIsVertexFlagsForVertex() {
    withSingleVertexResult("V_IsFlags", row -> {
      Assert.assertTrue(row.isEntity());
      Assert.assertTrue(row.isVertex());
      Assert.assertFalse("Vertex result must not claim to be an edge",
          row.isEdge());
      Assert.assertFalse("Vertex result must not claim to be a blob",
          row.isBlob());
    });
  }

  @Test
  public void testIsEdgeFlagForEdgeRow() {
    withSingleEdgeResult("V_ForEdgeFlag", "E_Flag", row -> {
      Assert.assertTrue(row.isEntity());
      Assert.assertTrue(row.isEdge());
      Assert.assertFalse("Edge result must not claim to be a vertex",
          row.isVertex());
      Assert.assertFalse(row.isBlob());
      // asEdge() is abstract on Result and resolved through ResultInternal#asEdge.
      Assert.assertNotNull(row.asEdge());
    });
  }

  /**
   * Projection rows are not entities, vertices, edges, or blobs. Documents
   * that all four is* flags flip to false for an aggregate/projection.
   */
  @Test
  public void testProjectionRowHasNoEntityFlags() {
    withProjectionRow("V_Projection", row -> {
      Assert.assertFalse("SELECT count(*) row must not be an entity", row.isEntity());
      Assert.assertFalse(row.isVertex());
      Assert.assertFalse(row.isEdge());
      Assert.assertFalse(row.isBlob());
      Assert.assertTrue("Projection result must report isProjection()",
          row.isProjection());
      Assert.assertEquals(1L, ((Number) row.getProperty("c")).longValue());
    });
  }

  @Test
  public void testAsVertexOnProjectionThrowsIllegalState() {
    // asVertex() default method delegates to asEntity().asVertex(); asEntity
    // throws IllegalStateException with the message "Result is not an
    // entity" when the result is not an entity. Asserting the message
    // content pins the specific throw site — ResultInternal#asEntity —
    // rather than any arbitrary IllegalStateException (ResultInternal
    // also throws ISE from setProperty for mutation guards).
    withProjectionRow("V_AsVertexProjection", row -> {
      var ise = Assert.assertThrows(IllegalStateException.class, row::asVertex);
      Assert.assertTrue(
          "Expected 'not an entity' message from asEntity(), got: " + ise.getMessage(),
          ise.getMessage() != null && ise.getMessage().contains("not an entity"));
      // rollbackIfLeftOpen (inherited @After) handles the open read-only tx.
    });
  }

  @Test
  public void testAsEdgeOrNullOnVertexRowReturnsNull() {
    // asEdgeOrNull on a vertex row must return null (the "wrong entity
    // kind" fall-through arm of asEdgeOrNull's default body).
    withSingleVertexResult("V_AsEdgeOrNull",
        row -> Assert.assertNull("asEdgeOrNull on a vertex row must return null",
            row.asEdgeOrNull()));
  }

  @Test
  public void testAsBlobOrNullOnVertexRowReturnsNull() {
    // asBlobOrNull on a vertex row exercises the non-Blob fall-through arm.
    withSingleVertexResult("V_AsBlobOrNull", row -> Assert.assertNull(row.asBlobOrNull()));
  }

  @Test
  public void testAsVertexOrNullOnEdgeRowReturnsNull() {
    // Default asVertexOrNull body: asEntityOrNull().asVertexOrNull() — on an
    // edge row, entity is non-null but asVertexOrNull() on the edge must
    // return null. Pins the cross-kind dispatch fall-through.
    withSingleEdgeResult("V_ForEdgeVertexOrNull", "E_VertexOrNull",
        row -> Assert.assertNull("asVertexOrNull on an edge row must return null",
            row.asVertexOrNull()));
  }

  @Test
  public void testAsVertexOrNullOnProjectionReturnsNull() {
    // asVertexOrNull's default body: asEntityOrNull() → null when not an
    // entity → returns null without throwing.
    withProjectionRow("V_AsVertexOrNullProjection",
        row -> Assert.assertNull("asVertexOrNull on a projection must return null",
            row.asVertexOrNull()));
  }

  /**
   * {@link Result#getVertex(String)} and {@link Result#getEdge(String)}
   * default bodies delegate to {@code getEntity(name)} and return null when
   * the property is missing. Exercising the happy-absence path on a vertex
   * row covers both defaults in one test.
   */
  @Test
  public void testGetVertexAndGetEdgeReturnNullForMissingProperty() {
    withSingleVertexResult("V_GetVertexEdge", row -> {
      Assert.assertNull(row.getVertex("missingLink"));
      Assert.assertNull(row.getEdge("missingLink"));
    });
  }

  /**
   * Happy-path companion to {@link #testGetVertexAndGetEdgeReturnNullForMissingProperty}:
   * when the probed property holds a link to an actual vertex, {@link Result#getVertex} must
   * return that vertex (the non-null branch of the default body). Without this pin, a mutation
   * that collapsed {@code getEntity(name).asVertex()} to {@code null} would slip past — every
   * existing test only covers the null short-circuit arm.
   */
  @Test
  public void testGetVertexReturnsLinkedVertex() {
    var schema = session.getMetadata().getSchema();
    var vClass = "V_GetVertexHappy";
    schema.createClass(vClass, schema.getClass("V"));

    session.begin();
    var linked = session.newVertex(vClass);
    linked.setProperty("tag", "target");
    var source = session.newVertex(vClass);
    source.setProperty("tag", "source");
    source.setProperty("link", linked);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + vClass + " WHERE tag = 'source'")) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      var resolved = row.getVertex("link");
      Assert.assertNotNull("getVertex must resolve a link property to the linked vertex",
          resolved);
      Assert.assertEquals("target", resolved.getProperty("tag"));
    }
    session.commit();
  }

  /**
   * Happy-path companion for {@link Result#getEdge(String)}: the default body delegates to
   * {@code getEntity(name).asEdge()}, and the only previously-covered branch was the
   * null-property short-circuit. Exercise it with a vertex that holds an edge in a user-named
   * property — if {@code entity.asEdge()} returned {@code null} for a valid edge entity, this
   * pin would fail. (Note: user-level link properties on vertices cannot use reserved names
   * like "in"/"out" because the schema layer reserves those for edge management.)
   */
  @Test
  public void testGetEdgeReturnsLinkedEdge() {
    var schema = session.getMetadata().getSchema();
    var vClass = "V_GetEdgeHappy";
    var eClass = "E_GetEdgeHappy";
    schema.createClass(vClass, schema.getClass("V"));
    schema.createClass(eClass, schema.getClass("E"));

    session.begin();
    var v1 = session.newVertex(vClass);
    var v2 = session.newVertex(vClass);
    var edge = v1.addEdge(v2, eClass);
    // Attach the edge to a third vertex via a non-reserved property name so we can query for
    // it by that name without tripping the schema's edge-management-name guard.
    var anchor = session.newVertex(vClass);
    anchor.setProperty("tag", "anchor");
    anchor.setProperty("linkedEdge", edge);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + vClass + " WHERE tag = 'anchor'")) {
      Assert.assertTrue(rs.hasNext());
      Result row = rs.next();
      var resolved = row.getEdge("linkedEdge");
      Assert.assertNotNull(
          "getEdge must resolve a link property to the linked edge via the default body",
          resolved);
      Assert.assertEquals(edge.getIdentity(), resolved.getIdentity());
    }
    session.commit();
  }
}
