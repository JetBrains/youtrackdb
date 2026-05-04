package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.Pair;
import com.jetbrains.youtrackdb.internal.common.util.Resettable;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
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

  /**
   * Verify that DatabaseSessionEmbedded.newEdge(from, to, SchemaClass) with null
   * SchemaClass defaults to the base "E" edge type and correctly connects the vertices.
   * This covers the null SchemaClass fallback in
   * DatabaseSessionEmbedded.newEdge(Vertex, Vertex, SchemaClass).
   */
  @Test
  public void testNewEdgeWithNullSchemaClass() {
    session.executeInTx(tx -> {
      var v1 = tx.newVertex();
      var v2 = tx.newVertex();
      // Call session.newEdge (not tx.newEdge) to directly exercise
      // DatabaseSessionEmbedded.newEdge(Vertex, Vertex, SchemaClass) null-guard
      var edge = session.newEdge(v1, v2, (SchemaClass) null);
      assertThat(edge.getSchemaClassName()).isEqualTo("E");
      assertThat(edge.getFrom().getIdentity()).isEqualTo(v1.getIdentity());
      assertThat(edge.getTo().getIdentity()).isEqualTo(v2.getIdentity());
    });
  }

  /**
   * Verify that addEdge(vertex, null) uses the default "E" type.
   * Covers the null-type branch in VertexEntityImpl.addEdge(Vertex, String).
   */
  @Test
  public void testAddEdgeWithNullType() {
    session.executeInTx(tx -> {
      var v1 = tx.newVertex();
      var v2 = tx.newVertex();
      var edge = v1.addEdge(v2, (String) null);
      assertThat(edge).isNotNull();
      assertThat(edge.getSchemaClassName()).isEqualTo("E");
    });
  }

  /**
   * Verify EdgeIterator.size() falls back to multiValue Collection size when the
   * explicit size is unknown (-1) and the iterator is not Sizeable.
   * Covers EdgeIterator lines 86-87 (multiValue instanceof Collection).
   */
  @Test
  public void testEdgeIteratorSizeFromCollectionMultiValue() {
    session.createEdgeClass("IterSizeE");

    var edgeRid = session.computeInTx(tx -> {
      var v1 = tx.newVertex();
      var v2 = tx.newVertex();
      return v1.addEdge(v2, "IterSizeE").getIdentity();
    });

    session.executeInTx(tx -> {
      // Build an EdgeIterator with a Collection as multiValue and unknown size
      var ridList = List.of(edgeRid);
      var iter = new EdgeIterator(ridList, ridList.iterator(), -1, session);

      // size() should fall through to multiValue instanceof Collection → 1
      assertThat(iter.size()).isEqualTo(1);
      // isSizeable() should return true because multiValue is a Collection
      assertThat(iter.isSizeable()).isTrue();
    });
  }

  /**
   * Verify EdgeIterator.size() falls back to Sizeable iterator when explicit size is
   * unknown and the iterator implements Sizeable.
   * Covers EdgeIterator line 81 (iterator instanceof Sizeable).
   */
  @Test
  public void testEdgeIteratorSizeFromSizeableIterator() {
    session.createEdgeClass("IterSizeablE");

    var edgeRid = session.computeInTx(tx -> {
      var v1 = tx.newVertex();
      var v2 = tx.newVertex();
      return v1.addEdge(v2, "IterSizeablE").getIdentity();
    });

    session.executeInTx(tx -> {
      // Wrap the RID list in a Sizeable iterator
      var sizeableIter = new SizeableRidIterator(List.of(edgeRid));
      // Use a non-Collection, non-Sizeable multiValue to force the iterator Sizeable path
      var iter = new EdgeIterator("not-a-collection", sizeableIter, -1, session);

      assertThat(iter.size()).isEqualTo(1);
      assertThat(iter.isSizeable()).isTrue();
    });
  }

  /**
   * Verify EdgeIterator.size() throws when no size source is available.
   * Covers EdgeIterator line 89 (UnsupportedOperationException).
   */
  @Test
  public void testEdgeIteratorSizeUnsupported() {
    session.executeInTx(tx -> {
      // Non-Sizeable iterator, non-Collection/Sizeable multiValue, unknown size
      var iter = new EdgeIterator(
          "not-a-collection", List.<RID>of().iterator(), -1, session);

      assertThatThrownBy(iter::size)
          .isInstanceOf(UnsupportedOperationException.class);
      assertThat(iter.isSizeable()).isFalse();
    });
  }

  /**
   * Verify EdgeIterator.reset() delegates to underlying Resettable iterator and
   * allows re-iteration. Also verifies isResetable() returns correct values.
   * Covers EdgeIterator lines 62-65 (reset with Resettable) and line 72 (isResetable).
   */
  @Test
  public void testEdgeIteratorResetAndIsResetable() {
    session.createEdgeClass("IterResetE");

    var edgeRid = session.computeInTx(tx -> {
      var v1 = tx.newVertex();
      var v2 = tx.newVertex();
      return v1.addEdge(v2, "IterResetE").getIdentity();
    });

    session.executeInTx(tx -> {
      // Test with a Resettable iterator
      var resettableIter = new ResettableRidIterator(List.of(edgeRid));
      var iter = new EdgeIterator(null, resettableIter, 1, session);

      assertThat(iter.isResetable()).isTrue();

      // Consume the edge
      assertThat(iter.hasNext()).isTrue();
      var edge = iter.next();
      assertThat(edge).isNotNull();
      assertThat(iter.hasNext()).isFalse();

      // Reset and re-iterate
      iter.reset();
      assertThat(iter.hasNext()).isTrue();
      var edge2 = iter.next();
      assertThat(edge2.getIdentity()).isEqualTo(edge.getIdentity());
    });
  }

  /**
   * Verify EdgeIterator.reset() throws on non-Resettable iterator and
   * isResetable() returns false.
   * Covers EdgeIterator line 67 (UnsupportedOperationException) and line 72 false branch.
   */
  @Test
  public void testEdgeIteratorResetOnNonResettableThrows() {
    session.executeInTx(tx -> {
      var iter = new EdgeIterator(null, List.<RID>of().iterator(), 0, session);
      assertThat(iter.isResetable()).isFalse();

      assertThatThrownBy(iter::reset)
          .isInstanceOf(UnsupportedOperationException.class);
    });
  }

  /**
   * Verify EdgeIterator.next() throws NoSuchElementException when exhausted.
   * Covers EdgeIterator line 57.
   */
  @Test
  public void testEdgeIteratorNextThrowsWhenExhausted() {
    session.executeInTx(tx -> {
      var iter = new EdgeIterator(null, List.<RID>of().iterator(), 0, session);
      assertThat(iter.hasNext()).isFalse();

      assertThatThrownBy(iter::next)
          .isInstanceOf(NoSuchElementException.class);
    });
  }

  /**
   * Verify EdgeIterator.isSizeable() returns true when explicit size is known (>= 0),
   * regardless of other parameters. Covers EdgeIterator line 94 first condition.
   */
  @Test
  public void testEdgeIteratorIsSizeableWithKnownSize() {
    session.executeInTx(tx -> {
      // Explicit size=0, non-Sizeable iterator, non-Collection multiValue
      var iter = new EdgeIterator("foo", List.<RID>of().iterator(), 0, session);
      assertThat(iter.isSizeable()).isTrue();
      assertThat(iter.size()).isEqualTo(0);
    });
  }

  /**
   * Verify EdgeIterator.isSizeable() uses multiValue Sizeable fallback when iterator
   * is not Sizeable. Covers EdgeIterator line 95 (multiValue instanceof Sizeable).
   */
  @Test
  public void testEdgeIteratorIsSizeableFromMultiValueSizeable() {
    session.executeInTx(tx -> {
      var sizeableMultiValue = new Sizeable() {
        @Override
        public int size() {
          return 42;
        }

        @Override
        public boolean isSizeable() {
          return true;
        }
      };
      var iter = new EdgeIterator(
          sizeableMultiValue, List.<RID>of().iterator(), -1, session);

      assertThat(iter.isSizeable()).isTrue();
      // size() should reach the multiValue Sizeable branch
      assertThat(iter.size()).isEqualTo(42);
    });
  }

  /**
   * Verify that creating a standalone entity with an abstract class throws SchemaException.
   * Covers EntityImpl constructor line 170 (abstract class check).
   */
  @Test
  public void testNewEntityWithAbstractClassThrows() {
    var cls = session.getSchema().createClass("AbstractTestCls");
    cls.setAbstract(true);

    session.executeInTx(tx -> {
      assertThatThrownBy(() -> tx.newEntity("AbstractTestCls"))
          .isInstanceOf(SchemaException.class)
          .hasMessageContaining("abstract");
    });
  }

  /**
   * Verify that creating an embedded entity with a non-abstract class throws DatabaseException.
   * Covers EntityImpl.checkEmbeddable() line 3893 (non-abstract class check).
   */
  @Test
  public void testNewEmbeddedEntityWithNonAbstractClassThrows() {
    session.getSchema().createClass("ConcreteTestCls");

    session.executeInTx(tx -> {
      assertThatThrownBy(() -> tx.newEmbeddedEntity("ConcreteTestCls"))
          .isInstanceOf(DatabaseException.class)
          .hasMessageContaining("abstract");
    });
  }

  /**
   * Simple Resettable Iterator over RIDs, used to test EdgeIterator.reset().
   */
  private static class ResettableRidIterator
      implements Iterator<RID>, Resettable {
    private final List<RID> items;
    private int index;

    ResettableRidIterator(List<RID> items) {
      this.items = new ArrayList<>(items);
      this.index = 0;
    }

    @Override
    public boolean hasNext() {
      return index < items.size();
    }

    @Override
    public RID next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return items.get(index++);
    }

    @Override
    public void reset() {
      index = 0;
    }

    @Override
    public boolean isResetable() {
      return true;
    }
  }

  /**
   * Sizeable Iterator over RIDs, used to test EdgeIterator.size() fallback.
   */
  private static class SizeableRidIterator
      implements Iterator<Identifiable>, Sizeable {
    private final List<? extends Identifiable> items;
    private int index;

    SizeableRidIterator(List<? extends Identifiable> items) {
      this.items = new ArrayList<>(items);
      this.index = 0;
    }

    @Override
    public boolean hasNext() {
      return index < items.size();
    }

    @Override
    public Identifiable next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return items.get(index++);
    }

    @Override
    public int size() {
      return items.size();
    }

    @Override
    public boolean isSizeable() {
      return true;
    }
  }

  /**
   * {@link VertexEntityImpl} extends {@link EntityImpl} and exposes a class-level
   * {@code RECORD_TYPE} constant {@code 'v'}; an instance returned by {@code newVertex} must
   * be exactly that class (not just an EntityImpl with vertex semantics) and must report the
   * same record-type byte through the instance method. The same shape is mirrored for
   * {@link EdgeEntityImpl} with constant {@code 'e'}. We assert both with
   * {@code assertEquals(X.class, x.getClass())} (the load-bearing wrapper-type idiom from
   * Track 11) — {@code instanceof} is NOT sufficient here because a regression that returned
   * a base {@link EntityImpl} with vertex flags flipped on would silently pass an
   * {@code instanceof Vertex} check.
   */
  @Test
  public void testVertexAndEdgeWrapperClassShape() {
    session.createVertexClass("VShape");
    session.createEdgeClass("EShape");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex("VShape");
      var v2 = tx.newVertex("VShape");

      assertThat(v1.getClass()).isEqualTo(VertexEntityImpl.class);
      assertThat(v2.getClass()).isEqualTo(VertexEntityImpl.class);
      assertThat(((VertexEntityImpl) v1).getRecordType()).isEqualTo(VertexEntityImpl.RECORD_TYPE);
      assertThat(VertexEntityImpl.RECORD_TYPE).isEqualTo((byte) 'v');

      var e = tx.newEdge(v1, v2, "EShape");
      assertThat(e.getClass()).isEqualTo(EdgeEntityImpl.class);
      assertThat(((EdgeEntityImpl) e).getRecordType()).isEqualTo(EdgeEntityImpl.RECORD_TYPE);
      assertThat(EdgeEntityImpl.RECORD_TYPE).isEqualTo((byte) 'e');
    });
  }

  /**
   * The {@link EdgeEntityImpl#label()} delegating method returns the schema-class name when one
   * is present. {@link EdgeEntityImpl#getSchemaClassName()} and {@link EdgeEntityImpl#getSchemaClass()}
   * forward to the underlying {@link EntityImpl} state. This pins the delegating-subclass
   * pass-through.
   */
  @Test
  public void testEdgeEntityImplDelegatesSchemaShapeToEntityImpl() {
    session.createVertexClass("EShapeV");
    session.createEdgeClass("EShapeLabel");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex("EShapeV");
      var v2 = tx.newVertex("EShapeV");
      var e = (EdgeEntityImpl) tx.newEdge(v1, v2, "EShapeLabel");

      assertThat(e.label()).isEqualTo("EShapeLabel");
      assertThat(e.getSchemaClassName()).isEqualTo("EShapeLabel");
      assertThat(e.getSchemaClass()).isNotNull();
      assertThat(e.getSchemaClass().getName()).isEqualTo("EShapeLabel");
    });
  }
}
