package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import org.junit.Assert;
import org.junit.Test;

public class FrontendTransactionIndexChangesListTest {

  @Test
  public void testEmpty() {
    var list = new FrontendTransactionIndexChangesList();
    Assert.assertEquals(0, list.size());
    try {
      Assert.assertFalse(list.iterator().hasNext());
      list.iterator().next();
      Assert.fail();
    } catch (IllegalStateException ex) {

    }
    try {
      list.get(0);
      Assert.fail();
    } catch (IndexOutOfBoundsException ex) {

    }
  }

  @Test
  public void testAddRemove() {

    var list = new FrontendTransactionIndexChangesList();
    var temp = new FrontendTransactionIndexChangesPerKey(null);

    list.add(
        temp.createEntryInternal(new RecordId(12, 0),
            FrontendTransactionIndexChanges.OPERATION.PUT));
    list.add(
        temp.createEntryInternal(new RecordId(12, 1),
            FrontendTransactionIndexChanges.OPERATION.PUT));
    list.add(
        temp.createEntryInternal(new RecordId(12, 2),
            FrontendTransactionIndexChanges.OPERATION.PUT));
    list.add(
        temp.createEntryInternal(new RecordId(12, 3),
            FrontendTransactionIndexChanges.OPERATION.PUT));
    Assert.assertEquals(4, list.size());

    Assert.assertEquals(2, list.get(2).getValue().getIdentity().getCollectionPosition());

    list.remove(list.get(2));
    Assert.assertEquals(3, list.size());
    Assert.assertEquals(3, list.get(2).getValue().getIdentity().getCollectionPosition());

    list.remove(list.get(0));
    Assert.assertEquals(2, list.size());
    Assert.assertEquals(3, list.get(1).getValue().getIdentity().getCollectionPosition());

    list.add(
        temp.createEntryInternal(new RecordId(12, 4),
            FrontendTransactionIndexChanges.OPERATION.PUT));
    Assert.assertEquals(3, list.size());
    Assert.assertEquals(4, list.get(2).getValue().getIdentity().getCollectionPosition());
  }
}
