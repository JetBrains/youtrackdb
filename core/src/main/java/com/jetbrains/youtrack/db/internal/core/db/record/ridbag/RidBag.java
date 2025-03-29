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

package com.jetbrains.youtrack.db.internal.core.db.record.ridbag;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.collection.DataContainer;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.EmbeddedLinkBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.AbstractLinkBag;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BonsaiCollectionPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.Change;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.RemoteTreeLinkBag;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 *       {@link com.jetbrains.youtrack.db.internal.core.storage.ridbag.ridbagbtree.EdgeBTree}.<br>
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
public class RidBag
    implements
    Iterable<RID>,
    Sizeable,
    TrackedMultiValue<RID, RID>,
    DataContainer<RID>,
    RecordElement {

  private LinkBagDelegate delegate;

  private RecordId ownerRecord;
  private String fieldName;

  private int topThreshold;
  private int bottomThreshold;
  private UUID uuid;

  @Nonnull
  private final DatabaseSessionInternal session;

  public RidBag(@Nonnull DatabaseSessionInternal session, final RidBag ridBag) {
    initThresholds(session);
    init();
    for (var identifiable : ridBag) {
      add(identifiable);
    }
    this.session = session;
  }

  public RidBag(@Nonnull DatabaseSessionInternal session) {
    this.session = session;
    initThresholds(session);
    init();
  }

  public RidBag(@Nonnull DatabaseSessionInternal session, UUID uuid) {
    this.session = session;
    initThresholds(session);
    init();
    this.uuid = uuid;
  }

  public RidBag(@Nonnull DatabaseSessionInternal session, BonsaiCollectionPointer pointer,
      Map<RID, Change> changes, UUID uuid, int size) {
    this.session = session;
    initThresholds(session);
    delegate = new BTreeBasedLinkBag(pointer, changes, session, size, Integer.MAX_VALUE);
    this.uuid = uuid;
  }


  public RidBag(@Nonnull DatabaseSessionInternal session, LinkBagDelegate delegate) {
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
  public void remove(RID identifiable) {
    delegate.remove(identifiable);
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

  @Override
  public int size() {
    return delegate.size();
  }

  public boolean isEmbedded() {
    return delegate instanceof EmbeddedLinkBag;
  }

  public boolean isToSerializeEmbedded() {
    if (isEmbedded()) {
      return true;
    }
    if (getOwner() instanceof DBRecord && !((DBRecord) getOwner()).getIdentity().isPersistent()) {
      return true;
    }

    var pointer = getPointer();
    return pointer == null || pointer == BonsaiCollectionPointer.INVALID;
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

  @Override
  public String toString() {
    return delegate.toString();
  }

  public void delete() {
    delegate.requestDelete();
  }

  @Override
  public Object returnOriginalState(
      DatabaseSessionInternal session,
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents) {
    return new RidBag(session,
        (LinkBagDelegate) delegate.returnOriginalState(session, multiValueChangeEvents));
  }


  public void setOwner(RecordElement owner) {
    if ((!(owner instanceof EntityImpl) && owner != null)
        || (owner != null && ((EntityImpl) owner).isEmbedded())) {
      throw new DatabaseException(session.getDatabaseName(),
          "RidBag are supported only at entity root");
    }
    delegate.setOwner(owner);
  }

  /**
   * Temporary id of collection to track changes in remote mode.
   *
   * <p>WARNING! Method is for internal usage.
   *
   * @return UUID
   */
  public UUID getTemporaryId() {
    return uuid;
  }

  public void setTemporaryId(UUID uuid) {
    this.uuid = uuid;
  }

  /**
   * Notify collection that changes has been saved. Converts to non embedded implementation if
   * needed.
   *
   * <p>WARNING! Method is for internal usage.
   *
   * @param newPointer new collection pointer
   */
  public void notifySaved(BonsaiCollectionPointer newPointer, DatabaseSessionInternal session) {
    if (newPointer.isValid()) {
      if (isEmbedded()) {
        replaceWithSBTree(newPointer, session);
      } else if (delegate instanceof BTreeBasedLinkBag) {
        ((BTreeBasedLinkBag) delegate).setCollectionPointer(newPointer);
        ((BTreeBasedLinkBag) delegate).clearChanges();
      }
    }
  }

  public BonsaiCollectionPointer getPointer() {
    if (isEmbedded()) {
      return BonsaiCollectionPointer.INVALID;
    } else if (delegate instanceof RemoteTreeLinkBag) {
      return ((RemoteTreeLinkBag) delegate).getCollectionPointer();
    } else {
      return ((BTreeBasedLinkBag) delegate).getCollectionPointer();
    }
  }

  /**
   * IMPORTANT! Only for internal usage.
   */
  public boolean tryMerge(final RidBag otherValue, boolean iMergeSingleItemsOfMultiValueFields) {
    if (!isEmbedded() && !otherValue.isEmbedded()) {
      final var thisTree = (BTreeBasedLinkBag) delegate;
      final var otherTree = (BTreeBasedLinkBag) otherValue.delegate;
      if (thisTree.getCollectionPointer().equals(otherTree.getCollectionPointer())) {

        thisTree.mergeChanges(otherTree);

        uuid = otherValue.uuid;

        return true;
      }
    } else if (iMergeSingleItemsOfMultiValueFields) {
      for (var value : otherValue) {
        if (value != null) {
          final var localIter = iterator();
          var found = false;
          while (localIter.hasNext()) {
            final Identifiable v = localIter.next();
            if (value.equals(v)) {
              found = true;
              break;
            }
          }
          if (!found) {
            add(value);
          }
        }
      }
      return true;
    }
    return false;
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
    delegate = topThreshold >= 0 || session.isRemote() ?
        new EmbeddedLinkBag(session, Integer.MAX_VALUE) :
        new BTreeBasedLinkBag(session, Integer.MAX_VALUE);
  }

  /**
   * Silently replace delegate by tree implementation.
   *
   * @param pointer new collection pointer
   */
  private void replaceWithSBTree(BonsaiCollectionPointer pointer, DatabaseSessionInternal session) {
    delegate.requestDelete();
    final var treeBag = new RemoteTreeLinkBag(pointer, session);
    treeBag.setRecordAndField(ownerRecord, fieldName);
    treeBag.setOwner(delegate.getOwner());
    treeBag.setTracker(delegate.getTracker());
    delegate = treeBag;
  }

  public LinkBagDelegate getDelegate() {
    return delegate;
  }

  public List<RawPair<RID, Change>> getChanges() {
    return delegate.getChanges();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof RidBag otherRidbag)) {
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
  public MultiValueChangeTimeLine<RID, RID> getTimeLine() {
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
  public MultiValueChangeTimeLine<RID, RID> getTransactionTimeLine() {
    return delegate.getTransactionTimeLine();
  }

  public void setRecordAndField(RecordId id, String fieldName) {
    if (this.delegate instanceof RemoteTreeLinkBag) {
      ((RemoteTreeLinkBag) this.delegate).setRecordAndField(id, fieldName);
    }
    this.ownerRecord = id;
    this.fieldName = fieldName;
  }

  public void makeTree() {
    if (isEmbedded()) {
      convertToTree();
    }
  }

  public void makeEmbedded() {
    if (!isEmbedded()) {
      convertToEmbedded();
    }
  }
}
