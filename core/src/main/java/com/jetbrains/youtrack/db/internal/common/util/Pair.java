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
package com.jetbrains.youtrack.db.internal.common.util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Keeps a pair of values as Key/Value.
 *
 * @param <K> Key
 * @param <V> Value
 * @see Triple
 */
public class Pair<K extends Comparable, V>
    implements Entry<K, V>, Comparable<Pair<K, V>>, Serializable {

  public K key;
  public V value;

  public Pair() {
  }

  public Pair(final K iKey, final V iValue) {
    key = iKey;
    value = iValue;
  }

  public Pair(final Entry<K, V> iSource) {
    key = iSource.getKey();
    value = iSource.getValue();
  }

  public void init(final K iKey, final V iValue) {
    key = iKey;
    value = iValue;
  }

  public K getKey() {
    return key;
  }

  public V getValue() {
    return value;
  }

  public V setValue(final V iValue) {
    var oldValue = value;
    value = iValue;
    return oldValue;
  }

  @Override
  public String toString() {
    final var buffer = new StringBuilder(512);
    buffer.append(key);
    buffer.append(':');

    if (value == null || !value.getClass().isArray()) {
      buffer.append(value);
    } else {
      buffer.append(Arrays.toString((Object[]) value));
    }

    return buffer.toString();
  }

  @Override
  public int hashCode() {
    final var prime = 31;
    var result = 1;
    result = prime * result + ((key == null) ? 0 : key.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    var other = (Pair<?, ?>) obj;
    if (key == null) {
      return other.key == null;
    } else {
      return key.equals(other.key);
    }
  }

  public int compareTo(final Pair<K, V> o) {
    return key.compareTo(o.key);
  }

  public static <K extends Comparable<K>, V> Map<K, V> convertToMap(
      final List<Pair<K, V>> iValues) {
    final var result = new HashMap<K, V>(iValues.size());
    for (var p : iValues) {
      result.put(p.key, p.value);
    }

    return result;
  }

  public static <K extends Comparable<K>, V> List<Pair<K, V>> convertFromMap(
      final Map<K, V> iValues) {
    final List<Pair<K, V>> result = new ArrayList<Pair<K, V>>(iValues.size());
    for (var p : iValues.entrySet()) {
      result.add(new Pair<K, V>(p.getKey(), p.getValue()));
    }

    return result;
  }
}
