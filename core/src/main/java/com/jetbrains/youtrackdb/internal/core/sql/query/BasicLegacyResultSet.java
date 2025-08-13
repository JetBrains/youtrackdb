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
package com.jetbrains.youtrackdb.internal.core.sql.query;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;

/**
 * ResultSet class that implements List interface for retro compatibility.
 *
 * @param <T>
 */
public class BasicLegacyResultSet<T> implements LegacyResultSet<T> {

  protected List<T> underlying;
  protected transient int limit = -1;

  public BasicLegacyResultSet() {
    underlying = Collections.synchronizedList(new ArrayList<T>());
  }

  public BasicLegacyResultSet(final int iCapacity) {
    underlying = Collections.synchronizedList(new ArrayList<T>(iCapacity));
  }

  @Override
  @Override
  public BasicLegacyResultSet<T> setCompleted() {
    return this;
  }

  @Override
  public T set(int index, T element) {
    return underlying.set(index, element);
  }

  @Override
  public int currentSize() {
    return underlying.size();
  }

  @Override
  public int size() {
    return underlying.size();
  }

  @Override
  public boolean isEmpty() {
    var empty = underlying.isEmpty();
    if (empty) {
      empty = underlying.isEmpty();
    }
    return empty;
  }

  @Override
  public boolean contains(final Object o) {
    return underlying.contains(o);
  }

  @Override
  public Iterator<T> iterator() {
    return new Iterator<T>() {
      private int index = 0;

      @Override
      public boolean hasNext() {
        return index < size();
      }

      @Override
      public T next() {
        if (index > size() || size() == 0) {
          throw new NoSuchElementException(
              "Error on browsing at entity "
                  + index
                  + " while the resultset contains only "
                  + size()
                  + " items");
        }

        return underlying.get(index++);
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("BasicLegacyResultSet.iterator.remove()");
      }
    };
  }

  @Override
  public Object[] toArray() {
    return underlying.toArray();
  }

  @Override
  public <T1> T1[] toArray(final T1[] a) {
    return underlying.toArray(a);
  }

  @Override
  public boolean add(final T t) {
    if (limit > -1 && underlying.size() >= limit) {
      return false;
    }

    final var result = underlying.add(t);
    return result;
  }

  @Override
  public boolean remove(final Object o) {
    throw new UnsupportedOperationException("remove");
  }

  @Override
  public boolean containsAll(final Collection<?> c) {
    throw new UnsupportedOperationException("remove");
  }

  @Override
  public boolean addAll(final Collection<? extends T> c) {
    return underlying.addAll(c);
  }

  @Override
  public boolean addAll(final int index, final Collection<? extends T> c) {
    return underlying.addAll(index, c);
  }

  @Override
  public boolean removeAll(final Collection<?> c) {
    throw new UnsupportedOperationException("remove");
  }

  @Override
  public boolean retainAll(final Collection<?> c) {
    throw new UnsupportedOperationException("remove");
  }

  @Override
  public void clear() {
    underlying.clear();
  }

  @Override
  public boolean equals(final Object o) {
    return underlying.equals(o);
  }

  @Override
  public int hashCode() {
    return underlying.hashCode();
  }

  @Override
  public T get(final int index) {
    return underlying.get(index);
  }

  @Override
  public void add(final int index, T element) {
    underlying.add(index, element);
  }

  @Override
  public T remove(final int index) {
    throw new UnsupportedOperationException("remove");
  }

  @Override
  public int indexOf(Object o) {
    throw new UnsupportedOperationException("indexOf");
  }

  @Override
  public int lastIndexOf(Object o) {
    throw new UnsupportedOperationException("lastIndexOf");
  }

  @Override
  public ListIterator<T> listIterator() {
    return underlying.listIterator();
  }

  @Override
  public ListIterator<T> listIterator(int index) {
    return underlying.listIterator(index);
  }

  @Override
  public List<T> subList(int fromIndex, int toIndex) {
    return underlying.subList(fromIndex, toIndex);
  }

  @Override
  @Override
  public int getLimit() {
    return limit;
  }

  @Override
  @Override
  public LegacyResultSet<T> setLimit(final int limit) {
    this.limit = limit;
    return this;
  }

  @Override
  public void writeExternal(final ObjectOutput out) throws IOException {
    out.writeObject(underlying);
  }

  @Override
  public void readExternal(final ObjectInput in) throws IOException, ClassNotFoundException {
    underlying = (List<T>) in.readObject();
  }

  @Override
  public BasicLegacyResultSet<T> copy() {
    final var newValue = new BasicLegacyResultSet<T>();
    newValue.underlying.addAll(underlying);
    return newValue;
  }

  @Override
  public boolean isEmptyNoWait() {
    return underlying.isEmpty();
  }

}
