package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link RecordSerializationContext}. The context buffers
 * {@link RecordSerializationOperation} instances during record serialization and replays
 * them in stack (LIFO) order against an {@link AtomicOperation} once the tx is ready to
 * commit. Tests pin the LIFO replay order, the post-execute clear semantics, and the
 * standalone {@code clear()} contract.
 */
public class RecordSerializationContextTest {

  private AtomicOperation atomicOperation;
  private AbstractStorage storage;

  @Before
  public void setUp() {
    atomicOperation = mock(AtomicOperation.class);
    storage = mock(AbstractStorage.class);
  }

  /**
   * A freshly constructed context with no pushed operations is a no-op when executed; no
   * downstream {@code execute} calls happen and the deque remains empty.
   */
  @Test
  public void testExecuteWithNoOperationsIsNoOp() {
    var context = new RecordSerializationContext();

    context.executeOperations(atomicOperation, storage);

    // No operation to execute; nothing to verify on collaborators (they were untouched).
    // Verifying the deque is logically empty: a second execute is also a no-op.
    context.executeOperations(atomicOperation, storage);
  }

  /**
   * A single pushed operation receives exactly one {@code execute} call when the context
   * is replayed.
   */
  @Test
  public void testSinglePushedOperationExecutedOnce() {
    var context = new RecordSerializationContext();
    var op = mock(RecordSerializationOperation.class);

    context.push(op);
    context.executeOperations(atomicOperation, storage);

    verify(op, times(1)).execute(atomicOperation, storage);
  }

  /**
   * {@link RecordSerializationContext#executeOperations(AtomicOperation, AbstractStorage)}
   * iterates the operations deque in LIFO order: the latest push runs first. This pins the
   * {@link java.util.ArrayDeque#push(Object)} / iterator contract used internally — a switch
   * to FIFO ordering would silently break tx replay where the operation order matters
   * (e.g., delete-before-create on the same RID).
   */
  @Test
  public void testOperationsExecutedInLifoOrder() {
    var context = new RecordSerializationContext();
    var executionOrder = new ArrayList<String>();

    RecordSerializationOperation first = (op, st) -> executionOrder.add("first");
    RecordSerializationOperation second = (op, st) -> executionOrder.add("second");
    RecordSerializationOperation third = (op, st) -> executionOrder.add("third");

    context.push(first);
    context.push(second);
    context.push(third);
    context.executeOperations(atomicOperation, storage);

    // ArrayDeque.push uses addFirst, so iteration yields LIFO order.
    assertThat(executionOrder).containsExactly("third", "second", "first");
  }

  /**
   * After {@code executeOperations}, the internal deque is cleared so a subsequent
   * {@code executeOperations} call is a no-op (does not re-execute any prior operation).
   */
  @Test
  public void testExecuteOperationsClearsTheQueue() {
    var context = new RecordSerializationContext();
    var op = mock(RecordSerializationOperation.class);

    context.push(op);
    context.executeOperations(atomicOperation, storage);
    verify(op, times(1)).execute(atomicOperation, storage);

    // Second execute must NOT re-run the same operation.
    context.executeOperations(atomicOperation, storage);
    verify(op, times(1)).execute(atomicOperation, storage);
  }

  /**
   * The standalone {@code clear()} method discards pending operations without executing
   * them — used on tx rollback paths to drop accumulated work.
   */
  @Test
  public void testClearDiscardsPendingOperationsWithoutExecuting() {
    var context = new RecordSerializationContext();
    var op = mock(RecordSerializationOperation.class);

    context.push(op);
    context.clear();

    // Subsequent execute must not invoke the discarded op.
    context.executeOperations(atomicOperation, storage);
    verify(op, never()).execute(atomicOperation, storage);
  }

  /**
   * {@code clear()} on an already-empty context is a no-op (does not throw).
   */
  @Test
  public void testClearOnEmptyContextIsNoOp() {
    var context = new RecordSerializationContext();
    context.clear();
    context.clear();
    // Subsequent execute on the empty context is also a no-op.
    context.executeOperations(atomicOperation, storage);
  }
}
