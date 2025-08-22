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

package com.jetbrains.youtrackdb.internal.core.db.record.ridbag;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.collection.DataContainer;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.StorageBackedMultiValue;
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.AbstractLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.EmbeddedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

/**
 * A collection that contain links to {@link Identifiable}. Bag is similar to set but can contain
 * several entering of the same object.<br>
 *
 * <p>Could be tree based and embedded representation.<br>
 *
 * <ul>
 *   <li><b>Embedded</b> stores its content directly to the entity that owns it.<br>
 *       It better fits for cases when only small amount of links are stored to the bag.<br>
 *   <li><b>Tree-based</b> implementation stores its content in a separate data structure called
 *       {@link IsolatedLinkBagBTree}.<br>
 *       It fits great for cases when you have a huge amount of links.<br>
 * </ul>
 *
 * <br>
 * The representation is automatically converted to tree-based implementation when top threshold is
 * reached. And backward to embedded one when size is decreased to bottom threshold. <br>
 * The thresholds could be configured by {@link
 * GlobalConfiguration#LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD} and {@link
 * GlobalConfiguration#LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD}. <br>
 * <br>
 * This collection is used to efficiently manage relationships in graph model.<br>
 * <br>
 * Does not implement {@link Collection} interface because some operations could not be efficiently
 * implemented and that's why should be avoided.<br>
 *
 * @since 1.7rc1
 */
public class LinkBag
    implements
    Iterable<RID>,
    Sizeable,
    TrackedMultiValue<RID, RID>,
    DataContainer<RID>,
    RecordElement, StorageBackedMultiValue {

  private LinkBagDelegate delegate;
  private int topThreshold;
  private int bottomThreshold;

  private final DatabaseSessionInternal session;

  protected LinkBag() {
    session = null;
  }

  public LinkBag(@Nonnull DatabaseSessionInternal session, final LinkBag source) {
    initThresholds(session);
    init();
    for (var identifiable : source) {
      add(identifiable);
    }
    this.session = session;
  }

  public LinkBag(@Nonnull DatabaseSessionInternal session) {
    this.session = session;
    initThresholds(session);
    init();
  }


  public LinkBag(@Nonnull DatabaseSessionInternal session, LinkBagDelegate delegate) {
    this.session = session;
    initThresholds(session);
    this.delegate = delegate;
  }

  /**
   * THIS IS VERY EXPENSIVE METHOD AND CAN NOT BE CALLED IN REMOTE STORAGE.
   *
   * @param rid RID to check.
   * @return true if ridbag contains at leas one instance with the same rid as passed in
   * identifiable.
   */
  public boolean contains(RID rid) {
    return delegate.contains(rid);
  }

  public void addAll(Collection<RID> values) {
    delegate.addAll(values);
  }

  @Override
  public void add(RID identifiable) {
    delegate.add(identifiable);
  }

  @Override
  public boolean remove(RID identifiable) {
    return delegate.remove(identifiable);
  }

  @Override
  public boolean isEmbeddedContainer() {
    return false;
  }

  public boolean isEmpty() {
    return delegate.isEmpty();
  }

  @Nonnull
  @Override
  public Iterator<RID> iterator() {
    return delegate.iterator();
  }

  @Nonnull
  public Stream<RID> stream() {
    return delegate.stream();
  }

  @Override
  public int size() {
    return delegate.size();
  }

  @Override
  public boolean isSizeable() {
    return true;
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
    if (isEmbedded()
        && session.getBTreeCollectionManager() != null
        && delegate.size() >= topThreshold) {
      convertToTree();
    } else if (bottomThreshold >= 0 && !isEmbedded() && delegate.size() <= bottomThreshold) {
      convertToEmbedded();
    }
  }

  private void convertToEmbedded() {
    var oldDelegate = (AbstractLinkBag) delegate;
    var isTransactionModified = oldDelegate.isTransactionModified();
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

    oldDelegate.requestDelete(session.getTransactionInternal());
  }

  private void convertToTree() {
    var oldDelegate = (AbstractLinkBag) delegate;
    var isTransactionModified = oldDelegate.isTransactionModified();

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

    oldDelegate.requestDelete(session.getTransactionInternal());
  }

  @Override
  public String toString() {
    return delegate.toString();
  }

  public void delete() {
    delegate.requestDelete(session.getTransactionInternal());
  }

  @Override
  public Object returnOriginalState(
      FrontendTransaction transaction,
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents) {
    return new LinkBag(transaction.getDatabaseSession(),
        (LinkBagDelegate) delegate.returnOriginalState(transaction, multiValueChangeEvents));
  }

  @Override
  public void rollbackChanges(FrontendTransaction transaction) {
    delegate.rollbackChanges(transaction);
  }

  @Override
  public void setOwner(RecordElement owner) {
    if ((!(owner instanceof EntityImpl) && owner != null)
        || (owner != null && ((EntityImpl) owner).isEmbedded())) {
      throw new DatabaseException(session.getDatabaseName(),
          "RidBag are supported only at entity root");
    }
    delegate.setOwner(owner);
  }

  public LinkBagPointer getPointer() {
    if (isEmbedded()) {
      return LinkBagPointer.INVALID;
    } else {
      return ((BTreeBasedLinkBag) delegate).getCollectionPointer();
    }
  }

  protected void initThresholds(@Nonnull DatabaseSessionInternal session) {
    assert session.assertIfNotActive();
    var conf = session.getConfiguration();
    topThreshold =
        conf.getValueAsInteger(GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD);

    bottomThreshold =
        conf.getValueAsInteger(GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD);
  }

  protected void init() {
    delegate = topThreshold >= 0 ?
        new EmbeddedLinkBag(session, Integer.MAX_VALUE) :
        new BTreeBasedLinkBag(session, Integer.MAX_VALUE);
  }

  public LinkBagDelegate getDelegate() {
    return delegate;
  }


  @Override
  public boolean equals(Object other) {
    if (!(other instanceof LinkBag otherRidbag)) {
      return false;
    }

    if (!delegate.getClass().equals(otherRidbag.delegate.getClass())) {
      return false;
    }

    var firstIter = delegate.iterator();
    var secondIter = otherRidbag.delegate.iterator();
    while (firstIter.hasNext()) {
      if (!secondIter.hasNext()) {
        return false;
      }

      Identifiable firstElement = firstIter.next();
      Identifiable secondElement = secondIter.next();
      if (!Objects.equals(firstElement, secondElement)) {
        return false;
      }
    }
    return !secondIter.hasNext();
  }

  @Override
  public void enableTracking(RecordElement parent) {
    delegate.enableTracking(parent);
  }

  @Override
  public void disableTracking(RecordElement entity) {
    delegate.disableTracking(entity);
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
  public MultiValueChangeTimeLine<? extends RID, ? extends RID> getTimeLine() {
    return delegate.getTimeLine();
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
  public RecordElement getOwner() {
    return delegate.getOwner();
  }

  @Override
  public boolean isTransactionModified() {
    return delegate.isTransactionModified();
  }

  @Override
  public MultiValueChangeTimeLine<? extends RID, ? extends RID> getTransactionTimeLine() {
    return delegate.getTransactionTimeLine();
  }
}
