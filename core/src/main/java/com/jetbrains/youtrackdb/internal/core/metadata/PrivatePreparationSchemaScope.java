package com.jetbrains.youtrackdb.internal.core.metadata;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerEmbedded;
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
  private final IndexManagerEmbedded manager;
  private final long word;
  private Runnable releaseFailureCleanup;

  /** Internal evidence for a fail-closed index refusal, never a builder failure. */
  public static final class GuardFailure extends RuntimeException {
    private GuardFailure() {
      super("Private index interval is not valid");
    }
  }

  public IndexManagerEmbedded manager() {
    return manager;
  }

  public long word() {
    return word;
  }

  public boolean matches(IndexManagerEmbedded candidateManager, long candidateWord) {
    return manager == candidateManager && word == candidateWord;
  }

  /** Keep all structural reads and owned metadata-dependent cleanup before this fence. */
  public void validate() {
    long finished = manager.finishIndexStampCapture(word);
    if (session.getSharedContext().getIndexManager() != manager || finished != word) {
      throw new GuardFailure();
    }
  }

  private boolean matches(ImmutableSchema snapshot) {
    return matches(snapshot.getIndexStampManager(), snapshot.getIndexStamp());
  }

  private void retire(ImmutableSchema snapshot) {
    var tx = session.getTxSchemaState();
    if (tx == null) {
      session.getSharedContext().getSchema().retireSnapshotIfSame(snapshot);
    } else if (tx.getOverlaySnapshot() == snapshot) {
      tx.invalidateOverlaySnapshot();
    }
  }

  private PrivatePreparationSchemaScope(DatabaseSessionEmbedded session) {
    this.session = session;
    previous = CURRENT.get();
    var metadata = session.getMetadata();
    manager = session.getSharedContext().getIndexManager();
    word = manager.readIndexChangeWord();
    if (!IndexManagerEmbedded.isIdleIndexStamp(word)) {
      throw new GuardFailure();
    }
    if (metadata.getThreadLocalSchemaSnapshotPinCount() == 0) {
      var tx = session.getTxSchemaState();
      var cached = tx == null ? session.getSharedContext().getSchema().peekSnapshot()
          : tx.getOverlaySnapshot();
      if (cached != null && !matches(cached)) {
        retire(cached);
      }
    }
    metadata.makeThreadLocalSchemaSnapshot();
    try {
      schema = metadata.getImmutableSchemaSnapshot();
      var tx = session.getTxSchemaState();
      if (!matches(schema)) {
        retire(schema);
        throw new GuardFailure();
      }
      if (tx != null && !matches(tx.getBaselineIndexStampManager(), tx.getBaselineIndexStamp())) {
        throw new GuardFailure();
      }
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
