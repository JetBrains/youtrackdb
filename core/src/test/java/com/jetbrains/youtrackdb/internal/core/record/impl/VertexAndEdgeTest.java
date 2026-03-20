package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.Pair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

public class VertexAndEdgeTest extends DbTestBase {

  @Test
  public void testAddEdgeAndDeleteVertex() {

    final var p = session.computeInTx(tx -> {
      var v1 = tx.newVertex();
      var v2 = tx.newVertex();

      return new Pair<>(v1, v2);
    });

    session.executeInTx(tx -> {
      var v1 = tx.loadVertex(p.getKey());
      var v2 = tx.loadVertex(p.getValue());

      v1.addEdge(v2, "E");
      v2.delete();
    });

    try {
      session.executeInTx(tx -> tx.load(p.getValue()));
      fail("Should throw RecordNotFoundException");
    } catch (RecordNotFoundException ex) {
      // ok
    }

    session.executeInTx(tx -> {
      final var v1 = tx.loadVertex(p.getKey());
      assertThat(v1.getEdges(Direction.BOTH)).isEmpty();
    });
  }

  /**
   * Verify that asEdge() on a vertex throws DatabaseException, and asEdgeOrNull() returns null.
   * This covers the EntityImpl.asEdge() throw path and EntityImpl.asEdgeOrNull() null path.
   */
  @Test
  public void testAsEdgeOnVertexThrows() {
    session.executeInTx(tx -> {
      var vertex = tx.newVertex();

      // asEdge() on a vertex should throw
      assertThatThrownBy(vertex::asEdge)
          .isInstanceOf(DatabaseException.class)
          .hasMessageContaining("not an edge");

      // asEdgeOrNull() on a vertex should return null
      assertThat(vertex.asEdgeOrNull()).isNull();
    });
  }

  /**
   * Verify that asEdge() on a plain entity throws DatabaseException,
   * and asEdgeOrNull() returns null. This covers the non-edge EntityImpl paths.
   */
  @Test
  public void testAsEdgeOnPlainEntityThrows() {
    session.getSchema().createClass("PlainEntity");

    session.executeInTx(tx -> {
      var entity = tx.newEntity("PlainEntity");

      assertThatThrownBy(entity::asEdge)
          .isInstanceOf(DatabaseException.class)
          .hasMessageContaining("not an edge");

      assertThat(entity.asEdgeOrNull()).isNull();
    });
  }

  /**
   * Verify that creating an edge via the transaction API and loading it back works correctly.
   * This exercises FrontendTransactionImpl.newEdge(from, to) and loadEdge paths.
   */
  @Test
  public void testNewEdgeAndLoadEdge() {
    session.createVertexClass("NewEdgeV");
    session.createEdgeClass("NewEdgeE");

    var edgeRid = session.computeInTx(tx -> {
      var v1 = tx.newVertex("NewEdgeV");
      var v2 = tx.newVertex("NewEdgeV");
      var edge = tx.newEdge(v1, v2, "NewEdgeE");
      edge.setString("key", "value");
      return edge.getIdentity();
    });

    session.executeInTx(tx -> {
      var edge = tx.loadEdge(edgeRid);
      assertThat(edge).isNotNull();
      assertThat(edge.getString("key")).isEqualTo("value");
    });
  }

  /**
   * Verify that loadEdgeOrNull returns null for a non-existent edge RID.
   * This covers the FrontendTransactionImpl.loadEdgeOrNull null-return path.
   */
  @Test
  public void testLoadEdgeOrNullReturnsNull() {
    session.executeInTx(tx -> {
      // Use a fake RID that doesn't exist
      var result = tx.loadEdgeOrNull(new RecordId(9999, 9999));
      assertThat(result).isNull();
    });
  }

  /**
   * Verify that newEdge without specifying a type creates a base "E" edge.
   * This covers FrontendTransactionImpl.newEdge(from, to) - the no-type overload.
   */
  @Test
  public void testNewEdgeWithoutType() {
    session.executeInTx(tx -> {
      var v1 = tx.newVertex();
      var v2 = tx.newVertex();
      var edge = tx.newEdge(v1, v2);
      assertThat(edge).isNotNull();
      assertThat(edge.getSchemaClassName()).isEqualTo("E");
    });
  }

  /**
   * Verify that asEdge() on a RecordBytes (blob) throws, and isEdge() returns false.
   * This covers RecordBytes.asEdge() throw path and RecordBytes.isEdge() false path.
   */
  @Test
  public void testAsEdgeOnBlobThrows() {
    session.executeInTx(tx -> {
      var blob = tx.newBlob(new byte[] {1, 2, 3});
      assertThat(blob.isEdge()).isFalse();
      assertThatThrownBy(blob::asEdge)
          .isInstanceOf(IllegalStateException.class);
      assertThat(blob.asEdgeOrNull()).isNull();
    });
  }

  /**
   * Verify that loading a vertex via loadEdge throws.
   * This covers the "id instanceof DBRecord" throw in FrontendTransactionImpl.loadEdge.
   */
  @Test
  public void testLoadEdgeWithNonEdgeRecordThrows() {
    session.executeInTx(tx -> {
      var vertex = tx.newVertex();
      // Loading a vertex via loadEdge(Identifiable) should throw
      assertThatThrownBy(() -> tx.loadEdge(vertex))
          .isInstanceOf(DatabaseException.class)
          .hasMessageContaining("not an edge");
    });
  }

  /**
   * Verify that creating an edge with a non-edge class name throws.
   * This covers DatabaseSessionEmbedded.newEdgeInternal validation (line 1072).
   */
  @Test
  public void testNewEdgeWithNonEdgeClassThrows() {
    session.getSchema().createClass("NotAnEdgeClass");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex();
      var v2 = tx.newVertex();

      assertThatThrownBy(() -> tx.newEdge(v1, v2, "NotAnEdgeClass"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not an edge class");
    });
  }

  /**
   * Verify that newEdge with SchemaClass parameter works correctly.
   * This covers FrontendTransactionImpl.newEdge(from, to, SchemaClass).
   */
  @Test
  public void testNewEdgeWithSchemaClass() {
    session.createVertexClass("SchemaEdgeV");
    session.createEdgeClass("SchemaEdgeE");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex("SchemaEdgeV");
      var v2 = tx.newVertex("SchemaEdgeV");
      var edgeClass = session.getSchema().getClass("SchemaEdgeE");
      var edge = tx.newEdge(v1, v2, edgeClass);
      assertThat(edge).isNotNull();
      assertThat(edge.getSchemaClassName()).isEqualTo("SchemaEdgeE");
    });
  }
}
