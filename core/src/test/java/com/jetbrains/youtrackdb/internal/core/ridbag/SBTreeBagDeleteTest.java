package com.jetbrains.youtrackdb.internal.core.ridbag;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class SBTreeBagDeleteTest extends BaseMemoryInternalDatabase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
  }

  @Test
  public void testDeleteRidbagTx() throws InterruptedException {
    var size =
        GlobalConfiguration.INDEX_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.getValueAsInteger() << 1;
    var stubIds = new ArrayList<RID>();
    session.begin();
    for (var i = 0; i < size; i++) {
      var stub = session.newEntity();
      stubIds.add(stub.getIdentity());
    }
    session.commit();

    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var bag = new LinkBag(session);

    for (var i = 0; i < size; i++) {
      bag.add(stubIds.get(i));
    }

    entity.setProperty("bag", bag);

    var id = entity.getIdentity();
    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    entity = activeTx1.load(entity);
    bag = entity.getProperty("bag");
    var pointer = bag.getPointer();

    var activeTx = session.getActiveTransaction();
    entity = activeTx.load(entity);
    session.delete(entity);
    session.commit();

    session.begin();
    try {
      session.load(id);
      Assert.fail();
    } catch (RecordNotFoundException e) {
      // ignore
    }
    session.rollback();

    Thread.sleep(100);
    var tree =
        session.getBTreeCollectionManager().loadIsolatedBTree(pointer);
    assertEquals(0, tree.getRealBagSize());
  }
}
