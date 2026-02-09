package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.engine.memory.EngineMemory;
import com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkSet;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class BTreeLinkSetTest extends AbstractLinkSetTest {

  private int topThreshold;
  private int bottomThreshold;

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

  @Test
  public void testLinkSetCollectionDistribution() {
    if (session.getStorage().getType().equals(EngineMemory.NAME)) {
      return;
    }
    final var collectionId = session.addCollection("collectionOne");
    session.begin();
    var entity = session.newEntity();
    var linkSet = session.newLinkSet();
    entity.setProperty("linkSet", linkSet);
    session.commit();

    final var directory = ((DiskStorage) session.getStorage()).getStoragePath().toString();
    final var wowCache =
        (WOWCache) (session.getStorage()).getWriteCache();

    final var fileId =
        wowCache.fileIdByName(
            LinkCollectionsBTreeManagerShared.FILE_NAME_PREFIX
                + collectionId
                + LinkCollectionsBTreeManagerShared.FILE_EXTENSION);
    final var fileName = wowCache.nativeFileNameById(fileId);
    assert fileName != null;
    final var linkSetFile = new File(directory, fileName);
    Assert.assertTrue(linkSetFile.exists());
  }

  @Test
  public void testIteratorOverAfterRemove() {
    session.begin();
    var scuti = session.newEntity();
    scuti.setProperty("name", "UY Scuti");

    var cygni = session.newEntity();
    cygni.setProperty("name", "NML Cygni");

    var scorpii = session.newEntity();
    scorpii.setProperty("name", "AH Scorpii");
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    scuti = activeTx.load(scuti);
    cygni = activeTx.load(cygni);
    scorpii = activeTx.load(scorpii);

    var expectedResult = new HashSet<>(Arrays.asList(scuti, scorpii));

    var linkSet = session.newLinkSet();
    linkSet.add(scuti.getIdentity());
    linkSet.add(cygni.getIdentity());
    linkSet.add(scorpii.getIdentity());

    var entity = session.newEntity();
    entity.setProperty("linkSet", linkSet);
    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    linkSet = entity.getProperty("linkSet");
    linkSet.remove(cygni.getIdentity());

    Set<Entity> result = new HashSet<>();
    for (var identifiable : linkSet) {
      var transaction = session.getActiveTransaction();
      result.add(transaction.loadEntity(identifiable));
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

  @Test
  public void testLinkSetConversion() {
    final var oldThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(5);

    session.begin();
    var entity_1 = session.newEntity();

    var entity_2 = session.newEntity();

    var entity_3 = session.newEntity();

    var entity_4 = session.newEntity();

    var entity = session.newEntity();

    var linkSet = session.newLinkSet();

    linkSet.add(entity_1.getIdentity());
    linkSet.add(entity_2.getIdentity());
    linkSet.add(entity_3.getIdentity());
    linkSet.add(entity_4.getIdentity());

    entity.setProperty("linkSet", linkSet);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.loadEntity(entity);
    var entity_5 = session.newEntity();
    var entity_6 = session.newEntity();

    linkSet = entity.getProperty("linkSet");
    linkSet.add(entity_5.getIdentity());
    linkSet.add(entity_6.getIdentity());

    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();
    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");
    Assert.assertEquals(linkSet.size(), 6);

    List<Identifiable> docs = new ArrayList<>();

    docs.add(entity_1.getIdentity());
    docs.add(entity_2.getIdentity());
    docs.add(entity_3.getIdentity());
    docs.add(entity_4.getIdentity());
    docs.add(entity_5.getIdentity());
    docs.add(entity_6.getIdentity());

    for (var rid : linkSet) {
      Assert.assertTrue(docs.remove(rid));
    }

    Assert.assertTrue(docs.isEmpty());

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(oldThreshold);
    session.rollback();
  }

  @Test
  public void testRemoveLinkSet() {
    var rid = session.computeInTx(transaction -> {
      var linkSet = session.newLinkSet();
      var entityHolder = session.newEntity();

      for (var i = 0; i < 10; i++) {
        var entity = session.newEntity();
        linkSet.add(entity.getIdentity());
      }

      entityHolder.setLinkSet("linkSet", linkSet);
      return entityHolder.getIdentity();
    });

    var pointer = session.computeInTx(transaction -> {
      var entityHolder = transaction.loadEntity(rid);
      var linkSet = entityHolder.getLinkSet("linkSet");
      Assert.assertEquals(linkSet.size(), 10);

      assertIsEmbedded(linkSet);

      var delegate = (BTreeBasedLinkBag) ((EntityLinkSetImpl) linkSet).getDelegate();
      return delegate.getCollectionPointer();
    });

    session.executeInTx(transaction -> {
      var entityHolder = transaction.loadEntity(rid);
      entityHolder.delete();
    });

    var collectionManager = session.getStorage().getLinkCollectionsBtreeCollectionManager();
    var isolatedTree = collectionManager.loadIsolatedBTree(pointer);
    var activeTx = session.begin();
    Assert.assertEquals(isolatedTree.getRealBagSize(activeTx.getAtomicOperation()), 0);
    session.rollback();
  }

  @Override
  protected void assertIsEmbedded(LinkSet set) {
    Assert.assertFalse(((EntityLinkSetImpl) set).isEmbedded());
  }
}
