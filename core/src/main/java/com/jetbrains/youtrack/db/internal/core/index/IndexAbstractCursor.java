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

import com.jetbrains.youtrack.db.api.record.Identifiable;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * @since 4/24/14
 */
public abstract class IndexAbstractCursor implements IndexCursor {

  protected int prefetchSize = -1;
  private Map.Entry<Object, Identifiable> nextEntry;
  private boolean firstTime = true;

  @Override
  public Set<Identifiable> toValues() {
    final var result = new HashSet<Identifiable>();
    var entry = nextEntry();

    while (entry != null) {
      result.add(entry.getValue());
      entry = nextEntry();
    }

    return result;
  }

  @Override
  public Set<Map.Entry<Object, Identifiable>> toEntries() {
    final var result =
        new HashSet<Map.Entry<Object, Identifiable>>();

    var entry = nextEntry();

    while (entry != null) {
      result.add(entry);
      entry = nextEntry();
    }

    return result;
  }

  @Override
  public Set<Object> toKeys() {
    final var result = new HashSet<Object>();

    var entry = nextEntry();

    while (entry != null) {
      result.add(entry.getKey());
      entry = nextEntry();
    }

    return result;
  }

  @Override
  public boolean hasNext() {
    if (firstTime) {
      nextEntry = nextEntry();
      firstTime = false;
    }

    return nextEntry != null;
  }

  @Override
  public Identifiable next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    final var result = nextEntry;
    nextEntry = nextEntry();

    return result.getValue();
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("remove");
  }

  public int getPrefetchSize() {
    return prefetchSize;
  }

  public void setPrefetchSize(final int prefetchSize) {
    this.prefetchSize = prefetchSize;
  }
}
