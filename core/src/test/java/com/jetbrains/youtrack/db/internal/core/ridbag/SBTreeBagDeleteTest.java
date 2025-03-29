package com.jetbrains.youtrack.db.internal.core.ridbag;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class SBTreeBagDeleteTest extends BaseMemoryInternalDatabase {

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
    var bag = new RidBag(session);

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
        session.getBTreeCollectionManager().loadSBTree(pointer);
    assertEquals(0, tree.getRealBagSize());
  }
}
