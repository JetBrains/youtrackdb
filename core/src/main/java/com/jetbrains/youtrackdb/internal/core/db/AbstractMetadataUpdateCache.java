package com.jetbrains.youtrackdb.internal.core.db;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Shared skeleton for shared-context caches invalidated by metadata changes.
 *
 * <p>The cache owns the Guava storage, the common invalidate timestamp, and the
 * {@link MetadataUpdateListener} fan-in. Concrete caches keep only their
 * key/value semantics and copy policy.
 */
public abstract class AbstractMetadataUpdateCache<K, V> implements MetadataUpdateListener {

  protected final int capacity;
  @Nullable protected final Cache<K, V> cache;
  private final AtomicLong lastInvalidation = new AtomicLong(-1);

  protected AbstractMetadataUpdateCache(int size) {
    this.capacity = size;
    this.cache = size > 0 ? CacheBuilder.newBuilder().maximumSize(size).build() : null;
  }

  public long getLastInvalidation() {
    return lastInvalidation.get();
  }

  protected final boolean cacheEnabled() {
    return capacity > 0 && cache != null;
  }

  protected final boolean containsKey(K key) {
    return cacheEnabled() && cache.asMap().containsKey(key);
  }

  @Nullable protected final V getCached(K key) {
    return cacheEnabled() ? cache.getIfPresent(key) : null;
  }

  protected final void putCached(K key, V value) {
    if (cacheEnabled()) {
      cache.put(key, value);
    }
  }

  public void invalidate() {
    if (cache != null) {
      cache.invalidateAll();
    }
    lastInvalidation.set(System.nanoTime());
  }

  @Override
  public void onSchemaUpdate(DatabaseSessionEmbedded session, String databaseName,
      SchemaShared schema) {
    invalidate();
  }

  @Override
  public void onIndexManagerUpdate(DatabaseSessionEmbedded session, String databaseName,
      IndexManagerAbstract indexManager) {
    invalidate();
  }

  @Override
  public void onFunctionLibraryUpdate(DatabaseSessionEmbedded session, String databaseName) {
    invalidate();
  }

  @Override
  public void onSequenceLibraryUpdate(DatabaseSessionEmbedded session, String databaseName) {
    invalidate();
  }

  @Override
  public void onStorageConfigurationUpdate(String databaseName, StorageConfiguration update) {
    invalidate();
  }
}
