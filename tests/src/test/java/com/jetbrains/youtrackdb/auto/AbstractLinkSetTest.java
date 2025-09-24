package com.jetbrains.youtrackdb.auto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.common.query.collection.links.LinkSet;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Random;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

public abstract class AbstractLinkSetTest extends BaseDBTest {

  @Test
  public void testAdd() {
    session.begin();
    var set = (EntityLinkSetImpl) session.newLinkSet();

    set.add(RecordIdInternal.fromString("#77:1", false));
    assertTrue(set.contains(RecordIdInternal.fromString("#77:1", false)));
    Assert.assertFalse(set.contains(RecordIdInternal.fromString("#78:2", false)));

    var iterator = set.iterator();
    assertTrue(iterator.hasNext());

    var identifiable = iterator.next();
    assertEquals(identifiable, RecordIdInternal.fromString("#77:1", false));
    Assert.assertFalse(iterator.hasNext());
    assertIsEmbedded(set);
    session.commit();
  }

  protected abstract void assertIsEmbedded(LinkSet set);

  @Test
  public void testAdd2() {
    session.begin();
    var set = (EntityLinkSetImpl) session.newLinkSet();

    set.add(RecordIdInternal.fromString("#77:2", false));
    set.add(RecordIdInternal.fromString("#77:2", false));

    assertTrue(set.contains(RecordIdInternal.fromString("#77:2", false)));
    Assert.assertFalse(set.contains(RecordIdInternal.fromString("#77:3", false)));

    assertEquals(set.size(), 1);
    assertIsEmbedded(set);
    session.commit();
  }

  @Test
  public void testAdd3() {
    var pair = session.computeInTx(transaction -> {
      var entityHolder = transaction.newEntity();
      var linkSet = transaction.newLinkSet();

      entityHolder.setLinkSet("linkSet", linkSet);

      var rids = new HashSet<RID>();
      for (var i = 0; i < 100; i++) {
        var id = transaction.newEntity().getIdentity();
        rids.add(id);
        linkSet.add(id);
      }

      return new RawPair<Set<RID>, Entity>(rids, entityHolder);
    });

    //reinit changed rids after commit
    var set = new HashSet<>(pair.first());
    var entity = pair.second();

    session.executeInTx(transaction -> {
      var entityHolder = transaction.loadEntity(entity);
      var linkSet = entityHolder.getLinkSet("linkSet");
      linkSet.addAll(set);
    });

    session.executeInTx(transaction -> {
      var entityHolder = transaction.loadEntity(entity);
      var linkSet = entityHolder.getLinkSet("linkSet");
      linkSet.addAll(set);
    });

    session.executeInTx(transaction -> {
      var entityHolder = transaction.loadEntity(entity);
      var linkSet = entityHolder.getLinkSet("linkSet");
      for (var rid : linkSet) {
        assertTrue(set.remove(rid.getIdentity()));
      }
      Assert.assertTrue(set.isEmpty());
    });
  }

  @Test
  @SuppressWarnings("OverwrittenKey")
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

    var set = (EntityLinkSetImpl) session.newLinkSet();

    set.add(id2);
    set.add(id2);
    set.add(id3);
    set.add(id4);
    set.add(id4);
    set.add(id4);
    set.add(id5);
    set.add(id6);

    var counter = 0;
    var iterator = set.iterator();

    set.remove(id2);
    while (iterator.hasNext()) {
      counter++;

      if (counter == 1) {
        set.remove(id1);
        set.remove(id2);
      }

      if (counter == 3) {
        set.remove(id4);
      }

      if (counter == 4) {
        set.remove(id6);
      }

      iterator.next();
    }

    assertTrue(set.contains(id3));
    assertTrue(set.contains(id5));

    Assert.assertFalse(set.contains(id2));
    Assert.assertFalse(set.contains(id4));
    Assert.assertFalse(set.contains(id6));
    Assert.assertFalse(set.contains(id1));
    Assert.assertFalse(set.contains(id0));

    assertIsEmbedded(set);

    var rids = new HashSet<Identifiable>();
    rids.add(id3);
    rids.add(id5);

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    rids.addAll(set);

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertIsEmbedded(set);

    assertTrue(set.contains(id3));
    assertTrue(set.contains(id5));

    Assert.assertFalse(set.contains(id2));
    Assert.assertFalse(set.contains(id4));
    Assert.assertFalse(set.contains(id6));
    Assert.assertFalse(set.contains(id1));
    Assert.assertFalse(set.contains(id0));

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  @Test
  @SuppressWarnings("OverwrittenKey")
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
    var set = (EntityLinkSetImpl) session.newLinkSet();

    set.add(id2);
    set.add(id2);
    set.add(id3);
    set.add(id4);
    set.add(id4);
    set.add(id4);
    set.add(id5);
    set.add(id6);

    set.remove(id1);
    set.remove(id2);
    set.remove(id2);
    set.remove(id4);
    set.remove(id6);

    assertTrue(set.contains(id3));
    assertTrue(set.contains(id5));

    Assert.assertFalse(set.contains(id2));
    Assert.assertFalse(set.contains(id4));
    Assert.assertFalse(set.contains(id6));
    Assert.assertFalse(set.contains(id1));
    Assert.assertFalse(set.contains(id0));

    assertIsEmbedded(set);

    var rids = new HashSet<Identifiable>();
    rids.add(id3);
    rids.add(id5);

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    rids.addAll(set);

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertIsEmbedded(set);

    assertTrue(set.contains(id3));
    assertTrue(set.contains(id5));

    Assert.assertFalse(set.contains(id2));
    Assert.assertFalse(set.contains(id4));
    Assert.assertFalse(set.contains(id6));
    Assert.assertFalse(set.contains(id1));
    Assert.assertFalse(set.contains(id0));

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  @Test
  @SuppressWarnings("OverwrittenKey")
  public void testAddRemoveContainsValues() {
    session.begin();

    var id1 = session.newEntity().getIdentity();
    var id2 = session.newEntity().getIdentity();
    var id3 = session.newEntity().getIdentity();
    var id4 = session.newEntity().getIdentity();
    var id5 = session.newEntity().getIdentity();
    var id6 = session.newEntity().getIdentity();
    session.commit();

    session.begin();
    var set = (EntityLinkSetImpl) session.newLinkSet();

    set.add(id2);
    set.add(id2);

    set.add(id3);

    set.add(id4);
    set.add(id4);
    set.add(id4);

    set.add(id5);

    set.add(id6);

    assertIsEmbedded(set);

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();

    session.close();

    session = createSessionInstance();
    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertIsEmbedded(set);

    set.remove(id1);

    set.remove(id2);
    set.remove(id2);

    set.remove(id4);

    set.remove(id6);

    var rids = new HashSet<Identifiable>();
    rids.add(id3);
    rids.add(id5);

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    rids.addAll(set);

    entity = session.newEntity();
    var otherSet = (EntityLinkSetImpl) session.newLinkSet();
    otherSet.addAll(set);

    assertIsEmbedded(otherSet);
    entity.setProperty("linkset", otherSet);

    session.commit();

    rid = entity.getIdentity();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertIsEmbedded(set);

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  @Test
  @SuppressWarnings("OverwrittenKey")
  public void testAddRemoveDuringIterationContainsValues() {
    session.begin();

    var id1 = session.newEntity().getIdentity();
    var id2 = session.newEntity().getIdentity();
    var id3 = session.newEntity().getIdentity();
    var id4 = session.newEntity().getIdentity();
    var id5 = session.newEntity().getIdentity();
    var id6 = session.newEntity().getIdentity();

    session.commit();
    session.begin();
    var set = (EntityLinkSetImpl) session.newLinkSet();
    assertIsEmbedded(set);

    set.add(id2);
    set.add(id2);

    set.add(id3);

    set.add(id4);
    set.add(id4);
    set.add(id4);

    set.add(id5);

    set.add(id6);
    assertIsEmbedded(set);

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();
    session.close();

    session = createSessionInstance();
    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertIsEmbedded(set);

    set.remove(id1);

    set.remove(id2);
    set.remove(id2);

    set.remove(id4);

    set.remove(id6);
    assertIsEmbedded(set);

    var rids = new HashSet<Identifiable>();
    rids.add(id3);
    rids.add(id5);

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    rids.addAll(set);

    var iterator = set.iterator();
    while (iterator.hasNext()) {
      final var identifiable = iterator.next();
      if (identifiable.equals(id5)) {
        iterator.remove();
        assertTrue(rids.remove(identifiable));
      }
    }

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    rids.addAll(set);

    assertIsEmbedded(set);
    entity = session.newEntity();

    final var otherSet = (EntityLinkSetImpl) session.newLinkSet();
    otherSet.addAll(set);

    assertIsEmbedded(otherSet);
    entity.setProperty("linkset", otherSet);

    session.commit();

    rid = entity.getIdentity();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertIsEmbedded(set);

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  @Test
  public void testEmptyIterator() {
    session.begin();
    var set = (EntityLinkSetImpl) session.newLinkSet();
    assertIsEmbedded(set);
    assertEquals(set.size(), 0);

    for (@SuppressWarnings("unused") var id : set) {
      Assert.fail();
    }
    session.commit();
  }

  @Test
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
    var rids = new HashSet<RID>();

    var set = (EntityLinkSetImpl) session.newLinkSet();
    assertIsEmbedded(set);

    set.add(id2);
    rids.add(id2);

    set.add(id2);
    rids.add(id2);

    set.add(id3);
    rids.add(id3);

    set.add(id4);
    rids.add(id4);

    set.add(id4);
    rids.add(id4);

    set.add(id4);
    rids.add(id4);

    set.add(id5);
    rids.add(id5);

    set.add(id6);
    rids.add(id6);
    assertIsEmbedded(set);

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();

    session.close();

    session = createSessionInstance();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertIsEmbedded(set);

    set.add(id2);
    rids.add(id2);

    set.remove(id4);
    rids.remove(id4);

    set.remove(id4);
    rids.remove(id4);

    set.remove(id2);
    rids.remove(id2);

    set.remove(id2);
    rids.remove(id2);

    set.remove(id7);
    rids.remove(id7);

    set.remove(id8);
    rids.remove(id8);

    set.remove(id8);
    rids.remove(id8);

    set.remove(id8);
    rids.remove(id8);

    assertIsEmbedded(set);

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable.getIdentity()));
    }

    assertTrue(rids.isEmpty());

    for (var identifiable : set) {
      rids.add(identifiable.getIdentity());
    }

    session.commit();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertIsEmbedded(set);

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable.getIdentity()));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  @Test
  public void testContentChange() {
    session.begin();
    var entity = session.newEntity();
    var set = session.newLinkSet();
    entity.setLinkSet("linkset", set);

    session.commit();

    session.begin();
    var id10 = session.newEntity().getIdentity();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    set = entity.getLinkSet("linkset");
    set.add(id10);
    assertTrue(entity.isDirty());
    session.commit();

    session.begin();
    var id12 = session.newEntity().getIdentity();
    var version = entity.getVersion();
    activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    set = entity.getProperty("linkset");
    set.add(id12);
    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    assertNotEquals(entity.getVersion(), version);
    session.commit();
  }

  @Test
  public void testAddAllAndIterator() {
    session.begin();
    final Set<RID> expected = new HashSet<>(8);

    expected.add(RecordIdInternal.fromString("#77:12", false));
    expected.add(RecordIdInternal.fromString("#77:13", false));
    expected.add(RecordIdInternal.fromString("#77:14", false));
    expected.add(RecordIdInternal.fromString("#77:15", false));
    expected.add(RecordIdInternal.fromString("#77:16", false));

    var set = (EntityLinkSetImpl) session.newLinkSet();

    set.addAll(expected);
    assertIsEmbedded(set);

    assertEquals(set.size(), 5);

    Set<Identifiable> actual = new HashSet<>(5);
    actual.addAll(set);

    assertEquals(actual, expected);
    session.commit();
  }

  @Test
  public void testAddAndIterate() {
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
    var rids = new HashSet<Identifiable>();

    var set = (EntityLinkSetImpl) session.newLinkSet();
    assertIsEmbedded(set);

    set.add(id2);
    rids.add(id2);

    set.add(id2);
    rids.add(id2);

    set.add(id3);
    rids.add(id3);

    set.add(id4);
    rids.add(id4);

    set.add(id4);
    rids.add(id4);
    assertIsEmbedded(set);

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();
    session.close();

    session = createSessionInstance();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertIsEmbedded(set);

    set.add(id0);
    rids.add(id0);

    set.add(id1);
    rids.add(id1);

    set.add(id2);
    rids.add(id2);

    set.add(id3);
    rids.add(id3);

    set.add(id5);
    rids.add(id5);

    set.add(id6);
    rids.add(id6);

    assertIsEmbedded(set);

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    rids.addAll(set);

    entity = session.newEntity();
    final var otherSet = (EntityLinkSetImpl) session.newLinkSet();
    otherSet.addAll(set);

    entity.setProperty("linkSet", otherSet);

    session.commit();

    rid = entity.getIdentity();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkSet");
    assertIsEmbedded(set);

    for (var entry : set) {
      assertTrue(rids.remove(entry));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  @Test
  public void testCycle() {
    session.begin();
    var entityOne = session.newEntity();
    var linkSetOne = (EntityLinkSetImpl) session.newLinkSet();

    var entityTwo = session.newEntity();
    var linkSetTwo = (EntityLinkSetImpl) session.newLinkSet();

    entityOne.setProperty("linkSet", linkSetOne);
    entityTwo.setProperty("linkSet", linkSetTwo);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    entityOne = activeTx.load(entityOne);
    entityTwo = activeTx.load(entityTwo);

    linkSetOne = entityOne.getProperty("linkSet");
    linkSetOne.add(entityTwo.getIdentity());

    linkSetTwo = entityTwo.getProperty("linkSet");
    linkSetTwo.add(entityOne.getIdentity());

    session.commit();

    activeTx = session.begin();
    entityOne = activeTx.load(entityOne.getIdentity());
    linkSetOne = entityOne.getProperty("linkSet");

    entityTwo = activeTx.load(entityTwo.getIdentity());
    linkSetTwo = entityTwo.getProperty("linkSet");

    assertEquals(linkSetOne.iterator().next(), entityTwo);
    assertEquals(linkSetTwo.iterator().next(), entityOne);
    activeTx.commit();
  }

  @Test
  public void testAddIterateAndRemove() {
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
    var rids = new HashSet<Identifiable>();

    var set = (EntityLinkSetImpl) session.newLinkSet();
    assertIsEmbedded(set);

    set.add(id2);
    rids.add(id2);

    set.add(id2);
    rids.add(id2);

    set.add(id3);
    rids.add(id3);

    set.add(id4);
    rids.add(id4);

    set.add(id4);
    rids.add(id4);

    set.add(id7);
    rids.add(id7);

    set.add(id8);
    rids.add(id8);

    assertIsEmbedded(set);

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();
    session.close();

    session = createSessionInstance();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertIsEmbedded(set);

    set.add(id0);
    rids.add(id0);

    set.add(id1);
    rids.add(id1);

    set.add(id2);
    rids.add(id2);

    set.add(id3);
    rids.add(id3);

    set.add(id3);
    rids.add(id3);

    set.add(id5);
    rids.add(id5);

    set.add(id6);
    rids.add(id6);

    assertIsEmbedded(set);

    var iterator = set.iterator();

    while (iterator.hasNext()) {
      var identifiable = iterator.next();
      if (identifiable.equals(id2)) {
        iterator.remove();
        rids.remove(identifiable);
      }

      if (identifiable.equals(id3)) {
        iterator.remove();
        rids.remove(identifiable);
      }

      if (identifiable.equals(id6)) {
        iterator.remove();
        rids.remove(identifiable);
      }

      if (identifiable.equals(id4)) {
        iterator.remove();
        rids.remove(identifiable);
      }

      if (identifiable.equals(id7)) {
        iterator.remove();
        rids.remove(identifiable);
      }
    }

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());

    rids.addAll(set);

    entity = session.newEntity();

    final var otherSet = (EntityLinkSetImpl) session.newLinkSet();
    otherSet.addAll(set);

    assertIsEmbedded(otherSet);

    entity.setLinkSet("linkset", otherSet);

    session.commit();

    rid = entity.getIdentity();

    session.begin();
    entity = session.load(rid);

    set = (EntityLinkSetImpl) entity.getLinkSet("linkset");
    assertIsEmbedded(set);

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  @Test
  public void testRemove() {
    final var expected = new HashSet<RID>(8);

    expected.add(RecordIdInternal.fromString("#77:12", false));
    expected.add(RecordIdInternal.fromString("#77:13", false));
    expected.add(RecordIdInternal.fromString("#77:14", false));
    expected.add(RecordIdInternal.fromString("#77:15", false));
    expected.add(RecordIdInternal.fromString("#77:16", false));

    final var set = (EntityLinkSetImpl) session.newLinkSet();
    assertIsEmbedded(set);
    set.addAll(expected);
    assertIsEmbedded(set);

    set.remove(RecordIdInternal.fromString("#77:23", false));
    assertIsEmbedded(set);

    final var expectedTwo = new HashSet<Identifiable>(8);
    expectedTwo.addAll(expected);

    for (var identifiable : set) {
      assertTrue(expectedTwo.remove(identifiable));
    }

    assertTrue(expectedTwo.isEmpty());

    expected.remove(RecordIdInternal.fromString("#77:14", false));
    set.remove(RecordIdInternal.fromString("#77:14", false));

    assertIsEmbedded(set);

    expectedTwo.addAll(expected);

    for (var identifiable : set) {
      assertTrue(expectedTwo.remove(identifiable));
    }
  }

  @Test
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
    var expected = new HashSet<RID>(8);

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

    var entity = session.newEntity();

    final var set = (EntityLinkSetImpl) session.newLinkSet();
    set.addAll(expected);

    entity.setLinkSet("linkSet", set);
    assertIsEmbedded(set);

    session.commit();
    final var id = entity.getIdentity();

    session.close();

    session = createSessionInstance();

    session.begin();
    entity = session.load(id);

    final var loaded = (EntityLinkSetImpl) entity.getLinkSet("linkSet");
    assertIsEmbedded(loaded);

    assertEquals(loaded.size(), expected.size());

    for (var identifiable : loaded) {
      assertTrue(expected.remove(identifiable.getIdentity()));
    }

    assertTrue(expected.isEmpty());
    session.commit();
  }

  @Test
  public void testSaveInBackOrder() {
    session.begin();
    var entityA = session.newEntity();
    entityA.setProperty("name", "A");

    var entityB = session.newEntity();
    entityB.setProperty("name", "B");

    session.commit();

    session.begin();
    var set = (EntityLinkSetImpl) session.newLinkSet();
    entityA = session.load(entityA.getIdentity());
    entityB = session.load(entityB.getIdentity());

    set.add(entityA.getIdentity());
    set.add(entityB.getIdentity());

    set.remove(entityB.getIdentity());

    assertIsEmbedded(set);

    var result = new HashSet<>(set);

    assertTrue(result.contains(entityA));
    Assert.assertFalse(result.contains(entityB));
    assertEquals(result.size(), 1);
    assertEquals(set.size(), 1);
    session.commit();
  }

  @Test
  public void testMassiveChanges() {
    session.begin();
    var entity = session.newEntity();
    var set = (EntityLinkSetImpl) session.newLinkSet();
    assertIsEmbedded(set);

    final var seed = System.nanoTime();
    System.out.println("testMassiveChanges seed: " + seed);

    var random = new Random(seed);
    var rids = new HashSet<Identifiable>();
    entity.setLinkSet("linkSet", set);

    session.commit();

    var rid = entity.getIdentity();

    for (var i = 0; i < 10; i++) {
      session.begin();
      entity = session.load(rid);

      set = (EntityLinkSetImpl) entity.getLinkSet("linkSet");
      assertIsEmbedded(set);
      rids = new HashSet<>(rids);

      massiveInsertionIteration(random, rids, set);
      assertIsEmbedded(set);

      session.commit();
    }

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(entity).delete();
    session.commit();
  }

  @Test
  public void testSimultaneousIterationAndRemove() {
    session.begin();
    var linkSet = (EntityLinkSetImpl) session.newLinkSet();
    var entity = session.newEntity();
    entity.setLinkSet("linkSet", linkSet);
    assertIsEmbedded(linkSet);

    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      linkSet.add(docToAdd.getIdentity());
    }

    session.commit();

    assertIsEmbedded(linkSet);

    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    linkSet = entity.getProperty("linkSet");

    var entities = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
    for (var id : linkSet) {
      // cache record inside session
      entities.add(activeTx.load(id));
    }

    linkSet = entity.getProperty("linkSet");
    assertIsEmbedded(linkSet);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var entityToAdd = session.newEntity();

      entities.add(entityToAdd);
      linkSet.add(entityToAdd.getIdentity());
      session.commit();
    }

    assertIsEmbedded(linkSet);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var entityToAdd = session.newEntity();
      entities.add(entityToAdd);
      linkSet.add(entityToAdd.getIdentity());
      session.commit();
    }

    assertIsEmbedded(linkSet);
    for (var identifiable : linkSet) {
      assertTrue(entities.remove(activeTx.loadEntity(identifiable)));
      linkSet.remove(identifiable.getIdentity());
      assertEquals(linkSet.size(), entities.size());

      var counter = 0;
      for (var id : linkSet) {
        var transaction = session.getActiveTransaction();
        assertTrue(entities.contains(transaction.loadEntity(id)));
        counter++;
      }

      assertEquals(counter, entities.size());
      assertIsEmbedded(linkSet);
    }

    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();
    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");
    assertEquals(linkSet.size(), 0);
    assertEquals(entities.size(), 0);
    session.commit();
  }

  @Test
  public void testAddMixedValues() {
    session.begin();
    var linkSet = (EntityLinkSetImpl) session.newLinkSet();
    var entity = session.newEntity();
    entity.setProperty("linkSet", linkSet);
    assertIsEmbedded(linkSet);

    var itemsToAdd = new HashSet<RID>();

    for (var i = 0; i < 10; i++) {
      var entityToAdd = session.newEntity();
      linkSet = entity.getProperty("linkSet");

      for (var k = 0; k < 2; k++) {
        linkSet.add(entityToAdd.getIdentity());
        itemsToAdd.add(entityToAdd.getIdentity());
      }
    }
    session.commit();

    assertIsEmbedded(linkSet);

    for (var i = 0; i < 10; i++) {
      session.begin();
      var entityToAdd = session.newEntity();

      var activeTx = session.getActiveTransaction();
      entity = activeTx.load(entity);
      linkSet = entity.getProperty("linkSet");

      for (var k = 0; k < 2; k++) {
        linkSet.add(entityToAdd.getIdentity());
        itemsToAdd.add(entityToAdd.getIdentity());
      }

      session.commit();
    }

    for (var i = 0; i < 10; i++) {
      session.begin();
      var entityToAdd = session.newEntity();

      var activeTx = session.getActiveTransaction();
      entity = activeTx.loadEntity(entity);
      linkSet = entity.getProperty("linkSet");

      linkSet.add(entityToAdd.getIdentity());
      itemsToAdd.add(entityToAdd.getIdentity());

      session.commit();
    }

    assertIsEmbedded(linkSet);

    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    linkSet = entity.getProperty("linkSet");

    for (var i = 0; i < 10; i++) {
      var entitiesToAdd = session.newEntity();

      for (var k = 0; k < 2; k++) {
        linkSet.add(entitiesToAdd.getIdentity());
        itemsToAdd.add(entitiesToAdd.getIdentity());
      }
    }

    for (var i = 0; i < 10; i++) {
      var entityToAdd = session.newEntity();

      linkSet.add(entityToAdd.getIdentity());
      itemsToAdd.add(entityToAdd.getIdentity());
    }

    assertIsEmbedded(linkSet);

    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    linkSet = entity.getProperty("linkSet");

    assertIsEmbedded(linkSet);

    assertEquals(linkSet.size(), itemsToAdd.size());

    itemsToAdd = new HashSet<>(itemsToAdd);

    for (var id : linkSet) {
      assertTrue(itemsToAdd.remove(id.getIdentity()));
    }

    assertTrue(itemsToAdd.isEmpty());
    session.commit();
  }

  @Test
  public void testRemoveSavedInCommit() {
    session.begin();
    var entitiesToAdd = new HashSet<Identifiable>();

    var linkSet = (EntityLinkSetImpl) session.newLinkSet();
    var entity = session.newEntity();
    entity.setProperty("linkSet", linkSet);

    for (var i = 0; i < 5; i++) {
      var entityToAdd = session.newEntity();

      linkSet = entity.getProperty("linkSet");
      linkSet.add(entityToAdd.getIdentity());

      entitiesToAdd.add(entityToAdd.getIdentity());
    }

    session.commit();

    entitiesToAdd = new HashSet<>(entitiesToAdd);

    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");
    assertIsEmbedded(linkSet);

    linkSet = entity.getProperty("linkSet");
    assertIsEmbedded(linkSet);

    linkSet = entity.getProperty("linkSet");
    for (var i = 0; i < 5; i++) {
      var entityToAdd = session.newEntity();
      linkSet.add(entityToAdd.getIdentity());
      entitiesToAdd.add(entityToAdd);
    }

    var iterator = entitiesToAdd.iterator();
    for (var i = 0; i < 7; i++) {
      if (iterator.hasNext()) {
        iterator.next();
      }
    }

    Assert.assertTrue(iterator.hasNext());

    while (iterator.hasNext()) {
      var entryToAdd = iterator.next();
      linkSet.remove(entryToAdd.getIdentity());
      iterator.remove();
    }

    session.commit();

    activeTx = session.begin();
    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");
    assertIsEmbedded(linkSet);

    entitiesToAdd = new HashSet<>(entitiesToAdd);
    var entriesToAddCopy = new HashSet<>(entitiesToAdd);

    for (var id : linkSet) {
      assertTrue(entitiesToAdd.remove(id));
    }

    assertTrue(entitiesToAdd.isEmpty());

    entitiesToAdd.addAll(entriesToAddCopy);

    linkSet = entity.getProperty("linkSet");

    for (var id : linkSet) {
      assertTrue(entitiesToAdd.remove(id));
    }

    assertTrue(entitiesToAdd.isEmpty());
    session.commit();
  }

  @Test
  public void testSizeNotChangeAfterRemoveNotExistentElement() {
    session.begin();
    final var bob = session.newEntity();
    final var fred = session.newEntity();
    final var jim = session.newEntity();
    session.commit();

    session.begin();
    var teamMates = session.newLinkSet();

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
    var teamMates = session.newLinkSet();

    final var bob = session.newEntity();
    session.commit();

    teamMates.remove(bob.getIdentity());

    assertEquals(teamMates.size(), 0);

    teamMates.add(bob.getIdentity());

    assertEquals(teamMates.size(), 1);
    assertEquals(teamMates.iterator().next().getIdentity(), bob.getIdentity());
  }

  @Test
  public void testAddNewItemsAndRemoveThem() {
    session.begin();
    var rids = new HashSet<RID>();
    var linkSet = (EntityLinkSetImpl) session.newLinkSet();

    var size = 0;
    for (var i = 0; i < 10; i++) {
      var entityToAdd = session.newEntity();

      for (var k = 0; k < 2; k++) {
        linkSet.add(entityToAdd.getIdentity());

        if (rids.add(entityToAdd.getIdentity())) {
          size++;
        }
      }
    }

    assertEquals(linkSet.size(), size);
    var entity = session.newEntity();
    entity.setProperty("linkSet", linkSet);

    session.commit();

    rids = new HashSet<>(rids);
    session.begin();
    entity = session.loadEntity(entity.getIdentity());
    linkSet = entity.getProperty("linkSet");
    assertEquals(linkSet.size(), size);

    final var newEntities = new HashSet<RID>();
    for (var i = 0; i < 10; i++) {
      var entityToAdd = session.newEntity();

      for (var k = 0; k < 2; k++) {
        linkSet.add(entityToAdd.getIdentity());

        if (rids.add(entityToAdd.getIdentity())) {
          newEntities.add(entityToAdd.getIdentity());
          size++;
        }
      }
    }

    session.commit();

    rids = new HashSet<>(rids);
    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");
    assertEquals(linkSet.size(), size);

    var rnd = new Random();

    for (var newEntity : newEntities) {
      if (rnd.nextBoolean()) {
        rids.remove(newEntity);
        linkSet.remove(newEntity.getIdentity());
        newEntities.remove(newEntity);
        size--;
      }
    }

    for (var identifiable : linkSet) {
      if (newEntities.contains(identifiable.getIdentity()) && rnd.nextBoolean()) {
        linkSet.remove(identifiable.getIdentity());
        if (rids.remove(identifiable.getIdentity())) {
          size--;
        }
      }
    }

    session.commit();
    session.begin();

    rids = new HashSet<>(rids);
    activeTx = session.getActiveTransaction();
    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");

    assertEquals(linkSet.size(), size);
    var ridsCopy = new HashSet<>(rids);

    for (var identifiable : linkSet) {
      assertTrue(rids.remove(identifiable.getIdentity()));
    }

    assertTrue(rids.isEmpty());

    session.begin();
    entity = session.loadEntity(entity.getIdentity());
    linkSet = entity.getProperty("linkSet");

    rids.addAll(ridsCopy);
    for (var identifiable : linkSet) {
      assertTrue(rids.remove(identifiable.getIdentity()));
    }

    assertTrue(rids.isEmpty());
    assertEquals(linkSet.size(), size);
    session.commit();
  }

  @Test
  public void testJsonSerialization() {
    session.begin();
    final var externalEntity = session.newEntity();

    final var highLevelLinkSet = session.newLinkSet();

    for (var i = 0; i < 10; i++) {
      var entity = session.newEntity();
      highLevelLinkSet.add(entity.getIdentity());
    }

    var testEntity = session.newEntity();
    testEntity.setProperty("type", "testEntity");
    testEntity.setProperty("linkSet", highLevelLinkSet);
    testEntity.setProperty("externalEntity", externalEntity);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    testEntity = activeTx.loadEntity(testEntity);
    final var origContent = testEntity.toMap();
    final var json = testEntity.toJSON("keepTypes,rid,class");

    final var map = session.createOrLoadEntityFromJson(json).toMap();

    origContent.remove(EntityHelper.ATTRIBUTE_RID);
    map.remove(EntityHelper.ATTRIBUTE_RID);

    assertEquals(map.get("@class"), origContent.get("@class"));
    assertEquals(map.get("externalEntity"), origContent.get("externalEntity"));
    assertEquals(map.get("type"), origContent.get("type"));
    assertEquals(
        map.get("linkSet"),
        origContent.get("linkSet")
    );
    session.commit();
  }

  @Test
  public void testRollBackChangesAfterCallback() {
    session.begin();
    final var entity = (EntityImpl) session.newEntity();

    final var originalSet = new HashSet<Identifiable>();
    var entity1 = session.newEntity();
    originalSet.add(entity1);

    var entity2 = session.newEntity();
    originalSet.add(entity2);

    var entity3 = session.newEntity();
    originalSet.add(entity3);

    var entity4 = session.newEntity();
    originalSet.add(entity4);

    var entity5 = session.newEntity();
    originalSet.add(entity5);

    var entitySet = entity.newLinkSet("linkSet");
    entitySet.addAll(originalSet);

    var tx = (FrontendTransactionImpl) session.getTransactionInternal();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    var entity6 = session.newEntity();
    entitySet.add(entity6);

    entitySet.remove(entity2);
    entitySet.remove(entity5);

    var entity7 = session.newEntity();
    entitySet.add(entity7);

    var entity8 = session.newEntity();
    entitySet.add(entity8);

    entitySet.remove(entity7);

    var entity9 = session.newEntity();
    entitySet.add(entity9);

    var entity10 = session.newEntity();
    entitySet.add(entity10);

    ((EntityLinkSetImpl) entitySet).rollbackChanges(tx);

    Assert.assertEquals(originalSet, entitySet);
    session.rollback();
  }

  private void massiveInsertionIteration(Random rnd, Set<Identifiable> rids,
      EntityLinkSetImpl linkSet) {
    var linkSetIterator = linkSet.iterator();

    while (linkSetIterator.hasNext()) {
      var setValue = linkSetIterator.next();
      assertTrue(rids.contains(setValue));
    }

    assertEquals(linkSet.size(), rids.size());

    for (var i = 0; i < 100; i++) {
      if (rnd.nextDouble() < 0.2 & rids.size() > 5) {
        final var index = rnd.nextInt(rids.size());

        RID ridToRemove = null;
        var iter = rids.iterator();
        for (var j = 0; j < index + 1; j++) {
          ridToRemove = iter.next().getIdentity();
        }

        rids.remove(ridToRemove);
        Assert.assertTrue(linkSet.remove(ridToRemove.getIdentity()));
      } else {
        final var recordId = session.newEntity().getIdentity();
        rids.add(recordId);
        linkSet.add(recordId);
      }
    }

    linkSetIterator = linkSet.iterator();

    while (linkSetIterator.hasNext()) {
      final var setValue = linkSetIterator.next();
      assertTrue(rids.contains(setValue));

      if (rnd.nextDouble() < 0.05) {
        linkSetIterator.remove();
        assertTrue(rids.remove(setValue));
      }
    }

    assertEquals(linkSet.size(), rids.size());
    linkSetIterator = linkSet.iterator();

    while (linkSetIterator.hasNext()) {
      final var setValue = linkSetIterator.next();
      assertTrue(rids.contains(setValue));
    }
  }
}
