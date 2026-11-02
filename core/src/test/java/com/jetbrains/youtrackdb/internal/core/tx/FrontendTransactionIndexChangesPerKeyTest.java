package com.jetbrains.youtrackdb.internal.core.tx;

import static com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION.PUT;
import static com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION.REMOVE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

public class FrontendTransactionIndexChangesPerKeyTest {

  private final RecordId r = new RecordId(12, 0);
  private final RecordId s = new RecordId(12, 1);
  private final FrontendTransactionIndexChangesPerKey changes =
      new FrontendTransactionIndexChangesPerKey("key");

  @Test
  public void removeCancelsPutAfterWholeKeyRemovalNotBeforeIt() {
    changes.add(r, PUT);
    changes.add(null, REMOVE);
    changes.add(r, PUT);
    changes.add(r, REMOVE);

    assertPrefixBeforeBarrier();
    assertEquals(2, changes.getEntriesAsList().size());
  }

  @Test
  public void redundantRemoveAfterWholeKeyRemovalPreservesEarlierPut() {
    changes.add(r, PUT);
    changes.add(null, REMOVE);
    changes.add(r, REMOVE);

    assertPrefixBeforeBarrier();
    assertEquals(2, changes.getEntriesAsList().size());
  }

  @Test
  public void redundantRemoveDoesNotHideAnotherRidAddedAfterBarrier() {
    changes.add(r, PUT);
    changes.add(null, REMOVE);
    changes.add(s, PUT);
    changes.add(r, REMOVE);

    assertPrefixBeforeBarrier();
    assertEquals(3, changes.getEntriesAsList().size());
    assertEntry(2, s, PUT);
  }

  @Test
  public void putAndRemoveWithoutWholeKeyRemovalCancelEachOther() {
    changes.add(r, PUT);
    changes.add(r, REMOVE);

    assertEquals(0, changes.getEntriesAsList().size());
  }

  @Test
  public void removeWithoutWholeKeyRemovalOrPutIsRecorded() {
    changes.add(r, REMOVE);

    assertEquals(1, changes.getEntriesAsList().size());
    assertEntry(0, r, REMOVE);
  }

  @Test
  public void onlyPutsAfterLastOfSeveralWholeKeyRemovalsCanBeCancelled() {
    changes.add(null, REMOVE);
    changes.add(r, PUT);
    changes.add(null, REMOVE);
    changes.add(r, REMOVE);

    assertEquals(3, changes.getEntriesAsList().size());
    assertEntry(0, null, REMOVE);
    assertEntry(1, r, PUT);
    assertEntry(2, null, REMOVE);
  }

  @Test
  public void repeatedPutsCancelFirstMatchingPutAfterLastBarrier() {
    changes.add(r, PUT);
    changes.add(null, REMOVE);
    changes.add(r, PUT);
    changes.add(s, PUT);
    changes.add(r, PUT);
    changes.add(r, REMOVE);

    assertPrefixBeforeBarrier();
    assertEquals(4, changes.getEntriesAsList().size());
    assertEntry(2, s, PUT);
    assertEntry(3, r, PUT);
    changes.add(r, REMOVE);
    changes.add(r, REMOVE);
    assertEquals(3, changes.getEntriesAsList().size());
    assertEntry(2, s, PUT);
  }

  @Test
  public void putAfterRedundantRemoveIsStillRecorded() {
    changes.add(null, REMOVE);
    changes.add(r, REMOVE);
    changes.add(r, PUT);

    assertEquals(2, changes.getEntriesAsList().size());
    assertEntry(0, null, REMOVE);
    assertEntry(1, r, PUT);
  }

  private void assertPrefixBeforeBarrier() {
    assertEntry(0, r, PUT);
    assertEntry(1, null, REMOVE);
  }

  private void assertEntry(int position, RecordId value,
      FrontendTransactionIndexChanges.OPERATION operation) {
    var entry = changes.getEntriesAsList().get(position);
    assertEquals(operation, entry.getOperation());
    if (value == null) {
      assertNull(entry.getValue());
    } else {
      assertEquals(value, entry.getValue());
    }
  }
}
