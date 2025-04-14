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

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBagDelegate;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.AbstractLinkBag;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.EmbeddedLinkBag;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.RemoteTreeLinkBag;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EntityLinkSetImpl extends AbstractSet<Identifiable> implements
    LinkTrackedMultiValue<Identifiable>,
    LinkSet, RecordElement,
    Serializable, TrackedCollection<Identifiable, Identifiable>, StorageBackedMultiValue {

  private LinkBagDelegate delegate;
  private int topThreshold;
  private int bottomThreshold;

  @Nonnull
  private final DatabaseSessionInternal session;


  public EntityLinkSetImpl(@Nonnull DatabaseSessionInternal session) {
    this.session = session;
    initThresholds(session);
    init();
  }


  public EntityLinkSetImpl(final RecordElement sourceRecord) {
    this(sourceRecord.getSession());
    delegate.setOwner(sourceRecord);
  }


  public EntityLinkSetImpl(RecordElement iSourceRecord, Collection<Identifiable> source) {
    this(iSourceRecord);
    if (source != null && !source.isEmpty()) {
      addAll(source);
    }
  }

  public EntityLinkSetImpl(@Nonnull DatabaseSessionInternal session, LinkBagDelegate delegate) {
    assert ((AbstractLinkBag) delegate).getCounterMaxValue() == 1;
    this.session = session;
    initThresholds(session);
    this.delegate = delegate;
  }

  private void initThresholds(@Nonnull DatabaseSessionInternal session) {
    assert session.assertIfNotActive();
    var conf = session.getConfiguration();
    topThreshold =
        conf.getValueAsInteger(GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD);

    bottomThreshold =
        conf.getValueAsInteger(GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD);
  }

  private void init() {
    delegate = topThreshold >= 0 || session.isRemote() ?
        new EmbeddedLinkBag(session, 1) :
        new BTreeBasedLinkBag(session, 1);
  }


  @Override
  public void addInternal(Identifiable e) {

  }

  public boolean remove(Object o) {
    if (o == null) {
      return false;
    }

    if (!(o instanceof Identifiable identifiable)) {
      return false;
    }

    return delegate.remove(identifiable.getIdentity());
  }

  @Nonnull
  @Override
  public DatabaseSessionInternal getSession() {
    return session;
  }

  @Override
  public void setOwner(RecordElement newOwner) {
    delegate.setOwner(newOwner);
  }

  @Override
  public RecordElement getOwner() {
    return delegate.getOwner();
  }

  @Nonnull
  @Override
  public Iterator<Identifiable> iterator() {
    //noinspection unchecked,rawtypes
    return (Iterator) delegate.iterator();
  }

  @Override
  public int size() {
    return delegate.size();
  }

  public boolean add(@Nullable final Identifiable e) {
    return delegate.add(e.getIdentity());
  }


  public void setDirty() {
    delegate.setDirty();
  }

  @Override
  public void setDirtyNoChanged() {
    delegate.setDirtyNoChanged();
  }

  public Set<Identifiable> returnOriginalState(
      FrontendTransaction transaction,
      final List<MultiValueChangeEvent<Identifiable, Identifiable>> multiValueChangeEvents) {
    var reverted = new HashSet<>(this);

    final var listIterator =
        multiValueChangeEvents.listIterator(multiValueChangeEvents.size());

    while (listIterator.hasPrevious()) {
      final var event = listIterator.previous();
      switch (event.getChangeType()) {
        case ADD:
          reverted.remove(event.getKey());
          break;
        case REMOVE:
          reverted.add(event.getOldValue());
          break;
        default:
          throw new IllegalArgumentException("Invalid change type : " + event.getChangeType());
      }
    }

    return reverted;
  }

  public void enableTracking(RecordElement parent) {
    delegate.enableTracking(parent);
  }

  public void disableTracking(RecordElement parent) {
    delegate.disableTracking(parent);
  }

  @Override
  public void transactionClear() {
    delegate.transactionClear();
  }

  @Override
  public boolean isModified() {
    return delegate.isModified();
  }

  @Override
  public boolean isTransactionModified() {
    return delegate.isTransactionModified();
  }

  @Override
  public MultiValueChangeTimeLine<? extends Identifiable, ? extends Identifiable> getTimeLine() {
    return delegate.getTimeLine();
  }

  public MultiValueChangeTimeLine<? extends Identifiable, ? extends Identifiable> getTransactionTimeLine() {
    return delegate.getTransactionTimeLine();
  }

  @Override
  public int hashCode() {
    if (isEmbedded()) {
      return super.hashCode();
    } else if (delegate instanceof RemoteTreeLinkBag remoteTreeLinkBag) {
      return remoteTreeLinkBag.getCollectionPointer().hashCode();
    } else {
      return ((BTreeBasedLinkBag) delegate).getCollectionPointer().hashCode();
    }
  }

  @Override
  public boolean isEmpty() {
    return size() == 0;
  }

  @Override
  public boolean contains(Object o) {
    if (!(o instanceof Identifiable identifiable)) {
      return false;
    }
    var rid = convertToRid(identifiable);
    return delegate.contains(rid);
  }


  @Override
  public String toString() {
    return "LinkSet[" + delegate.size() + "]";
  }

  public boolean isEmbedded() {
    return delegate instanceof EmbeddedLinkBag;
  }

  public boolean isToSerializeEmbedded() {
    if (isEmbedded()) {
      return true;
    }
    if (getOwner() instanceof DBRecord record && !record.getIdentity().isPersistent()) {
      return true;
    }

    var pointer = getPointer();
    return pointer == null || !pointer.isValid();
  }

  public void checkAndConvert() {
    if (!session.isRemote()) {
      if (isEmbedded()
          && session.getBTreeCollectionManager() != null
          && delegate.size() >= topThreshold) {
        convertToTree();
      } else if (bottomThreshold >= 0 && !isEmbedded() && delegate.size() <= bottomThreshold) {
        convertToEmbedded();
      }
    }
  }

  private void convertToEmbedded() {
    var oldDelegate = (AbstractLinkBag) delegate;
    var isTransactionModified = oldDelegate.isTransactionModified();

    assert oldDelegate.getCounterMaxValue() == 1;
    delegate = new EmbeddedLinkBag(session, oldDelegate.getCounterMaxValue());

    final var owner = oldDelegate.getOwner();
    delegate.disableTracking(owner);
    for (var identifiable : oldDelegate) {
      delegate.add(identifiable);
    }

    delegate.setOwner(owner);

    delegate.setTracker(oldDelegate.getTracker());
    oldDelegate.disableTracking(owner);

    delegate.setDirty();
    delegate.setTransactionModified(isTransactionModified);
    delegate.enableTracking(owner);

    oldDelegate.requestDelete();
  }

  private void convertToTree() {
    var oldDelegate = (AbstractLinkBag) delegate;
    var isTransactionModified = oldDelegate.isTransactionModified();

    assert oldDelegate.getCounterMaxValue() == 1;
    delegate = new BTreeBasedLinkBag(session, oldDelegate.getCounterMaxValue());

    final var owner = oldDelegate.getOwner();
    delegate.disableTracking(owner);
    for (var identifiable : oldDelegate) {
      delegate.add(identifiable);
    }

    delegate.setOwner(owner);

    delegate.setTracker(oldDelegate.getTracker());
    oldDelegate.disableTracking(owner);
    delegate.setDirty();
    delegate.setTransactionModified(isTransactionModified);
    delegate.enableTracking(owner);

    oldDelegate.requestDelete();
  }

  public LinkBagPointer getPointer() {
    if (isEmbedded()) {
      return LinkBagPointer.INVALID;
    } else if (delegate instanceof RemoteTreeLinkBag) {
      return ((RemoteTreeLinkBag) delegate).getCollectionPointer();
    } else {
      return ((BTreeBasedLinkBag) delegate).getCollectionPointer();
    }
  }

  public LinkBagDelegate getDelegate() {
    return delegate;
  }


  public void setOwnerFieldName(String fieldName) {
    if (this.delegate instanceof RemoteTreeLinkBag) {
      ((RemoteTreeLinkBag) this.delegate).setOwnerFieldName(fieldName);
    }
  }
}
