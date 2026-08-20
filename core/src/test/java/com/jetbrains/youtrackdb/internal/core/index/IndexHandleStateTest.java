package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineReference;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexLifecycleCell;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
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
  public void ownerRecoveryReusesTheResolvedCarrier() {
    var storage = mock(AbstractStorage.class);
    var reference = new IndexEngineReference(7, 1, 2);
    var owner = new RecordId(3, 4);
    when(storage.resolveIndexEngineByOwner(owner))
        .thenReturn(new AbstractStorage.ResolvedIndexEngine(7, reference));
    var index = new TestIndex(storage);
    index.setHandleStateForTest(99, owner);
    var calls = new AtomicInteger();

    var used = index.runWithOwner(current -> {
      if (calls.getAndIncrement() == 0) {
        throw new InvalidIndexEngineIdException("stale fixture");
      }
      return current;
    });

    assertEquals(2, calls.get());
    assertEquals(7, used.engineIdentifier());
    assertTrue(used.engineReference() == reference);
  }

  @Test
  public void listenerConvergesAndAttachmentCancelsSubscription() throws Exception {
    var storage = mock(AbstractStorage.class);
    var lifecycleCell = mock(IndexLifecycleCell.class);
    var pending = new ChangeableRecordId();
    var index = new TestIndex(storage);
    index.setHandleStateForTest(1, null);
    when(storage.attachIndexEngineOwner(1, new RecordId(3, 4), null)).thenReturn(null);
    when(storage.getOrCreateIndexLifecycle(new RecordId(3, 4))).thenReturn(lifecycleCell);

    var publish = IndexAbstract.class.getDeclaredMethod(
        "publishDescriptorIdentity",
        com.jetbrains.youtrackdb.internal.core.db.record.record.RID.class);
    publish.setAccessible(true);
    publish.invoke(index, pending);
    pending.setCollectionAndPosition(3, 4);

    var converged = index.state();
    assertTrue(converged.isDurablyIdentified());
    assertNotSame(pending, converged.descriptorIdentity());
    index.attachDescriptorIdentity();

    var subscriptionField = IndexAbstract.class.getDeclaredField("identitySubscription");
    subscriptionField.setAccessible(true);
    @SuppressWarnings("unchecked")
    var subscription = (AtomicReference<Object>) subscriptionField.get(index);
    assertNull(subscription.get());
  }

  @Test
  public void failedDropCleanupReleasesOnlyDetachedHandle() {
    var storage = mock(AbstractStorage.class);
    var identity = new RecordId(3, 4);
    var index = new TestIndex(storage);
    index.setHandleStateForTest(1, identity);

    index.removeLifecycleRegistrationIfDetached();
    verify(storage, never()).removeIndexLifecycle(identity);

    index.setEngineIdentifierForTest(-1);
    index.removeLifecycleRegistrationIfDetached();
    verify(storage).removeIndexLifecycle(identity);
    assertFalse(index.state().hasEngine());
    assertNull(index.state().lifecycleCell());
  }

  @Test
  public void competingPublishersRebaseAfterCompareAndSetLoss() throws Exception {
    var index = new TestIndex(mock(AbstractStorage.class));
    var originalIdentity = new RecordId(3, 4);
    var replacementIdentity = new RecordId(3, 5);
    index.setHandleStateForTest(1, originalIdentity);
    var update = IndexAbstract.class.getDeclaredMethod("updateState", UnaryOperator.class);
    update.setAccessible(true);
    var bothDerived = new CountDownLatch(2);

    UnaryOperator<IndexHandleState> engineUpdate = current -> {
      awaitFirstDerivation(bothDerived);
      return new IndexHandleState(
          2, current.engineReference(), current.descriptorIdentity(), current.lifecycleCell());
    };
    UnaryOperator<IndexHandleState> identityUpdate = current -> {
      awaitFirstDerivation(bothDerived);
      return new IndexHandleState(
          current.engineIdentifier(), current.engineReference(), replacementIdentity,
          current.lifecycleCell());
    };

    try (var executor = Executors.newFixedThreadPool(2)) {
      var engineFuture = executor.submit(() -> invokeUpdate(update, index, engineUpdate));
      var identityFuture = executor.submit(() -> invokeUpdate(update, index, identityUpdate));
      engineFuture.get(10, TimeUnit.SECONDS);
      identityFuture.get(10, TimeUnit.SECONDS);
    }

    assertEquals(2, index.state().engineIdentifier());
    assertEquals(replacementIdentity, index.state().descriptorIdentity());
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

  private static void awaitFirstDerivation(CountDownLatch bothDerived) {
    if (bothDerived.getCount() > 0) {
      bothDerived.countDown();
      try {
        assertTrue(bothDerived.await(10, TimeUnit.SECONDS));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError(exception);
      }
    }
  }

  private static void invokeUpdate(
      java.lang.reflect.Method method, TestIndex index,
      UnaryOperator<IndexHandleState> update) {
    try {
      method.invoke(index, update);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }

  private static final class TestIndex extends IndexNotUnique {

    private TestIndex(AbstractStorage storage) {
      super(storage);
    }

    private IndexHandleState runWithOwner(StateOperation<IndexHandleState> operation) {
      return withOwnedEngine(operation);
    }
  }
}
