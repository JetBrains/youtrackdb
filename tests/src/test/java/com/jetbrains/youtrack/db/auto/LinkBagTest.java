package com.jetbrains.youtrack.db.auto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.client.remote.ServerAdmin;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.StorageProxy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public abstract class LinkBagTest extends BaseDBTest {

  @Parameters(value = "remote")
  public LinkBagTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  public void testAdd() {
    session.begin();
    var bag = new LinkBag(session);

    bag.add(new RecordId("#77:1"));
    assertTrue(bag.contains(new RecordId("#77:1")));
    Assert.assertFalse(bag.contains(new RecordId("#78:2")));

    var iterator = bag.iterator();
    assertTrue(iterator.hasNext());

    Identifiable identifiable = iterator.next();
    assertEquals(identifiable, new RecordId("#77:1"));
    Assert.assertFalse(iterator.hasNext());
    assertEmbedded(bag.isEmbedded());
    session.commit();
  }

  public void testAdd2() {
    session.begin();
    var bag = new LinkBag(session);

    bag.add(new RecordId("#77:2"));
    bag.add(new RecordId("#77:2"));

    assertTrue(bag.contains(new RecordId("#77:2")));
    Assert.assertFalse(bag.contains(new RecordId("#77:3")));

    assertEquals(bag.size(), 2);
    assertEmbedded(bag.isEmbedded());
    session.commit();
  }

  public void testAddRemoveInTheMiddleOfIteration() {

    session.begin();
    var id0 = session.newEntity().getIdentity();
    var id1 = session.newEntity().getIdentity();
    var id2 = session.newEntity().getIdentity();
    var id3 = session.newEntity().getIdentity();
    var id4 = session.newEntity().getIdentity();
    var id5 = session.newEntity().getIdentity();
    var id6 = session.newEntity().getIdentity();
    session.commit();

    session.begin();

    var bag = new LinkBag(session);

    bag.add(id2);
    bag.add(id2);
    bag.add(id3);
    bag.add(id4);
    bag.add(id4);
    bag.add(id4);
    bag.add(id5);
    bag.add(id6);

    var counter = 0;
    var iterator = bag.iterator();

    bag.remove(id2);
    while (iterator.hasNext()) {
      counter++;
      if (counter == 1) {
        bag.remove(id1);
        bag.remove(id2);
      }

      if (counter == 3) {
        bag.remove(id4);
      }

      if (counter == 5) {
        bag.remove(id6);
      }

      iterator.next();
    }

    assertTrue(bag.contains(id3));
    assertTrue(bag.contains(id4));
    assertTrue(bag.contains(id5));

    Assert.assertFalse(bag.contains(id2));
    Assert.assertFalse(bag.contains(id6));
    Assert.assertFalse(bag.contains(id1));
    Assert.assertFalse(bag.contains(id0));

    assertEmbedded(bag.isEmbedded());

    final List<Identifiable> rids = new ArrayList<>();
    rids.add(id3);
    rids.add(id4);
    rids.add(id4);
    rids.add(id5);

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);

    session.commit();

    RID rid = doc.getIdentity();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    assertTrue(bag.contains(id3));
    assertTrue(bag.contains(id4));
    assertTrue(bag.contains(id5));

    Assert.assertFalse(bag.contains(id2));
    Assert.assertFalse(bag.contains(id6));
    Assert.assertFalse(bag.contains(id1));
    Assert.assertFalse(bag.contains(id0));

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  public void testAddRemove() {
    session.begin();

    var id0 = session.newEntity().getIdentity();
    var id1 = session.newEntity().getIdentity();
    var id2 = session.newEntity().getIdentity();
    var id3 = session.newEntity().getIdentity();
    var id4 = session.newEntity().getIdentity();
    var id5 = session.newEntity().getIdentity();
    var id6 = session.newEntity().getIdentity();
    session.commit();

    session.begin();
    var bag = new LinkBag(session);

    bag.add(id2);
    bag.add(id2);
    bag.add(id3);
    bag.add(id4);
    bag.add(id4);
    bag.add(id4);
    bag.add(id5);
    bag.add(id6);

    bag.remove(id1);
    bag.remove(id2);
    bag.remove(id2);
    bag.remove(id4);
    bag.remove(id6);

    assertTrue(bag.contains(id3));
    assertTrue(bag.contains(id4));
    assertTrue(bag.contains(id5));

    Assert.assertFalse(bag.contains(id2));
    Assert.assertFalse(bag.contains(id6));
    Assert.assertFalse(bag.contains(id1));
    Assert.assertFalse(bag.contains(id0));

    assertEmbedded(bag.isEmbedded());

    final List<Identifiable> rids = new ArrayList<>();
    rids.add(id3);
    rids.add(id4);
    rids.add(id4);
    rids.add(id5);

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);

    session.commit();

    RID rid = doc.getIdentity();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    assertTrue(bag.contains(id3));
    assertTrue(bag.contains(id4));
    assertTrue(bag.contains(id5));

    Assert.assertFalse(bag.contains(id2));
    Assert.assertFalse(bag.contains(id6));
    Assert.assertFalse(bag.contains(id1));
    Assert.assertFalse(bag.contains(id0));

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  public void testAddRemoveSBTreeContainsValues() {

    session.begin();

    var id1 = session.newEntity().getIdentity();
    var id2 = session.newEntity().getIdentity();
    var id3 = session.newEntity().getIdentity();
    var id4 = session.newEntity().getIdentity();
    var id5 = session.newEntity().getIdentity();
    var id6 = session.newEntity().getIdentity();
    session.commit();

    session.begin();
    var bag = new LinkBag(session);

    bag.add(id2);
    bag.add(id2);
    bag.add(id3);
    bag.add(id4);
    bag.add(id4);
    bag.add(id4);
    bag.add(id5);
    bag.add(id6);

    assertEmbedded(bag.isEmbedded());

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);

    session.commit();

    RID rid = doc.getIdentity();

    session.close();

    session = createSessionInstance();
    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    bag.remove(id1);
    bag.remove(id2);
    bag.remove(id2);
    bag.remove(id4);
    bag.remove(id6);

    final List<Identifiable> rids = new ArrayList<>();
    rids.add(id3);
    rids.add(id4);
    rids.add(id4);
    rids.add(id5);

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    doc = ((EntityImpl) session.newEntity());
    var otherBag = new LinkBag(session);
    for (var id : bag) {
      otherBag.add(id);
    }

    assertEmbedded(otherBag.isEmbedded());
    doc.setProperty("ridbag", otherBag);

    session.commit();

    rid = doc.getIdentity();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  public void testAddRemoveDuringIterationSBTreeContainsValues() {
    session.begin();

    var id1 = session.newEntity().getIdentity();
    var id2 = session.newEntity().getIdentity();
    var id3 = session.newEntity().getIdentity();
    var id4 = session.newEntity().getIdentity();
    var id5 = session.newEntity().getIdentity();
    var id6 = session.newEntity().getIdentity();

    session.commit();
    session.begin();
    var bag = new LinkBag(session);
    assertEmbedded(bag.isEmbedded());

    bag.add(id2);
    bag.add(id2);
    bag.add(id3);
    bag.add(id4);
    bag.add(id4);
    bag.add(id4);
    bag.add(id5);
    bag.add(id6);
    assertEmbedded(bag.isEmbedded());

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);

    session.commit();

    RID rid = doc.getIdentity();
    session.close();

    session = createSessionInstance();
    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    bag.remove(id1);
    bag.remove(id2);
    bag.remove(id2);
    bag.remove(id4);
    bag.remove(id6);
    assertEmbedded(bag.isEmbedded());

    final List<Identifiable> rids = new ArrayList<>();
    rids.add(id3);
    rids.add(id4);
    rids.add(id4);
    rids.add(id5);

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    var iterator = bag.iterator();
    while (iterator.hasNext()) {
      final var identifiable = iterator.next();
      if (identifiable.equals(id4)) {
        iterator.remove();
        assertTrue(rids.remove(identifiable));
      }
    }

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    assertEmbedded(bag.isEmbedded());
    doc = ((EntityImpl) session.newEntity());

    final var otherBag = new LinkBag(session);
    for (var id : bag) {
      otherBag.add(id);
    }

    assertEmbedded(otherBag.isEmbedded());
    doc.setProperty("ridbag", otherBag);

    session.commit();

    rid = doc.getIdentity();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  public void testEmptyIterator() {
    session.begin();
    var bag = new LinkBag(session);
    assertEmbedded(bag.isEmbedded());
    assertEquals(bag.size(), 0);

    for (@SuppressWarnings("unused") Identifiable id : bag) {
      Assert.fail();
    }
    session.commit();
  }

  public void testAddRemoveNotExisting() {

    session.begin();

    var id2 = session.newEntity().getIdentity();
    var id3 = session.newEntity().getIdentity();
    var id4 = session.newEntity().getIdentity();
    var id5 = session.newEntity().getIdentity();
    var id6 = session.newEntity().getIdentity();
    var id7 = session.newEntity().getIdentity();
    var id8 = session.newEntity().getIdentity();
    session.commit();

    session.begin();
    List<RID> rids = new ArrayList<>();

    var bag = new LinkBag(session);
    assertEmbedded(bag.isEmbedded());

    bag.add(id2);
    rids.add(id2);

    bag.add(id2);
    rids.add(id2);

    bag.add(id3);
    rids.add(id3);

    bag.add(id4);
    rids.add(id4);

    bag.add(id4);
    rids.add(id4);

    bag.add(id4);
    rids.add(id4);

    bag.add(id5);
    rids.add(id5);

    bag.add(id6);
    rids.add(id6);
    assertEmbedded(bag.isEmbedded());

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);

    session.commit();

    RID rid = doc.getIdentity();

    session.close();

    session = createSessionInstance();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    bag.add(id2);
    rids.add(id2);

    bag.remove(id4);
    rids.remove(id4);

    bag.remove(id4);
    rids.remove(id4);

    bag.remove(id2);
    rids.remove(id2);

    bag.remove(id2);
    rids.remove(id2);

    bag.remove(id7);
    rids.remove(id7);

    bag.remove(id8);
    rids.remove(id8);

    bag.remove(id8);
    rids.remove(id8);

    bag.remove(id8);
    rids.remove(id8);

    assertEmbedded(bag.isEmbedded());

    for (var identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (var identifiable : bag) {
      rids.add(identifiable);
    }

    session.commit();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (var identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  public void testContentChange() {
    session.begin();
    var entity = ((EntityImpl) session.newEntity());
    var ridBag = new LinkBag(session);
    entity.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    var id10 = session.newEntity().getIdentity();
    var activeTx2 = session.getActiveTransaction();
    entity = activeTx2.load(entity);
    ridBag = entity.getProperty("ridBag");
    ridBag.add(id10);
    assertTrue(entity.isDirty());
    session.commit();

    session.begin();
    var id12 = session.newEntity().getIdentity();
    var version = entity.getVersion();
    var activeTx1 = session.getActiveTransaction();
    entity = activeTx1.load(entity);
    ridBag = entity.getProperty("ridBag");
    ridBag.add(id12);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    assertNotEquals(entity.getVersion(), version);
    session.commit();
  }

  public void testAddAllAndIterator() {
    session.begin();
    final Set<RID> expected = new HashSet<>(8);

    expected.add(new RecordId("#77:12"));
    expected.add(new RecordId("#77:13"));
    expected.add(new RecordId("#77:14"));
    expected.add(new RecordId("#77:15"));
    expected.add(new RecordId("#77:16"));

    var bag = new LinkBag(session);

    bag.addAll(expected);
    assertEmbedded(bag.isEmbedded());

    assertEquals(bag.size(), 5);

    Set<Identifiable> actual = new HashSet<>(8);
    for (Identifiable id : bag) {
      actual.add(id);
    }

    assertEquals(actual, expected);
    session.commit();
  }

  public void testAddSBTreeAddInMemoryIterate() {

    session.begin();

    var id0 = session.newEntity().getIdentity();
    var id1 = session.newEntity().getIdentity();
    var id2 = session.newEntity().getIdentity();
    var id3 = session.newEntity().getIdentity();
    var id4 = session.newEntity().getIdentity();
    var id5 = session.newEntity().getIdentity();
    var id6 = session.newEntity().getIdentity();
    session.commit();

    session.begin();
    List<RID> rids = new ArrayList<>();

    var bag = new LinkBag(session);
    assertEmbedded(bag.isEmbedded());

    bag.add(id2);
    rids.add(id2);

    bag.add(id2);
    rids.add(id2);

    bag.add(id3);
    rids.add(id3);

    bag.add(id4);
    rids.add(id4);

    bag.add(id4);
    rids.add(id4);
    assertEmbedded(bag.isEmbedded());

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);

    session.commit();

    RID rid = doc.getIdentity();

    session.close();

    session = createSessionInstance();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    bag.add(id0);
    rids.add(id0);

    bag.add(id1);
    rids.add(id1);

    bag.add(id2);
    rids.add(id2);

    bag.add(id3);
    rids.add(id3);

    bag.add(id5);
    rids.add(id5);

    bag.add(id6);
    rids.add(id6);

    assertEmbedded(bag.isEmbedded());

    for (var identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (var identifiable : bag) {
      rids.add(identifiable);
    }

    doc = ((EntityImpl) session.newEntity());
    final var otherBag = new LinkBag(session);
    for (var id : bag) {
      otherBag.add(id);
    }

    doc.setProperty("ridbag", otherBag);

    session.commit();

    rid = doc.getIdentity();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (var entry : bag) {
      assertTrue(rids.remove(entry));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  public void testCycle() {
    session.begin();
    var docOne = ((EntityImpl) session.newEntity());
    var ridBagOne = new LinkBag(session);

    var docTwo = ((EntityImpl) session.newEntity());
    var ridBagTwo = new LinkBag(session);

    docOne.setProperty("ridBag", ridBagOne);
    docTwo.setProperty("ridBag", ridBagTwo);

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    docOne = activeTx1.load(docOne);
    var activeTx = session.getActiveTransaction();
    docTwo = activeTx.load(docTwo);

    ridBagOne = docOne.getProperty("ridBag");
    ridBagOne.add(docTwo.getIdentity());

    ridBagTwo = docTwo.getProperty("ridBag");
    ridBagTwo.add(docOne.getIdentity());

    session.commit();

    session.begin();
    docOne = session.load(docOne.getIdentity());
    ridBagOne = docOne.getProperty("ridBag");

    docTwo = session.load(docTwo.getIdentity());
    ridBagTwo = docTwo.getProperty("ridBag");

    assertEquals(ridBagOne.iterator().next(), docTwo);
    assertEquals(ridBagTwo.iterator().next(), docOne);
    session.commit();
  }

  public void testAddSBTreeAddInMemoryIterateAndRemove() {

    session.begin();

    var id0 = session.newEntity().getIdentity();
    var id1 = session.newEntity().getIdentity();
    var id2 = session.newEntity().getIdentity();
    var id3 = session.newEntity().getIdentity();
    var id4 = session.newEntity().getIdentity();
    var id5 = session.newEntity().getIdentity();
    var id6 = session.newEntity().getIdentity();
    var id7 = session.newEntity().getIdentity();
    var id8 = session.newEntity().getIdentity();
    session.commit();
    session.begin();
    List<Identifiable> rids = new ArrayList<>();

    var bag = new LinkBag(session);
    assertEmbedded(bag.isEmbedded());

    bag.add(id2);
    rids.add(id2);

    bag.add(id2);
    rids.add(id2);

    bag.add(id3);
    rids.add(id3);

    bag.add(id4);
    rids.add(id4);

    bag.add(id4);
    rids.add(id4);

    bag.add(id7);
    rids.add(id7);

    bag.add(id8);
    rids.add(id8);

    assertEmbedded(bag.isEmbedded());

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);

    session.commit();

    RID rid = doc.getIdentity();
    session.close();

    session = createSessionInstance();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    bag.add(id0);
    rids.add(id0);

    bag.add(id1);
    rids.add(id1);

    bag.add(id2);
    rids.add(id2);

    bag.add(id3);
    rids.add(id3);

    bag.add(id3);
    rids.add(id3);

    bag.add(id5);
    rids.add(id5);

    bag.add(id6);
    rids.add(id6);

    assertEmbedded(bag.isEmbedded());

    var iterator = bag.iterator();
    var r2c = 0;
    var r3c = 0;
    var r6c = 0;
    var r4c = 0;
    var r7c = 0;

    while (iterator.hasNext()) {
      Identifiable identifiable = iterator.next();
      if (identifiable.equals(id2)) {
        if (r2c < 2) {
          r2c++;
          iterator.remove();
          rids.remove(identifiable);
        }
      }

      if (identifiable.equals(id3)) {
        if (r3c < 1) {
          r3c++;
          iterator.remove();
          rids.remove(identifiable);
        }
      }

      if (identifiable.equals(id6)) {
        if (r6c < 1) {
          r6c++;
          iterator.remove();
          rids.remove(identifiable);
        }
      }

      if (identifiable.equals(id4)) {
        if (r4c < 1) {
          r4c++;
          iterator.remove();
          rids.remove(identifiable);
        }
      }

      if (identifiable.equals(id7)) {
        if (r7c < 1) {
          r7c++;
          iterator.remove();
          rids.remove(identifiable);
        }
      }
    }

    assertEquals(r2c, 2);
    assertEquals(r3c, 1);
    assertEquals(r6c, 1);
    assertEquals(r4c, 1);
    assertEquals(r7c, 1);

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    doc = ((EntityImpl) session.newEntity());

    final var otherBag = new LinkBag(session);
    for (var id : bag) {
      otherBag.add(id);
    }

    assertEmbedded(otherBag.isEmbedded());

    doc.setProperty("ridbag", otherBag);

    session.commit();

    rid = doc.getIdentity();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  public void testRemove() {
    final Set<RID> expected = new HashSet<>(8);

    expected.add(new RecordId("#77:12"));
    expected.add(new RecordId("#77:13"));
    expected.add(new RecordId("#77:14"));
    expected.add(new RecordId("#77:15"));
    expected.add(new RecordId("#77:16"));

    final var bag = new LinkBag(session);
    assertEmbedded(bag.isEmbedded());
    bag.addAll(expected);
    assertEmbedded(bag.isEmbedded());

    bag.remove(new RecordId("#77:23"));
    assertEmbedded(bag.isEmbedded());

    final Set<Identifiable> expectedTwo = new HashSet<>(8);
    expectedTwo.addAll(expected);

    for (Identifiable identifiable : bag) {
      assertTrue(expectedTwo.remove(identifiable));
    }

    assertTrue(expectedTwo.isEmpty());

    expected.remove(new RecordId("#77:14"));
    bag.remove(new RecordId("#77:14"));
    assertEmbedded(bag.isEmbedded());

    expectedTwo.addAll(expected);

    for (Identifiable identifiable : bag) {
      assertTrue(expectedTwo.remove(identifiable));
    }
  }

  public void testSaveLoad() {
    session.begin();

    final var id12 = session.newEntity().getIdentity();
    final var id13 = session.newEntity().getIdentity();
    final var id14 = session.newEntity().getIdentity();
    final var id15 = session.newEntity().getIdentity();
    final var id16 = session.newEntity().getIdentity();
    final var id17 = session.newEntity().getIdentity();
    final var id18 = session.newEntity().getIdentity();
    final var id19 = session.newEntity().getIdentity();
    final var id20 = session.newEntity().getIdentity();
    final var id21 = session.newEntity().getIdentity();
    final var id22 = session.newEntity().getIdentity();

    session.commit();
    session.begin();
    Set<RID> expected = new HashSet<>(8);

    expected.add(id12);
    expected.add(id13);
    expected.add(id14);
    expected.add(id15);
    expected.add(id16);
    expected.add(id17);
    expected.add(id18);
    expected.add(id19);
    expected.add(id20);
    expected.add(id21);
    expected.add(id22);

    var doc = ((EntityImpl) session.newEntity());

    final var bag = new LinkBag(session);
    bag.addAll(expected);

    doc.setProperty("ridbag", bag);
    assertEmbedded(bag.isEmbedded());

    session.commit();
    final RID id = doc.getIdentity();

    session.close();

    session = createSessionInstance();

    session.begin();
    doc = session.load(id);
    doc.setLazyLoad(false);

    final LinkBag loaded = doc.getProperty("ridbag");
    assertEmbedded(loaded.isEmbedded());

    assertEquals(loaded.size(), expected.size());
    for (var identifiable : loaded) {
      assertTrue(expected.remove(identifiable));
    }

    assertTrue(expected.isEmpty());
    session.commit();
  }

  public void testSaveInBackOrder() {
    session.begin();
    var docA = ((EntityImpl) session.newEntity()).setPropertyInChain("name", "A");

    var docB =
        ((EntityImpl) session.newEntity())
            .setPropertyInChain("name", "B");

    session.commit();

    session.begin();
    var ridBag = new LinkBag(session);
    docA = session.load(docA.getIdentity());
    docB = session.load(docB.getIdentity());

    ridBag.add(docA.getIdentity());
    ridBag.add(docB.getIdentity());

    ridBag.remove(docB.getIdentity());

    assertEmbedded(ridBag.isEmbedded());

    var result = new HashSet<Identifiable>();

    for (Identifiable oIdentifiable : ridBag) {
      result.add(oIdentifiable);
    }

    assertTrue(result.contains(docA));
    Assert.assertFalse(result.contains(docB));
    assertEquals(result.size(), 1);
    assertEquals(ridBag.size(), 1);
    session.commit();
  }

  public void testMassiveChanges() {
    session.begin();
    var document = ((EntityImpl) session.newEntity());
    var bag = new LinkBag(session);
    assertEmbedded(bag.isEmbedded());

    final var seed = System.nanoTime();
    System.out.println("testMassiveChanges seed: " + seed);

    var random = new Random(seed);
    List<Identifiable> rids = new ArrayList<>();
    document.setProperty("bag", bag);

    session.commit();

    RID rid = document.getIdentity();

    for (var i = 0; i < 10; i++) {
      session.begin();
      document = session.load(rid);
      document.setLazyLoad(false);

      bag = document.getProperty("bag");
      assertEmbedded(bag.isEmbedded());

      massiveInsertionIteration(random, rids, bag);
      assertEmbedded(bag.isEmbedded());

      session.commit();
    }

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.commit();
  }

  public void testSimultaneousIterationAndRemove() {
    session.begin();
    var ridBag = new LinkBag(session);
    var document = ((EntityImpl) session.newEntity());
    document.setProperty("ridBag", ridBag);
    assertEmbedded(ridBag.isEmbedded());

    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      ridBag.add(docToAdd.getIdentity());
    }

    session.commit();

    assertEmbedded(ridBag.isEmbedded());

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    ridBag = document.getProperty("ridBag");

    Set<Identifiable> docs = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Identifiable id : ridBag) {
      // cache record inside session
      var transaction = session.getActiveTransaction();
      docs.add(transaction.load(id));
    }

    ridBag = document.getProperty("ridBag");
    assertEmbedded(ridBag.isEmbedded());

    for (var i = 0; i < 10; i++) {
      session.begin();
      var docToAdd = ((EntityImpl) session.newEntity());

      docs.add(docToAdd);
      ridBag.add(docToAdd.getIdentity());
      session.commit();
    }

    assertEmbedded(ridBag.isEmbedded());

    for (var i = 0; i < 10; i++) {
      session.begin();
      var docToAdd = ((EntityImpl) session.newEntity());
      docs.add(docToAdd);
      ridBag.add(docToAdd.getIdentity());
      session.commit();
    }

    assertEmbedded(ridBag.isEmbedded());
    for (Identifiable identifiable : ridBag) {
      var transaction1 = session.getActiveTransaction();
      assertTrue(docs.remove(transaction1.load(identifiable)));
      ridBag.remove(identifiable.getIdentity());
      assertEquals(ridBag.size(), docs.size());

      var counter = 0;
      for (Identifiable id : ridBag) {
        var transaction = session.getActiveTransaction();
        assertTrue(docs.contains(transaction.load(id)));
        counter++;
      }

      assertEquals(counter, docs.size());
      assertEmbedded(ridBag.isEmbedded());
    }

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");
    assertEquals(ridBag.size(), 0);
    assertEquals(docs.size(), 0);
    session.commit();
  }

  public void testAddMixedValues() {
    session.begin();
    var ridBag = new LinkBag(session);
    var document = ((EntityImpl) session.newEntity());
    document.setProperty("ridBag", ridBag);
    assertEmbedded(ridBag.isEmbedded());

    List<Identifiable> itemsToAdd = new ArrayList<>();

    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());
      ridBag = document.getProperty("ridBag");

      for (var k = 0; k < 2; k++) {
        ridBag.add(docToAdd.getIdentity());
        itemsToAdd.add(docToAdd);
      }

    }
    session.commit();

    assertEmbedded(ridBag.isEmbedded());

    for (var i = 0; i < 10; i++) {
      session.begin();
      var docToAdd = ((EntityImpl) session.newEntity());

      var activeTx = session.getActiveTransaction();
      document = activeTx.load(document);
      ridBag = document.getProperty("ridBag");
      for (var k = 0; k < 2; k++) {
        ridBag.add(docToAdd.getIdentity());
        itemsToAdd.add(docToAdd);
      }

      session.commit();
    }

    for (var i = 0; i < 10; i++) {
      session.begin();
      var docToAdd = ((EntityImpl) session.newEntity());

      var activeTx = session.getActiveTransaction();
      document = activeTx.load(document);
      ridBag = document.getProperty("ridBag");
      ridBag.add(docToAdd.getIdentity());
      itemsToAdd.add(docToAdd);

      session.commit();
    }

    assertEmbedded(ridBag.isEmbedded());

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    ridBag = document.getProperty("ridBag");
    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      for (var k = 0; k < 2; k++) {
        ridBag.add(docToAdd.getIdentity());
        itemsToAdd.add(docToAdd);
      }
    }
    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      ridBag.add(docToAdd.getIdentity());
      itemsToAdd.add(docToAdd);
    }

    assertEmbedded(ridBag.isEmbedded());

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");
    assertEmbedded(ridBag.isEmbedded());

    assertEquals(ridBag.size(), itemsToAdd.size());

    assertEquals(ridBag.size(), itemsToAdd.size());

    for (Identifiable id : ridBag) {
      assertTrue(itemsToAdd.remove(id));
    }

    assertTrue(itemsToAdd.isEmpty());
    session.commit();
  }

  public void testFromEmbeddedToSBTreeAndBack() throws IOException {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(7);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(-1);

    if (session.getStorage() instanceof StorageProxy) {
      var server = new ServerAdmin(session.getURL()).connect("root", SERVER_PASSWORD);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD, 7);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD, -1);
      server.close();
    }

    session.begin();
    var ridBag = new LinkBag(session);
    var document = ((EntityImpl) session.newEntity());
    document.setProperty("ridBag", ridBag);

    assertTrue(ridBag.isEmbedded());

    session.commit();

    session.begin();
    var activeTx6 = session.getActiveTransaction();
    document = activeTx6.load(document);
    ridBag = document.getProperty("ridBag");
    assertTrue(ridBag.isEmbedded());

    List<RID> addedItems = new ArrayList<>();
    for (var i = 0; i < 6; i++) {
      session.begin();
      var docToAdd = ((EntityImpl) session.newEntity());

      ridBag = document.getProperty("ridBag");
      ridBag.add(docToAdd.getIdentity());
      addedItems.add(docToAdd.getIdentity());
      session.commit();
    }

    session.commit();

    session.begin();
    var activeTx5 = session.getActiveTransaction();
    document = activeTx5.load(document);
    ridBag = document.getProperty("ridBag");
    assertTrue(ridBag.isEmbedded());
    session.rollback();

    session.begin();
    var docToAdd = ((EntityImpl) session.newEntity());

    session.commit();
    session.begin();

    var activeTx4 = session.getActiveTransaction();
    docToAdd = activeTx4.load(docToAdd);
    var activeTx3 = session.getActiveTransaction();
    document = activeTx3.load(document);
    ridBag = document.getProperty("ridBag");
    ridBag.add(docToAdd.getIdentity());
    addedItems.add(docToAdd.getIdentity());

    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    document = activeTx2.load(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    List<RID> addedItemsCopy = new ArrayList<>(addedItems);
    for (Identifiable id : ridBag) {
      assertTrue(addedItems.remove(id.getIdentity()));
    }

    assertTrue(addedItems.isEmpty());
    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (Identifiable id : ridBag) {
      assertTrue(addedItems.remove(id.getIdentity()));
    }

    assertTrue(addedItems.isEmpty());

    addedItems.addAll(addedItemsCopy);

    for (var i = 0; i < 3; i++) {
      ridBag.remove(addedItems.remove(i).getIdentity());
    }

    addedItemsCopy.clear();
    addedItemsCopy.addAll(addedItems);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    for (var id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());

    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (var id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());
    session.commit();
  }

  public void testFromEmbeddedToSBTreeAndBackTx() throws IOException {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(7);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(-1);

    if (session.isRemote()) {
      var server = new ServerAdmin(session.getURL()).connect("root", SERVER_PASSWORD);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD, 7);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD, -1);
      server.close();
    }

    session.begin();
    var ridBag = new LinkBag(session);
    var document = ((EntityImpl) session.newEntity());
    document.setProperty("ridBag", ridBag);

    assertTrue(ridBag.isEmbedded());

    session.commit();

    session.begin();
    var activeTx5 = session.getActiveTransaction();
    document = activeTx5.load(document);
    ridBag = document.getProperty("ridBag");
    assertTrue(ridBag.isEmbedded());

    List<Identifiable> addedItems = new ArrayList<>();

    ridBag = document.getProperty("ridBag");
    for (var i = 0; i < 6; i++) {

      var docToAdd = ((EntityImpl) session.newEntity());

      ridBag.add(docToAdd.getIdentity());
      addedItems.add(docToAdd);
    }

    session.commit();

    session.begin();
    var activeTx4 = session.getActiveTransaction();
    document = activeTx4.load(document);
    ridBag = document.getProperty("ridBag");
    assertTrue(ridBag.isEmbedded());

    var docToAdd = ((EntityImpl) session.newEntity());

    session.commit();

    session.begin();
    var activeTx3 = session.getActiveTransaction();
    document = activeTx3.load(document);
    var activeTx2 = session.getActiveTransaction();
    docToAdd = activeTx2.load(docToAdd);

    ridBag = document.getProperty("ridBag");
    ridBag.add(docToAdd.getIdentity());
    addedItems.add(docToAdd);

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    List<Identifiable> addedItemsCopy = new ArrayList<>(addedItems);
    for (Identifiable id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());

    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (Identifiable id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());

    addedItems.addAll(addedItemsCopy);

    for (var i = 0; i < 3; i++) {
      ridBag.remove(addedItems.remove(i).getIdentity());
    }

    addedItemsCopy.clear();
    addedItemsCopy.addAll(addedItems);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    for (Identifiable id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());

    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (Identifiable id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());
    session.commit();
  }

  public void testRemoveSavedInCommit() {
    session.begin();
    List<Identifiable> docsToAdd = new ArrayList<>();

    var ridBag = new LinkBag(session);
    var document = ((EntityImpl) session.newEntity());
    document.setProperty("ridBag", ridBag);

    for (var i = 0; i < 5; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      ridBag = document.getProperty("ridBag");
      ridBag.add(docToAdd.getIdentity());

      docsToAdd.add(docToAdd);
    }

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    ridBag = document.getProperty("ridBag");
    assertEmbedded(ridBag.isEmbedded());

    ridBag = document.getProperty("ridBag");
    assertEmbedded(ridBag.isEmbedded());

    ridBag = document.getProperty("ridBag");
    for (var i = 0; i < 5; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      ridBag.add(docToAdd.getIdentity());

      docsToAdd.add(docToAdd);
    }

    for (var i = 5; i < 10; i++) {
      var transaction = session.getActiveTransaction();
      EntityImpl docToAdd = transaction.load(docsToAdd.get(i));

    }

    Iterator<Identifiable> iterator = docsToAdd.listIterator(7);
    while (iterator.hasNext()) {
      var docToAdd = iterator.next();
      ridBag.remove(docToAdd.getIdentity());
      iterator.remove();
    }

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");
    assertEmbedded(ridBag.isEmbedded());

    List<Identifiable> docsToAddCopy = new ArrayList<>(docsToAdd);
    for (Identifiable id : ridBag) {
      assertTrue(docsToAdd.remove(id));
    }

    assertTrue(docsToAdd.isEmpty());

    docsToAdd.addAll(docsToAddCopy);

    ridBag = document.getProperty("ridBag");

    for (Identifiable id : ridBag) {
      assertTrue(docsToAdd.remove(id));
    }

    assertTrue(docsToAdd.isEmpty());
    session.commit();
  }

  @Test
  public void testSizeNotChangeAfterRemoveNotExistentElement() {
    session.begin();
    final var bob = ((EntityImpl) session.newEntity());

    final var fred = ((EntityImpl) session.newEntity());

    final var jim =
        ((EntityImpl) session.newEntity());

    session.commit();

    session.begin();
    var teamMates = new LinkBag(session);

    teamMates.add(bob.getIdentity());
    teamMates.add(fred.getIdentity());

    assertEquals(teamMates.size(), 2);

    teamMates.remove(jim.getIdentity());

    assertEquals(teamMates.size(), 2);
    session.commit();
  }

  @Test
  public void testRemoveNotExistentElementAndAddIt() {
    session.begin();
    var teamMates = new LinkBag(session);

    final var bob = ((EntityImpl) session.newEntity());

    session.commit();

    teamMates.remove(bob.getIdentity());

    assertEquals(teamMates.size(), 0);

    teamMates.add(bob.getIdentity());

    assertEquals(teamMates.size(), 1);
    assertEquals(teamMates.iterator().next().getIdentity(), bob.getIdentity());
  }

  public void testAddNewItemsAndRemoveThem() {
    session.begin();
    final List<RID> rids = new ArrayList<>();
    var ridBag = new LinkBag(session);
    var size = 0;
    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      for (var k = 0; k < 2; k++) {
        ridBag.add(docToAdd.getIdentity());
        rids.add(docToAdd.getIdentity());
        size++;
      }
    }

    assertEquals(ridBag.size(), size);
    var document = ((EntityImpl) session.newEntity());
    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.getProperty("ridBag");
    assertEquals(ridBag.size(), size);

    final List<RID> newDocs = new ArrayList<>();
    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      for (var k = 0; k < 2; k++) {
        ridBag.add(docToAdd.getIdentity());
        rids.add(docToAdd.getIdentity());
        newDocs.add(docToAdd.getIdentity());
        size++;
      }
    }

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");
    assertEquals(ridBag.size(), size);

    var rnd = new Random();

    for (var i = 0; i < newDocs.size(); i++) {
      if (rnd.nextBoolean()) {
        var newDoc = newDocs.get(i);
        rids.remove(newDoc);
        ridBag.remove(newDoc.getIdentity());
        newDocs.remove(newDoc);

        size--;
      }
    }

    for (var identifiable : ridBag) {
      if (newDocs.contains(identifiable) && rnd.nextBoolean()) {
        ridBag.remove(identifiable.getIdentity());
        if (rids.remove(identifiable)) {
          size--;
        }
      }
    }

    session.commit();
    session.begin();

    activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");

    assertEquals(ridBag.size(), size);
    List<RID> ridsCopy = new ArrayList<>(rids);

    for (var identifiable : ridBag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.getProperty("ridBag");

    rids.addAll(ridsCopy);
    for (var identifiable : ridBag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    assertEquals(ridBag.size(), size);
    session.commit();
  }

  @Test
  public void testJsonSerialization() {
    session.begin();
    final var externalDoc = ((EntityImpl) session.newEntity());

    final var highLevelRidBag = new LinkBag(session);

    for (var i = 0; i < 10; i++) {
      var doc = ((EntityImpl) session.newEntity());

      highLevelRidBag.add(doc.getIdentity());
    }

    var testDocument = ((EntityImpl) session.newEntity());
    testDocument.setProperty("type", "testDocument");
    testDocument.setProperty("ridBag", highLevelRidBag);
    testDocument.setProperty("externalDoc", externalDoc);

    final var origContent = testDocument.toMap();
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    testDocument = activeTx.load(testDocument);
    final var json = testDocument.toJSON("keepTypes,rid,class");

    final var doc = session.createOrLoadEntityFromJson(json).toMap();

    origContent.remove(EntityHelper.ATTRIBUTE_RID);
    doc.remove(EntityHelper.ATTRIBUTE_RID);

    assertEquals(doc.get("@class"), origContent.get("@class"));
    assertEquals(doc.get("externalDoc"), origContent.get("externalDoc"));
    assertEquals(doc.get("type"), origContent.get("type"));
    assertEquals(
        new HashSet<>((List<?>) doc.get("ridBag")),
        new HashSet<>(((List<?>) origContent.get("ridBag")))
    );
    session.commit();
  }

  protected abstract void assertEmbedded(boolean isEmbedded);

  private void massiveInsertionIteration(Random rnd, List<Identifiable> rids,
      LinkBag bag) {
    var bagIterator = bag.iterator();

    while (bagIterator.hasNext()) {
      Identifiable bagValue = bagIterator.next();
      assertTrue(rids.contains(bagValue));
    }

    assertEquals(bag.size(), rids.size());

    for (var i = 0; i < 100; i++) {
      if (rnd.nextDouble() < 0.2 & rids.size() > 5) {
        final var index = rnd.nextInt(rids.size());
        final var rid = rids.remove(index);
        Assert.assertTrue(bag.remove(rid.getIdentity()));
      } else {
        final var recordId = session.newEntity().getIdentity();
        rids.add(recordId);
        bag.add(recordId);
      }
    }

    bagIterator = bag.iterator();

    while (bagIterator.hasNext()) {
      final Identifiable bagValue = bagIterator.next();
      assertTrue(rids.contains(bagValue));
      if (rnd.nextDouble() < 0.05) {
        bagIterator.remove();
        assertTrue(rids.remove(bagValue));

      }
    }

    assertEquals(bag.size(), rids.size());
    bagIterator = bag.iterator();

    while (bagIterator.hasNext()) {
      final Identifiable bagValue = bagIterator.next();
      assertTrue(rids.contains(bagValue));
    }
  }
}
