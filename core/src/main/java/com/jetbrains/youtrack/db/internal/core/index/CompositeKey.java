/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.IdentityChangeListener;
import com.jetbrains.youtrack.db.internal.core.index.comparator.AlwaysGreaterKey;
import com.jetbrains.youtrack.db.internal.core.index.comparator.AlwaysLessKey;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * Container for the list of heterogeneous values that are going to be stored in in index as
 * composite keys.
 */
public class CompositeKey
    implements Comparable<CompositeKey>,
    Serializable,
    EntitySerializable,
    ChangeableIdentity,
    IdentityChangeListener {

  private boolean canChangeIdentity;

  private Set<IdentityChangeListener> identityChangeListeners;

  private final List<Object> keys;

  public CompositeKey(final List<?> keys) {
    this.keys = new ArrayList<>(keys.size());

    for (final var key : keys) {
      addKey(key);
    }
  }

  public CompositeKey(final Object... keys) {
    this.keys = new ArrayList<>(keys.length);

    for (final var key : keys) {
      addKey(key);
    }
  }

  public CompositeKey() {
    this.keys = new ArrayList<>();
  }

  public CompositeKey(final int size) {
    this.keys = new ArrayList<>(size);
  }

  /**
   * Clears the keys array for reuse of the object
   */
  public void reset() {
    if (this.keys != null) {
      this.keys.clear();
    }
  }

  /**
   *
   */
  public List<Object> getKeys() {
    return Collections.unmodifiableList(keys);
  }

  /**
   * Add new key value to the list of already registered values.
   *
   * <p>If passed in value is {@link CompositeKey} itself then its values will be copied in
   * current index. But key itself will not be added.
   *
   * @param key Key to add.
   */
  public void addKey(final Object key) {
    if (key instanceof CompositeKey compositeKey) {
      for (final var inKey : compositeKey.keys) {
        addKey(inKey);
      }
    } else {
      keys.add(key);
    }

    if (key instanceof ChangeableIdentity changeableIdentity) {
      var canChangeIdentity = changeableIdentity.canChangeIdentity();

      if (canChangeIdentity) {
        changeableIdentity.addIdentityChangeListener(this);
        this.canChangeIdentity = true;
      }
    }
  }

  /**
   * Performs partial comparison of two composite keys.
   *
   * <p>Two objects will be equal if the common subset of their keys is equal. For example if first
   * object contains two keys and second contains four keys then only first two keys will be
   * compared.
   *
   * @param otherKey Key to compare.
   * @return a negative integer, zero, or a positive integer as this object is less than, equal to,
   * or greater than the specified object.
   */
  public int compareTo(final CompositeKey otherKey) {
    final var inIter = keys.iterator();
    final var outIter = otherKey.keys.iterator();

    while (inIter.hasNext() && outIter.hasNext()) {
      final var inKey = inIter.next();
      final var outKey = outIter.next();

      if (outKey instanceof AlwaysGreaterKey) {
        return -1;
      }

      if (outKey instanceof AlwaysLessKey) {
        return 1;
      }

      if (inKey instanceof AlwaysGreaterKey) {
        return 1;
      }

      if (inKey instanceof AlwaysLessKey) {
        return -1;
      }

      final var result = DefaultComparator.INSTANCE.compare(inKey, outKey);
      if (result != 0) {
        return result;
      }
    }
    return 0;
  }

  /**
   * {@inheritDoc }
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final var that = (CompositeKey) o;

    return keys.equals(that.keys);
  }

  /**
   * {@inheritDoc }
   */
  @Override
  public int hashCode() {
    return keys.hashCode();
  }

  /**
   * {@inheritDoc }
   */
  @Override
  public String toString() {
    return "CompositeKey{" + "keys=" + keys + '}';
  }

  @Override
  public EntityImpl toEntity(DatabaseSessionInternal db) {
    final var entity = db.newEmbeddedEntity();
    for (var i = 0; i < keys.size(); i++) {
      entity.setProperty("key" + i, keys.get(i));
    }

    return (EntityImpl) entity;
  }

  @Override
  public void fromDocument(EntityImpl entity) {
    entity.setLazyLoad(false);

    final var fieldNames = entity.fieldNames();

    final SortedMap<Integer, Object> keyMap = new TreeMap<>();

    for (var fieldName : fieldNames) {
      if (fieldName.startsWith("key")) {
        final var keyIndex = fieldName.substring(3);
        keyMap.put(Integer.valueOf(keyIndex), entity.field(fieldName));
      }
    }

    keys.clear();
    keys.addAll(keyMap.values());
  }

  // Alternative (de)serialization methods that avoid converting the CompositeKey to a entity.
  public void toStream(DatabaseSessionInternal db, RecordSerializerNetworkV37 serializer,
      DataOutput out) throws IOException {
    var l = keys.size();
    out.writeInt(l);
    for (var key : keys) {
      if (key instanceof CompositeKey) {
        throw new SerializationException(db.getDatabaseName(),
            "Cannot serialize unflattened nested composite key.");
      }
      if (key == null) {
        out.writeByte((byte) -1);
      } else {
        var type = PropertyType.getTypeByValue(key);
        var bytes = serializer.serializeValue(db, key, type);
        out.writeByte((byte) type.getId());
        out.writeInt(bytes.length);
        out.write(bytes);
      }
    }
  }

  public void fromStream(DatabaseSessionInternal db, RecordSerializerNetworkV37 serializer,
      DataInput in) throws IOException {
    var l = in.readInt();
    for (var i = 0; i < l; i++) {
      var b = in.readByte();
      if (b == -1) {
        addKey(null);
      } else {
        var len = in.readInt();
        var bytes = new byte[len];
        in.readFully(bytes);
        var type = PropertyType.getById(b);
        var k = serializer.deserializeValue(db, bytes, type);
        addKey(k);
      }
    }
  }

  public void addIdentityChangeListener(IdentityChangeListener identityChangeListeners) {
    if (!canChangeIdentity()) {
      return;
    }

    if (this.identityChangeListeners == null) {
      this.identityChangeListeners = Collections.newSetFromMap(new WeakHashMap<>());
    }

    this.identityChangeListeners.add(identityChangeListeners);
  }

  public void removeIdentityChangeListener(IdentityChangeListener identityChangeListener) {
    if (this.identityChangeListeners != null) {
      this.identityChangeListeners.remove(identityChangeListener);

      if (this.identityChangeListeners.isEmpty()) {
        this.identityChangeListeners = null;
      }
    }
  }

  private void fireBeforeIdentityChange() {
    if (this.identityChangeListeners != null) {
      for (var listener : this.identityChangeListeners) {
        listener.onBeforeIdentityChange(this);
      }
    }
  }

  private void fireAfterIdentityChange() {
    if (this.identityChangeListeners != null) {
      for (var listener : this.identityChangeListeners) {
        listener.onAfterIdentityChange(this);
      }
    }
  }

  @Override
  public void onBeforeIdentityChange(Object source) {
    fireBeforeIdentityChange();
  }

  @Override
  public void onAfterIdentityChange(Object source) {
    fireAfterIdentityChange();
  }

  @Override
  public boolean canChangeIdentity() {
    return canChangeIdentity;
  }
}
