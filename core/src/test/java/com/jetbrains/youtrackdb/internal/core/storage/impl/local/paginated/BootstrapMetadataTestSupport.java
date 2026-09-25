package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import java.io.IOException;
import java.nio.file.Files;
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

  /** Holds the candidate observation and removes the current-thread failure strategy. */
  public record FailedPublication(AtomicBoolean candidateObserved, AutoCloseable scope)
      implements AutoCloseable {

    @Override
    public void close() throws Exception {
      scope.close();
    }
  }
}
