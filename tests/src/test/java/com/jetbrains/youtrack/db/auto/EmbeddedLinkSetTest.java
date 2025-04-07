package com.jetbrains.youtrack.db.auto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

public class EmbeddedLinkSetTest extends BaseDBTest {

  @Parameters(value = "remote")
  public EmbeddedLinkSetTest(@Optional Boolean remote) {
    super(remote != null && remote);
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

    for (var identifiable : set) {
      rids.add(identifiable);
    }

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

    for (var identifiable : set) {
      rids.add(identifiable);
    }

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

}
