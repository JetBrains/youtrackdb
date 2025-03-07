package com.jetbrains.youtrack.db.internal.client.remote.db;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.client.remote.YouTrackDBRemote;
import com.jetbrains.youtrack.db.internal.client.remote.metadata.schema.SchemaRemote;
import com.jetbrains.youtrack.db.internal.client.remote.metadata.security.SecurityRemote;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.SharedContext;
import com.jetbrains.youtrack.db.internal.core.db.StringCache;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerRemote;
import com.jetbrains.youtrack.db.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.SequenceLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.schedule.SchedulerImpl;
import com.jetbrains.youtrack.db.internal.core.storage.StorageInfo;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 */
public class SharedContextRemote extends SharedContext {

  private final ReentrantLock lock = new ReentrantLock();

  public SharedContextRemote(StorageInfo storage, YouTrackDBRemote youTrackDbRemote) {
    stringCache =
        new StringCache(
            youTrackDbRemote
                .getContextConfiguration()
                .getValueAsInteger(GlobalConfiguration.DB_STRING_CAHCE_SIZE));
    this.youtrackDB = youTrackDbRemote;
    this.storage = storage;
    schema = new SchemaRemote();
    security = new SecurityRemote();
    indexManager = new IndexManagerRemote(storage);
    functionLibrary = new FunctionLibraryImpl();
    scheduler = new SchedulerImpl(youtrackDB);
    sequenceLibrary = new SequenceLibraryImpl();
  }

  public void load(DatabaseSessionInternal database) {
    if (loaded) {
      return;
    }

    lock.lock();

    try {
      if (loaded) {
        return;
      }
      schema.load(database);
      indexManager.load(database);
      // The Immutable snapshot should be after index and schema that require and before
      // everything else that use it
      schema.forceSnapshot(database);
      security.load(database);
      sequenceLibrary.load(database);
      schema.onPostIndexManagement(database);
      loaded = true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    lock.lock();
    try {
      stringCache.close();
      schema.close();
      security.close();
      indexManager.close();
      sequenceLibrary.close();
      loaded = false;
    } finally {
      lock.unlock();
    }
  }

  public void reload(DatabaseSessionInternal database) {
    lock.lock();
    try {
      schema.reload(database);
      indexManager.reload(database);
      // The Immutable snapshot should be after index and schema that require and before everything
      // else that use it
      schema.forceSnapshot(database);
      security.load(database);
      scheduler.load(database);
      sequenceLibrary.load(database);
      functionLibrary.load(database);
    } finally {
      lock.unlock();
    }
  }
}
