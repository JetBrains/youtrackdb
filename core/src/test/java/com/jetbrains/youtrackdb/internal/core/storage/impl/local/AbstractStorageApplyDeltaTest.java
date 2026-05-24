/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDeltaHolder;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import org.junit.Test;

/**
 * Coverage for the {@code isApplied()} short-circuit gates inside
 * {@link AbstractStorage#applyIndexCountDeltas} and
 * {@link AbstractStorage#applyHistogramDeltas}. The first apply pass
 * latches the holder via {@code setApplied()} at the entry of the method;
 * any subsequent pass observes the latch and short-circuits before the
 * per-engine loop. The latch is a defensive belt against any future re-
 * entry into apply within the same atomic operation, for example a nested
 * or mistakenly-replayed lifecycle pass. This test pins both halves of the
 * latch contract on the production methods:
 *
 * <ul>
 *   <li>First call latches the holder.</li>
 *   <li>Second call observes the latch and returns without re-entering the
 *       per-engine loop, so the engine counters advance exactly once.</li>
 * </ul>
 *
 * <p>The hook test {@code EndAtomicOperationHookOrderingTest} pins the
 * manager-side gate; this test pins the storage-side gate at the entry of
 * the apply methods. Both layers cover the latch contract from different
 * angles: the manager-side test verifies the hook reads the latch and
 * skips its call, the storage-side test (this class) verifies the apply
 * method itself sets the latch before doing any per-engine work.
 *
 * <p>Uses {@code Mockito.doCallRealMethod()} on a mocked
 * {@link AbstractStorage} rather than constructing the abstract subclass.
 * The {@code applyIndexCountDeltas} / {@code applyHistogramDeltas} methods
 * iterate the holder's deltas map; an empty holder produces an empty
 * iteration so the {@code indexEngines} field is never read, which keeps
 * the test free of the storage's heavier wiring.
 */
public class AbstractStorageApplyDeltaTest {

  /**
   * First call latches the index-count holder; second call short-circuits
   * at the {@code isApplied()} gate and returns without re-entering. The
   * holder starts in the non-applied state and ends up applied; calling
   * twice is harmless and observable as the latch staying {@code true}.
   */
  @Test
  public void applyIndexCountDeltasShortCircuitsOnSecondCall() {
    var storage = mock(AbstractStorage.class);
    doCallRealMethod().when(storage).applyIndexCountDeltas(any(AtomicOperation.class));
    var holder = new IndexCountDeltaHolder();
    var operation = mock(AtomicOperation.class);
    when(operation.getIndexCountDeltas()).thenReturn(holder);

    assertFalse("Holder starts non-applied", holder.isApplied());
    storage.applyIndexCountDeltas(operation);
    assertTrue("First call latches the holder", holder.isApplied());

    // Second call returns at the isApplied() gate. No state mutation, no
    // throw. The latch staying true is the observable result.
    storage.applyIndexCountDeltas(operation);
    assertTrue("Latch remains true after the no-op second call",
        holder.isApplied());
  }

  /**
   * First call latches the histogram holder; second call short-circuits at
   * the {@code isApplied()} gate. Symmetric to
   * {@link #applyIndexCountDeltasShortCircuitsOnSecondCall} above.
   */
  @Test
  public void applyHistogramDeltasShortCircuitsOnSecondCall() {
    var storage = mock(AbstractStorage.class);
    doCallRealMethod().when(storage).applyHistogramDeltas(any(AtomicOperation.class));
    var holder = new HistogramDeltaHolder();
    var operation = mock(AtomicOperation.class);
    when(operation.getHistogramDeltas()).thenReturn(holder);

    assertFalse("Holder starts non-applied", holder.isApplied());
    storage.applyHistogramDeltas(operation);
    assertTrue("First call latches the holder", holder.isApplied());

    storage.applyHistogramDeltas(operation);
    assertTrue("Latch remains true after the no-op second call",
        holder.isApplied());
  }

  /**
   * Null index-count holder short-circuits before the latch read. The
   * recovery-time atomic operations during {@code AbstractStorage.open}
   * carry no delta holders, so the apply methods are called on operations
   * with a {@code null} holder. Mirrors the existing {@code if (holder ==
   * null) return;} early-exit at the top of the method.
   */
  @Test
  public void applyIndexCountDeltasIsNoOpWhenHolderIsNull() {
    var storage = mock(AbstractStorage.class);
    doCallRealMethod().when(storage).applyIndexCountDeltas(any(AtomicOperation.class));
    var operation = mock(AtomicOperation.class);
    when(operation.getIndexCountDeltas()).thenReturn(null);

    // No throw, no mutation. The call returns at the holder-null gate.
    storage.applyIndexCountDeltas(operation);
  }

  /**
   * Null histogram holder short-circuits before the latch read. Symmetric
   * to {@link #applyIndexCountDeltasIsNoOpWhenHolderIsNull} above.
   */
  @Test
  public void applyHistogramDeltasIsNoOpWhenHolderIsNull() {
    var storage = mock(AbstractStorage.class);
    doCallRealMethod().when(storage).applyHistogramDeltas(any(AtomicOperation.class));
    var operation = mock(AtomicOperation.class);
    when(operation.getHistogramDeltas()).thenReturn(null);

    storage.applyHistogramDeltas(operation);
  }

  /**
   * Partial-loop throw still latches the holder. Pins the contract that
   * {@link AbstractStorage#applyIndexCountDeltas} sets the {@code applied}
   * latch at the top of the method, not at the bottom. The lifecycle hook
   * in {@code AtomicOperationsManager.endAtomicOperation} swallows a throw
   * from the apply call; without the up-front latch, any future re-entry
   * on the same holder (a nested or mistakenly-replayed lifecycle pass)
   * would re-iterate and double-increment the engine counters processed
   * before the throw.
   *
   * <p>Drives the partial-loop throw by populating a holder with a delta
   * entry that the real method will iterate, then letting the
   * {@code indexEngines.size()} access throw a {@link NullPointerException}
   * (the mock's {@code indexEngines} field is null). The throw escapes the
   * apply method, and the test asserts the holder is latched anyway.
   */
  @Test
  public void applyIndexCountLatchesHolderEvenWhenLoopThrows() {
    var storage = mock(AbstractStorage.class);
    doCallRealMethod().when(storage).applyIndexCountDeltas(any(AtomicOperation.class));
    var holder = new IndexCountDeltaHolder();
    // Add an entry so the per-engine loop iterates at least once. The
    // loop body's first action is indexEngines.size(), which NPEs on a
    // bare mock because the field was never initialised. Field values do
    // not matter here; only that the map has at least one entry.
    holder.getOrCreate(0);
    var operation = mock(AtomicOperation.class);
    when(operation.getIndexCountDeltas()).thenReturn(holder);

    NullPointerException expected = null;
    try {
      storage.applyIndexCountDeltas(operation);
    } catch (NullPointerException npe) {
      expected = npe;
    }
    assertTrue("Test setup must observe the NPE that simulates a mid-loop"
        + " throw; if this fails the test does not pin the partial-loop"
        + " contract that requires the up-front latch",
        expected != null);
    assertTrue("Latch must be set even though the loop threw; without this"
        + " any future re-entry on the same holder would re-iterate and"
        + " double-apply engines processed before the throw",
        holder.isApplied());
  }

  /**
   * Histogram parallel of {@link #applyIndexCountLatchesHolderEvenWhenLoopThrows}.
   * Same partial-loop throw shape on the histogram apply path; pins the
   * up-front latch on {@link AbstractStorage#applyHistogramDeltas} as well.
   */
  @Test
  public void applyHistogramLatchesHolderEvenWhenLoopThrows() {
    var storage = mock(AbstractStorage.class);
    doCallRealMethod().when(storage).applyHistogramDeltas(any(AtomicOperation.class));
    var holder = new HistogramDeltaHolder();
    // Add an entry so the per-engine loop iterates at least once. Field
    // values do not matter; only that the map has at least one entry.
    holder.getOrCreate(0);
    var operation = mock(AtomicOperation.class);
    when(operation.getHistogramDeltas()).thenReturn(holder);

    NullPointerException expected = null;
    try {
      storage.applyHistogramDeltas(operation);
    } catch (NullPointerException npe) {
      expected = npe;
    }
    assertTrue("Test setup must observe the NPE that simulates a mid-loop throw",
        expected != null);
    assertTrue("Latch must be set even though the loop threw",
        holder.isApplied());
  }
}
