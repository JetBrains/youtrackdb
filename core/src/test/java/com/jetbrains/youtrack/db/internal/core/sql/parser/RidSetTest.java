package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.sql.executor.RidSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class RidSetTest extends ParserTestAbstract {

  @Test
  public void testPut() {
    var set = new RidSet();
    RID rid = new RecordId(12, 100);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testPut0() {
    var set = new RidSet();
    RID rid = new RecordId(12, 0);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testPut31() {
    var set = new RidSet();
    RID rid = new RecordId(12, 31);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testPut32() {
    var set = new RidSet();
    RID rid = new RecordId(12, 32);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testPut63() {
    var set = new RidSet();
    RID rid = new RecordId(12, 63);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testPut64() {
    var set = new RidSet();
    RID rid = new RecordId(12, 64);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testPut65() {
    var set = new RidSet();
    RID rid = new RecordId(12, 65);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
  }

  @Test
  public void testRemove() {
    var set = new RidSet();
    RID rid = new RecordId(12, 31);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
    set.remove(rid);
    Assert.assertFalse(set.contains(rid));
  }

  @Test
  public void testBigCollectionId() {
    var set = new RidSet();
    RID rid = new RecordId(1200, 100);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
    set.remove(rid);
    Assert.assertFalse(set.contains(rid));
  }

  @Test
  public void testBigCollectionPosition() {
    var set = new RidSet();
    RID rid = new RecordId(12, 200L * 1000 * 1000);
    Assert.assertFalse(set.contains(rid));
    set.add(rid);
    Assert.assertTrue(set.contains(rid));
    set.remove(rid);
    Assert.assertFalse(set.contains(rid));
  }

  @Test
  public void testIterator() {

    Set<RID> set = new RidSet();
    var collections = 100;
    var idsPerCollection = 10;

    for (var collection = 0; collection < collections; collection++) {
      for (long id = 0; id < idsPerCollection; id++) {
        set.add(new RecordId(collection, id));
      }
    }
    var iterator = set.iterator();

    System.out.println("stating");
    var begin = System.currentTimeMillis();
    for (var collection = 0; collection < collections; collection++) {
      for (long id = 0; id < idsPerCollection; id++) {
        Assert.assertTrue(iterator.hasNext());
        var next = iterator.next();
        Assert.assertNotNull(next);
        //        Assert.assertEquals(new RecordId(collection, id), next);
      }
    }
    System.out.println("elapsed: " + (System.currentTimeMillis() - begin));
    Assert.assertFalse(iterator.hasNext());
  }

  @Test
  public void testIteratorOffset() {

    Set<RID> control = new HashSet<>();
    Set<RID> set = new RidSet();

    var offset = (((long) Integer.MAX_VALUE)) * 63;
    long idsPerCollection = 10;

    var collection = 1;
    for (long id = 0; id < idsPerCollection; id++) {
      var rid = new RecordId(collection, offset + id);
      set.add(rid);
      control.add(rid);
    }
    var iterator = set.iterator();

    for (long id = 0; id < idsPerCollection; id++) {
      Assert.assertTrue(iterator.hasNext());
      var next = iterator.next();
      Assert.assertNotNull(next);
      control.remove(next);
    }

    Assert.assertFalse(iterator.hasNext());
    Assert.assertTrue(control.isEmpty());
  }
}
