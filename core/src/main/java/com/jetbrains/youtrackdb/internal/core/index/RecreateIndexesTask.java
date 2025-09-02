package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.SharedContext;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.Collection;
import java.util.Map;

public class RecreateIndexesTask implements Runnable {

  private final IndexManagerEmbedded indexManager;

  private final SharedContext ctx;
  private int ok;
  private int errors;

  public RecreateIndexesTask(IndexManagerEmbedded indexManager, SharedContext ctx) {
    this.indexManager = indexManager;
    this.ctx = ctx;
  }

  @Override
  public void run() {
    try {
      final var newDb =
          new DatabaseSessionEmbedded((Storage) ctx.getStorage(), false);
      try (newDb) {
        newDb.activateOnCurrentThread();
        newDb.init(null, ctx);
        newDb.internalOpen("admin", "nopass", false);
        var indexesToRebuild = indexManager.getIndexesConfiguration(newDb);
        recreateIndexes(indexesToRebuild, newDb);
      } finally {
        if (indexManager.storage instanceof AbstractStorage abstractStorage) {
          abstractStorage.synch();
        }
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

    indexManager.rebuildCompleted = true;

    LogManager.instance()
        .info(this, "%d indexes were restored successfully, %d errors", ok, errors);
  }

  private void recreateIndex(Map<String, Object> indexMap, DatabaseSessionEmbedded session) {
    session.executeInTxInternal(transaction -> {
      var indexManager = session.getSharedContext().getIndexManager();
      final var index = createIndex(transaction, indexMap, indexManager.storage);

      final var indexMetadata = index.loadMetadata(transaction, indexMap);
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
          addIndexAsIs(session, index, transaction);
        } else {
          LogManager.instance()
              .info(
                  this,
                  "Index '%s' is a non-durable automatic index and must be rebuilt",
                  indexMetadata.getName());
          rebuildNonDurableAutomaticIndex(session, indexMap, index, indexMetadata, indexDefinition);
        }
      } else {
        if (durable) {
          LogManager.instance()
              .info(
                  this,
                  "Index '%s' is a durable non-automatic index and will be added as is without"
                      + " rebuilding",
                  indexMetadata.getName());
          addIndexAsIs(session, index, transaction);
        } else {
          LogManager.instance()
              .info(
                  this,
                  "Index '%s' is a non-durable non-automatic index and will be added as is without"
                      + " rebuilding",
                  indexMetadata.getName());
          addIndexAsIs(session, index, transaction);
        }
      }
    });
  }

  private void rebuildNonDurableAutomaticIndex(
      DatabaseSessionEmbedded session, Map<String, ?> indexMap,
      Index index,
      IndexMetadata indexMetadata,
      IndexDefinition indexDefinition) {
    session.executeInTxInternal(transaction -> {
      index.delete(transaction);

      final var indexName = indexMetadata.getName();
      final var collections = indexMetadata.getCollectionsToIndex();
      final var type = indexMetadata.getType();

      if (collections != null && !collections.isEmpty() && type != null) {
        LogManager.instance().info(this, "Start creation of index '%s'", indexName);
        index.create(transaction, indexMetadata);

        indexManager.addIndexInternal(session, transaction, index, true);

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
                    + "index name '%s', index definition '%s', collections %s, type %s",
                null,
                indexName,
                indexDefinition,
                collections,
                type);
      }
    });
  }

  private void addIndexAsIs(
      DatabaseSessionInternal session, Index index, FrontendTransaction transaction) {
    try {
      indexManager.addIndexInternal(session, transaction, index, false);
      ok++;
      LogManager.instance().info(this, "Index '%s' was added in DB index list", index.getName());
    } catch (Exception e) {
      try {
        LogManager.instance()
            .error(this, "Index '%s' can't be restored and will be deleted", null,
                index.getName());
        index.delete(transaction);
      } catch (Exception ex) {
        LogManager.instance().error(this, "Error while deleting index '%s'", ex, index.getName());
      }

      errors++;
    }
  }

  private Index createIndex(FrontendTransaction transaction, Map<String, Object> idx,
      Storage storage) {
    final var indexType = (String) idx.get(Index.CONFIG_TYPE);

    if (indexType == null) {
      LogManager.instance().error(this, "Index type is null, will process other record", null);
      throw new IndexException(transaction.getDatabaseSession(),
          "Index type is null, will process other record. Index configuration: " + idx);
    }

    var m = IndexAbstract.loadMetadataFromMap(transaction, idx);
    return Indexes.createIndexInstance(m.getType(), m.getAlgorithm(), storage, transaction,
        (RID) idx.get(EntityHelper.ATTRIBUTE_RID));
  }
}
