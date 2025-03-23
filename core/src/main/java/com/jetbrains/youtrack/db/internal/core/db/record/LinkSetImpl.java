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
import com.jetbrains.youtrack.db.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrack.db.internal.core.id.IdentityChangeListener;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LinkSetImpl extends TrackedSet<Identifiable> implements IdentityChangeListener,
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
    var result = super.addInternal(e);

    if (result) {
      var rid = e.getIdentity();

      if (rid instanceof ChangeableIdentity changeableIdentity
          && changeableIdentity.canChangeIdentity()) {
        changeableIdentity.addIdentityChangeListener(this);
      }
    }

    return result;
  }


  public void clear() {
    for (var identifiable : this) {
      if (identifiable instanceof ChangeableIdentity changeableIdentity) {
        changeableIdentity.removeIdentityChangeListener(this);
      }
    }

    super.clear();
  }

  public boolean removeAll(final Collection<?> c) {
    var result = super.removeAll(c);

    if (result) {
      for (var item : c) {
        if (item instanceof ChangeableIdentity changeableIdentity) {
          changeableIdentity.removeIdentityChangeListener(this);
        }
      }
    }

    return result;
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

      if (resultAdd) {
        if (o instanceof ChangeableIdentity changeableIdentity
            && changeableIdentity.canChangeIdentity()) {
          changeableIdentity.addIdentityChangeListener(this);
        }
      }
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

        if (current instanceof ChangeableIdentity changeableIdentity) {
          changeableIdentity.removeIdentityChangeListener(LinkSetImpl.this);
        }
        current = null;
      }
    };
  }

  @Override
  public boolean add(@Nullable Identifiable e) {
    e = convertToRid(e);
    var result = super.add(e);

    if (result) {
      if (e instanceof ChangeableIdentity changeableIdentity
          && changeableIdentity.canChangeIdentity()) {
        changeableIdentity.addIdentityChangeListener(this);
      }
    }

    return result;
  }

  @Override
  public void onBeforeIdentityChange(Object source) {
    super.remove(source);
  }

  @Override
  public void onAfterIdentityChange(Object source) {
    super.addInternal((Identifiable) source);
  }

  public boolean remove(Object o) {
    if (o == null) {
      return false;
    }

    var result = super.remove(o);
    if (result) {
      if (o instanceof ChangeableIdentity changeableIdentity) {
        changeableIdentity.removeIdentityChangeListener(this);
      }
    }

    return result;
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
