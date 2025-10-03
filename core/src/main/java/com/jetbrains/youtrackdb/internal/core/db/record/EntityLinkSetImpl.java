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
package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.api.common.query.collection.links.LinkSet;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBagDelegate;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.AbstractLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.EmbeddedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
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
  private final DatabaseSessionEmbedded session;


  public EntityLinkSetImpl(@Nonnull DatabaseSessionEmbedded session) {
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

  public EntityLinkSetImpl(@Nonnull DatabaseSessionEmbedded session, LinkBagDelegate delegate) {
    assert ((AbstractLinkBag) delegate).getCounterMaxValue() == 1;
    this.session = session;
    initThresholds(session);
    this.delegate = delegate;
  }

  private void initThresholds(@Nonnull DatabaseSessionEmbedded session) {
    assert session.assertIfNotActive();
    var conf = session.getConfiguration();
    topThreshold =
        conf.getValueAsInteger(GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD);

    bottomThreshold =
        conf.getValueAsInteger(GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD);
  }

  private void init() {
    delegate = topThreshold >= 0 ?
        new EmbeddedLinkBag(session, 1) :
        new BTreeBasedLinkBag(session, 1);
  }


  @Override
  public void addInternal(Identifiable e) {

  }

  @Override
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
  public DatabaseSessionEmbedded getSession() {
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

  @Override
  public boolean add(@Nullable final Identifiable e) {
    return delegate.add(e.getIdentity());
  }


  @Override
  public void setDirty() {
    delegate.setDirty();
  }

  @Override
  public void setDirtyNoChanged() {
    delegate.setDirtyNoChanged();
  }

  @Override
  public Set<Identifiable> returnOriginalState(
      FrontendTransaction transaction,
      final List<MultiValueChangeEvent<Identifiable, Identifiable>> multiValueChangeEvents) {
    var reverted = new HashSet<>(this);

    doRollBackChanges(multiValueChangeEvents, reverted);

    return reverted;
  }

  @Override
  public void rollbackChanges(FrontendTransaction transaction) {
    var tracker = delegate.getTracker();
    if (!tracker.isEnabled()) {
      throw new DatabaseException(transaction.getDatabaseSession(),
          "Changes are not tracked so it is impossible to rollback them");
    }

    var timeLine = tracker.getTimeLine();
    //no changes were performed
    if (timeLine == null) {
      return;
    }
    var changeEvents = timeLine.getMultiValueChangeEvents();
    //no changes were performed
    if (changeEvents == null || changeEvents.isEmpty()) {
      return;
    }

    //noinspection rawtypes,unchecked
    doRollBackChanges((List) changeEvents, this);
  }

  private static void doRollBackChanges(
      List<MultiValueChangeEvent<Identifiable, Identifiable>> multiValueChangeEvents,
      Set<Identifiable> reverted) {
    multiValueChangeEvents = List.copyOf(multiValueChangeEvents);
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
  }

  @Override
  public void enableTracking(RecordElement parent) {
    delegate.enableTracking(parent);
  }

  @Override
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

  @Override
  public MultiValueChangeTimeLine<? extends Identifiable, ? extends Identifiable> getTransactionTimeLine() {
    return delegate.getTransactionTimeLine();
  }

  @Override
  public int hashCode() {
    if (isEmbedded()) {
      return super.hashCode();
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

  public void checkAndConvert(FrontendTransaction transaction) {
    if (isEmbedded()
        && session.getBTreeCollectionManager() != null
        && delegate.size() >= topThreshold) {
      convertToTree(transaction);
    } else if (bottomThreshold >= 0 && !isEmbedded() && delegate.size() <= bottomThreshold) {
      convertToEmbedded(transaction);
    }
  }

  private void convertToEmbedded(FrontendTransaction transaction) {
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

    oldDelegate.requestDelete(transaction);
  }

  private void convertToTree(FrontendTransaction transaction) {
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

    oldDelegate.requestDelete(transaction);
  }

  public LinkBagPointer getPointer() {
    if (isEmbedded()) {
      return LinkBagPointer.INVALID;
    } else {
      return ((BTreeBasedLinkBag) delegate).getCollectionPointer();
    }
  }

  public LinkBagDelegate getDelegate() {
    return delegate;
  }
}
