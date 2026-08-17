package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineReference;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexLifecycleCell;
import org.junit.Test;

/** Verifies legal carrier shapes and the component invariants that reject torn states. */
public class IndexHandleStateTest {

  @Test
  public void emptyAndEngineShapesExposeTheirDerivedState() {
    assertFalse(IndexHandleState.EMPTY.hasEngine());
    assertFalse(IndexHandleState.EMPTY.isDurablyIdentified());

    var engine = new IndexHandleState(1, new IndexEngineReference(1, 0, 1), null, null);
    assertTrue(engine.hasEngine());
    assertFalse(engine.isDurablyIdentified());
  }

  @Test
  public void absentEngineRejectsReference() {
    assertThrows(
        IllegalStateException.class,
        () -> new IndexHandleState(-1, new IndexEngineReference(1, 0, 1), null, null));
  }

  @Test
  public void lifecycleCellRequiresDurableIdentity() {
    var cell = mock(IndexLifecycleCell.class);
    assertThrows(
        IllegalStateException.class,
        () -> new IndexHandleState(1, new IndexEngineReference(1, 0, 1), null, cell));

    var durable = new IndexHandleState(
        1, new IndexEngineReference(1, 0, 1), new RecordId(3, 4), cell);
    assertTrue(durable.isDurablyIdentified());
  }
}
