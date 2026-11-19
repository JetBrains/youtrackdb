package com.jetbrains.youtrackdb.internal.core.metadata;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import javax.annotation.Nullable;

/**
 * Session-qualified schema view for private preparation. Sessions remain confined to their owner
 * thread. Closing this scope restores any enclosing marker and releases only this scope's pin.
 */
public final class PrivatePreparationSchemaScope implements AutoCloseable {

  private static final ThreadLocal<PrivatePreparationSchemaScope> CURRENT = new ThreadLocal<>();

  private final DatabaseSessionEmbedded session;
  private final PrivatePreparationSchemaScope previous;
  private final ImmutableSchema schema;
  private Runnable releaseFailureCleanup;

  private PrivatePreparationSchemaScope(DatabaseSessionEmbedded session) {
    this.session = session;
    previous = CURRENT.get();
    var metadata = session.getMetadata();
    metadata.makeThreadLocalSchemaSnapshot();
    try {
      schema = metadata.getImmutableSchemaSnapshot();
      CURRENT.set(this);
    } catch (RuntimeException | Error failure) {
      try {
        metadata.clearThreadLocalSchemaSnapshot();
      } catch (RuntimeException | Error closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  public static PrivatePreparationSchemaScope open(DatabaseSessionEmbedded session) {
    return new PrivatePreparationSchemaScope(session);
  }

  public static boolean isActive(DatabaseSessionEmbedded session) {
    return schemaFor(session) != null;
  }

  @Nullable public static ImmutableSchema schemaFor(DatabaseSessionEmbedded session) {
    var scope = CURRENT.get();
    return scope != null && scope.session == session ? scope.schema : null;
  }

  /** Retain ownership of a selected result until pin release succeeds. */
  public void onReleaseFailure(Runnable cleanup) {
    releaseFailureCleanup = cleanup;
  }

  @Override
  public void close() {
    try {
      session.getMetadata().clearThreadLocalSchemaSnapshot();
    } catch (RuntimeException | Error failure) {
      // The exact-session marker must remain installed while the result is discarded.
      if (releaseFailureCleanup != null) {
        try {
          releaseFailureCleanup.run();
        } catch (RuntimeException | Error cleanupFailure) {
          if (cleanupFailure != failure) {
            failure.addSuppressed(cleanupFailure);
          }
        }
      }
      throw failure;
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }
}
