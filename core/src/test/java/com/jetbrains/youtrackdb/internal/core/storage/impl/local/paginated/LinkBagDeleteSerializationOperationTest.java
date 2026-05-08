package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import org.junit.Test;

/**
 * Unit tests for {@link LinkBagDeleteSerializationOperation}. The class is a thin facade
 * deferring a B-tree linkbag delete until the atomic operation runs. Tests pin the
 * delegation contract: the constructor captures the linkbag, and {@code execute} forwards
 * to {@link AbstractStorage#deleteTreeLinkBag(BTreeBasedLinkBag, AtomicOperation)} with
 * the captured linkbag and the supplied atomic operation.
 */
public class LinkBagDeleteSerializationOperationTest {

  /**
   * A single {@code execute} invocation forwards exactly one delete call to the storage,
   * passing the linkbag captured at construction and the atomic operation supplied to
   * {@code execute}.
   */
  @Test
  public void testExecuteDelegatesToStorageDelete() {
    var linkBag = mock(BTreeBasedLinkBag.class);
    var atomicOp = mock(AtomicOperation.class);
    var storage = mock(AbstractStorage.class);
    var op = new LinkBagDeleteSerializationOperation(linkBag);

    op.execute(atomicOp, storage);

    verify(storage, times(1)).deleteTreeLinkBag(linkBag, atomicOp);
    verifyNoMoreInteractions(storage);
  }

  /**
   * A second {@code execute} invocation produces a second downstream delete call (the
   * operation is not single-shot internally; replay is the caller's responsibility).
   */
  @Test
  public void testExecuteCanBeInvokedMultipleTimes() {
    var linkBag = mock(BTreeBasedLinkBag.class);
    var atomicOp = mock(AtomicOperation.class);
    var storage = mock(AbstractStorage.class);
    var op = new LinkBagDeleteSerializationOperation(linkBag);

    op.execute(atomicOp, storage);
    op.execute(atomicOp, storage);

    verify(storage, times(2)).deleteTreeLinkBag(linkBag, atomicOp);
  }
}
