package com.jetbrains.youtrack.db.auto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.client.remote.ServerAdmin;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
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
public abstract class RidBagTest extends BaseDBTest {

  @Parameters(value = "remote")
  public RidBagTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  public void testAdd() {
    var bag = new RidBag(session);

    bag.add(new RecordId("#77:1"));
    Assert.assertTrue(bag.contains(new RecordId("#77:1")));
    Assert.assertFalse(bag.contains(new RecordId("#78:2")));

    var iterator = bag.iterator();
    Assert.assertTrue(iterator.hasNext());

    Identifiable identifiable = iterator.next();
    Assert.assertEquals(identifiable, new RecordId("#77:1"));

    Assert.assertFalse(iterator.hasNext());
    assertEmbedded(bag.isEmbedded());
  }

  public void testAdd2() {
    var bag = new RidBag(session);

    bag.add(new RecordId("#77:2"));
    bag.add(new RecordId("#77:2"));

    Assert.assertTrue(bag.contains(new RecordId("#77:2")));
    Assert.assertFalse(bag.contains(new RecordId("#77:3")));

    assertEquals(bag.size(), 2);
    assertEmbedded(bag.isEmbedded());
  }

  public void testAddRemoveInTheMiddleOfIteration() {
    var bag = new RidBag(session);

    bag.add(new RecordId("#77:2"));
    bag.add(new RecordId("#77:2"));
    bag.add(new RecordId("#77:3"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:5"));
    bag.add(new RecordId("#77:6"));

    var counter = 0;
    var iterator = bag.iterator();

    bag.remove(new RecordId("#77:2"));
    while (iterator.hasNext()) {
      counter++;
      if (counter == 1) {
        bag.remove(new RecordId("#77:1"));
        bag.remove(new RecordId("#77:2"));
      }

      if (counter == 3) {
        bag.remove(new RecordId("#77:4"));
      }

      if (counter == 5) {
        bag.remove(new RecordId("#77:6"));
      }

      iterator.next();
    }

    Assert.assertTrue(bag.contains(new RecordId("#77:3")));
    Assert.assertTrue(bag.contains(new RecordId("#77:4")));
    Assert.assertTrue(bag.contains(new RecordId("#77:5")));

    Assert.assertFalse(bag.contains(new RecordId("#77:2")));
    Assert.assertFalse(bag.contains(new RecordId("#77:6")));
    Assert.assertFalse(bag.contains(new RecordId("#77:1")));
    Assert.assertFalse(bag.contains(new RecordId("#77:0")));

    assertEmbedded(bag.isEmbedded());

    final List<Identifiable> rids = new ArrayList<>();
    rids.add(new RecordId("#77:3"));
    rids.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:5"));

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);
    session.begin();

    session.commit();

    RID rid = doc.getIdentity();

    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    Assert.assertTrue(bag.contains(new RecordId("#77:3")));
    Assert.assertTrue(bag.contains(new RecordId("#77:4")));
    Assert.assertTrue(bag.contains(new RecordId("#77:5")));

    Assert.assertFalse(bag.contains(new RecordId("#77:2")));
    Assert.assertFalse(bag.contains(new RecordId("#77:6")));
    Assert.assertFalse(bag.contains(new RecordId("#77:1")));
    Assert.assertFalse(bag.contains(new RecordId("#77:0")));

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
  }

  public void testAddRemove() {
    var bag = new RidBag(session);

    bag.add(new RecordId("#77:2"));
    bag.add(new RecordId("#77:2"));
    bag.add(new RecordId("#77:3"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:5"));
    bag.add(new RecordId("#77:6"));

    bag.remove(new RecordId("#77:1"));
    bag.remove(new RecordId("#77:2"));
    bag.remove(new RecordId("#77:2"));
    bag.remove(new RecordId("#77:4"));
    bag.remove(new RecordId("#77:6"));

    Assert.assertTrue(bag.contains(new RecordId("#77:3")));
    Assert.assertTrue(bag.contains(new RecordId("#77:4")));
    Assert.assertTrue(bag.contains(new RecordId("#77:5")));

    Assert.assertFalse(bag.contains(new RecordId("#77:2")));
    Assert.assertFalse(bag.contains(new RecordId("#77:6")));
    Assert.assertFalse(bag.contains(new RecordId("#77:1")));
    Assert.assertFalse(bag.contains(new RecordId("#77:0")));

    assertEmbedded(bag.isEmbedded());

    final List<Identifiable> rids = new ArrayList<>();
    rids.add(new RecordId("#77:3"));
    rids.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:5"));

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);
    session.begin();

    session.commit();

    RID rid = doc.getIdentity();

    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    Assert.assertTrue(bag.contains(new RecordId("#77:3")));
    Assert.assertTrue(bag.contains(new RecordId("#77:4")));
    Assert.assertTrue(bag.contains(new RecordId("#77:5")));

    Assert.assertFalse(bag.contains(new RecordId("#77:2")));
    Assert.assertFalse(bag.contains(new RecordId("#77:6")));
    Assert.assertFalse(bag.contains(new RecordId("#77:1")));
    Assert.assertFalse(bag.contains(new RecordId("#77:0")));

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
  }

  public void testAddRemoveSBTreeContainsValues() {
    var bag = new RidBag(session);

    bag.add(new RecordId("#77:2"));
    bag.add(new RecordId("#77:2"));
    bag.add(new RecordId("#77:3"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:5"));
    bag.add(new RecordId("#77:6"));

    assertEmbedded(bag.isEmbedded());

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);
    session.begin();

    session.commit();

    RID rid = doc.getIdentity();

    session.close();

    session = createSessionInstance();
    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    bag.remove(new RecordId("#77:1"));
    bag.remove(new RecordId("#77:2"));
    bag.remove(new RecordId("#77:2"));
    bag.remove(new RecordId("#77:4"));
    bag.remove(new RecordId("#77:6"));

    final List<Identifiable> rids = new ArrayList<>();
    rids.add(new RecordId("#77:3"));
    rids.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:5"));

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    doc = ((EntityImpl) session.newEntity());
    var otherBag = new RidBag(session);
    for (var id : bag) {
      otherBag.add(id);
    }

    assertEmbedded(otherBag.isEmbedded());
    doc.setProperty("ridbag", otherBag);

    session.commit();

    rid = doc.getIdentity();

    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
  }

  public void testAddRemoveDuringIterationSBTreeContainsValues() {
    session.begin();
    var bag = new RidBag(session);
    assertEmbedded(bag.isEmbedded());

    bag.add(new RecordId("#77:2"));
    bag.add(new RecordId("#77:2"));
    bag.add(new RecordId("#77:3"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:4"));
    bag.add(new RecordId("#77:5"));
    bag.add(new RecordId("#77:6"));
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

    bag.remove(new RecordId("#77:1"));
    bag.remove(new RecordId("#77:2"));
    bag.remove(new RecordId("#77:2"));
    bag.remove(new RecordId("#77:4"));
    bag.remove(new RecordId("#77:6"));
    assertEmbedded(bag.isEmbedded());

    final List<Identifiable> rids = new ArrayList<>();
    rids.add(new RecordId("#77:3"));
    rids.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:5"));

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
      if (identifiable.equals(new RecordId("#77:4"))) {
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

    final var otherBag = new RidBag(session);
    for (var id : bag) {
      otherBag.add(id);
    }

    assertEmbedded(otherBag.isEmbedded());
    doc.setProperty("ridbag", otherBag);

    session.commit();

    rid = doc.getIdentity();

    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
  }

  public void testEmptyIterator() {
    var bag = new RidBag(session);
    assertEmbedded(bag.isEmbedded());
    assertEquals(bag.size(), 0);

    for (@SuppressWarnings("unused") Identifiable id : bag) {
      Assert.fail();
    }
  }

  public void testAddRemoveNotExisting() {
    session.begin();
    List<Identifiable> rids = new ArrayList<>();

    var bag = new RidBag(session);
    assertEmbedded(bag.isEmbedded());

    bag.add(new RecordId("#77:2"));
    rids.add(new RecordId("#77:2"));

    bag.add(new RecordId("#77:2"));
    rids.add(new RecordId("#77:2"));

    bag.add(new RecordId("#77:3"));
    rids.add(new RecordId("#77:3"));

    bag.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));

    bag.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));

    bag.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));

    bag.add(new RecordId("#77:5"));
    rids.add(new RecordId("#77:5"));

    bag.add(new RecordId("#77:6"));
    rids.add(new RecordId("#77:6"));
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

    bag.add(new RecordId("#77:2"));
    rids.add(new RecordId("#77:2"));

    bag.remove(new RecordId("#77:4"));
    rids.remove(new RecordId("#77:4"));

    bag.remove(new RecordId("#77:4"));
    rids.remove(new RecordId("#77:4"));

    bag.remove(new RecordId("#77:2"));
    rids.remove(new RecordId("#77:2"));

    bag.remove(new RecordId("#77:2"));
    rids.remove(new RecordId("#77:2"));

    bag.remove(new RecordId("#77:7"));
    rids.remove(new RecordId("#77:7"));

    bag.remove(new RecordId("#77:8"));
    rids.remove(new RecordId("#77:8"));

    bag.remove(new RecordId("#77:8"));
    rids.remove(new RecordId("#77:8"));

    bag.remove(new RecordId("#77:8"));
    rids.remove(new RecordId("#77:8"));

    assertEmbedded(bag.isEmbedded());

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    session.commit();

    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (var identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
  }

  public void testContentChange() {
    var entity = ((EntityImpl) session.newEntity());
    var ridBag = new RidBag(session);
    entity.setProperty("ridBag", ridBag);

    session.begin();

    session.commit();

    session.begin();
    entity = session.bindToSession(entity);
    ridBag = entity.getProperty("ridBag");
    ridBag.add(new RecordId("#77:10"));
    Assert.assertTrue(entity.isDirty());

    var expectCME = false;
    final var rec = (RecordAbstract) entity;
    if (rec.isContentChanged()) {
      assertEmbedded(true);
      expectCME = true;
    } else {
      assertEmbedded(false);
    }

    session.commit();

    session.begin();
    var version = entity.getVersion();
    entity = session.bindToSession(entity);
    ridBag = entity.getProperty("ridBag");
    ridBag.add(new RecordId("#77:12"));

    session.commit();

    session.begin();
    entity = session.bindToSession(entity);
    Assert.assertEquals(version == entity.getVersion(), !expectCME);
    session.rollback();
  }

  public void testAddAllAndIterator() {
    final Set<RID> expected = new HashSet<>(8);

    expected.add(new RecordId("#77:12"));
    expected.add(new RecordId("#77:13"));
    expected.add(new RecordId("#77:14"));
    expected.add(new RecordId("#77:15"));
    expected.add(new RecordId("#77:16"));

    var bag = new RidBag(session);

    bag.addAll(expected);
    assertEmbedded(bag.isEmbedded());

    assertEquals(bag.size(), 5);

    Set<Identifiable> actual = new HashSet<>(8);
    for (Identifiable id : bag) {
      actual.add(id);
    }

    assertEquals(actual, expected);
  }

  public void testAddSBTreeAddInMemoryIterate() {
    List<Identifiable> rids = new ArrayList<>();

    var bag = new RidBag(session);
    assertEmbedded(bag.isEmbedded());

    bag.add(new RecordId("#77:2"));
    rids.add(new RecordId("#77:2"));

    bag.add(new RecordId("#77:2"));
    rids.add(new RecordId("#77:2"));

    bag.add(new RecordId("#77:3"));
    rids.add(new RecordId("#77:3"));

    bag.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));

    bag.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));
    assertEmbedded(bag.isEmbedded());

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);

    session.begin();

    session.commit();

    RID rid = doc.getIdentity();

    session.close();

    session = createSessionInstance();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    bag.add(new RecordId("#77:0"));
    rids.add(new RecordId("#77:0"));

    bag.add(new RecordId("#77:1"));
    rids.add(new RecordId("#77:1"));

    bag.add(new RecordId("#77:2"));
    rids.add(new RecordId("#77:2"));

    bag.add(new RecordId("#77:3"));
    rids.add(new RecordId("#77:3"));

    bag.add(new RecordId("#77:5"));
    rids.add(new RecordId("#77:5"));

    bag.add(new RecordId("#77:6"));
    rids.add(new RecordId("#77:6"));

    assertEmbedded(bag.isEmbedded());

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiable : bag) {
      rids.add(identifiable);
    }

    doc = ((EntityImpl) session.newEntity());
    final var otherBag = new RidBag(session);
    for (var id : bag) {
      otherBag.add(id);
    }

    doc.setProperty("ridbag", otherBag);

    session.commit();

    rid = doc.getIdentity();

    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
  }

  public void testCycle() {
    var docOne = ((EntityImpl) session.newEntity());
    var ridBagOne = new RidBag(session);

    var docTwo = ((EntityImpl) session.newEntity());
    var ridBagTwo = new RidBag(session);

    docOne.setProperty("ridBag", ridBagOne);
    docTwo.setProperty("ridBag", ridBagTwo);

    session.begin();

    session.commit();

    session.begin();
    docOne = session.bindToSession(docOne);
    docTwo = session.bindToSession(docTwo);

    ridBagOne = docOne.getProperty("ridBag");
    ridBagOne.add(docTwo.getIdentity());

    ridBagTwo = docTwo.getProperty("ridBag");
    ridBagTwo.add(docOne.getIdentity());

    session.commit();

    docOne = session.load(docOne.getIdentity());
    ridBagOne = docOne.getProperty("ridBag");

    docTwo = session.load(docTwo.getIdentity());
    ridBagTwo = docTwo.getProperty("ridBag");

    Assert.assertEquals(ridBagOne.iterator().next(), docTwo);
    Assert.assertEquals(ridBagTwo.iterator().next(), docOne);
  }

  public void testAddSBTreeAddInMemoryIterateAndRemove() {
    List<Identifiable> rids = new ArrayList<>();

    var bag = new RidBag(session);
    assertEmbedded(bag.isEmbedded());

    bag.add(new RecordId("#77:2"));
    rids.add(new RecordId("#77:2"));

    bag.add(new RecordId("#77:2"));
    rids.add(new RecordId("#77:2"));

    bag.add(new RecordId("#77:3"));
    rids.add(new RecordId("#77:3"));

    bag.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));

    bag.add(new RecordId("#77:4"));
    rids.add(new RecordId("#77:4"));

    bag.add(new RecordId("#77:7"));
    rids.add(new RecordId("#77:7"));

    bag.add(new RecordId("#77:8"));
    rids.add(new RecordId("#77:8"));

    assertEmbedded(bag.isEmbedded());

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridbag", bag);

    session.begin();

    session.commit();

    RID rid = doc.getIdentity();
    session.close();

    session = createSessionInstance();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    bag.add(new RecordId("#77:0"));
    rids.add(new RecordId("#77:0"));

    bag.add(new RecordId("#77:1"));
    rids.add(new RecordId("#77:1"));

    bag.add(new RecordId("#77:2"));
    rids.add(new RecordId("#77:2"));

    bag.add(new RecordId("#77:3"));
    rids.add(new RecordId("#77:3"));

    bag.add(new RecordId("#77:3"));
    rids.add(new RecordId("#77:3"));

    bag.add(new RecordId("#77:5"));
    rids.add(new RecordId("#77:5"));

    bag.add(new RecordId("#77:6"));
    rids.add(new RecordId("#77:6"));

    assertEmbedded(bag.isEmbedded());

    var iterator = bag.iterator();
    var r2c = 0;
    var r3c = 0;
    var r6c = 0;
    var r4c = 0;
    var r7c = 0;

    while (iterator.hasNext()) {
      Identifiable identifiable = iterator.next();
      if (identifiable.equals(new RecordId("#77:2"))) {
        if (r2c < 2) {
          r2c++;
          iterator.remove();
          rids.remove(identifiable);
        }
      }

      if (identifiable.equals(new RecordId("#77:3"))) {
        if (r3c < 1) {
          r3c++;
          iterator.remove();
          rids.remove(identifiable);
        }
      }

      if (identifiable.equals(new RecordId("#77:6"))) {
        if (r6c < 1) {
          r6c++;
          iterator.remove();
          rids.remove(identifiable);
        }
      }

      if (identifiable.equals(new RecordId("#77:4"))) {
        if (r4c < 1) {
          r4c++;
          iterator.remove();
          rids.remove(identifiable);
        }
      }

      if (identifiable.equals(new RecordId("#77:7"))) {
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

    final var otherBag = new RidBag(session);
    for (var id : bag) {
      otherBag.add(id);
    }

    assertEmbedded(otherBag.isEmbedded());

    doc.setProperty("ridbag", otherBag);

    session.commit();

    rid = doc.getIdentity();

    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (Identifiable identifiable : bag) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
  }

  public void testRemove() {
    final Set<RID> expected = new HashSet<>(8);

    expected.add(new RecordId("#77:12"));
    expected.add(new RecordId("#77:13"));
    expected.add(new RecordId("#77:14"));
    expected.add(new RecordId("#77:15"));
    expected.add(new RecordId("#77:16"));

    final var bag = new RidBag(session);
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

    Assert.assertTrue(expectedTwo.isEmpty());

    expected.remove(new RecordId("#77:14"));
    bag.remove(new RecordId("#77:14"));
    assertEmbedded(bag.isEmbedded());

    expectedTwo.addAll(expected);

    for (Identifiable identifiable : bag) {
      assertTrue(expectedTwo.remove(identifiable));
    }
  }

  public void testSaveLoad() {
    Set<RID> expected = new HashSet<>(8);

    expected.add(new RecordId("#77:12"));
    expected.add(new RecordId("#77:13"));
    expected.add(new RecordId("#77:14"));
    expected.add(new RecordId("#77:15"));
    expected.add(new RecordId("#77:16"));
    expected.add(new RecordId("#77:17"));
    expected.add(new RecordId("#77:18"));
    expected.add(new RecordId("#77:19"));
    expected.add(new RecordId("#77:20"));
    expected.add(new RecordId("#77:21"));
    expected.add(new RecordId("#77:22"));

    var doc = ((EntityImpl) session.newEntity());

    final var bag = new RidBag(session);
    bag.addAll(expected);

    doc.setProperty("ridbag", bag);
    assertEmbedded(bag.isEmbedded());

    session.begin();

    session.commit();
    final RID id = doc.getIdentity();

    session.close();

    session = createSessionInstance();

    doc = session.load(id);
    doc.setLazyLoad(false);

    final RidBag loaded = doc.getProperty("ridbag");
    assertEmbedded(loaded.isEmbedded());

    Assert.assertEquals(loaded.size(), expected.size());
    for (var identifiable : loaded) {
      Assert.assertTrue(expected.remove(identifiable));
    }

    Assert.assertTrue(expected.isEmpty());
  }

  public void testSaveInBackOrder() {
    var docA = ((EntityImpl) session.newEntity()).setPropertyInChain("name", "A");

    session.begin();
    var docB =
        ((EntityImpl) session.newEntity())
            .setPropertyInChain("name", "B");

    session.commit();

    var ridBag = new RidBag(session);

    ridBag.add(docA.getIdentity());
    ridBag.add(docB.getIdentity());

    session.begin();

    session.commit();

    ridBag.remove(docB.getIdentity());

    assertEmbedded(ridBag.isEmbedded());

    var result = new HashSet<Identifiable>();

    for (Identifiable oIdentifiable : ridBag) {
      result.add(oIdentifiable);
    }

    Assert.assertTrue(result.contains(docA));
    Assert.assertFalse(result.contains(docB));
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(ridBag.size(), 1);
  }

  public void testMassiveChanges() {
    var document = ((EntityImpl) session.newEntity());
    var bag = new RidBag(session);
    assertEmbedded(bag.isEmbedded());

    final var seed = System.nanoTime();
    System.out.println("testMassiveChanges seed: " + seed);

    var random = new Random(seed);
    List<Identifiable> rids = new ArrayList<>();
    document.setProperty("bag", bag);

    session.begin();

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
    session.bindToSession(document).delete();
    session.commit();
  }

  public void testSimultaneousIterationAndRemove() {
    session.begin();
    var ridBag = new RidBag(session);
    var document = ((EntityImpl) session.newEntity());
    document.setProperty("ridBag", ridBag);
    assertEmbedded(ridBag.isEmbedded());

    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      ridBag.add(docToAdd.getIdentity());
    }

    session.commit();

    session.begin();
    assertEmbedded(ridBag.isEmbedded());
    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");

    Set<Identifiable> docs = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Identifiable id : ridBag) {
      // cache record inside session
      docs.add(id.getRecord(session));
    }

    ridBag = document.getProperty("ridBag");
    assertEmbedded(ridBag.isEmbedded());

    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      session.begin();

      session.commit();

      docs.add(docToAdd);
      ridBag.add(docToAdd.getIdentity());
    }

    assertEmbedded(ridBag.isEmbedded());

    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      session.begin();

      session.commit();

      docs.add(docToAdd);
      ridBag.add(docToAdd.getIdentity());
    }

    assertEmbedded(ridBag.isEmbedded());
    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(docs.remove(identifiable.getRecord(session)));
      ridBag.remove(identifiable.getIdentity());
      Assert.assertEquals(ridBag.size(), docs.size());

      var counter = 0;
      for (Identifiable id : ridBag) {
        Assert.assertTrue(docs.contains(id.getRecord(session)));
        counter++;
      }

      Assert.assertEquals(counter, docs.size());
      assertEmbedded(ridBag.isEmbedded());
    }

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertEquals(ridBag.size(), 0);
    Assert.assertEquals(docs.size(), 0);
    session.rollback();
  }

  public void testAddMixedValues() {
    session.begin();
    var ridBag = new RidBag(session);
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

      document = session.bindToSession(document);
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

      document = session.bindToSession(document);
      ridBag = document.getProperty("ridBag");
      ridBag.add(docToAdd.getIdentity());
      itemsToAdd.add(docToAdd);

      session.commit();
    }

    assertEmbedded(ridBag.isEmbedded());

    session.begin();
    document = session.bindToSession(document);
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
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    assertEmbedded(ridBag.isEmbedded());

    Assert.assertEquals(ridBag.size(), itemsToAdd.size());

    Assert.assertEquals(ridBag.size(), itemsToAdd.size());

    for (Identifiable id : ridBag) {
      Assert.assertTrue(itemsToAdd.remove(id));
    }

    Assert.assertTrue(itemsToAdd.isEmpty());
    session.rollback();
  }

  public void testFromEmbeddedToSBTreeAndBack() throws IOException {
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(7);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(-1);

    if (session.getStorage() instanceof StorageProxy) {
      var server = new ServerAdmin(session.getURL()).connect("root", SERVER_PASSWORD);
      server.setGlobalConfiguration(
          GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD, 7);
      server.setGlobalConfiguration(
          GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD, -1);
      server.close();
    }

    session.begin();
    var ridBag = new RidBag(session);
    var document = ((EntityImpl) session.newEntity());
    document.setProperty("ridBag", ridBag);

    Assert.assertTrue(ridBag.isEmbedded());

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertTrue(ridBag.isEmbedded());

    List<RID> addedItems = new ArrayList<>();
    for (var i = 0; i < 6; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      session.begin();

      session.commit();

      ridBag = document.getProperty("ridBag");
      ridBag.add(docToAdd.getIdentity());
      addedItems.add(docToAdd.getIdentity());
    }

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertTrue(ridBag.isEmbedded());
    session.rollback();

    session.begin();
    var docToAdd = ((EntityImpl) session.newEntity());

    session.commit();
    session.begin();

    docToAdd = session.bindToSession(docToAdd);
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    ridBag.add(docToAdd.getIdentity());
    addedItems.add(docToAdd.getIdentity());

    session.commit();

    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    List<RID> addedItemsCopy = new ArrayList<>(addedItems);
    for (Identifiable id : ridBag) {
      Assert.assertTrue(addedItems.remove(id.getIdentity()));
    }

    Assert.assertTrue(addedItems.isEmpty());

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (Identifiable id : ridBag) {
      Assert.assertTrue(addedItems.remove(id.getIdentity()));
    }

    Assert.assertTrue(addedItems.isEmpty());

    addedItems.addAll(addedItemsCopy);

    for (var i = 0; i < 3; i++) {
      ridBag.remove(addedItems.remove(i).getIdentity());
    }

    addedItemsCopy.clear();
    addedItemsCopy.addAll(addedItems);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    for (var id : ridBag) {
      Assert.assertTrue(addedItems.remove(id));
    }

    Assert.assertTrue(addedItems.isEmpty());

    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (var id : ridBag) {
      Assert.assertTrue(addedItems.remove(id));
    }

    Assert.assertTrue(addedItems.isEmpty());
    session.rollback();
  }

  public void testFromEmbeddedToSBTreeAndBackTx() throws IOException {
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(7);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(-1);

    if (session.isRemote()) {
      var server = new ServerAdmin(session.getURL()).connect("root", SERVER_PASSWORD);
      server.setGlobalConfiguration(
          GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD, 7);
      server.setGlobalConfiguration(
          GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD, -1);
      server.close();
    }

    var ridBag = new RidBag(session);
    var document = ((EntityImpl) session.newEntity());
    document.setProperty("ridBag", ridBag);

    Assert.assertTrue(ridBag.isEmbedded());
    session.begin();

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertTrue(ridBag.isEmbedded());

    List<Identifiable> addedItems = new ArrayList<>();

    ridBag = document.getProperty("ridBag");
    for (var i = 0; i < 6; i++) {

      var docToAdd = ((EntityImpl) session.newEntity());

      ridBag.add(docToAdd.getIdentity());
      addedItems.add(docToAdd);
    }

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertTrue(ridBag.isEmbedded());

    var docToAdd = ((EntityImpl) session.newEntity());

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    docToAdd = session.bindToSession(docToAdd);

    ridBag = document.getProperty("ridBag");
    ridBag.add(docToAdd.getIdentity());
    addedItems.add(docToAdd);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    List<Identifiable> addedItemsCopy = new ArrayList<>(addedItems);
    for (Identifiable id : ridBag) {
      Assert.assertTrue(addedItems.remove(id));
    }

    Assert.assertTrue(addedItems.isEmpty());

    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (Identifiable id : ridBag) {
      Assert.assertTrue(addedItems.remove(id));
    }

    Assert.assertTrue(addedItems.isEmpty());

    addedItems.addAll(addedItemsCopy);

    for (var i = 0; i < 3; i++) {
      ridBag.remove(addedItems.remove(i).getIdentity());
    }

    addedItemsCopy.clear();
    addedItemsCopy.addAll(addedItems);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    for (Identifiable id : ridBag) {
      Assert.assertTrue(addedItems.remove(id));
    }

    Assert.assertTrue(addedItems.isEmpty());

    ridBag = document.getProperty("ridBag");
    Assert.assertFalse(ridBag.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (Identifiable id : ridBag) {
      Assert.assertTrue(addedItems.remove(id));
    }

    Assert.assertTrue(addedItems.isEmpty());
    session.rollback();
  }

  public void testRemoveSavedInCommit() {
    session.begin();
    List<Identifiable> docsToAdd = new ArrayList<>();

    var ridBag = new RidBag(session);
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
    document = session.bindToSession(document);
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
      EntityImpl docToAdd = docsToAdd.get(i).getRecord(session);

    }

    Iterator<Identifiable> iterator = docsToAdd.listIterator(7);
    while (iterator.hasNext()) {
      var docToAdd = iterator.next();
      ridBag.remove(docToAdd.getIdentity());
      iterator.remove();
    }

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    assertEmbedded(ridBag.isEmbedded());

    List<Identifiable> docsToAddCopy = new ArrayList<>(docsToAdd);
    for (Identifiable id : ridBag) {
      Assert.assertTrue(docsToAdd.remove(id));
    }

    Assert.assertTrue(docsToAdd.isEmpty());

    docsToAdd.addAll(docsToAddCopy);

    ridBag = document.getProperty("ridBag");

    for (Identifiable id : ridBag) {
      Assert.assertTrue(docsToAdd.remove(id));
    }

    Assert.assertTrue(docsToAdd.isEmpty());
    session.rollback();
  }

  @Test
  public void testSizeNotChangeAfterRemoveNotExistentElement() {
    final var bob = ((EntityImpl) session.newEntity());

    session.begin();
    final var fred = ((EntityImpl) session.newEntity());

    final var jim =
        ((EntityImpl) session.newEntity());

    session.commit();

    var teamMates = new RidBag(session);

    teamMates.add(bob.getIdentity());
    teamMates.add(fred.getIdentity());

    Assert.assertEquals(teamMates.size(), 2);

    teamMates.remove(jim.getIdentity());

    Assert.assertEquals(teamMates.size(), 2);
  }

  @Test
  public void testRemoveNotExistentElementAndAddIt() {
    var teamMates = new RidBag(session);

    session.begin();
    final var bob = ((EntityImpl) session.newEntity());

    session.commit();

    teamMates.remove(bob.getIdentity());

    Assert.assertEquals(teamMates.size(), 0);

    teamMates.add(bob.getIdentity());

    Assert.assertEquals(teamMates.size(), 1);
    Assert.assertEquals(teamMates.iterator().next().getIdentity(), bob.getIdentity());
  }

  public void testAddNewItemsAndRemoveThem() {
    session.begin();
    final List<Identifiable> rids = new ArrayList<>();
    var ridBag = new RidBag(session);
    var size = 0;
    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      for (var k = 0; k < 2; k++) {
        ridBag.add(docToAdd.getIdentity());
        rids.add(docToAdd);
        size++;
      }
    }

    Assert.assertEquals(ridBag.size(), size);
    var document = ((EntityImpl) session.newEntity());
    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.getProperty("ridBag");
    Assert.assertEquals(ridBag.size(), size);

    final List<Identifiable> newDocs = new ArrayList<>();
    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      for (var k = 0; k < 2; k++) {
        ridBag.add(docToAdd.getIdentity());
        rids.add(docToAdd);
        newDocs.add(docToAdd);
        size++;
      }
    }

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertEquals(ridBag.size(), size);

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

    for (Identifiable identifiable : ridBag) {
      if (newDocs.contains(identifiable) && rnd.nextBoolean()) {
        ridBag.remove(identifiable.getIdentity());
        rids.remove(identifiable);

        size--;
      }
    }

    Assert.assertEquals(ridBag.size(), size);
    List<Identifiable> ridsCopy = new ArrayList<>(rids);

    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(rids.remove(identifiable));
    }

    Assert.assertTrue(rids.isEmpty());

    session.commit();

    document = session.load(document.getIdentity());
    ridBag = document.getProperty("ridBag");

    rids.addAll(ridsCopy);
    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(rids.remove(identifiable));
    }

    Assert.assertTrue(rids.isEmpty());
    Assert.assertEquals(ridBag.size(), size);
  }

  @Test
  public void testJsonSerialization() {
    session.begin();
    final var externalDoc = ((EntityImpl) session.newEntity());

    final var highLevelRidBag = new RidBag(session);

    for (var i = 0; i < 10; i++) {
      var doc = ((EntityImpl) session.newEntity());

      highLevelRidBag.add(doc.getIdentity());
    }

    var testDocument = ((EntityImpl) session.newEntity());
    testDocument.setProperty("type", "testDocument");
    testDocument.setProperty("ridBag", highLevelRidBag);
    testDocument.setProperty("externalDoc", externalDoc);

    session.commit();

    session.begin();

    testDocument = session.bindToSession(testDocument);
    final var json = testDocument.toJSON();

    final var doc = ((EntityImpl) session.newEntity());
    doc.updateFromJSON(json);

    Assert.assertTrue(
        EntityHelper.hasSameContentOf(doc, session, testDocument, session, null));
    session.rollback();
  }

  protected abstract void assertEmbedded(boolean isEmbedded);

  private static void massiveInsertionIteration(Random rnd, List<Identifiable> rids,
      RidBag bag) {
    var bagIterator = bag.iterator();

    while (bagIterator.hasNext()) {
      Identifiable bagValue = bagIterator.next();
      Assert.assertTrue(rids.contains(bagValue));
    }

    Assert.assertEquals(bag.size(), rids.size());

    for (var i = 0; i < 100; i++) {
      if (rnd.nextDouble() < 0.2 & rids.size() > 5) {
        final var index = rnd.nextInt(rids.size());
        final var rid = rids.remove(index);
        bag.remove(rid.getIdentity());
      } else {
        final long position;
        position = rnd.nextInt(300);

        final var recordId = new RecordId(1, position);
        rids.add(recordId);
        bag.add(recordId);
      }
    }

    bagIterator = bag.iterator();

    while (bagIterator.hasNext()) {
      final Identifiable bagValue = bagIterator.next();
      Assert.assertTrue(rids.contains(bagValue));

      if (rnd.nextDouble() < 0.05) {
        bagIterator.remove();
        Assert.assertTrue(rids.remove(bagValue));
      }
    }

    Assert.assertEquals(bag.size(), rids.size());
    bagIterator = bag.iterator();

    while (bagIterator.hasNext()) {
      final Identifiable bagValue = bagIterator.next();
      Assert.assertTrue(rids.contains(bagValue));
    }
  }
}
