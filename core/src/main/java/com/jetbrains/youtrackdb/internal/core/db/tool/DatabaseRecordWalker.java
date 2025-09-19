package com.jetbrains.youtrackdb.internal.core.db.tool;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.SessionMetadata;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.PhysicalPosition;
import java.util.Set;

/**
 * Common logic for iterating over all database collections and performing some action on all
 * records.
 */
public class DatabaseRecordWalker {

  private final DatabaseSessionEmbedded session;
  private final Set<String> excludeCollections;

  private long onProgressInterval = 0;
  private ProgressListener progressListener = ProgressListener.NOOP;

  public DatabaseRecordWalker(DatabaseSessionEmbedded session, Set<String> excludeCollections) {
    this.session = session;
    this.excludeCollections = excludeCollections;
  }

  public DatabaseRecordWalker onProgressPeriodically(long millis, ProgressListener listener) {
    progressListener = listener;
    onProgressInterval = millis;
    return this;
  }

  /**
   * Walk over all database records, excluding "internal" collection.
   */
  public long walk(RecordIdVisitor visitor) {

    var collectionNames = session.getCollectionNames()
        .stream()
        .filter(c -> !excludeCollections.contains(c))
        .toList();

    var storage = session.getStorage();

    var visitedTotal = 0L;
    var visitedLastLap = 0L;
    var lastLap = System.currentTimeMillis();

    for (var collectionName : collectionNames) {
      if (SessionMetadata.COLLECTION_INTERNAL_NAME.equals(collectionName)) {
        continue;
      }

      var visited = 0L;

      final var collectionId = session.getCollectionIdByName(collectionName);
      final var collectionSize = session.countCollectionElements(collectionId);

      var positions = storage.ceilingPhysicalPositions(
          session, collectionId, new PhysicalPosition(0), Integer.MAX_VALUE);

      while (positions.length > 0) {
        for (var position : positions) {
          if (visitor.visit(new RecordId(collectionId, position.collectionPosition))) {
            visited++;
            visitedTotal++;

            final var now = System.currentTimeMillis();
            final var interval = now - lastLap;
            if (interval >= onProgressInterval) {
              final var recordsPerSecond = (float) visitedLastLap * 1000 / (float) interval;

              progressListener.onProgress(
                  collectionName, collectionSize, visited, false,
                  visitedTotal,
                  recordsPerSecond
              );
              visitedLastLap = 0;
              lastLap = now;
            }
          }
        }

        positions = storage.higherPhysicalPositions(
            session, collectionId,
            positions[positions.length - 1],
            Integer.MAX_VALUE
        );
      }

      final var now = System.currentTimeMillis();
      final var interval = now - lastLap;
      final var recordsPerSecond = (float) visitedLastLap * 1000 / (float) interval;

      progressListener.onProgress(collectionName, collectionSize, visited, true, visitedTotal,
          recordsPerSecond);
    }

    return visitedTotal;
  }

  /**
   * Walk over all database entities, excluding "internal" collection and starting a new transaction
   * for each entity.
   */
  public long walkEntitiesInTx(EntityVisitor visitor) {
    return walkEntitiesInTx(false, visitor);
  }

  /**
   * Walk over all database entities, excluding "internal" collection and starting a new transaction
   * for each entity.
   *
   * @param skipLinkConsistencyCheck Whether the transaction should be started with disabled link
   *                                 consistency check. Use this with care as it may bring the
   *                                 database into an inconsistent state.
   */
  public long walkEntitiesInTx(boolean skipLinkConsistencyCheck, EntityVisitor visitor) {
    return walk(recordId -> {
      try {
        if (skipLinkConsistencyCheck) {
          session.disableLinkConsistencyCheck();
        }
        return session.computeInTx(
            tx -> tx.load(recordId) instanceof EntityImpl entity && visitor.visit(entity));
      } finally {
        if (skipLinkConsistencyCheck) {
          session.enableLinkConsistencyCheck();
        }
      }
    });
  }

  @FunctionalInterface
  public interface RecordIdVisitor {

    boolean visit(RecordIdInternal recordId);
  }

  @FunctionalInterface
  public interface EntityVisitor {

    boolean visit(EntityImpl entity);
  }

  @FunctionalInterface
  public interface ProgressListener {

    ProgressListener NOOP = (colName, colSize, seenInCol, colFinished, seenTotal, rps) -> {
    };

    void onProgress(
        String collectionName,
        long collectionSize,
        long seenInCollection,
        boolean collecitonFinished,
        long seenTotal,
        float recordsPerSecond
    );
  }
}
