package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.util.ArrayList;
import org.junit.Assert;
import org.junit.Test;

public class LinkListTest extends DbTestBase {

  @Test
  public void testRollBackChangesOne() {
    session.begin();
    final var entity = session.newEntity();

    final var originalList = new ArrayList<Identifiable>();
    var entity1 = session.newEntity();
    originalList.add(entity1);

    var entity2 = session.newEntity();
    originalList.add(entity2);

    var entity3 = session.newEntity();
    originalList.add(entity3);

    var entity4 = session.newEntity();
    originalList.add(entity4);

    var entity5 = session.newEntity();
    originalList.add(entity5);

    var trackedList = entity.newLinkList("list", originalList);
    var tx = session.getActiveTransaction();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    var entity6 = session.newEntity();
    trackedList.add(entity6);

    var entity7 = session.newEntity();
    trackedList.add(entity7);

    var entity10 = session.newEntity();
    trackedList.set(2, entity10);

    var entity8 = session.newEntity();
    trackedList.add(1, entity8);
    trackedList.add(1, entity8);

    trackedList.remove(3);
    trackedList.remove(entity7);

    var entity9 = session.newEntity();
    trackedList.addFirst(entity9);
    trackedList.addFirst(entity9);
    trackedList.addFirst(entity9);
    trackedList.addFirst(entity9);

    trackedList.remove(entity9);
    trackedList.remove(entity9);

    var entity11 = session.newEntity();
    trackedList.add(4, entity11);

    ((EntityLinkListImpl) trackedList).rollbackChanges(tx);

    Assert.assertEquals(originalList, trackedList);
    session.rollback();
  }

  @Test
  public void testRollBackChangesTwo() {
    session.begin();
    final var entity = session.newEntity();

    final var originalList = new ArrayList<Identifiable>();

    var entity1 = session.newEntity();
    originalList.add(entity1);

    var entity2 = session.newEntity();
    originalList.add(entity2);

    var entity3 = session.newEntity();
    originalList.add(entity3);

    var entity4 = session.newEntity();
    originalList.add(entity4);

    var entity5 = session.newEntity();
    originalList.add(entity5);

    var trackedList = entity.newLinkList("list", originalList);
    var tx = session.getActiveTransaction();
    tx.preProcessRecordsAndExecuteCallCallbacks();

    var entity6 = session.newEntity();
    trackedList.add(entity6);

    var entity7 = session.newEntity();
    trackedList.add(entity7);

    var entity10 = session.newEntity();
    trackedList.set(2, entity10);

    var entity8 = session.newEntity();
    trackedList.add(1, entity8);
    trackedList.remove(3);
    trackedList.clear();

    //noinspection RedundantOperationOnEmptyContainer
    trackedList.remove(entity7);

    var entity9 = session.newEntity();
    trackedList.addFirst(entity9);

    var entity11 = session.newEntity();
    trackedList.add(entity11);

    var entity12 = session.newEntity();
    trackedList.addFirst(entity12);
    trackedList.add(entity12);

    ((EntityLinkListImpl) trackedList).rollbackChanges(tx);

    Assert.assertEquals(originalList, trackedList);
    session.rollback();
  }
}
