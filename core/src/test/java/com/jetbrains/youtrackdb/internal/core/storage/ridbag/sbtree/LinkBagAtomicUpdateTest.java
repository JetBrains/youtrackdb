package com.jetbrains.youtrackdb.internal.core.storage.ridbag.sbtree;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LinkBagAtomicUpdateTest extends DbTestBase {

  private int topThreshold;
  private int bottomThreshold;

  @Before
  public void beforeMethod() {
    topThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(-1);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(-1);
  }

  @After
  public void afterMethod() {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);
  }

  @Test
  public void testAddTwoNewDocuments() {
    session.begin();
    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();

    var activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    ridBag = rootDoc.getProperty("ridBag");

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.rollback();

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.getProperty("ridBag");

    Assert.assertEquals(0, ridBag.size());
    session.commit();
  }

  @Test
  public void testAddTwoNewDocumentsWithCME() throws Exception {
    session.begin();

    var cmeDoc = (EntityImpl) session.newEntity();

    session.commit();

    session.begin();
    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    cmeDoc = activeTx2.load(cmeDoc);
    cmeDoc.setProperty("v", 1);

    session.commit();

    session.begin();

    var activeTx1 = session.getActiveTransaction();
    cmeDoc = activeTx1.load(cmeDoc);
    var activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    ridBag = rootDoc.getProperty("ridBag");

    cmeDoc.setProperty("v", 2);

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    generateCME(cmeDoc.getIdentity());

    try {
      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException ignored) {
    }

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.getProperty("ridBag");

    Assert.assertEquals(0, ridBag.size());
    session.commit();
  }

  @Test
  public void testAddTwoAdditionalNewDocuments() {
    session.begin();

    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();
    var activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    ridBag = rootDoc.getProperty("ridBag");

    var docThree = (EntityImpl) session.newEntity();
    var docFour = (EntityImpl) session.newEntity();

    ridBag.add(docThree.getIdentity());
    ridBag.add(docFour.getIdentity());

    session.rollback();

    session.begin();
    Assert.assertEquals(session.countClass(Entity.DEFAULT_CLASS_NAME), recordsCount);

    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.getProperty("ridBag");

    Assert.assertEquals(2, ridBag.size());

    var iterator = ridBag.iterator();
    List<RID> addedDocs = new ArrayList<>(
        Arrays.asList(docOne.getIdentity(), docTwo.getIdentity()));

    Assert.assertTrue(addedDocs.remove(iterator.next()));
    Assert.assertTrue(addedDocs.remove(iterator.next()));
    session.commit();
  }

  @Test
  public void testAddingDocsDontUpdateVersion() {
    session.begin();
    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());

    session.commit();

    final var version = rootDoc.getVersion();

    session.begin();
    var activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    ridBag = rootDoc.getProperty("ridBag");

    var docTwo = (EntityImpl) session.newEntity();
    ridBag.add(docTwo.getIdentity());

    session.commit();
    session.begin();
    activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    ridBag = rootDoc.getProperty("ridBag");
    Assert.assertEquals(2, ridBag.size());
    Assert.assertEquals(rootDoc.getVersion(), version + 1);
    session.rollback();
  }

  @Test
  public void testAddingDocsDontUpdateVersionInTx() {
    session.begin();

    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());

    session.commit();

    final var version = rootDoc.getVersion();

    session.begin();
    var activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    ridBag = rootDoc.getProperty("ridBag");

    var docTwo = (EntityImpl) session.newEntity();
    ridBag.add(docTwo.getIdentity());

    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);

    ridBag = rootDoc.getProperty("ridBag");
    Assert.assertEquals(2, ridBag.size());
    Assert.assertEquals(rootDoc.getVersion(), version + 1);
    session.rollback();
  }

  @Test
  public void testAddTwoAdditionalNewDocumentsWithCME() throws Exception {
    session.begin();
    var cmeDoc = (EntityImpl) session.newEntity();

    session.commit();

    session.begin();

    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();

    var activeTx = session.getActiveTransaction();
    cmeDoc = activeTx.load(cmeDoc);
    cmeDoc.setProperty("v", "v");

    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.getProperty("ridBag");

    var docThree = (EntityImpl) session.newEntity();
    var docFour = (EntityImpl) session.newEntity();

    ridBag.add(docThree.getIdentity());
    ridBag.add(docFour.getIdentity());

    generateCME(cmeDoc.getIdentity());
    try {
      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException ignored) {
    }

    Assert.assertEquals(session.countClass(Entity.DEFAULT_CLASS_NAME), recordsCount);

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.getProperty("ridBag");

    Assert.assertEquals(2, ridBag.size());

    var iterator = ridBag.iterator();
    List<RID> addedDocs = new ArrayList<>(
        Arrays.asList(docOne.getIdentity(), docTwo.getIdentity()));

    Assert.assertTrue(addedDocs.remove(iterator.next()));
    Assert.assertTrue(addedDocs.remove(iterator.next()));
    session.commit();
  }

  @Test
  public void testAddTwoSavedDocuments() {
    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();

    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();

    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.rollback();

    Assert.assertEquals(session.countClass(Entity.DEFAULT_CLASS_NAME), recordsCount);
  }

  @Test
  public void testAddTwoAdditionalSavedDocuments() {
    session.begin();

    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());

    var activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    ridBag = rootDoc.getProperty("ridBag");

    var docThree = (EntityImpl) session.newEntity();

    var docFour = (EntityImpl) session.newEntity();

    ridBag.add(docThree.getIdentity());
    ridBag.add(docFour.getIdentity());

    session.rollback();

    session.begin();
    Assert.assertEquals(session.countClass(Entity.DEFAULT_CLASS_NAME), recordsCount);

    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.getProperty("ridBag");

    Assert.assertEquals(2, ridBag.size());

    List<Identifiable> addedDocs = new ArrayList<>(Arrays.asList(docOne, docTwo));

    var iterator = ridBag.iterator();
    Assert.assertTrue(addedDocs.remove(iterator.next()));
    Assert.assertTrue(addedDocs.remove(iterator.next()));
    session.commit();
  }

  @Test
  public void testAddTwoAdditionalSavedDocumentsWithCME() throws Exception {
    session.begin();
    var cmeDoc = (EntityImpl) session.newEntity();

    session.commit();

    session.begin();

    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());

    var activeTx1 = session.getActiveTransaction();
    cmeDoc = activeTx1.load(cmeDoc);
    cmeDoc.setProperty("v", "v");

    var docThree = (EntityImpl) session.newEntity();

    var docFour = (EntityImpl) session.newEntity();

    var activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    ridBag = rootDoc.getProperty("ridBag");

    ridBag.add(docThree.getIdentity());
    ridBag.add(docFour.getIdentity());

    generateCME(cmeDoc.getIdentity());

    try {
      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException ignored) {
    }

    Assert.assertEquals(session.countClass(Entity.DEFAULT_CLASS_NAME), recordsCount);

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.getProperty("ridBag");

    Assert.assertEquals(2, ridBag.size());

    List<RID> addedDocs = new ArrayList<>(
        Arrays.asList(docOne.getIdentity(), docTwo.getIdentity()));

    var iterator = ridBag.iterator();
    Assert.assertTrue(addedDocs.remove(iterator.next()));
    Assert.assertTrue(addedDocs.remove(iterator.next()));
    session.commit();
  }

  @Test
  public void testAddInternalDocumentsAndSubDocuments() {
    session.begin();

    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();

    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();
    var docThree = (EntityImpl) session.newEntity();

    var docFour = (EntityImpl) session.newEntity();

    var activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    ridBag = rootDoc.getProperty("ridBag");

    ridBag.add(docThree.getIdentity());
    ridBag.add(docFour.getIdentity());

    var docThreeOne = (EntityImpl) session.newEntity();

    var docThreeTwo = (EntityImpl) session.newEntity();

    var ridBagThree = new LinkBag(session);
    ridBagThree.add(docThreeOne.getIdentity());
    ridBagThree.add(docThreeTwo.getIdentity());
    docThree.setProperty("ridBag", ridBagThree);

    var docFourOne = (EntityImpl) session.newEntity();

    var docFourTwo = (EntityImpl) session.newEntity();

    var ridBagFour = new LinkBag(session);
    ridBagFour.add(docFourOne.getIdentity());
    ridBagFour.add(docFourTwo.getIdentity());

    docFour.setProperty("ridBag", ridBagFour);

    session.rollback();

    Assert.assertEquals(session.countClass(Entity.DEFAULT_CLASS_NAME), recordsCount);
    List<RID> addedDocs = new ArrayList<>(
        Arrays.asList(docOne.getIdentity(), docTwo.getIdentity()));

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.getProperty("ridBag");

    var iterator = ridBag.iterator();
    Assert.assertTrue(addedDocs.remove(iterator.next()));
    Assert.assertTrue(addedDocs.remove(iterator.next()));
    session.commit();
  }

  @Test
  public void testAddInternalDocumentsAndSubDocumentsWithCME() throws Exception {
    session.begin();
    var cmeDoc = (EntityImpl) session.newEntity();

    session.commit();

    session.begin();
    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();

    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    cmeDoc = activeTx1.load(cmeDoc);
    cmeDoc.setProperty("v", "v2");

    var docThree = (EntityImpl) session.newEntity();

    var docFour = (EntityImpl) session.newEntity();

    var activeTx = session.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    ridBag = rootDoc.getProperty("ridBag");

    ridBag.add(docThree.getIdentity());
    ridBag.add(docFour.getIdentity());

    var docThreeOne = (EntityImpl) session.newEntity();

    var docThreeTwo = (EntityImpl) session.newEntity();

    var ridBagThree = new LinkBag(session);
    ridBagThree.add(docThreeOne.getIdentity());
    ridBagThree.add(docThreeTwo.getIdentity());
    docThree.setProperty("ridBag", ridBagThree);

    var docFourOne = (EntityImpl) session.newEntity();

    var docFourTwo = (EntityImpl) session.newEntity();

    var ridBagFour = new LinkBag(session);
    ridBagFour.add(docFourOne.getIdentity());
    ridBagFour.add(docFourTwo.getIdentity());

    docFour.setProperty("ridBag", ridBagFour);

    generateCME(cmeDoc.getIdentity());
    try {
      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException ignored) {
    }

    Assert.assertEquals(session.countClass(Entity.DEFAULT_CLASS_NAME), recordsCount);
    List<RID> addedDocs = new ArrayList<>(
        Arrays.asList(docOne.getIdentity(), docTwo.getIdentity()));

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.getProperty("ridBag");

    var iterator = ridBag.iterator();
    Assert.assertTrue(addedDocs.remove(iterator.next()));
    Assert.assertTrue(addedDocs.remove(iterator.next()));
    session.commit();
  }

  @Test
  public void testRandomChangedInTxLevel2() {
    testRandomChangedInTx(2);
  }

  @Test
  public void testRandomChangedInTxLevel1() {
    testRandomChangedInTx(1);
  }

  private void testRandomChangedInTx(final int levels) {
    var rnd = new Random();

    final List<Integer> amountOfAddedDocsPerLevel = new ArrayList<>();
    final List<Integer> amountOfAddedDocsAfterSavePerLevel = new ArrayList<>();
    final List<Integer> amountOfDeletedDocsPerLevel = new ArrayList<>();
    Map<LevelKey, List<Identifiable>> addedDocPerLevel =
        new HashMap<>();

    for (var i = 0; i < levels; i++) {
      amountOfAddedDocsPerLevel.add(rnd.nextInt(5) + 10);
      amountOfAddedDocsAfterSavePerLevel.add(rnd.nextInt(5) + 5);
      amountOfDeletedDocsPerLevel.add(rnd.nextInt(5) + 5);
    }

    session.begin();
    var rootDoc = (EntityImpl) session.newEntity();
    createDocsForLevel(amountOfAddedDocsPerLevel, 0, levels, addedDocPerLevel, rootDoc);
    session.commit();

    addedDocPerLevel = new HashMap<>(addedDocPerLevel);

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());

    deleteDocsForLevel(session, amountOfDeletedDocsPerLevel, 0, levels, rootDoc, rnd);
    addDocsForLevel(session, amountOfAddedDocsAfterSavePerLevel, 0, levels, rootDoc);
    session.rollback();

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());
    assertDocsAfterRollback(0, levels, addedDocPerLevel, rootDoc);
    session.commit();
  }

  @Test
  public void testRandomChangedInTxWithCME() throws Exception {
    session.begin();
    var cmeDoc = (EntityImpl) session.newEntity();

    session.commit();

    var rnd = new Random();

    final var levels = rnd.nextInt(2) + 1;
    final List<Integer> amountOfAddedDocsPerLevel = new ArrayList<>();
    final List<Integer> amountOfAddedDocsAfterSavePerLevel = new ArrayList<>();
    final List<Integer> amountOfDeletedDocsPerLevel = new ArrayList<>();
    Map<LevelKey, List<Identifiable>> addedDocPerLevel =
        new HashMap<>();

    for (var i = 0; i < levels; i++) {
      amountOfAddedDocsPerLevel.add(rnd.nextInt(5) + 10);
      amountOfAddedDocsAfterSavePerLevel.add(rnd.nextInt(5) + 5);
      amountOfDeletedDocsPerLevel.add(rnd.nextInt(5) + 5);
    }

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    cmeDoc = activeTx1.load(cmeDoc);
    cmeDoc.setProperty("v", 1);

    session.commit();

    session.begin();
    var rootDoc = (EntityImpl) session.newEntity();
    createDocsForLevel(amountOfAddedDocsPerLevel, 0, levels, addedDocPerLevel, rootDoc);
    session.commit();

    addedDocPerLevel = new HashMap<>(addedDocPerLevel);

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());

    deleteDocsForLevel(session, amountOfDeletedDocsPerLevel, 0, levels, rootDoc, rnd);
    addDocsForLevel(session, amountOfAddedDocsAfterSavePerLevel, 0, levels, rootDoc);

    var activeTx = session.getActiveTransaction();
    cmeDoc = activeTx.load(cmeDoc);
    cmeDoc.setProperty("v", 2);

    generateCME(cmeDoc.getIdentity());
    try {
      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException ignored) {
    }

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());
    assertDocsAfterRollback(0, levels, addedDocPerLevel, rootDoc);
    session.commit();
  }

  @Test
  public void testFromEmbeddedToSBTreeRollback() {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(5);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(5);

    List<Identifiable> docsToAdd = new ArrayList<>();

    session.begin();
    var document = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    document = activeTx2.load(document);
    ridBag = document.getProperty("ridBag");
    for (var i = 0; i < 3; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
      docsToAdd.add(docToAdd);
    }

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertEquals(3, docsToAdd.size());
    Assert.assertTrue(ridBag.isEmbedded());

    document = session.load(document.getIdentity());

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");

    for (var i = 0; i < 3; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
    }

    Assert.assertTrue(document.isDirty());

    session.rollback();

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.getProperty("ridBag");

    Assert.assertTrue(ridBag.isEmbedded());
    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(docsToAdd.remove(identifiable));
    }

    Assert.assertTrue(docsToAdd.isEmpty());
    session.commit();
  }

  @Test
  public void testFromEmbeddedToSBTreeTXWithCME() throws Exception {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(5);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(5);

    session.begin();
    var cmeDocument = (EntityImpl) session.newEntity();
    cmeDocument.setProperty("v", 1);

    session.commit();

    session.begin();
    List<RID> docsToAdd = new ArrayList<>();

    var document = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    document = activeTx2.load(document);
    ridBag = document.getProperty("ridBag");

    for (var i = 0; i < 3; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
      docsToAdd.add(docToAdd.getIdentity());
    }

    session.commit();

    Assert.assertEquals(3, docsToAdd.size());
    Assert.assertTrue(ridBag.isEmbedded());

    session.begin();
    document = session.load(document.getIdentity());

    EntityImpl staleDocument = session.load(cmeDocument.getIdentity());
    Assert.assertNotSame(staleDocument, cmeDocument);

    var activeTx1 = session.getActiveTransaction();
    cmeDocument = activeTx1.load(cmeDocument);
    cmeDocument.setProperty("v", 234);

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");
    for (var i = 0; i < 3; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
    }

    Assert.assertTrue(document.isDirty());

    generateCME(cmeDocument.getIdentity());
    try {
      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException ignored) {
    }

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.getProperty("ridBag");

    Assert.assertTrue(ridBag.isEmbedded());
    for (var identifiable : ridBag) {
      Assert.assertTrue(docsToAdd.remove(identifiable));
    }

    Assert.assertTrue(docsToAdd.isEmpty());
    session.commit();
  }

  @Test
  public void testFromEmbeddedToSBTreeWithCME() throws Exception {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(5);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(5);

    List<Identifiable> docsToAdd = new ArrayList<>();

    session.begin();
    var document = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    ridBag = document.getProperty("ridBag");
    for (var i = 0; i < 3; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
      docsToAdd.add(docToAdd);
    }

    session.commit();

    Assert.assertEquals(3, docsToAdd.size());
    Assert.assertTrue(ridBag.isEmbedded());

    session.begin();
    document = session.load(document.getIdentity());

    var rid = document.getIdentity();

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");
    for (var i = 0; i < 3; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
    }

    Assert.assertTrue(document.isDirty());
    try {
      generateCME(rid);

      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException ignored) {
    }

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.getProperty("ridBag");

    Assert.assertTrue(ridBag.isEmbedded());
    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(docsToAdd.remove(identifiable));
    }

    Assert.assertTrue(docsToAdd.isEmpty());
    session.commit();
  }

  private void generateCME(RID rid) {
    var session = this.session.copy();
    try (session) {
      session.begin();
      EntityImpl cmeDocument = session.load(rid);

      var v = cmeDocument.getInt("v");
      if (v != null) {
        cmeDocument.setProperty("v", v + 1);
      } else {
        cmeDocument.setProperty("v", 1);
      }

      session.commit();
    }
  }

  @Test
  public void testFromSBTreeToEmbeddedRollback() {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(5);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(7);

    List<Identifiable> docsToAdd = new ArrayList<>();

    session.begin();
    var document = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    document = activeTx2.load(document);
    ridBag = document.getProperty("ridBag");

    for (var i = 0; i < 10; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
      docsToAdd.add(docToAdd);
    }

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    ridBag = document.getProperty("ridBag");
    Assert.assertEquals(10, docsToAdd.size());
    Assert.assertFalse(ridBag.isEmbedded());

    document = session.load(document.getIdentity());

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");
    for (var i = 0; i < 4; i++) {
      var docToRemove = docsToAdd.get(i);
      ridBag.remove(docToRemove.getIdentity());
    }

    Assert.assertTrue(document.isDirty());

    session.rollback();

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.getProperty("ridBag");

    Assert.assertFalse(ridBag.isEmbedded());

    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(docsToAdd.remove(identifiable));
    }

    Assert.assertTrue(docsToAdd.isEmpty());
    session.commit();
  }

  @Test
  public void testFromSBTreeToEmbeddedTxWithCME() throws Exception {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(5);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(7);

    session.begin();
    var cmeDoc = (EntityImpl) session.newEntity();

    session.commit();

    session.begin();
    List<Identifiable> docsToAdd = new ArrayList<>();

    var document = (EntityImpl) session.newEntity();

    var ridBag = new LinkBag(session);
    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    document = activeTx2.load(document);
    ridBag = document.getProperty("ridBag");

    for (var i = 0; i < 10; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
      docsToAdd.add(docToAdd);
    }

    session.commit();

    Assert.assertEquals(10, docsToAdd.size());
    Assert.assertFalse(ridBag.isEmbedded());

    session.begin();
    document = session.load(document.getIdentity());
    document.getProperty("ridBag");

    var activeTx1 = session.getActiveTransaction();
    cmeDoc = activeTx1.load(cmeDoc);
    cmeDoc.setProperty("v", "sd");

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    ridBag = document.getProperty("ridBag");
    for (var i = 0; i < 4; i++) {
      var docToRemove = docsToAdd.get(i);
      ridBag.remove(docToRemove.getIdentity());
    }

    Assert.assertTrue(document.isDirty());

    generateCME(cmeDoc.getIdentity());
    try {
      session.commit();
      Assert.fail();
    } catch (ConcurrentModificationException ignored) {
    }

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.getProperty("ridBag");

    Assert.assertFalse(ridBag.isEmbedded());

    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(docsToAdd.remove(identifiable));
    }

    Assert.assertTrue(docsToAdd.isEmpty());
    session.commit();
  }

  private void createDocsForLevel(
      final List<Integer> amountOfAddedDocsPerLevel,
      int level,
      int levels,
      Map<LevelKey, List<Identifiable>> addedDocPerLevel,
      EntityImpl rootDoc) {

    int docs = amountOfAddedDocsPerLevel.get(level);

    List<Identifiable> addedDocs = new ArrayList<>();
    addedDocPerLevel.put(new LevelKey(rootDoc.getIdentity(), level), addedDocs);

    var ridBag = new LinkBag(session);
    rootDoc.setProperty("ridBag", ridBag);

    for (var i = 0; i < docs; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      addedDocs.add(docToAdd.getIdentity());
      ridBag.add(docToAdd.getIdentity());

      if (level + 1 < levels) {
        createDocsForLevel(
            amountOfAddedDocsPerLevel, level + 1, levels, addedDocPerLevel, docToAdd);
      }
    }

  }

  private static void deleteDocsForLevel(
      DatabaseSessionEmbedded db,
      List<Integer> amountOfDeletedDocsPerLevel,
      int level,
      int levels,
      EntityImpl rootDoc,
      Random rnd) {
    var activeTx = db.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    LinkBag linkBag = rootDoc.getProperty("ridBag");
    for (Identifiable identifiable : linkBag) {
      var transaction = db.getActiveTransaction();
      EntityImpl doc = transaction.load(identifiable);
      if (level + 1 < levels) {
        deleteDocsForLevel(db, amountOfDeletedDocsPerLevel, level + 1, levels, doc, rnd);
      }
    }

    int docs = amountOfDeletedDocsPerLevel.get(level);

    var k = 0;
    var iterator = linkBag.iterator();
    while (k < docs && iterator.hasNext()) {
      iterator.next();

      if (rnd.nextBoolean()) {
        iterator.remove();
        k++;
      }

      if (!iterator.hasNext()) {
        iterator = linkBag.iterator();
      }
    }

  }

  private static void addDocsForLevel(
      DatabaseSessionEmbedded db,
      List<Integer> amountOfAddedDocsAfterSavePerLevel,
      int level,
      int levels,
      EntityImpl rootDoc) {
    var activeTx = db.getActiveTransaction();
    rootDoc = activeTx.load(rootDoc);
    LinkBag linkBag = rootDoc.getProperty("ridBag");

    for (Identifiable identifiable : linkBag) {
      var transaction = db.getActiveTransaction();
      EntityImpl doc = transaction.load(identifiable);
      if (level + 1 < levels) {
        addDocsForLevel(db, amountOfAddedDocsAfterSavePerLevel, level + 1, levels, doc);
      }
    }

    int docs = amountOfAddedDocsAfterSavePerLevel.get(level);
    for (var i = 0; i < docs; i++) {
      var docToAdd = (EntityImpl) db.newEntity();

      linkBag.add(docToAdd.getIdentity());
    }

  }

  private void assertDocsAfterRollback(
      int level,
      int levels,
      Map<LevelKey, List<Identifiable>> addedDocPerLevel,
      EntityImpl rootDoc) {
    LinkBag linkBag = rootDoc.getProperty("ridBag");
    List<Identifiable> addedDocs =
        new ArrayList<>(
            addedDocPerLevel.get(new LevelKey(rootDoc.getIdentity(), level)));

    for (Identifiable identifiable : linkBag) {
      var transaction = session.getActiveTransaction();
      EntityImpl doc = transaction.load(identifiable);
      if (level + 1 < levels) {
        assertDocsAfterRollback(level + 1, levels, addedDocPerLevel, doc);
      } else {
        Assert.assertNull(doc.getProperty("ridBag"));
      }

      Assert.assertTrue(addedDocs.remove(doc));
    }

    Assert.assertTrue(addedDocs.isEmpty());
  }

  private record LevelKey(RID rid, int level) {

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      var levelKey = (LevelKey) o;

      if (level != levelKey.level) {
        return false;
      }
      return rid.equals(levelKey.rid);
    }

  }
}
