package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Opens the two birth completion boundaries of one storage to a test.
 *
 * <p>Storage birth is the creation of a new storage image. Genesis is the creation of the initial
 * database metadata. A durability barrier forces earlier writes to durable media before later
 * work. The creation path runs genesis, then the durability barrier, and then the activation.
 *
 * <p>A test needs both boundaries to build the on-disk image of a crash at each boundary. The two
 * boundaries live inside one final method of the storage, so no subclass can reach them. This
 * class therefore exposes the package-private observer seam of the storage to a test of another
 * package.
 */
public final class StorageBirthTestSupport {

  private StorageBirthTestSupport() {
  }

  /**
   * Installs two observers of one storage birth on the current thread.
   *
   * @param afterGenesis  runs after genesis finished and before the durability barrier starts
   * @param afterBarrier  runs after the durability barrier finished and before the activation
   * @return the scope that removes both observers again
   */
  public static AutoCloseable observeBirthCompletion(
      final Consumer<AbstractStorage> afterGenesis,
      final Consumer<AbstractStorage> afterBarrier) {
    Objects.requireNonNull(afterGenesis, "afterGenesis");
    Objects.requireNonNull(afterBarrier, "afterBarrier");
    return AbstractStorage.useBirthCompletionObserverForCurrentThread(
        new AbstractStorage.BirthCompletionObserver() {

          @Override
          public void afterGenesisBeforeBarrier(final AbstractStorage storage) {
            afterGenesis.accept(storage);
          }

          @Override
          public void afterBarrierBeforeActivation(final AbstractStorage storage) {
            afterBarrier.accept(storage);
          }
        });
  }
}
