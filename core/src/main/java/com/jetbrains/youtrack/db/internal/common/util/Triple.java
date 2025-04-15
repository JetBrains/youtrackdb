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

/**
 * Structure to handle a triple of values configured as a key and a Pair as value.
 *
 * @see Pair
 */
public class Triple<K extends Comparable<K>, V extends Comparable<V>, SV>
    implements Comparable<Triple<K, V, SV>> {

  public K key;
  public Pair<V, SV> value;

  public Triple() {
  }

  public Triple(final K iKey, final V iValue, final SV iSubValue) {
    init(iKey, iValue, iSubValue);
  }

  public void init(final K iKey, final V iValue, final SV iSubValue) {
    key = iKey;
    value = new Pair(iValue, iSubValue);
  }

  public K getKey() {
    return key;
  }

  public Pair<V, SV> getValue() {
    return value;
  }

  public Pair<V, SV> setValue(final Pair<V, SV> iValue) {
    final var oldValue = value;
    value = iValue;
    return oldValue;
  }

  public void setSubValue(final SV iSubValue) {
    final var oldValue = value;
    value.setValue(iSubValue);
  }

  @Override
  public String toString() {
    return key + ":" + value.getKey() + "/" + value.getValue();
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
    var other = (Triple<?, ?, ?>) obj;
    if (key == null) {
      return other.key == null;
    } else {
      return key.equals(other.key);
    }
  }

  public int compareTo(final Triple<K, V, SV> o) {
    return key.compareTo(o.key);
  }
}
