package com.jetbrains.youtrackdb.internal.core.ridbag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.EmbeddedLinkBag;
import org.junit.Test;

public class LinkBagBasicTest extends DbTestBase {

  @Test(expected = IllegalArgumentException.class)
  public void testExceptionInCaseOfNull() {
    session.begin();
    try {
      var bag = new EmbeddedLinkBag(session, Integer.MAX_VALUE);
      bag.add(null);
    } finally {
      session.rollback();
    }
  }

  /** Verify that hashCode() returns consistent values for equal LinkBags. */
  @Test
  public void testHashCodeConsistentWithEquals() {
    session.begin();
    var entity1 = session.newEntity();
    var entity2 = session.newEntity();
    session.commit();

    session.begin();
    var bag1 = new LinkBag(session);
    bag1.add(entity1.getIdentity());
    bag1.add(entity2.getIdentity());

    var bag2 = new LinkBag(session);
    bag2.add(entity1.getIdentity());
    bag2.add(entity2.getIdentity());

    assertEquals(bag1, bag2);
    assertEquals(bag1.hashCode(), bag2.hashCode());
    session.rollback();
  }

  /** Verify that hashCode() works for an empty LinkBag. */
  @Test
  public void testHashCodeEmptyBag() {
    session.begin();
    var bag1 = new LinkBag(session);
    var bag2 = new LinkBag(session);

    assertEquals(bag1, bag2);
    assertEquals(bag1.hashCode(), bag2.hashCode());
    session.rollback();
  }

  @Test
  public void allowOnlyAtRoot() {
    try {
      session.executeInTx(transaction -> {
        var record = session.newVertex();
        var valueList = session.newEmbeddedList();

        valueList.add(new LinkBag(session));
        record.setProperty("emb", valueList);
      });

      fail("Should not be possible to save a ridbag in a list");
    } catch (SchemaException ex) {
      // this is expected
    }

    try {
      session.executeInTx(transaction -> {
        var record = session.newVertex();
        var valueSet = session.newEmbeddedSet();

        valueSet.add(new LinkBag(session));
        record.setProperty("emb", valueSet);
      });

      fail("Should not be possible to save a ridbag in a set");
    } catch (SchemaException ex) {
      // this is expected
    }

    try {
      session.executeInTx(transaction -> {
        var record = session.newVertex();
        var valueMap = session.newEmbeddedMap();

        valueMap.put("key", new LinkBag(session));
        record.setProperty("emb", valueMap);
      });

      fail("Should not be possible to save a ridbag in a set");
    } catch (SchemaException ex) {
      // this is expected
    }

    try {
      session.executeInTx(transaction -> {
        var record = session.newVertex();
        var valueSet = session.newEmbeddedMap();

        var nested = session.newEmbeddedEntity();
        nested.setProperty("bag", new LinkBag(session));
        valueSet.put("key", nested);
        record.setProperty("emb", valueSet);
      });

      fail("Should not be possible to save a ridbag in a set");
    } catch (IllegalArgumentException ex) {
      // this is expected
    }

    try {
      session.executeInTx(transaction -> {
        var record = session.newVertex();
        var valueList = session.newEmbeddedList();
        var nested = session.newEmbeddedEntity();

        nested.setProperty("bag", new LinkBag(session));
        valueList.add(nested);
        record.setProperty("emb", valueList);
      });

      fail("Should not be possible to save a ridbag in a list");
    } catch (IllegalArgumentException ex) {
      // this is expected
    }

    try {
      session.executeInTx(transaction -> {
        var record = session.newVertex();
        var valueSet = session.newEmbeddedSet();

        var nested = session.newEmbeddedEntity();
        nested.setProperty("bag", new LinkBag(session));
        valueSet.add(nested);
        record.setProperty("emb", valueSet);
      });

      fail("Should not be possible to save a ridbag in a set");
    } catch (IllegalArgumentException ex) {
      // this is expected
    }

    try {
      session.executeInTx(transaction -> {
        var record = session.newVertex();
        var nested = session.newEmbeddedEntity();

        nested.setProperty("bag", new LinkBag(session));
        record.setEmbeddedEntity("emb", nested);
      });

      fail("Should not be possible to save a ridbag in a set");
    } catch (IllegalArgumentException ex) {
      // this is expected
    }
  }
}
