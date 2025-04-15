package com.jetbrains.youtrack.db.internal.core.ridbag;

import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.EmbeddedLinkBag;
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
    } catch (DatabaseException ex) {
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
    } catch (DatabaseException ex) {
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
    } catch (DatabaseException ex) {
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
    } catch (DatabaseException ex) {
      // this is expected
    }
  }
}
