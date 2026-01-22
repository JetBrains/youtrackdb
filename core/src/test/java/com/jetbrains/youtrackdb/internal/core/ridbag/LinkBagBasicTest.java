package com.jetbrains.youtrackdb.internal.core.ridbag;

import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.EmbeddedLinkBag;
import org.junit.Test;

public class LinkBagBasicTest extends DbTestBase {

  @Test(expected = IllegalArgumentException.class)
  public void testExceptionInCaseOfNull() {
    var bag = new EmbeddedLinkBag(session, Integer.MAX_VALUE);
    bag.add(null);
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
