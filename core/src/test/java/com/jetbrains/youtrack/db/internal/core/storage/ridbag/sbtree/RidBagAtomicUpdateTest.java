package com.jetbrains.youtrack.db.internal.core.storage.ridbag.sbtree;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
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

public class RidBagAtomicUpdateTest extends DbTestBase {

  private int topThreshold;
  private int bottomThreshold;

  @Before
  public void beforeMethod() {
    topThreshold =
        GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(-1);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(-1);
  }

  @After
  public void afterMethod() {
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);
  }

  @Test
  public void testAddTwoNewDocuments() {
    session.begin();
    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

    session.commit();

    session.begin();

    rootDoc = session.bindToSession(rootDoc);
    ridBag = rootDoc.field("ridBag");

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.rollback();

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.field("ridBag");

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

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

    session.commit();

    session.begin();
    cmeDoc = session.bindToSession(cmeDoc);
    cmeDoc.field("v", "v");

    session.commit();

    session.begin();

    cmeDoc = session.bindToSession(cmeDoc);
    rootDoc = session.bindToSession(rootDoc);
    ridBag = rootDoc.field("ridBag");

    cmeDoc.field("v", "v234");

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
    ridBag = rootDoc.field("ridBag");

    Assert.assertEquals(0, ridBag.size());
    session.commit();
  }

  @Test
  public void testAddTwoAdditionalNewDocuments() {
    session.begin();

    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();
    rootDoc = session.bindToSession(rootDoc);
    ridBag = rootDoc.field("ridBag");

    var docThree = (EntityImpl) session.newEntity();
    var docFour = (EntityImpl) session.newEntity();

    ridBag.add(docThree.getIdentity());
    ridBag.add(docFour.getIdentity());

    session.rollback();

    session.begin();
    Assert.assertEquals(session.countClass(Entity.DEFAULT_CLASS_NAME), recordsCount);

    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.field("ridBag");

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

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());

    session.commit();

    final var version = rootDoc.getVersion();

    session.begin();
    rootDoc = session.bindToSession(rootDoc);
    ridBag = rootDoc.field("ridBag");

    var docTwo = (EntityImpl) session.newEntity();
    ridBag.add(docTwo.getIdentity());

    session.commit();

    Assert.assertEquals(2, ridBag.size());
    Assert.assertEquals(rootDoc.getVersion(), version);

    session.begin();

    Assert.assertEquals(2, ridBag.size());
    Assert.assertEquals(rootDoc.getVersion(), version);
    session.rollback();
  }

  @Test
  public void testAddingDocsDontUpdateVersionInTx() {
    session.begin();

    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());

    session.commit();

    final var version = rootDoc.getVersion();

    session.begin();
    rootDoc = session.bindToSession(rootDoc);
    ridBag = rootDoc.field("ridBag");

    var docTwo = (EntityImpl) session.newEntity();
    ridBag.add(docTwo.getIdentity());

    session.commit();

    Assert.assertEquals(2, ridBag.size());
    Assert.assertEquals(rootDoc.getVersion(), version);

    session.begin();

    Assert.assertEquals(2, ridBag.size());
    Assert.assertEquals(rootDoc.getVersion(), version);
    session.rollback();
  }

  @Test
  public void testAddTwoAdditionalNewDocumentsWithCME() throws Exception {
    session.begin();
    var cmeDoc = (EntityImpl) session.newEntity();

    session.commit();

    session.begin();

    var rootDoc = (EntityImpl) session.newEntity();

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();

    cmeDoc = session.bindToSession(cmeDoc);
    cmeDoc.field("v", "v");

    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.field("ridBag");

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
    ridBag = rootDoc.field("ridBag");

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

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

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

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());

    rootDoc = session.bindToSession(rootDoc);
    ridBag = rootDoc.field("ridBag");

    var docThree = (EntityImpl) session.newEntity();

    var docFour = (EntityImpl) session.newEntity();

    ridBag.add(docThree.getIdentity());
    ridBag.add(docFour.getIdentity());

    session.rollback();

    session.begin();
    Assert.assertEquals(session.countClass(Entity.DEFAULT_CLASS_NAME), recordsCount);

    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.field("ridBag");

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

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();
    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());

    cmeDoc = session.bindToSession(cmeDoc);
    cmeDoc.field("v", "v");

    var docThree = (EntityImpl) session.newEntity();

    var docFour = (EntityImpl) session.newEntity();

    rootDoc = session.bindToSession(rootDoc);
    ridBag = rootDoc.field("ridBag");

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
    ridBag = rootDoc.field("ridBag");

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

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();

    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();
    var docThree = (EntityImpl) session.newEntity();

    var docFour = (EntityImpl) session.newEntity();

    rootDoc = session.bindToSession(rootDoc);
    ridBag = rootDoc.field("ridBag");

    ridBag.add(docThree.getIdentity());
    ridBag.add(docFour.getIdentity());

    var docThreeOne = (EntityImpl) session.newEntity();

    var docThreeTwo = (EntityImpl) session.newEntity();

    var ridBagThree = new RidBag(session);
    ridBagThree.add(docThreeOne.getIdentity());
    ridBagThree.add(docThreeTwo.getIdentity());
    docThree.field("ridBag", ridBagThree);

    var docFourOne = (EntityImpl) session.newEntity();

    var docFourTwo = (EntityImpl) session.newEntity();

    var ridBagFour = new RidBag(session);
    ridBagFour.add(docFourOne.getIdentity());
    ridBagFour.add(docFourTwo.getIdentity());

    docFour.field("ridBag", ridBagFour);

    session.rollback();

    Assert.assertEquals(session.countClass(Entity.DEFAULT_CLASS_NAME), recordsCount);
    List<RID> addedDocs = new ArrayList<>(
        Arrays.asList(docOne.getIdentity(), docTwo.getIdentity()));

    session.begin();
    rootDoc = session.load(rootDoc.getIdentity());
    ridBag = rootDoc.field("ridBag");

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

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

    var docOne = (EntityImpl) session.newEntity();

    var docTwo = (EntityImpl) session.newEntity();

    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    session.commit();

    var recordsCount = session.countClass(Entity.DEFAULT_CLASS_NAME);

    session.begin();
    cmeDoc = session.bindToSession(cmeDoc);
    cmeDoc.field("v", "v2");

    var docThree = (EntityImpl) session.newEntity();

    var docFour = (EntityImpl) session.newEntity();

    rootDoc = session.bindToSession(rootDoc);
    ridBag = rootDoc.field("ridBag");

    ridBag.add(docThree.getIdentity());
    ridBag.add(docFour.getIdentity());

    var docThreeOne = (EntityImpl) session.newEntity();

    var docThreeTwo = (EntityImpl) session.newEntity();

    var ridBagThree = new RidBag(session);
    ridBagThree.add(docThreeOne.getIdentity());
    ridBagThree.add(docThreeTwo.getIdentity());
    docThree.field("ridBag", ridBagThree);

    var docFourOne = (EntityImpl) session.newEntity();

    var docFourTwo = (EntityImpl) session.newEntity();

    var ridBagFour = new RidBag(session);
    ridBagFour.add(docFourOne.getIdentity());
    ridBagFour.add(docFourTwo.getIdentity());

    docFour.field("ridBag", ridBagFour);

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
    ridBag = rootDoc.field("ridBag");

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
    cmeDoc = session.bindToSession(cmeDoc);
    cmeDoc.field("v", "v");

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

    cmeDoc = session.bindToSession(cmeDoc);
    cmeDoc.field("v", "vn");

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
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(5);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(5);

    List<Identifiable> docsToAdd = new ArrayList<>();

    session.begin();
    var document = (EntityImpl) session.newEntity();

    var ridBag = new RidBag(session);
    document.field("ridBag", ridBag);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.field("ridBag");
    for (var i = 0; i < 3; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
      docsToAdd.add(docToAdd);
    }

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.field("ridBag");
    Assert.assertEquals(3, docsToAdd.size());
    Assert.assertTrue(ridBag.isEmbedded());

    document = session.load(document.getIdentity());

    document = session.bindToSession(document);
    ridBag = document.field("ridBag");

    for (var i = 0; i < 3; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
    }

    Assert.assertTrue(document.isDirty());

    session.rollback();

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.field("ridBag");

    Assert.assertTrue(ridBag.isEmbedded());
    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(docsToAdd.remove(identifiable));
    }

    Assert.assertTrue(docsToAdd.isEmpty());
    session.commit();
  }

  @Test
  public void testFromEmbeddedToSBTreeTXWithCME() throws Exception {
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(5);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(5);

    session.begin();
    var cmeDocument = (EntityImpl) session.newEntity();
    cmeDocument.field("v", "v1");

    session.commit();

    session.begin();
    List<RID> docsToAdd = new ArrayList<>();

    var document = (EntityImpl) session.newEntity();

    var ridBag = new RidBag(session);
    document.field("ridBag", ridBag);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.field("ridBag");

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

    cmeDocument = session.bindToSession(cmeDocument);
    cmeDocument.field("v", "v234");

    document = session.bindToSession(document);
    ridBag = document.field("ridBag");
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
    ridBag = document.field("ridBag");

    Assert.assertTrue(ridBag.isEmbedded());
    for (var identifiable : ridBag) {
      Assert.assertTrue(docsToAdd.remove(identifiable));
    }

    Assert.assertTrue(docsToAdd.isEmpty());
    session.commit();
  }

  @Test
  public void testFromEmbeddedToSBTreeWithCME() throws Exception {
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(5);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(5);

    List<Identifiable> docsToAdd = new ArrayList<>();

    session.begin();
    var document = (EntityImpl) session.newEntity();

    var ridBag = new RidBag(session);
    document.field("ridBag", ridBag);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.field("ridBag");
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

    document = session.bindToSession(document);
    ridBag = document.field("ridBag");
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
    ridBag = document.field("ridBag");

    Assert.assertTrue(ridBag.isEmbedded());
    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(docsToAdd.remove(identifiable));
    }

    Assert.assertTrue(docsToAdd.isEmpty());
    session.commit();
  }

  private void generateCME(RID rid) throws InterruptedException {
    var session = this.session.copy();
    var th =
        new Thread(
            () -> {
              try (session) {
                session.activateOnCurrentThread();
                session.begin();
                EntityImpl cmeDocument = session.load(rid);

                cmeDocument.field("v", "v1");

                session.commit();
              }
            });
    th.start();
    th.join();
  }

  @Test
  public void testFromSBTreeToEmbeddedRollback() {
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(5);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(7);

    List<Identifiable> docsToAdd = new ArrayList<>();

    session.begin();
    var document = (EntityImpl) session.newEntity();

    var ridBag = new RidBag(session);
    document.field("ridBag", ridBag);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.field("ridBag");

    for (var i = 0; i < 10; i++) {
      var docToAdd = (EntityImpl) session.newEntity();

      ridBag.add(docToAdd.getIdentity());
      docsToAdd.add(docToAdd);
    }

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.field("ridBag");
    Assert.assertEquals(10, docsToAdd.size());
    Assert.assertFalse(ridBag.isEmbedded());

    document = session.load(document.getIdentity());

    document = session.bindToSession(document);
    ridBag = document.field("ridBag");
    for (var i = 0; i < 4; i++) {
      var docToRemove = docsToAdd.get(i);
      ridBag.remove(docToRemove.getIdentity());
    }

    Assert.assertTrue(document.isDirty());

    session.rollback();

    session.begin();
    document = session.load(document.getIdentity());
    ridBag = document.field("ridBag");

    Assert.assertFalse(ridBag.isEmbedded());

    for (Identifiable identifiable : ridBag) {
      Assert.assertTrue(docsToAdd.remove(identifiable));
    }

    Assert.assertTrue(docsToAdd.isEmpty());
    session.commit();
  }

  @Test
  public void testFromSBTreeToEmbeddedTxWithCME() throws Exception {
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(5);
    GlobalConfiguration.RID_BAG_SBTREEBONSAI_TO_EMBEDDED_THRESHOLD.setValue(7);

    session.begin();
    var cmeDoc = (EntityImpl) session.newEntity();

    session.commit();

    session.begin();
    List<Identifiable> docsToAdd = new ArrayList<>();

    var document = (EntityImpl) session.newEntity();

    var ridBag = new RidBag(session);
    document.field("ridBag", ridBag);

    session.commit();

    session.begin();
    document = session.bindToSession(document);
    ridBag = document.field("ridBag");

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
    document.field("ridBag");

    cmeDoc = session.bindToSession(cmeDoc);
    cmeDoc.field("v", "sd");

    document = session.bindToSession(document);
    ridBag = document.field("ridBag");
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
    ridBag = document.field("ridBag");

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

    var ridBag = new RidBag(session);
    rootDoc.field("ridBag", ridBag);

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
      DatabaseSessionInternal db,
      List<Integer> amountOfDeletedDocsPerLevel,
      int level,
      int levels,
      EntityImpl rootDoc,
      Random rnd) {
    rootDoc = db.bindToSession(rootDoc);
    RidBag ridBag = rootDoc.field("ridBag");
    for (Identifiable identifiable : ridBag) {
      EntityImpl doc = identifiable.getRecord(db);
      if (level + 1 < levels) {
        deleteDocsForLevel(db, amountOfDeletedDocsPerLevel, level + 1, levels, doc, rnd);
      }
    }

    int docs = amountOfDeletedDocsPerLevel.get(level);

    var k = 0;
    var iterator = ridBag.iterator();
    while (k < docs && iterator.hasNext()) {
      iterator.next();

      if (rnd.nextBoolean()) {
        iterator.remove();
        k++;
      }

      if (!iterator.hasNext()) {
        iterator = ridBag.iterator();
      }
    }

  }

  private static void addDocsForLevel(
      DatabaseSessionInternal db,
      List<Integer> amountOfAddedDocsAfterSavePerLevel,
      int level,
      int levels,
      EntityImpl rootDoc) {
    rootDoc = db.bindToSession(rootDoc);
    RidBag ridBag = rootDoc.field("ridBag");

    for (Identifiable identifiable : ridBag) {
      EntityImpl doc = identifiable.getRecord(db);
      if (level + 1 < levels) {
        addDocsForLevel(db, amountOfAddedDocsAfterSavePerLevel, level + 1, levels, doc);
      }
    }

    int docs = amountOfAddedDocsAfterSavePerLevel.get(level);
    for (var i = 0; i < docs; i++) {
      var docToAdd = (EntityImpl) db.newEntity();

      ridBag.add(docToAdd.getIdentity());
    }

  }

  private void assertDocsAfterRollback(
      int level,
      int levels,
      Map<LevelKey, List<Identifiable>> addedDocPerLevel,
      EntityImpl rootDoc) {
    RidBag ridBag = rootDoc.field("ridBag");
    List<Identifiable> addedDocs =
        new ArrayList<>(
            addedDocPerLevel.get(new LevelKey(rootDoc.getIdentity(), level)));

    for (Identifiable identifiable : ridBag) {
      EntityImpl doc = identifiable.getRecord(session);
      if (level + 1 < levels) {
        assertDocsAfterRollback(level + 1, levels, addedDocPerLevel, doc);
      } else {
        Assert.assertNull(doc.field("ridBag"));
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
