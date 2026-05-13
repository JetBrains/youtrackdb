package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Shape pin for {@link EnterpriseStorageOperationListener}. The interface is the public
 * extension point used by the enterprise storage layer to observe commit, rollback, and
 * read events; this test ensures all three abstract method names remain stable so a
 * dependent implementation does not silently lose its observation hook on a future rename.
 */
public class EnterpriseStorageOperationListenerTest {

  /**
   * A listener that counts each event type. Verifies the three callbacks
   * ({@code onCommit}, {@code onRollback}, {@code onRead}) are reachable and dispatched
   * on the expected method name. A renaming refactor that breaks any of these would
   * fail to compile here.
   */
  @Test
  public void testListenerCallbacksAreInvoked() {
    var commits = new AtomicInteger();
    var rollbacks = new AtomicInteger();
    var reads = new AtomicInteger();

    EnterpriseStorageOperationListener listener =
        new EnterpriseStorageOperationListener() {
          @Override
          public void onCommit(List<RecordOperation> operations) {
            commits.incrementAndGet();
          }

          @Override
          public void onRollback() {
            rollbacks.incrementAndGet();
          }

          @Override
          public void onRead() {
            reads.incrementAndGet();
          }
        };

    // Empty list is sufficient to exercise the dispatch path; the listener does not
    // inspect operation contents in this test.
    listener.onCommit(List.<RecordOperation>of());
    listener.onRollback();
    listener.onRead();
    listener.onRead();

    assertThat(commits.get()).isEqualTo(1);
    assertThat(rollbacks.get()).isEqualTo(1);
    assertThat(reads.get()).isEqualTo(2);
  }

  /**
   * The {@code onCommit} callback may receive an empty list (a tx that touched no records but
   * still needs to fire commit observers); the implementation must accept it.
   */
  @Test
  public void testOnCommitAcceptsEmptyList() {
    EnterpriseStorageOperationListener listener =
        new EnterpriseStorageOperationListener() {
          @Override
          public void onCommit(List<RecordOperation> operations) {
            assertThat(operations).isEmpty();
          }

          @Override
          public void onRollback() {
          }

          @Override
          public void onRead() {
          }
        };

    listener.onCommit(List.of());
  }
}
