package com.jetbrains.youtrack.db.internal.core.cache;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.IdentityChangeListener;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache implementation that uses Weak References.
 */
public final class WeakValueHashMap<K, V> extends AbstractMap<K, V>
    implements IdentityChangeListener {

  private static final Logger logger = LoggerFactory.getLogger(WeakValueHashMap.class);

  private final ReferenceQueue<V> refQueue = new ReferenceQueue<>();

  private final Map<K, WeakRefValue<K, V>> referenceMap;
  private final Consumer<K> cleanupCallback;

  private boolean stopModification = false;

  /**
   * Map that is used to keep records between before identity change and after identity change
   * events.
   */
  private final IdentityHashMap<K, V> identityChangeMap = new IdentityHashMap<>();

  public WeakValueHashMap() {
    this(false, k -> {
    });
  }

  public WeakValueHashMap(boolean concurrent, Consumer<K> cleanupCallback) {
    this.referenceMap = concurrent ? new ConcurrentHashMap<>() : new HashMap<>();
    this.cleanupCallback = cleanupCallback;
  }

  @Nullable
  @Override
  public V get(Object key) {
    evictStaleEntries();
    final var k = (K) key;
    final var weakRef = referenceMap.get(k);

    return weakRef == null ? null : weakRef.get();

  }

  private void evictStaleEntries() {
    checkModificationAllowed();

    var evicted = 0;

    WeakRefValue<K, V> sv;
    //noinspection unchecked
    while ((sv = (WeakRefValue<K, V>) refQueue.poll()) != null) {
      final var key = sv.key;

      cleanupCallback.accept(key);
      cleanupReference(key, sv);
      evicted++;
    }

    if (evicted > 0) {
      if (logger.isDebugEnabled()) {
        LogManager.instance().debug(this, "Evicted %d items", logger, evicted);
      }
    }
  }

  private void cleanupReference(K key, WeakRefValue<K, V> weakRef) {
    referenceMap.compute(key, (k, v) -> {
      if (v == weakRef) {
        return null;
      } else {
        return v;
      }
    });

    if (key instanceof ChangeableIdentity changeableIdentity) {
      changeableIdentity.removeIdentityChangeListener(this);
    }
  }

  @Override
  @Nullable
  public V put(final K key, final V value) {
    checkModificationAllowed();
    evictStaleEntries();

    if (key instanceof ChangeableIdentity changeableIdentity) {
      changeableIdentity.addIdentityChangeListener(this);
    }

    final var result =
        referenceMap.put(key, new WeakRefValue<>(key, value, refQueue));
    return result == null ? null : result.get();
  }

  private void checkModificationAllowed() {
    if (stopModification) {
      throw new IllegalStateException("Modification is not allowed");
    }
  }

  @Override
  @Nullable
  public V remove(Object key) {
    checkModificationAllowed();
    evictStaleEntries();

    if (key instanceof ChangeableIdentity changeableIdentity) {
      changeableIdentity.removeIdentityChangeListener(this);
    }
    final var result = referenceMap.remove(key);
    return result == null ? null : result.get();

  }

  @Override
  public void clear() {
    checkModificationAllowed();
    referenceMap.clear();
  }

  @Override
  public int size() {
    evictStaleEntries();
    return referenceMap.size();
  }

  @Override
  public @Nonnull Set<Entry<K, V>> entrySet() {
    evictStaleEntries();
    Set<Entry<K, V>> result = new HashSet<>();
    for (final var entry : referenceMap.entrySet()) {
      final var value = entry.getValue().get();
      if (value != null) {
        result.add(
            new SimpleEntry<>(entry.getKey(), value) {
              @Override
              public V setValue(V value) {
                throw new UnsupportedOperationException("setValue is not supported");
              }
            }
        );
      }
    }
    return result;
  }

  @Override
  public void forEach(BiConsumer<? super K, ? super V> action) {
    evictStaleEntries();

    stopModification = true;
    try {
      for (final var entry : referenceMap.entrySet()) {
        final var value = entry.getValue().get();
        if (value != null) {
          action.accept(entry.getKey(), value);
        }
      }
    } finally {
      stopModification = false;
    }
  }

  @Override
  public void onBeforeIdentityChange(Object source) {
    var key = (K) source;
    checkModificationAllowed();

    var record = referenceMap.remove(key);

    if (record != null) {
      var recordValue = record.get();

      if (recordValue != null) {
        identityChangeMap.put(key, recordValue);
      }
    }
  }

  @Override
  public void onAfterIdentityChange(Object source) {
    checkModificationAllowed();

    var key = (K) source;
    var record = identityChangeMap.remove(key);

    if (record != null) {
      referenceMap.put(key, new WeakRefValue<>(key, record, refQueue));
    }
  }

  private static final class WeakRefValue<K, V> extends WeakReference<V> {

    private final @Nonnull K key;

    public WeakRefValue(
        @Nonnull final K key, @Nonnull final V value, final ReferenceQueue<V> queue) {
      super(value, queue);
      this.key = key;
    }

    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      @SuppressWarnings("unchecked")
      var that = (WeakRefValue<K, V>) o;
      return key.equals(that.key);
    }

    public int hashCode() {
      return key.hashCode();
    }

    public String toString() {
      return WeakValueHashMap.class.getSimpleName() + " {" + "key=" + key + '}';
    }
  }
}
