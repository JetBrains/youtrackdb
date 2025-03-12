package com.jetbrains.youtrack.db.internal.core.ridbag;

import static com.jetbrains.youtrack.db.api.config.GlobalConfiguration.RID_BAG_SBTREEBONSAI_DELETE_DELAY;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class SBTreeBagDeleteTest extends BaseMemoryInternalDatabase {

  public void beforeTest() throws Exception {
    super.beforeTest();
    session.getConfiguration().setValue(RID_BAG_SBTREEBONSAI_DELETE_DELAY, 50);
  }

  @Test
  public void testDeleteRidbagTx() throws InterruptedException {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    var bag = new RidBag(session);
    var size =
        GlobalConfiguration.INDEX_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.getValueAsInteger() << 1;
    for (var i = 0; i < size; i++) {
      bag.add(new RecordId(10, i));
    }

    entity.setProperty("bag", bag);

    var id = entity.getIdentity();
    session.commit();

    session.begin();
    entity = session.bindToSession(entity);
    bag = entity.field("bag");
    var pointer = bag.getPointer();

    entity = session.bindToSession(entity);
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
    assertEquals(0, tree.getRealBagSize(Collections.emptyMap()));
  }
}
