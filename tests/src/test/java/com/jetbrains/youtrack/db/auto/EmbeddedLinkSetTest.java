package com.jetbrains.youtrack.db.auto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.client.remote.ServerAdmin;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Random;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

public class EmbeddedLinkSetTest extends BaseDBTest {

  private int topThreshold;
  private int bottomThreshold;

  @Parameters(value = "remote")
  public EmbeddedLinkSetTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    topThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(Integer.MAX_VALUE);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(Integer.MAX_VALUE);

    if (session.isRemote()) {
      var server = new ServerAdmin(session.getURL()).connect("root", SERVER_PASSWORD);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD, Integer.MAX_VALUE);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD, Integer.MAX_VALUE);
      server.close();
    }
    super.beforeMethod();
  }

  @AfterMethod
  public void afterMethod() throws Exception {
    super.afterMethod();
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);

    if (session.isRemote()) {
      var server = new ServerAdmin(session.getURL()).connect("root", SERVER_PASSWORD);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD, topThreshold);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD, bottomThreshold);
      server.close();
    }
  }


  public void testAdd() {
    session.begin();
    var set = (EntityLinkSetImpl) session.newLinkSet();

    set.add(new RecordId("#77:1"));
    assertTrue(set.contains(new RecordId("#77:1")));
    Assert.assertFalse(set.contains(new RecordId("#78:2")));

    var iterator = set.iterator();
    assertTrue(iterator.hasNext());

    var identifiable = iterator.next();
    assertEquals(identifiable, new RecordId("#77:1"));
    Assert.assertFalse(iterator.hasNext());
    Assert.assertTrue(set.isEmbedded());
    session.commit();
  }

  public void testAdd2() {
    session.begin();
    var set = (EntityLinkSetImpl) session.newLinkSet();

    set.add(new RecordId("#77:2"));
    set.add(new RecordId("#77:2"));

    assertTrue(set.contains(new RecordId("#77:2")));
    Assert.assertFalse(set.contains(new RecordId("#77:3")));

    assertEquals(set.size(), 1);
    assertTrue(set.isEmbedded());
    session.commit();
  }

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

    assertTrue(set.isEmbedded());

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
    assertTrue(set.isEmbedded());

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

    assertTrue(set.isEmbedded());

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
    assertTrue(set.isEmbedded());

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

    assertTrue(set.isEmbedded());

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();

    session.close();

    session = createSessionInstance();
    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertTrue(set.isEmbedded());

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

    assertTrue(otherSet.isEmbedded());
    entity.setProperty("linkset", otherSet);

    session.commit();

    rid = entity.getIdentity();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertTrue(set.isEmbedded());

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

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
    assertTrue(set.isEmbedded());

    set.add(id2);
    set.add(id2);

    set.add(id3);

    set.add(id4);
    set.add(id4);
    set.add(id4);

    set.add(id5);

    set.add(id6);
    assertTrue(set.isEmbedded());

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();
    session.close();

    session = createSessionInstance();
    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertTrue(set.isEmbedded());

    set.remove(id1);

    set.remove(id2);
    set.remove(id2);

    set.remove(id4);

    set.remove(id6);
    assertTrue(set.isEmbedded());

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

    assertTrue(set.isEmbedded());
    entity = session.newEntity();

    final var otherSet = (EntityLinkSetImpl) session.newLinkSet();
    otherSet.addAll(set);

    assertTrue(otherSet.isEmbedded());
    entity.setProperty("linkset", otherSet);

    session.commit();

    rid = entity.getIdentity();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertTrue(set.isEmbedded());

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  public void testEmptyIterator() {
    session.begin();
    var set = (EntityLinkSetImpl) session.newLinkSet();
    Assert.assertTrue(set.isEmbedded());
    assertEquals(set.size(), 0);

    for (@SuppressWarnings("unused") var id : set) {
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
    var rids = new HashSet<RID>();

    var set = (EntityLinkSetImpl) session.newLinkSet();
    assertTrue(set.isEmbedded());

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
    assertTrue(set.isEmbedded());

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();

    session.close();

    session = createSessionInstance();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertTrue(set.isEmbedded());

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

    assertTrue(set.isEmbedded());

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
    assertTrue(set.isEmbedded());

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable.getIdentity()));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

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

  public void testAddAllAndIterator() {
    session.begin();
    final Set<RID> expected = new HashSet<>(8);

    expected.add(new RecordId("#77:12"));
    expected.add(new RecordId("#77:13"));
    expected.add(new RecordId("#77:14"));
    expected.add(new RecordId("#77:15"));
    expected.add(new RecordId("#77:16"));

    var set = (EntityLinkSetImpl) session.newLinkSet();

    set.addAll(expected);
    assertTrue(set.isEmbedded());

    assertEquals(set.size(), 5);

    Set<Identifiable> actual = new HashSet<>(5);
    actual.addAll(set);

    assertEquals(actual, expected);
    session.commit();
  }

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
    assertTrue(set.isEmbedded());

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
    assertTrue(set.isEmbedded());

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();
    session.close();

    session = createSessionInstance();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertTrue(set.isEmbedded());

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

    assertTrue(set.isEmbedded());

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
    assertTrue(set.isEmbedded());

    for (var entry : set) {
      assertTrue(rids.remove(entry));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

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
    assertTrue(set.isEmbedded());

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

    assertTrue(set.isEmbedded());

    var entity = session.newEntity();
    entity.setProperty("linkset", set);

    session.commit();

    var rid = entity.getIdentity();
    session.close();

    session = createSessionInstance();

    session.begin();
    entity = session.load(rid);

    set = entity.getProperty("linkset");
    assertTrue(set.isEmbedded());

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

    assertTrue(set.isEmbedded());

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

    assertTrue(otherSet.isEmbedded());

    entity.setLinkSet("linkset", otherSet);

    session.commit();

    rid = entity.getIdentity();

    session.begin();
    entity = session.load(rid);

    set = (EntityLinkSetImpl) entity.getLinkSet("linkset");
    assertTrue(set.isEmbedded());

    for (var identifiable : set) {
      assertTrue(rids.remove(identifiable));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  public void testRemove() {
    final var expected = new HashSet<RID>(8);

    expected.add(new RecordId("#77:12"));
    expected.add(new RecordId("#77:13"));
    expected.add(new RecordId("#77:14"));
    expected.add(new RecordId("#77:15"));
    expected.add(new RecordId("#77:16"));

    final var set = (EntityLinkSetImpl) session.newLinkSet();
    assertTrue(set.isEmbedded());
    set.addAll(expected);
    assertTrue(set.isEmbedded());

    set.remove(new RecordId("#77:23"));
    assertTrue(set.isEmbedded());

    final var expectedTwo = new HashSet<Identifiable>(8);
    expectedTwo.addAll(expected);

    for (var identifiable : set) {
      assertTrue(expectedTwo.remove(identifiable));
    }

    assertTrue(expectedTwo.isEmpty());

    expected.remove(new RecordId("#77:14"));
    set.remove(new RecordId("#77:14"));

    assertTrue(set.isEmbedded());

    expectedTwo.addAll(expected);

    for (var identifiable : set) {
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
    assertTrue(set.isEmbedded());

    session.commit();
    final var id = entity.getIdentity();

    session.close();

    session = createSessionInstance();

    session.begin();
    entity = session.load(id);

    final var loaded = (EntityLinkSetImpl) entity.getLinkSet("linkSet");
    assertTrue(loaded.isEmbedded());

    assertEquals(loaded.size(), expected.size());

    for (var identifiable : loaded) {
      assertTrue(expected.remove(identifiable.getIdentity()));
    }

    assertTrue(expected.isEmpty());
    session.commit();
  }

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

    assertTrue(set.isEmbedded());

    var result = new HashSet<>(set);

    assertTrue(result.contains(entityA));
    Assert.assertFalse(result.contains(entityB));
    assertEquals(result.size(), 1);
    assertEquals(set.size(), 1);
    session.commit();
  }

  public void testMassiveChanges() {
    session.begin();
    var entity = session.newEntity();
    var set = (EntityLinkSetImpl) session.newLinkSet();
    assertTrue(set.isEmbedded());

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
      assertTrue(set.isEmbedded());
      rids = new HashSet<>(rids);

      massiveInsertionIteration(random, rids, set);
      assertTrue(set.isEmbedded());

      session.commit();
    }

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(entity).delete();
    session.commit();
  }

  public void testSimultaneousIterationAndRemove() {
    session.begin();
    var linkSet = (EntityLinkSetImpl) session.newLinkSet();
    var entity = session.newEntity();
    entity.setLinkSet("linkSet", linkSet);
    assertTrue(linkSet.isEmbedded());

    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());

      linkSet.add(docToAdd.getIdentity());
    }

    session.commit();

    assertTrue(linkSet.isEmbedded());

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
    assertTrue(linkSet.isEmbedded());

    for (var i = 0; i < 10; i++) {
      session.begin();
      var entityToAdd = session.newEntity();

      entities.add(entityToAdd);
      linkSet.add(entityToAdd.getIdentity());
      session.commit();
    }

    assertTrue(linkSet.isEmbedded());

    for (var i = 0; i < 10; i++) {
      session.begin();
      var entityToAdd = session.newEntity();
      entities.add(entityToAdd);
      linkSet.add(entityToAdd.getIdentity());
      session.commit();
    }

    assertTrue(linkSet.isEmbedded());
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
      assertTrue(linkSet.isEmbedded());
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

  public void testAddMixedValues() {
    session.begin();
    var linkSet = (EntityLinkSetImpl) session.newLinkSet();
    var entity = session.newEntity();
    entity.setProperty("linkSet", linkSet);
    assertTrue(linkSet.isEmbedded());

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

    assertTrue(linkSet.isEmbedded());

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

    assertTrue(linkSet.isEmbedded());

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

    assertTrue(linkSet.isEmbedded());

    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    linkSet = entity.getProperty("linkSet");

    assertTrue(linkSet.isEmbedded());

    assertEquals(linkSet.size(), itemsToAdd.size());

    itemsToAdd = new HashSet<>(itemsToAdd);

    for (var id : linkSet) {
      assertTrue(itemsToAdd.remove(id.getIdentity()));
    }

    assertTrue(itemsToAdd.isEmpty());
    session.commit();
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
