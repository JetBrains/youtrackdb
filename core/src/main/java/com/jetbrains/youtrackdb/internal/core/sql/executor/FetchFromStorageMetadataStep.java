package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.StorageEntryConfiguration;
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
 * Returns an Result containing metadata regarding the storage
 */
public class FetchFromStorageMetadataStep extends AbstractExecutionStep {

  public FetchFromStorageMetadataStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    return new ProduceExecutionStream(this::produce).limit(1);
  }

  private Result produce(CommandContext ctx) {
    var db = ctx.getDatabaseSession();
    var result = new ResultInternal(db);

    var storage = db.getStorage();
    result.setProperty("collections", toResult(db, storage.getCollectionInstances()));
    result.setProperty("totalCollections", storage.getCollections());
    result.setProperty("configuration", toResult(db, storage.getConfiguration()));
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

  private static Object toResult(DatabaseSessionEmbedded db,
      StorageConfiguration configuration) {
    var result = new ResultInternal(db);
    result.setProperty("charset", configuration.getCharset());
    result.setProperty("collectionSelection", configuration.getCollectionSelection());
    result.setProperty("conflictStrategy", configuration.getConflictStrategy());
    result.setProperty("dateFormat", configuration.getDateFormat());
    result.setProperty("dateTimeFormat", configuration.getDateTimeFormat());
    result.setProperty("localeCountry", configuration.getLocaleCountry());
    result.setProperty("localeLanguage", configuration.getLocaleLanguage());
    result.setProperty("recordSerializer", configuration.getRecordSerializer());
    result.setProperty("timezone", String.valueOf(configuration.getTimeZone()));
    result.setProperty("properties", toResult(db, configuration.getProperties()));
    return result;
  }

  private static List<Result> toResult(DatabaseSessionEmbedded db,
      List<StorageEntryConfiguration> properties) {
    List<Result> result = new ArrayList<>();
    if (properties != null) {
      for (var entry : properties) {
        var item = new ResultInternal(db);
        item.setProperty("name", entry.name);
        item.setProperty("value", entry.value);
        result.add(item);
      }
    }
    return result;
  }

  private List<Result> toResult(DatabaseSessionEmbedded db,
      Collection<? extends StorageCollection> collectionInstances) {
    List<Result> result = new ArrayList<>();
    if (collectionInstances != null) {
      for (var collection : collectionInstances) {
        var item = new ResultInternal(db);
        item.setProperty("name", collection.getName());
        item.setProperty("fileName", collection.getFileName());
        item.setProperty("id", collection.getId());
        item.setProperty("entries", collection.getEntries());
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

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromStorageMetadataStep(ctx, profilingEnabled);
  }
}
