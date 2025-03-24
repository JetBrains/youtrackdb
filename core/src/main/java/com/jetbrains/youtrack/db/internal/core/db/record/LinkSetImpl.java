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
package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LinkSetImpl extends TrackedSet<Identifiable> implements
    LinkTrackedMultiValue<Identifiable>, LinkSet {

  @Nonnull
  private final WeakReference<DatabaseSessionInternal> session;


  public LinkSetImpl(DatabaseSessionInternal session) {
    super();
    this.session = new WeakReference<>(session);
  }

  public LinkSetImpl(int size, DatabaseSessionInternal session) {
    super(size);
    this.session = new WeakReference<>(session);
  }

  public LinkSetImpl(final RecordElement iSourceRecord) {
    super(iSourceRecord);
    this.session = new WeakReference<>(iSourceRecord.getSession());
  }

  public LinkSetImpl(final RecordElement iSourceRecord, int size) {
    super(iSourceRecord, size);
    this.session = new WeakReference<>(iSourceRecord.getSession());
  }

  public LinkSetImpl(RecordElement iSourceRecord, Collection<Identifiable> iOrigin) {
    this(iSourceRecord);

    if (iOrigin != null && !iOrigin.isEmpty()) {
      addAll(iOrigin);
    }
  }

  public boolean addInternal(Identifiable e) {
    e = convertToRid(e);
    return super.addInternal(e);
  }


  public boolean addAll(@Nonnull final Collection<? extends Identifiable> c) {
    if (c.isEmpty()) {
      return false;
    }

    var result = false;
    for (var o : c) {
      o = convertToRid(o);

      var resultAdd = super.add(o);
      result = result || resultAdd;
    }

    return result;
  }

  public boolean retainAll(@Nonnull final Collection<?> c) {
    if (c.isEmpty()) {
      return false;
    }

    Objects.requireNonNull(c);
    var modified = false;
    var it = iterator();

    while (it.hasNext()) {
      if (!c.contains(it.next())) {
        it.remove();
        modified = true;
      }
    }

    return modified;
  }

  @Override
  public Class<?> getGenericClass() {
    return Identifiable.class;
  }

  @Override
  @Nonnull
  public Iterator<Identifiable> iterator() {
    var iterator = super.iterator();
    return new Iterator<>() {
      private Identifiable current = null;

      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public Identifiable next() {
        current = iterator.next();
        return current;
      }

      @Override
      public void remove() {
        iterator.remove();
        current = null;
      }
    };
  }

  @Override
  public boolean add(@Nullable Identifiable e) {
    e = convertToRid(e);
    return super.add(e);
  }

  public boolean remove(Object o) {
    if (o == null) {
      return false;
    }

    return super.remove(o);
  }

  @Nullable
  @Override
  public DatabaseSessionInternal getSession() {
    return session.get();
  }

  @Override
  public boolean isEmbeddedContainer() {
    return false;
  }

  @Override
  public void setOwner(RecordElement newOwner) {
    LinkTrackedMultiValue.checkEntityAsOwner(newOwner);
    super.setOwner(newOwner);
  }
}
