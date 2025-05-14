/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.client.remote.EngineRemote;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.engine.memory.EngineMemory;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrack.db.internal.core.storage.disk.LocalStorage;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 *
 */
@Test
public class BTreeBasedLinkBagTest extends LinkBagTest {
  private int topThreshold;
  private int bottomThreshold;

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();
  }

  @Override
  @BeforeMethod
  public void beforeMethod() throws Exception {
    topThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(-1);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(-1);
    super.beforeMethod();
  }

  @Override
  @AfterMethod
  public void afterMethod() throws Exception {
    super.afterMethod();
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);
  }

  public void testRidBagCollectionDistribution() {
    if (session.getStorage().getType().equals(EngineRemote.NAME)
        || session.getStorage().getType().equals(EngineMemory.NAME)) {
      return;
    }

    final var collectionIdOne = session.addCollection("collectionOne");

    var docCollectionOne = ((EntityImpl) session.newEntity());
    var ridBagCollectionOne = new LinkBag(session);
    docCollectionOne.setProperty("ridBag", ridBagCollectionOne);

    session.begin();

    session.commit();

    final var directory = session.getStorage().getConfiguration().getDirectory();

    final var wowCache =
        (WOWCache) ((LocalStorage) (session.getStorage())).getWriteCache();

    final var fileId =
        wowCache.fileIdByName(
            LinkCollectionsBTreeManagerShared.FILE_NAME_PREFIX
                + collectionIdOne
                + LinkCollectionsBTreeManagerShared.FILE_EXTENSION);
    final var fileName = wowCache.nativeFileNameById(fileId);
    assert fileName != null;
    final var ridBagOneFile = new File(directory, fileName);
    Assert.assertTrue(ridBagOneFile.exists());
  }

  public void testIteratorOverAfterRemove() {
    session.begin();
    var scuti =
        ((EntityImpl) session.newEntity());
    scuti.setProperty("name", "UY Scuti");

    var cygni =
        ((EntityImpl) session.newEntity());
    cygni.setProperty("name", "NML Cygni");

    var scorpii =
        ((EntityImpl) session.newEntity());
    scorpii.setProperty("name", "AH Scorpii");

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    scuti = activeTx.load(scuti);
    cygni = activeTx.load(cygni);
    scorpii = activeTx.load(scorpii);

    var expectedResult = new HashSet<>(Arrays.asList(scuti, scorpii));

    var bag = new LinkBag(session);
    bag.add(scuti.getIdentity());
    bag.add(cygni.getIdentity());
    bag.add(scorpii.getIdentity());

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridBag", bag);

    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    bag = doc.getProperty("ridBag");
    bag.remove(cygni.getIdentity());

    Set<EntityImpl> result = new HashSet<>();
    for (Identifiable identifiable : bag) {
      var transaction = session.getActiveTransaction();
      result.add(transaction.load(identifiable));
    }

    final var tx = session.getActiveTransaction();
    Assert.assertEquals(
        result,
        expectedResult.stream()
            .map(tx::load)
            .collect(Collectors.toSet())
    );
    session.commit();
  }

  public void testRidBagConversion() {
    final var oldThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(5);

    session.begin();
    var doc_1 = ((EntityImpl) session.newEntity());

    var doc_2 = ((EntityImpl) session.newEntity());

    var doc_3 = ((EntityImpl) session.newEntity());

    var doc_4 = ((EntityImpl) session.newEntity());

    var doc = ((EntityImpl) session.newEntity());

    var bag = new LinkBag(session);
    bag.add(doc_1.getIdentity());
    bag.add(doc_2.getIdentity());
    bag.add(doc_3.getIdentity());
    bag.add(doc_4.getIdentity());

    doc.setProperty("ridBag", bag);

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    doc = activeTx1.load(doc);
    var doc_5 = ((EntityImpl) session.newEntity());

    var doc_6 = ((EntityImpl) session.newEntity());

    bag = doc.getProperty("ridBag");
    bag.add(doc_5.getIdentity());
    bag.add(doc_6.getIdentity());

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    bag = doc.getProperty("ridBag");
    Assert.assertEquals(bag.size(), 6);

    List<Identifiable> docs = new ArrayList<>();

    docs.add(doc_1.getIdentity());
    docs.add(doc_2.getIdentity());
    docs.add(doc_3.getIdentity());
    docs.add(doc_4.getIdentity());
    docs.add(doc_5.getIdentity());
    docs.add(doc_6.getIdentity());

    for (Identifiable rid : bag) {
      Assert.assertTrue(docs.remove(rid));
    }

    Assert.assertTrue(docs.isEmpty());

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(oldThreshold);
    session.rollback();
  }

  public void testRidBagDelete() {
    if (session.getStorage().getType().equals(EngineRemote.NAME)
        || session.getStorage().getType().equals(EngineMemory.NAME)) {
      return;
    }

    var realDoc = ((EntityImpl) session.newEntity());
    var realDocRidBag = new LinkBag(session);
    realDoc.setProperty("ridBag", realDocRidBag);

    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());
      realDocRidBag.add(docToAdd.getIdentity());
    }

    assertEmbedded(realDocRidBag.isEmbedded());

    session.begin();

    session.commit();

    final var collectionId = session.addCollection("ridBagDeleteTest");

    var testDocument = crateTestDeleteDoc(realDoc);
    session.freeze();
    session.release();

    final var directory = session.getStorage().getConfiguration().getDirectory();

    var testRidBagFile =
        new File(
            directory,
            LinkCollectionsBTreeManagerShared.FILE_NAME_PREFIX
                + collectionId
                + LinkCollectionsBTreeManagerShared.FILE_EXTENSION);
    var testRidBagSize = testRidBagFile.length();

    for (var i = 0; i < 100; i++) {
      session.begin();
      var activeTx = session.getActiveTransaction();
      activeTx.<EntityImpl>load(testDocument).delete();
      session.commit();

      testDocument = crateTestDeleteDoc(realDoc);
    }

    session.freeze();
    session.release();

    testRidBagFile =
        new File(
            directory,
            LinkCollectionsBTreeManagerShared.FILE_NAME_PREFIX
                + collectionId
                + LinkCollectionsBTreeManagerShared.FILE_EXTENSION);

    Assert.assertEquals(testRidBagFile.length(), testRidBagSize);

    realDoc = session.load(realDoc.getIdentity());
    LinkBag linkBag = realDoc.getProperty("ridBag");
    Assert.assertEquals(linkBag.size(), 10);
  }

  private EntityImpl crateTestDeleteDoc(EntityImpl realDoc) {
    var testDocument = ((EntityImpl) session.newEntity());
    var highLevelRidBag = new LinkBag(session);
    testDocument.setProperty("ridBag", highLevelRidBag);
    var activeTx = session.getActiveTransaction();
    realDoc = activeTx.load(realDoc);
    testDocument.setProperty("realDoc", realDoc);

    session.begin();

    session.commit();

    return testDocument;
  }

  @Override
  protected void assertEmbedded(boolean isEmbedded) {
    Assert.assertTrue((!isEmbedded));
  }
}
