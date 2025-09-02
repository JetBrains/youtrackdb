package com.jetbrains.youtrackdb.auto;

import static org.testng.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.common.query.collection.links.LinkSet;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import java.io.IOException;
import java.util.HashSet;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class EmbeddedLinkSetTest extends AbstractLinkSetTest {
  private int topThreshold;
  private int bottomThreshold;

  @Override
  @BeforeMethod
  public void beforeMethod() throws Exception {
    topThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(Integer.MAX_VALUE);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(Integer.MAX_VALUE);

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
  public void testFromEmbeddedToBTreeAndBack() throws IOException {
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(7);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(-1);

    session.begin();
    var linkSet = (EntityLinkSetImpl) session.newLinkSet();
    var entity = session.newEntity();
    entity.setProperty("linkSet", linkSet);

    assertIsEmbedded(linkSet);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");
    assertIsEmbedded(linkSet);

    var addedItems = new HashSet<RID>();
    for (var i = 0; i < 6; i++) {
      session.begin();
      var entityToAdd = session.newEntity();

      linkSet = entity.getProperty("linkSet");
      linkSet.add(entityToAdd.getIdentity());
      addedItems.add(entityToAdd.getIdentity());
      session.commit();
    }

    session.commit();

    addedItems = new HashSet<>(addedItems);
    session.begin();

    activeTx = session.getActiveTransaction();
    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");

    assertIsEmbedded(linkSet);
    session.rollback();

    session.begin();
    var entityToAdd = session.newEntity();

    session.commit();
    session.begin();

    activeTx = session.getActiveTransaction();
    entityToAdd = activeTx.loadEntity(entityToAdd);

    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");

    linkSet.add(entityToAdd.getIdentity());
    addedItems.add(entityToAdd.getIdentity());

    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();

    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");

    Assert.assertFalse(linkSet.isEmbedded());

    var addedItemsCopy = new HashSet<>(addedItems);
    for (var id : linkSet) {
      assertTrue(addedItems.remove(id.getIdentity()));
    }

    assertTrue(addedItems.isEmpty());
    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();

    entity = activeTx.loadEntity(entity);
    linkSet = entity.getProperty("linkSet");
    Assert.assertFalse(linkSet.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (var id : linkSet) {
      assertTrue(addedItems.remove(id.getIdentity()));
    }

    assertTrue(addedItems.isEmpty());

    addedItems.addAll(addedItemsCopy);

    for (var i = 0; i < 3; i++) {
      var toRemove = addedItems.iterator().next();
      addedItems.remove(toRemove.getIdentity());
      linkSet.remove(toRemove.getIdentity());
    }

    addedItemsCopy.clear();
    addedItemsCopy.addAll(addedItems);

    session.commit();

    session.begin();
    activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    linkSet = entity.getProperty("linkSet");
    Assert.assertFalse(linkSet.isEmbedded());

    for (var id : linkSet) {
      assertTrue(addedItems.remove(id.getIdentity()));
    }

    assertTrue(addedItems.isEmpty());

    linkSet = entity.getProperty("linkSet");
    Assert.assertFalse(linkSet.isEmbedded());

    addedItems.addAll(addedItemsCopy);
    for (var id : linkSet) {
      assertTrue(addedItems.remove(id.getIdentity()));
    }

    assertTrue(addedItems.isEmpty());
    session.commit();
  }


  @Override
  protected void assertIsEmbedded(LinkSet set) {
    assertTrue(((EntityLinkSetImpl) set).isEmbedded());
  }
}
