package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.SharedContext;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class RecreateIndexesTask implements Runnable {

  /**
   *
   */
  private final IndexManagerShared indexManager;

  private final SharedContext ctx;
  private int ok;
  private int errors;

  public RecreateIndexesTask(IndexManagerShared indexManager, SharedContext ctx) {
    this.indexManager = indexManager;
    this.ctx = ctx;
  }

  @Override
  public void run() {
    try {
      final var newDb =
          new DatabaseSessionEmbedded((Storage) ctx.getStorage());
      newDb.activateOnCurrentThread();
      newDb.init(null, ctx);
      newDb.internalOpen("admin", "nopass", false);

      final ArrayList<Map<String, Object>> indexesToRebuild;
      indexManager.acquireExclusiveLock(newDb);
      try {
        var knownIndexes =
            (indexManager.getEntity(newDb)
                .<Set<Entity>>getProperty(IndexManagerShared.CONFIG_INDEXES)).stream()
                .map(Entity::toMap).toList();
        // make copies to safely iterate them later
        indexesToRebuild = new ArrayList<>(knownIndexes);
      } finally {
        indexManager.releaseExclusiveLock(newDb);
      }

      try {
        recreateIndexes(indexesToRebuild, newDb);
      } finally {
        if (indexManager.storage instanceof AbstractPaginatedStorage abstractPaginatedStorage) {
          abstractPaginatedStorage.synch();
        }
        newDb.close();
      }

    } catch (Exception e) {
      LogManager.instance()
          .error(this, "Error when attempt to restore indexes after crash was performed", e);
    }
  }

  private void recreateIndexes(
      Collection<Map<String, Object>> indexesToRebuild, DatabaseSessionEmbedded db) {
    ok = 0;
    errors = 0;
    for (var index : indexesToRebuild) {
      try {
        recreateIndex(index, db);
      } catch (RuntimeException e) {
        LogManager.instance().error(this, "Error during addition of index '%s'", e, index);
        errors++;
      }
    }

    db.getMetadata().getIndexManagerInternal().save(db);

    indexManager.rebuildCompleted = true;

    LogManager.instance()
        .info(this, "%d indexes were restored successfully, %d errors", ok, errors);
  }

  private void recreateIndex(Map<String, Object> indexMap, DatabaseSessionEmbedded db) {
    final var index = createIndex(db, indexMap);
    final var indexMetadata = index.loadMetadata(db, indexMap);
    final var indexDefinition = indexMetadata.getIndexDefinition();

    final var automatic = indexDefinition != null && indexDefinition.isAutomatic();
    // XXX: At this moment Lucene-based indexes are not durable, so we still need to rebuild them.
    final var durable = !"LUCENE".equalsIgnoreCase(indexMetadata.getAlgorithm());

    // The database and its index manager are in a special half-open state now, the index manager
    // is created, but not populated
    // with the index metadata, we have to rebuild the whole index list manually and insert it
    // into the index manager.

    if (automatic) {
      if (durable) {
        LogManager.instance()
            .info(
                this,
                "Index '%s' is a durable automatic index and will be added as is without"
                    + " rebuilding",
                indexMetadata.getName());
        addIndexAsIs(indexMap, index, db);
      } else {
        LogManager.instance()
            .info(
                this,
                "Index '%s' is a non-durable automatic index and must be rebuilt",
                indexMetadata.getName());
        rebuildNonDurableAutomaticIndex(db, indexMap, index, indexMetadata, indexDefinition);
      }
    } else {
      if (durable) {
        LogManager.instance()
            .info(
                this,
                "Index '%s' is a durable non-automatic index and will be added as is without"
                    + " rebuilding",
                indexMetadata.getName());
        addIndexAsIs(indexMap, index, db);
      } else {
        LogManager.instance()
            .info(
                this,
                "Index '%s' is a non-durable non-automatic index and will be added as is without"
                    + " rebuilding",
                indexMetadata.getName());
        addIndexAsIs(indexMap, index, db);
      }
    }
  }

  private void rebuildNonDurableAutomaticIndex(
      DatabaseSessionInternal session, Map<String, ?> indexMap,
      Index index,
      IndexMetadata indexMetadata,
      IndexDefinition indexDefinition) {
    index.loadFromConfiguration(session, indexMap);
    index.delete(session);

    final var indexName = indexMetadata.getName();
    final var clusters = indexMetadata.getClustersToIndex();
    final var type = indexMetadata.getType();

    if (clusters != null && !clusters.isEmpty() && type != null) {
      LogManager.instance().info(this, "Start creation of index '%s'", indexName);
      index.create(session, indexMetadata, false, new IndexRebuildOutputListener(index));

      indexManager.addIndexInternal(session, index);

      LogManager.instance()
          .info(
              this,
              "Index '%s' was successfully created and rebuild is going to be started",
              indexName);

      index.rebuild(session, new IndexRebuildOutputListener(index));

      ok++;

      LogManager.instance()
          .info(this, "Rebuild of '%s index was successfully finished", indexName);
    } else {
      errors++;
      LogManager.instance()
          .error(
              this,
              "Information about index was restored incorrectly, following data were loaded : "
                  + "index name '%s', index definition '%s', clusters %s, type %s",
              null,
              indexName,
              indexDefinition,
              clusters,
              type);
    }
  }

  private void addIndexAsIs(
      Map<String, ?> indexMap, Index index, DatabaseSessionEmbedded database) {
    if (index.loadFromConfiguration(database, indexMap)) {
      indexManager.addIndexInternal(database, index);

      ok++;
      LogManager.instance().info(this, "Index '%s' was added in DB index list", index.getName());
    } else {
      try {
        LogManager.instance()
            .error(this, "Index '%s' can't be restored and will be deleted", null, index.getName());
        index.delete(database);
      } catch (Exception e) {
        LogManager.instance().error(this, "Error while deleting index '%s'", e, index.getName());
      }
      errors++;
    }
  }

  private Index createIndex(DatabaseSessionInternal db, Map<String, Object> idx) {
    final var indexType = (String) idx.get(Index.CONFIG_TYPE);

    if (indexType == null) {
      LogManager.instance().error(this, "Index type is null, will process other record", null);
      throw new IndexException(db.getDatabaseName(),
          "Index type is null, will process other record. Index configuration: " + idx);
    }
    var m = IndexAbstract.loadMetadataFromMap(db, idx);
    return Indexes.createIndex(indexManager.storage, m);
  }
}
