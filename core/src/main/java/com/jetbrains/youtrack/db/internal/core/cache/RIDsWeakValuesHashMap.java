package com.jetbrains.youtrack.db.internal.core.cache;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.IdentityChangeListener;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache implementation that uses Weak References.
 */
public final class RIDsWeakValuesHashMap<V> extends AbstractMap<RID, V>
    implements IdentityChangeListener {

  private static final Logger logger = LoggerFactory.getLogger(RIDsWeakValuesHashMap.class);

  private final ReferenceQueue<V> refQueue = new ReferenceQueue<>();

  private final HashMap<RID, WeakRefValue<V>> hashMap = new HashMap<>();

  private boolean stopModification = false;

  /**
   * Map that is used to keep records between before identity change and after identity change
   * events.
   */
  private final IdentityHashMap<RID, V> identityChangeMap = new IdentityHashMap<>();

  public V get(RID key) {
    evictStaleEntries();
    V result = null;
    final var soft_ref = hashMap.get(key);

    if (soft_ref != null) {
      result = soft_ref.get();
      if (result == null) {
        if (stopModification) {
          throw new IllegalStateException("Modification is not allowed");
        }

        hashMap.remove(key);
        if (key instanceof ChangeableIdentity changeableIdentity) {
          changeableIdentity.removeIdentityChangeListener(this);
        }
      }
    }
    return result;
  }

  private void evictStaleEntries() {
    if (stopModification) {
      throw new IllegalStateException("Modification is not allowed");
    }

    var evicted = 0;

    WeakRefValue<V> sv;
    //noinspection unchecked
    while ((sv = (WeakRefValue<V>) refQueue.poll()) != null) {
      final var key = sv.key;

      if (key instanceof ChangeableIdentity changeableIdentity) {
        changeableIdentity.removeIdentityChangeListener(this);
      }

      hashMap.remove(key);
      evicted++;
    }

    if (evicted > 0) {
      if (logger.isDebugEnabled()) {
        LogManager.instance().debug(this, "Evicted %d items", logger, evicted);
      }
    }
  }

  @Override
  @Nullable
  public V put(final RID key, final V value) {
    if (stopModification) {
      throw new IllegalStateException("Modification is not allowed");
    }

    evictStaleEntries();

    final var soft_ref = new WeakRefValue<V>(key, value, refQueue);

    if (key instanceof ChangeableIdentity changeableIdentity) {
      changeableIdentity.addIdentityChangeListener(this);
    }

    final var result = hashMap.put(key, soft_ref);
    if (result == null) {
      return null;
    }

    return result.get();
  }

  @Nullable
  public V remove(RID key) {
    if (stopModification) {
      throw new IllegalStateException("Modification is not allowed");
    }

    evictStaleEntries();

    final var result = hashMap.remove(key);
    if (key instanceof ChangeableIdentity changeableIdentity) {
      changeableIdentity.removeIdentityChangeListener(this);
    }

    if (result == null) {
      return null;
    }

    return result.get();
  }

  @Override
  public void clear() {
    if (stopModification) {
      throw new IllegalStateException("Modification is not allowed");
    }

    hashMap.clear();
  }

  @Override
  public int size() {
    evictStaleEntries();

    return hashMap.size();
  }

  @Override
  public @Nonnull Set<Entry<RID, V>> entrySet() {
    evictStaleEntries();
    Set<Entry<RID, V>> result = new HashSet<>();
    for (final var entry : hashMap.entrySet()) {
      final var value = entry.getValue().get();
      if (value != null) {
        result.add(
            new Entry<>() {
              @Override
              public RID getKey() {
                return entry.getKey();
              }

              @Override
              public V getValue() {
                return value;
              }

              @Override
              public V setValue(V v) {
                throw new UnsupportedOperationException();
              }
            });
      }
    }
    return result;
  }

  @Override
  public void forEach(BiConsumer<? super RID, ? super V> action) {
    evictStaleEntries();

    stopModification = true;
    try {
      for (final var entry : hashMap.entrySet()) {
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
    var rid = (RID) source;
    if (stopModification) {
      throw new IllegalStateException("Modification is not allowed");
    }

    var record = hashMap.remove(rid);

    if (record != null) {
      var recordValue = record.get();

      if (recordValue != null) {
        identityChangeMap.put(rid, recordValue);
      }
    }
  }

  @Override
  public void onAfterIdentityChange(Object source) {
    if (stopModification) {
      throw new IllegalStateException("Modification is not allowed");
    }

    var rid = (RID) source;
    var record = identityChangeMap.remove(rid);

    if (record != null) {
      hashMap.put(rid, new WeakRefValue<>(rid, record, refQueue));
    }
  }

  private static final class WeakRefValue<V> extends WeakReference<V> {

    private final @Nonnull RID key;

    public WeakRefValue(
        @Nonnull final RID key, @Nonnull final V value, final ReferenceQueue<V> queue) {
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
      var that = (WeakRefValue<V>) o;
      return key.equals(that.key);
    }

    public int hashCode() {
      return key.hashCode();
    }

    public String toString() {
      return RIDsWeakValuesHashMap.class.getSimpleName() + " {" + "key=" + key + '}';
    }
  }
}
