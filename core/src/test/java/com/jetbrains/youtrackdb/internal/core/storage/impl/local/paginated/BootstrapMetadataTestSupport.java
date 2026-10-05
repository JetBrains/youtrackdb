package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Provides an observable bootstrap publication failure to a real storage creation. */
public final class BootstrapMetadataTestSupport {

  private BootstrapMetadataTestSupport() {
  }

  /** Fails the next current-thread move after observing its completed publication candidate. */
  public static FailedPublication failNextPublication() {
    var candidateObserved = new AtomicBoolean();
    var scope = StorageBootstrapMetadata.useMoveStrategyForCurrentThread(
        (source, target, requester) -> {
          candidateObserved.set(Files.isRegularFile(source));
          throw new IOException("injected birth publication failure");
        });
    return new FailedPublication(candidateObserved, scope);
  }

  /**
   * Fails the activation move for one storage on this thread. The birth move runs normally.
   * When requested, the activation move succeeds before the failure is reported.
   */
  public static FailedActivationPublication failActivationPublication(
      Path storageDirectory, boolean failAfterMove) {
    var candidateObserved = new AtomicBoolean();
    var activationMoveObserved = new AtomicBoolean();
    var cause = new IOException(failAfterMove
        ? "injected failure after creation activation move"
        : "injected creation activation move failure");
    var scope = StorageBootstrapMetadata.useMoveStrategyForCurrentThread(
        (source, target, requester) -> {
          // Metadata resolves its storage directory before constructing move targets. Resolve
          // both parents when the move runs, after creation has made the directory available.
          if (!target.getFileName().toString().equals("storage-bootstrap-1.bsm")
              || !target.getParent().toRealPath().equals(storageDirectory.toRealPath())) {
            FileUtils.durableAtomicMove(source, target, requester);
            return;
          }
          activationMoveObserved.set(true);
          candidateObserved.set(Files.isRegularFile(source));
          if (failAfterMove) {
            FileUtils.durableAtomicMove(source, target, requester);
          }
          throw cause;
        });
    return new FailedActivationPublication(activationMoveObserved, candidateObserved, cause, scope);
  }

  /** The observed activation candidate and injected error for one current-thread move scope. */
  public record FailedActivationPublication(
      AtomicBoolean activationMoveObserved, AtomicBoolean candidateObserved,
      IOException cause, AutoCloseable scope)
      implements AutoCloseable {

    @Override
    public void close() throws Exception {
      scope.close();
    }
  }

  /** Holds the candidate observation and removes the current-thread failure strategy. */
  public record FailedPublication(AtomicBoolean candidateObserved, AutoCloseable scope)
      implements AutoCloseable {

    @Override
    public void close() throws Exception {
      scope.close();
    }
  }
}
