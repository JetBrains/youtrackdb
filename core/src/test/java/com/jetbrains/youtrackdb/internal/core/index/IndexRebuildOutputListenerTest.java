package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link IndexRebuildOutputListener}, the progress listener that logs rebuild progress
 * for index creation and rebuilding.
 *
 * <p>Tests exercise both the {@code rebuild=true} and {@code rebuild=false} branches in
 * {@link IndexRebuildOutputListener#onBegin}, {@link IndexRebuildOutputListener#onProgress}, and
 * {@link IndexRebuildOutputListener#onCompletition}. Verification focuses on successful execution
 * without exception (the listener's primary contract is side-effect logging; behaviour under test
 * is that all paths complete without error for any combination of arguments).
 */
public class IndexRebuildOutputListenerTest extends DbTestBase {

  private static final String CLS = "IrolTestCls";
  private static final String IDX = CLS + ".val";

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();
    var cls = session.getMetadata().getSchema().createClass(CLS);
    cls.createProperty("val", PropertyType.INTEGER);
    cls.createIndex(IDX, SchemaClass.INDEX_TYPE.UNIQUE, "val");

    // Insert a few records so the index is non-empty (used in onCompletition path).
    session.begin();
    for (int i = 1; i <= 3; i++) {
      var e = session.newEntity(CLS);
      e.setProperty("val", i);
    }
    session.commit();
  }

  // -----------------------------------------------------------------------
  //  onBegin
  // -----------------------------------------------------------------------

  /**
   * onBegin with iTotal > 0 and rebuild=true must complete without exception (logs an
   * INFO message about rebuilding) and the listener must remain functional afterwards —
   * a follow-up onProgress call must return true to continue iteration.
   */
  @Test
  public void onBegin_rebuildTrue_totalPositive_acceptsProgressAfterwards() {
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    var listener = new IndexRebuildOutputListener(idx);
    // rebuild=true, total > 0 → INFO branch taken.
    listener.onBegin(this, 100L, Boolean.TRUE);
    assertTrue("listener must accept progress after onBegin",
        listener.onProgress(this, 50L, 0.5f));
  }

  /**
   * onBegin with iTotal > 0 and rebuild=false must complete without exception (logs a
   * DEBUG message about building) and remain functional for subsequent onProgress calls.
   */
  @Test
  public void onBegin_rebuildFalse_totalPositive_acceptsProgressAfterwards() {
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    var listener = new IndexRebuildOutputListener(idx);
    // rebuild=false, total > 0 → DEBUG branch taken.
    listener.onBegin(this, 100L, Boolean.FALSE);
    assertTrue("listener must accept progress after onBegin",
        listener.onProgress(this, 50L, 0.5f));
  }

  /**
   * onBegin with iTotal == 0 must complete without exception (neither branch logs since
   * the iTotal > 0 guard is false) and the listener must accept onProgress afterwards.
   */
  @Test
  public void onBegin_zeroTotal_acceptsProgressAfterwards() {
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    var listener = new IndexRebuildOutputListener(idx);
    listener.onBegin(this, 0L, Boolean.TRUE);
    assertTrue("listener must accept progress after onBegin (zero total)",
        listener.onProgress(this, 0L, 1.0f));
  }

  // -----------------------------------------------------------------------
  //  onProgress
  // -----------------------------------------------------------------------

  /**
   * onProgress must return true (continue) and not throw. The time-gated dump is only
   * triggered when more than 10 000 ms have elapsed since the last dump — a fresh listener
   * will not log but must still return true.
   */
  @Test
  public void onProgress_freshListener_returnsTrueWithoutException() {
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    var listener = new IndexRebuildOutputListener(idx);
    listener.onBegin(this, 1000L, Boolean.TRUE);
    assertTrue("onProgress must return true to signal 'continue'",
        listener.onProgress(this, 5L, 0.5f));
  }

  // -----------------------------------------------------------------------
  //  onCompletition
  // -----------------------------------------------------------------------

  /**
   * onCompletition with rebuild=true and a non-empty index must log the INFO message about
   * indexed items, complete without exception, and remain idempotent — a second invocation
   * must not throw.
   */
  @Test
  public void onCompletition_rebuildTrue_nonEmptyIndex_isIdempotent() {
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    var listener = new IndexRebuildOutputListener(idx);
    listener.onBegin(this, 3L, Boolean.TRUE);
    // iSucceed=true, index has 3 entries — the idxSize > 0 + rebuild=true INFO branch fires.
    listener.onCompletition(session, this, true);
    // Calling onCompletition again must remain a safe no-op (idempotent contract).
    listener.onCompletition(session, this, true);
  }

  /**
   * onCompletition with rebuild=false and a non-empty index must complete without exception
   * (takes the DEBUG branch for idxSize > 0 + rebuild=false) and is idempotent.
   */
  @Test
  public void onCompletition_rebuildFalse_nonEmptyIndex_isIdempotent() {
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    var listener = new IndexRebuildOutputListener(idx);
    listener.onBegin(this, 3L, Boolean.FALSE);
    // rebuild=false → idxSize > 0 + rebuild=false DEBUG branch fires.
    listener.onCompletition(session, this, true);
    listener.onCompletition(session, this, true);
  }

  /**
   * onCompletition with an empty index (idxSize == 0) must complete without exception
   * (neither INFO nor DEBUG branch inside onCompletition fires) and remain idempotent under
   * a second invocation.
   */
  @Test
  public void onCompletition_emptyIndex_isIdempotent() {
    // Create a separate class+index with no records.
    var emptyClass = "IrolEmptyCls";
    var emptyIdx = emptyClass + ".v";
    var cls2 = session.getMetadata().getSchema().createClass(emptyClass);
    cls2.createProperty("v", PropertyType.INTEGER);
    cls2.createIndex(emptyIdx, SchemaClass.INDEX_TYPE.UNIQUE, "v");

    var idx = session.getSharedContext().getIndexManager().getIndex(emptyIdx);
    var listener = new IndexRebuildOutputListener(idx);
    listener.onBegin(this, 0L, Boolean.TRUE);
    // idxSize == 0 → no log output, must not throw.
    listener.onCompletition(session, this, false);
    listener.onCompletition(session, this, false);
  }
}
