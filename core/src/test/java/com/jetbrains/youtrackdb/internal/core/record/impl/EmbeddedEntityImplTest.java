package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.List;
import org.junit.After;
import org.junit.Test;

public class EmbeddedEntityImplTest extends DbTestBase {

  /**
   * Defensive {@code @After} (Track 5+ idiom) — rolls back any transaction the test forgot to
   * close so subsequent tests start with a fresh session.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session == null || session.isClosed()) {
      return;
    }
    var tx = session.getActiveTransactionOrNull();
    if (tx != null && tx.isActive()) {
      tx.rollback();
    }
  }

  @Test
  public void testEmbeddedEntity() {
    final var id = session.computeInTx(tx -> {

      final var outer = tx.newEntity();
      final var inner = tx.newEmbeddedEntity();
      inner.setString("name", "inner");
      outer.setString("name", "outer");

      outer.setEmbeddedEntity("inner", inner);

      return outer.getIdentity();
    });

    session.executeInTx(tx -> {
      final var outer = tx.loadEntity(id);
      assertThat(outer.getString("name")).isEqualTo("outer");

      final var inner = outer.getEmbeddedEntity("inner");
      assertThat(inner.getString("name")).isEqualTo("inner");
    });
  }

  @Test
  public void testEmbeddedEntityWithLinks() {
    final var tx = session.begin();
    final var outer = tx.newEntity();
    final var inner = tx.newEmbeddedEntity();
    outer.setEmbeddedEntity("inner", inner);

    final List<Runnable> invalidOps = List.of(
        () -> inner.setLink("linked", tx.newEntity()),
        () -> inner.setLinkList("linkList1", session.newLinkList()),
        () -> inner.getOrCreateLinkList("linkList2"),
        () -> inner.setLinkSet("linkSet1", session.newLinkSet()),
        () -> inner.getOrCreateLinkSet("linkSet2"),
        () -> inner.setLinkMap("linkMap1", session.newLinkMap()),
        () -> inner.getOrCreateLinkMap("linkMap2"));

    for (var invalidOp : invalidOps) {
      try {
        invalidOp.run();
        fail("Adding links to embedded entities should fail.");
      } catch (IllegalArgumentException e) {
        //expected
      }
    }
    session.rollback();
  }

  /**
   * An embedded entity has no persistent record id — its identity is a fresh
   * {@link com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId} initialised at
   * construction. Pin the no-rid invariant: until the embedded entity is materialised inside
   * a parent and persisted with that parent, its identity must report
   * {@link com.jetbrains.youtrackdb.internal.core.db.record.record.RID#isPersistent()} false.
   */
  @Test
  public void testEmbeddedEntityHasNoPersistentRid() {
    session.executeInTx(tx -> {
      var emb = tx.newEmbeddedEntity();
      emb.setString("k", "v");
      assertNotNull(emb.getIdentity());
      assertFalse(
          "embedded entity identity must not be a persistent rid before parent persistence",
          emb.getIdentity().isPersistent());
    });
  }

  /**
   * An orphan embedded entity has no owner; assigning it as a child via {@code
   * setEmbeddedEntity} threads the parent in as the owner. Once an owner is set, attempting
   * to assign the SAME embedded value as a property on a different parent triggers the cycle
   * / re-parent guards. We pin the basic no-owner-then-owner transition; the negative
   * re-parent path is covered separately by {@link EntityImplTest}.
   */
  @Test
  public void testEmbeddedEntityOwnerSetOnAssignment() {
    session.begin();
    try {
      var parent = (EntityImpl) session.newEntity();
      var child = (EmbeddedEntityImpl) session.newEmbeddedEntity();

      assertTrue(child.isEmbedded());
      assertNull("orphan embedded entity must have no owner", child.getOwner());

      parent.setEmbeddedEntity("child", child);
      assertEquals(parent, child.getOwner());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@link EmbeddedEntityImpl#unload()} must throw {@link UnsupportedOperationException}
   * because embedded entities are not separately loadable — they live inside their owner's
   * payload. This pins the explicit override at line 60 of {@code EmbeddedEntityImpl}.
   */
  @Test
  public void testEmbeddedEntityUnloadIsUnsupported() {
    session.begin();
    try {
      var child = (EmbeddedEntityImpl) session.newEmbeddedEntity();
      try {
        child.unload();
        fail("EmbeddedEntityImpl.unload() must throw UnsupportedOperationException");
      } catch (UnsupportedOperationException expected) {
        // expected
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * {@link EmbeddedEntityImpl#checkPropertyValue(String, Object)} (overridden at
   * {@code EmbeddedEntityImpl.java:39-51}) rejects link / link-collection assignments on the
   * embedded child. We exercise the override directly via {@code setProperty(name, value,
   * PropertyType.LINK)} — the {@code Identifiable && !EmbeddedEntity} branch of
   * checkPropertyValue must produce an {@link IllegalArgumentException} rather than silently
   * accepting the link.
   */
  @Test
  public void testEmbeddedEntityRejectsLinkAssignmentViaSetProperty() {
    session.begin();
    try {
      var parent = session.newEntity();
      var child = (EmbeddedEntityImpl) session.newEmbeddedEntity();
      parent.setEmbeddedEntity("child", child);

      var target = session.newEntity();
      try {
        child.setProperty("link-on-child", target.getIdentity(), PropertyType.LINK);
        fail("Embedded entity must reject link assignment");
      } catch (IllegalArgumentException expected) {
        // expected — the override returns the rejection message
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * Schema-bound embedded entities (created with {@code newEmbeddedEntity(className)}) carry
   * the schema class name on the embedded child. Pin the contract: the inner class is
   * preserved and {@code getSchemaClassName} returns it.
   */
  @Test
  public void testSchemaBoundEmbeddedCarriesClassName() {
    var schema = session.getMetadata().getSchema();
    schema.createAbstractClass("EmbAddr");
    session.begin();
    try {
      var parent = (EntityImpl) session.newEntity();
      var addr = session.newEmbeddedEntity("EmbAddr");
      addr.setString("street", "Main");
      parent.setEmbeddedEntity("addr", addr);

      var fetched = parent.getEmbeddedEntity("addr");
      assertNotNull(fetched);
      assertEquals("EmbAddr", fetched.getSchemaClassName());
      assertEquals("Main", fetched.getString("street"));
    } finally {
      session.rollback();
    }
  }
}
