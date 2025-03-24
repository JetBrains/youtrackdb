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
import com.jetbrains.youtrack.db.internal.client.remote.ServerAdmin;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.engine.memory.EngineMemory;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrack.db.internal.core.storage.disk.LocalPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManagerShared;
import java.io.File;
import java.io.IOException;
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
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 *
 */
@Test
public class BTreeBasedRidBagTest extends RidBagTest {

  private int topThreshold;
  private int bottomThreshold;

  @Parameters(value = "remote")
  public BTreeBasedRidBagTest(@Optional Boolean remote) {
    //super(remote != null && remote);
    super(true);
  }

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();
  }

  @BeforeMethod
  public void beforeMethod() throws IOException {
    topThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    if (session.isRemote()) {
      var server =
          new ServerAdmin(session.getURL())
              .connect("root", SERVER_PASSWORD);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD, -1);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD, -1);
      server.close();
    }

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(-1);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(-1);
  }

  @AfterMethod
  public void afterMethod() throws IOException {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);

    if (session.isRemote()) {
      var server =
          new ServerAdmin(session.getURL())
              .connect("root", SERVER_PASSWORD);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD, topThreshold);
      server.setGlobalConfiguration(
          GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD, bottomThreshold);
      server.close();
    }
  }

  public void testRidBagClusterDistribution() {
    if (session.getStorage().getType().equals(EngineRemote.NAME)
        || session.getStorage().getType().equals(EngineMemory.NAME)) {
      return;
    }

    final var clusterIdOne = session.addCluster("clusterOne");

    var docClusterOne = ((EntityImpl) session.newEntity());
    var ridBagClusterOne = new RidBag(session);
    docClusterOne.setProperty("ridBag", ridBagClusterOne);

    session.begin();

    session.commit();

    final var directory = session.getStorage().getConfiguration().getDirectory();

    final var wowCache =
        (WOWCache) ((LocalPaginatedStorage) (session.getStorage())).getWriteCache();

    final var fileId =
        wowCache.fileIdByName(
            BTreeCollectionManagerShared.FILE_NAME_PREFIX
                + clusterIdOne
                + BTreeCollectionManagerShared.FILE_EXTENSION);
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

    var activeTx3 = session.getActiveTransaction();
    scuti = activeTx3.load(scuti);
    var activeTx2 = session.getActiveTransaction();
    cygni = activeTx2.load(cygni);
    var activeTx1 = session.getActiveTransaction();
    scorpii = activeTx1.load(scorpii);

    var expectedResult = new HashSet<EntityImpl>(Arrays.asList(scuti, scorpii));

    var bag = new RidBag(session);
    bag.add(scuti.getIdentity());
    bag.add(cygni.getIdentity());
    bag.add(scorpii.getIdentity());

    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("ridBag", bag);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
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

    var bag = new RidBag(session);
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
    var realDocRidBag = new RidBag(session);
    realDoc.setProperty("ridBag", realDocRidBag);

    for (var i = 0; i < 10; i++) {
      var docToAdd = ((EntityImpl) session.newEntity());
      realDocRidBag.add(docToAdd.getIdentity());
    }

    assertEmbedded(realDocRidBag.isEmbedded());

    session.begin();

    session.commit();

    final var clusterId = session.addCluster("ridBagDeleteTest");

    var testDocument = crateTestDeleteDoc(realDoc);
    session.freeze();
    session.release();

    final var directory = session.getStorage().getConfiguration().getDirectory();

    var testRidBagFile =
        new File(
            directory,
            BTreeCollectionManagerShared.FILE_NAME_PREFIX
                + clusterId
                + BTreeCollectionManagerShared.FILE_EXTENSION);
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
            BTreeCollectionManagerShared.FILE_NAME_PREFIX
                + clusterId
                + BTreeCollectionManagerShared.FILE_EXTENSION);

    Assert.assertEquals(testRidBagFile.length(), testRidBagSize);

    realDoc = session.load(realDoc.getIdentity());
    RidBag ridBag = realDoc.getProperty("ridBag");
    Assert.assertEquals(ridBag.size(), 10);
  }

  private EntityImpl crateTestDeleteDoc(EntityImpl realDoc) {
    var testDocument = ((EntityImpl) session.newEntity());
    var highLevelRidBag = new RidBag(session);
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
    Assert.assertTrue((!isEmbedded || session.isRemote()));
  }
}
