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
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Lazy implementation of ArrayList. It's bound to a source Record object to keep track of changes.
 * This avoid to call the makeDirty() by hand when the list is changed. It handles an internal
 * contentType to speed up some operations like conversion to/from record/links.
 */
public class LinkList extends TrackedList<Identifiable> implements Sizeable,
    LinkTrackedMultiValue<Integer> {

  @Nonnull
  private final WeakReference<DatabaseSessionInternal> session;

  public LinkList(DatabaseSessionInternal session) {
    super();
    this.session = new WeakReference<>(session);
  }

  public LinkList(int size, DatabaseSessionInternal session) {
    super(size);
    this.session = new WeakReference<>(session);
  }

  public LinkList(@Nonnull final RecordElement sourceRecord) {
    super(sourceRecord);
    this.session = new WeakReference<>(sourceRecord.getSession());
  }

  public LinkList(@Nonnull final RecordElement sourceRecord, int size) {
    super(sourceRecord, size);
    this.session = new WeakReference<>(sourceRecord.getSession());
  }

  public LinkList(
      @Nonnull final RecordElement sourceRecord, final Collection<? extends Identifiable> origin) {
    this(sourceRecord);
    if (origin != null && !origin.isEmpty()) {
      addAll(origin);
    }
  }

  @Override
  public boolean addAll(Collection<? extends Identifiable> c) {
    for (var o : c) {
      add(o);
    }

    return true;
  }

  @Override
  public boolean add(Identifiable e) {
    e = convertToRid(e);
    return super.add(e);
  }


  @Override
  public void add(int index, Identifiable e) {
    e = convertToRid(e);
    super.add(index, e);
  }

  @Override
  public boolean addInternal(Identifiable e) {
    e = convertToRid(e);
    return super.addInternal(e);
  }


  @Override
  public void replaceAll(@Nonnull UnaryOperator<Identifiable> operator) {
    Objects.requireNonNull(operator);
    var li = this.listIterator();
    while (li.hasNext()) {
      li.set(convertToRid(operator.apply(li.next())));
    }
  }

  @Override
  public void setInternal(int index, Identifiable element) {
    super.setInternal(index, convertToRid(element));
  }

  @Override
  public boolean addAll(int index, Collection<? extends Identifiable> c) {
    for (var o : c) {
      add(index++, o);
    }
    return true;
  }

  @Override
  public Identifiable set(int index, Identifiable e) {
    e = convertToRid(e);
    return super.set(index, e);
  }

  @Override
  public void addFirst(Identifiable identifiable) {
    super.addFirst(convertToRid(identifiable));
  }

  @Override
  public void addLast(Identifiable identifiable) {
    super.addLast(convertToRid(identifiable));
  }

  @Nullable
  @Override
  public DatabaseSessionInternal getSession() {
    return session.get();
  }
}
