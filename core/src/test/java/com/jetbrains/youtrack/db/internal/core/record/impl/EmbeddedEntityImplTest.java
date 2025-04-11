package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.util.List;
import org.junit.Test;

public class EmbeddedEntityImplTest extends DbTestBase {

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
        () -> inner.getOrCreateLinkMap("linkMap2")
    );

    for (var invalidOp : invalidOps) {
      try {
        invalidOp.run();
        fail("Adding links to embedded entities should fail.");
      } catch (DatabaseException e) {

      }
    }
    session.rollback();
  }
}