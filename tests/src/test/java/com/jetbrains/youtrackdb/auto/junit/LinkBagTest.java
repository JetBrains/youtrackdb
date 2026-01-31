/*
 * JUnit 4 version of LinkBagTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.BulkSet;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Abstract base class for LinkBag implementation tests.
 *
 * <p><b>Suite Dependency:</b> Subclasses ({@link EmbeddedLinkBagTest}, {@link BTreeBasedLinkBagTest})
 * are part of {@link DatabaseTestSuite}. This base class contains common tests for LinkBag
 * operations including add, remove, iteration, and persistence.</p>
 *
 * <p><b>Implementing Subclasses:</b> Must:</p>
 * <ul>
 *   <li>Add a {@code @BeforeClass} method that calls {@code beforeClass()}</li>
 *   <li>Implement {@link #assertEmbedded(boolean)} to verify expected storage mode</li>
 *   <li>Configure {@code GlobalConfiguration} thresholds in {@code @Before/@After} if needed</li>
 * </ul>
 *
 * <p>Original: {@code tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java}</p>
 *
 * @see EmbeddedLinkBagTest
 * @see BTreeBasedLinkBagTest
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public abstract class LinkBagTest extends BaseDBTest {

  /**
   * Must be implemented by subclasses to verify the expected embedded state.
   * Original: assertEmbedded (line 1945)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  protected abstract void assertEmbedded(boolean isEmbedded);

  /**
   * Original: testAdd (line 30)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test01_Add() {
    session.begin();
    var bag = new LinkBag(session);

    bag.add(RecordIdInternal.fromString("#77:1", false));
    assertTrue(bag.contains(RecordIdInternal.fromString("#77:1", false)));
    assertFalse(bag.contains(RecordIdInternal.fromString("#78:2", false)));

    var iterator = bag.iterator();
    assertTrue(iterator.hasNext());

    Identifiable identifiable = iterator.next();
    assertEquals(identifiable, RecordIdInternal.fromString("#77:1", false));
    assertFalse(iterator.hasNext());
    assertEmbedded(bag.isEmbedded());
    session.commit();
  }

  /**
   * Original: testAdd2 (line 48)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test02_Add2() {
    session.begin();
    var bag = new LinkBag(session);

    bag.add(RecordIdInternal.fromString("#77:2", false));
    bag.add(RecordIdInternal.fromString("#77:2", false));

    assertTrue(bag.contains(RecordIdInternal.fromString("#77:2", false)));
    assertFalse(bag.contains(RecordIdInternal.fromString("#77:3", false)));

    assertEquals(2, bag.size());
    assertEmbedded(bag.isEmbedded());
    session.commit();
  }

  /**
   * Original: testAddRemoveInTheMiddleOfIteration (line 63)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test03_AddRemoveInTheMiddleOfIteration() {
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

    assertFalse(bag.contains(id2));
    assertFalse(bag.contains(id6));
    assertFalse(bag.contains(id1));
    assertFalse(bag.contains(id0));

    assertEmbedded(bag.isEmbedded());

    final List<Identifiable> rids = new ArrayList<>();
    rids.add(id3);
    rids.add(id4);
    rids.add(id4);
    rids.add(id5);

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiableItem : bag) {
      rids.add(identifiableItem);
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

    assertFalse(bag.contains(id2));
    assertFalse(bag.contains(id6));
    assertFalse(bag.contains(id1));
    assertFalse(bag.contains(id0));

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  /**
   * Original: testAddRemove (line 168)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test04_AddRemove() {
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

    assertFalse(bag.contains(id2));
    assertFalse(bag.contains(id6));
    assertFalse(bag.contains(id1));
    assertFalse(bag.contains(id0));

    assertEmbedded(bag.isEmbedded());

    final List<Identifiable> rids = new ArrayList<>();
    rids.add(id3);
    rids.add(id4);
    rids.add(id4);
    rids.add(id5);

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiableItem : bag) {
      rids.add(identifiableItem);
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

    assertFalse(bag.contains(id2));
    assertFalse(bag.contains(id6));
    assertFalse(bag.contains(id1));
    assertFalse(bag.contains(id0));

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  /**
   * Original: testAddRemoveSBTreeContainsValues (line 256)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test05_AddRemoveSBTreeContainsValues() {
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

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiableItem : bag) {
      rids.add(identifiableItem);
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

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  /**
   * Original: testAddRemoveDuringIterationSBTreeContainsValues (line 349)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test06_AddRemoveDuringIterationSBTreeContainsValues() {
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

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiableItem : bag) {
      rids.add(identifiableItem);
    }

    var iterator = bag.iterator();
    while (iterator.hasNext()) {
      final var identifiableItem = iterator.next();
      if (identifiableItem.equals(id4)) {
        iterator.remove();
        assertTrue(rids.remove(identifiableItem));
      }
    }

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    for (Identifiable identifiableItem : bag) {
      rids.add(identifiableItem);
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

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  /**
   * Original: testEmptyIterator (line 460)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test07_EmptyIterator() {
    session.begin();
    var bag = new LinkBag(session);
    assertEmbedded(bag.isEmbedded());
    assertEquals(0, bag.size());

    for (@SuppressWarnings("unused") Identifiable id : bag) {
      Assert.fail();
    }
    session.commit();
  }

  /**
   * Original: testAddRemoveNotExisting (line 472)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test08_AddRemoveNotExisting() {
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

    for (var identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());

    for (var identifiableItem : bag) {
      rids.add(identifiableItem);
    }

    session.commit();

    session.begin();
    doc = session.load(rid);
    doc.setLazyLoad(false);

    bag = doc.getProperty("ridbag");
    assertEmbedded(bag.isEmbedded());

    for (var identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  /**
   * Original: testContentChange (line 590)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test09_ContentChange() {
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

  /**
   * Original: testAddAllAndIterator (line 624)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test10_AddAllAndIterator() {
    session.begin();
    final Set<RID> expected = new HashSet<>(8);

    expected.add(RecordIdInternal.fromString("#77:12", false));
    expected.add(RecordIdInternal.fromString("#77:13", false));
    expected.add(RecordIdInternal.fromString("#77:14", false));
    expected.add(RecordIdInternal.fromString("#77:15", false));
    expected.add(RecordIdInternal.fromString("#77:16", false));

    var bag = new LinkBag(session);

    bag.addAll(expected);
    assertEmbedded(bag.isEmbedded());

    assertEquals(5, bag.size());

    Set<Identifiable> actual = new HashSet<>(8);
    for (Identifiable id : bag) {
      actual.add(id);
    }

    assertEquals(actual, expected);
    session.commit();
  }

  /**
   * Original: testAddSBTreeAddInMemoryIterate (line 650)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test11_AddSBTreeAddInMemoryIterate() {
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

    for (var identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());

    for (var identifiableItem : bag) {
      rids.add(identifiableItem);
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

  /**
   * Original: testCycle (line 760)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test12_Cycle() {
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

  /**
   * Original: testAddSBTreeAddInMemoryIterateAndRemove (line 799)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test13_AddSBTreeAddInMemoryIterateAndRemove() {
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
      Identifiable identifiableItem = iterator.next();
      if (identifiableItem.equals(id2)) {
        if (r2c < 2) {
          r2c++;
          iterator.remove();
          rids.remove(identifiableItem);
        }
      }

      if (identifiableItem.equals(id3)) {
        if (r3c < 1) {
          r3c++;
          iterator.remove();
          rids.remove(identifiableItem);
        }
      }

      if (identifiableItem.equals(id6)) {
        if (r6c < 1) {
          r6c++;
          iterator.remove();
          rids.remove(identifiableItem);
        }
      }

      if (identifiableItem.equals(id4)) {
        if (r4c < 1) {
          r4c++;
          iterator.remove();
          rids.remove(identifiableItem);
        }
      }

      if (identifiableItem.equals(id7)) {
        if (r7c < 1) {
          r7c++;
          iterator.remove();
          rids.remove(identifiableItem);
        }
      }
    }

    assertEquals(2, r2c);
    assertEquals(1, r3c);
    assertEquals(1, r6c);
    assertEquals(1, r4c);
    assertEquals(1, r7c);

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());

    for (Identifiable identifiableItem : bag) {
      rids.add(identifiableItem);
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

    for (Identifiable identifiableItem : bag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());
    session.commit();
  }

  /**
   * Original: testRemove (line 978)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test14_Remove() {
    session.begin();
    final Set<RID> expected = new HashSet<>(8);

    expected.add(RecordIdInternal.fromString("#77:12", false));
    expected.add(RecordIdInternal.fromString("#77:13", false));
    expected.add(RecordIdInternal.fromString("#77:14", false));
    expected.add(RecordIdInternal.fromString("#77:15", false));
    expected.add(RecordIdInternal.fromString("#77:16", false));

    final var bag = new LinkBag(session);
    assertEmbedded(bag.isEmbedded());
    bag.addAll(expected);
    assertEmbedded(bag.isEmbedded());

    bag.remove(RecordIdInternal.fromString("#77:23", false));
    assertEmbedded(bag.isEmbedded());

    final Set<Identifiable> expectedTwo = new HashSet<>(8);
    expectedTwo.addAll(expected);

    for (Identifiable identifiableItem : bag) {
      assertTrue(expectedTwo.remove(identifiableItem));
    }

    assertTrue(expectedTwo.isEmpty());

    expected.remove(RecordIdInternal.fromString("#77:14", false));
    bag.remove(RecordIdInternal.fromString("#77:14", false));
    assertEmbedded(bag.isEmbedded());

    expectedTwo.addAll(expected);

    for (Identifiable identifiableItem : bag) {
      assertTrue(expectedTwo.remove(identifiableItem));
    }
    session.commit();
  }

  /**
   * Original: testSaveLoad (line 1015)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test15_SaveLoad() {
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
    for (var identifiableItem : loaded) {
      assertTrue(expected.remove(identifiableItem));
    }

    assertTrue(expected.isEmpty());
    session.commit();
  }

  /**
   * Original: testSaveInBackOrder (line 1077)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test16_SaveInBackOrder() {
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
    assertFalse(result.contains(docB));
    assertEquals(1, result.size());
    assertEquals(1, ridBag.size());
    session.commit();
  }

  /**
   * Original: testMassiveChanges (line 1112)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test17_MassiveChanges() {
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

  /**
   * Original: testSimultaneousIterationAndRemove (line 1149)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test18_SimultaneousIterationAndRemove() {
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
    for (Identifiable identifiableItem : ridBag) {
      var transaction1 = session.getActiveTransaction();
      assertTrue(docs.remove(transaction1.load(identifiableItem)));
      ridBag.remove(identifiableItem.getIdentity());
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
    assertEquals(0, ridBag.size());
    assertEquals(0, docs.size());
    session.commit();
  }

  /**
   * Original: testAddMixedValues (line 1229)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test19_AddMixedValues() {
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

  /**
   * Original: testFromEmbeddedToSBTreeAndBack (line 1323)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test20_FromEmbeddedToSBTreeAndBack() throws IOException {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(7);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(-1);

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
    assertFalse(ridBag.isEmbedded());

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
    assertFalse(ridBag.isEmbedded());

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
    assertFalse(ridBag.isEmbedded());

    for (var id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());

    ridBag = document.getProperty("ridBag");
    assertFalse(ridBag.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (var id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());
    session.commit();
  }

  /**
   * Original: testFromEmbeddedToSBTreeAndBackTx (line 1440)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test21_FromEmbeddedToSBTreeAndBackTx() throws IOException {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(7);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(-1);

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
    assertFalse(ridBag.isEmbedded());

    List<Identifiable> addedItemsCopy = new ArrayList<>(addedItems);
    for (Identifiable id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());

    ridBag = document.getProperty("ridBag");
    assertFalse(ridBag.isEmbedded());

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
    assertFalse(ridBag.isEmbedded());

    for (Identifiable id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());

    ridBag = document.getProperty("ridBag");
    assertFalse(ridBag.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (Identifiable id : ridBag) {
      assertTrue(addedItems.remove(id));
    }

    assertTrue(addedItems.isEmpty());
    session.commit();
  }

  /**
   * Original: testRemoveSavedInCommit (line 1552)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test22_RemoveSavedInCommit() {
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

  /**
   * Original: testSizeNotChangeAfterRemoveNotExistentElement (line 1629)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test23_SizeNotChangeAfterRemoveNotExistentElement() {
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

    assertEquals(2, teamMates.size());

    teamMates.remove(jim.getIdentity());

    assertEquals(2, teamMates.size());
    session.commit();
  }

  /**
   * Original: testRemoveNotExistentElementAndAddIt (line 1655)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test24_RemoveNotExistentElementAndAddIt() {
    session.begin();
    var teamMates = new LinkBag(session);

    final var bob = ((EntityImpl) session.newEntity());

    session.commit();

    session.begin();
    teamMates.remove(bob.getIdentity());

    assertEquals(0, teamMates.size());

    teamMates.add(bob.getIdentity());

    assertEquals(1, teamMates.size());
    assertEquals(teamMates.iterator().next().getIdentity(), bob.getIdentity());
    session.commit();
  }

  /**
   * Original: testAddNewItemsAndRemoveThem (line 1674)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test25_AddNewItemsAndRemoveThem() {
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

    for (var identifiableItem : ridBag) {
      if (newDocs.contains(identifiableItem) && rnd.nextBoolean()) {
        ridBag.remove(identifiableItem.getIdentity());
        if (rids.remove(identifiableItem)) {
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

    for (var identifiableItem : ridBag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());
    session.commit();

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.getProperty("ridBag");

    rids.addAll(ridsCopy);
    for (var identifiableItem : ridBag) {
      assertTrue(rids.remove(identifiableItem));
    }

    assertTrue(rids.isEmpty());
    assertEquals(ridBag.size(), size);
    session.commit();
  }

  /**
   * Original: testJsonSerialization (line 1772)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test26_JsonSerialization() {
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

  /**
   * Original: testRollBackChangesOne (line 1813)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test27_RollBackChangesOne() {
    session.begin();
    final var entity = session.newEntity();

    final var originalBulkSet = new BulkSet<RID>();
    var entity1 = session.newEntity();
    originalBulkSet.add(entity1.getIdentity());

    var entity2 = session.newEntity();
    originalBulkSet.add(entity2.getIdentity());

    var entity3 = session.newEntity();
    originalBulkSet.add(entity3.getIdentity());

    var entity4 = session.newEntity();
    originalBulkSet.add(entity4.getIdentity());

    var entity5 = session.newEntity();
    originalBulkSet.add(entity5.getIdentity());

    final var linkBag = new LinkBag(session);
    entity.setProperty("linkBag", linkBag);
    linkBag.addAll(originalBulkSet);

    var tx = session.getActiveTransaction();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    var entity6 = session.newEntity();
    linkBag.add(entity6.getIdentity());

    var entity7 = session.newEntity();
    linkBag.add(entity7.getIdentity());

    var entity10 = session.newEntity();
    linkBag.add(entity10.getIdentity());

    var entity8 = session.newEntity();
    linkBag.add(entity8.getIdentity());
    linkBag.add(entity8.getIdentity());

    linkBag.remove(entity3.getIdentity());
    linkBag.remove(entity7.getIdentity());

    var entity9 = session.newEntity();
    linkBag.add(entity9.getIdentity());
    linkBag.add(entity9.getIdentity());
    linkBag.add(entity9.getIdentity());
    linkBag.add(entity9.getIdentity());

    linkBag.remove(entity9.getIdentity());
    linkBag.remove(entity9.getIdentity());

    var entity11 = session.newEntity();
    linkBag.add(entity11.getIdentity());

    linkBag.rollbackChanges(tx);

    var linkBagBulkSet = new BulkSet<RID>();
    for (var rid : linkBag) {
      linkBagBulkSet.add(rid);
    }

    Assert.assertEquals(originalBulkSet, linkBagBulkSet);
    session.rollback();
  }

  /**
   * Original: testRollBackChangesTwo (line 1881)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
  @Test
  public void test28_RollBackChangesTwo() {
    session.begin();
    final var entity = session.newEntity();

    final var originalBulkSet = new BulkSet<RID>();

    var entity1 = session.newEntity();
    originalBulkSet.add(entity1.getIdentity());

    var entity2 = session.newEntity();
    originalBulkSet.add(entity2.getIdentity());

    var entity3 = session.newEntity();
    originalBulkSet.add(entity3.getIdentity());

    var entity4 = session.newEntity();
    originalBulkSet.add(entity4.getIdentity());

    var entity5 = session.newEntity();
    originalBulkSet.add(entity5.getIdentity());

    final var linkBag = new LinkBag(session);
    entity.setProperty("linkBag", linkBag);
    linkBag.addAll(originalBulkSet);

    var tx = session.getActiveTransaction();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    var entity6 = session.newEntity();
    linkBag.add(entity6.getIdentity());

    var entity7 = session.newEntity();
    linkBag.add(entity7.getIdentity());

    var entity10 = session.newEntity();
    linkBag.add(entity10.getIdentity());

    var entity8 = session.newEntity();
    linkBag.add(entity8.getIdentity());
    linkBag.remove(entity3.getIdentity());

    linkBag.remove(entity7.getIdentity());

    var entity9 = session.newEntity();
    linkBag.add(entity9.getIdentity());

    var entity11 = session.newEntity();
    linkBag.add(entity11.getIdentity());

    var entity12 = session.newEntity();
    linkBag.add(entity12.getIdentity());
    linkBag.add(entity12.getIdentity());

    linkBag.rollbackChanges(tx);

    var linkBagBulkSet = new BulkSet<RID>();
    for (var rid : linkBag) {
      linkBagBulkSet.add(rid);
    }

    Assert.assertEquals(originalBulkSet, linkBagBulkSet);
    session.rollback();
  }

  /**
   * Helper method for massiveInsertionIteration.
   * Original: massiveInsertionIteration (line 1947)
   * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkBagTest.java
   */
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
