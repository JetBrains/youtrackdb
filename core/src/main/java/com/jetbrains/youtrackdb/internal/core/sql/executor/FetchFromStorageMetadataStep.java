package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ProduceExecutionStream;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Source step for {@code SELECT FROM metadata:STORAGE}.
 *
 * <p>Produces a single result record containing storage-level metadata:
 * collections (with their details), configuration entries, conflict strategy, size, and version.
 *
 * <pre>
 *  Result (top-level)
 *  +-- collections: List&lt;Result&gt;
 *  |     +-- name, fileName, id, entries, conflictStrategy, tombstonesCount
 *  +-- totalCollections: int
 *  +-- configuration: Result
 *  |     +-- charset, collectionSelection, conflictStrategy, dateFormat, ...
 *  |     +-- properties: List&lt;Result&gt;
 *  |           +-- name, value
 *  +-- conflictStrategy, name, size, type, version, createdAtVersion
 * </pre>
 *
 * @see SelectExecutionPlanner#handleMetadataAsTarget
 */
public class FetchFromStorageMetadataStep extends AbstractExecutionStep {

  public FetchFromStorageMetadataStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects before producing metadata.
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    return new ProduceExecutionStream(FetchFromStorageMetadataStep::produce).limit(1);
  }

  private static Result produce(CommandContext ctx) {
    var db = ctx.getDatabaseSession();
    var result = new ResultInternal(db);

    var storage = db.getStorage();
    result.setProperty("collections", toResult(db, storage.getCollectionInstances()));
    result.setProperty("totalCollections", storage.getCollections());
    result.setProperty(
        "conflictStrategy",
        storage.getRecordConflictStrategy() == null
            ? null
            : storage.getRecordConflictStrategy().getName());
    result.setProperty("name", storage.getName());
    result.setProperty("type", storage.getType());
    result.setProperty("version", storage.getVersion());
    result.setProperty("createdAtVersion", storage.getCreatedAtVersion());
    return result;
  }

  private static List<Result> toResult(DatabaseSessionEmbedded db,
      Collection<? extends StorageCollection> collectionInstances) {
    List<Result> result = new ArrayList<>();
    if (collectionInstances != null) {
      for (var collection : collectionInstances) {
        var item = new ResultInternal(db);
        item.setProperty("name", collection.getName());
        item.setProperty("fileName", collection.getFileName());
        item.setProperty("id", collection.getId());
        item.setProperty("entries", collection.getApproximateRecordsCount());
        item.setProperty(
            "conflictStrategy",
            collection.getRecordConflictStrategy() == null
                ? null
                : collection.getRecordConflictStrategy().getName());
        item.setProperty("tombstonesCount", collection.getTombstonesCount());
        result.add(item);
      }
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ FETCH STORAGE METADATA";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  /**
   * Not cacheable: storage metadata (size, collections, configuration) may change
   * between executions and must always be read fresh.
   */
  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromStorageMetadataStep(ctx, profilingEnabled);
  }
}
