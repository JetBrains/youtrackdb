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
package com.jetbrains.youtrack.db.internal.common.collection;

import java.util.ListIterator;
import javax.annotation.Nullable;

/**
 * Lazy iterator implementation based on List Iterator.
 */
public class LazyIteratorListWrapper<T> implements LazyIterator<T> {

  private final ListIterator<T> underlying;

  public LazyIteratorListWrapper(ListIterator<T> iUnderlying) {
    underlying = iUnderlying;
  }

  public boolean hasNext() {
    return underlying.hasNext();
  }

  public T next() {
    return underlying.next();
  }

  public void remove() {
    underlying.remove();
  }

  @Nullable
  public T update(T e) {
    underlying.set(e);
    return null;
  }
}
