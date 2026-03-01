package com.jetbrains.youtrackdb.internal.core.db.record.ridbag;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.StorageBackedMultiValue;
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.AbstractLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.EmbeddedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

public class LinkBag
    implements
    Iterable<RidPair>,
    Sizeable,
    TrackedMultiValue<RID, RID>,
    RecordElement, StorageBackedMultiValue {

  private LinkBagDelegate delegate;
  private int topThreshold;
  private int bottomThreshold;

  private final DatabaseSessionEmbedded session;

  protected LinkBag() {
    session = null;
  }

  public LinkBag(@Nonnull DatabaseSessionEmbedded session, final LinkBag source) {
    this.session = session;
    initThresholds(session);
    init();
    for (var pair : source) {
      add(pair.primaryRid(), pair.secondaryRid());
    }
  }

  public LinkBag(@Nonnull DatabaseSessionEmbedded session) {
    this.session = session;
    initThresholds(session);
    init();
  }


  public LinkBag(@Nonnull DatabaseSessionEmbedded session, LinkBagDelegate delegate) {
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

  public void add(RID identifiable) {
    delegate.add(identifiable);
  }

  public boolean add(RID primaryRid, RID secondaryRid) {
    return delegate.add(primaryRid, secondaryRid);
  }

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
  public Iterator<RidPair> iterator() {
    return delegate.iterator();
  }

  @Nonnull
  public Stream<RidPair> stream() {
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
    for (var pair : oldDelegate) {
      delegate.add(pair.primaryRid(), pair.secondaryRid());
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
    for (var pair : oldDelegate) {
      delegate.add(pair.primaryRid(), pair.secondaryRid());
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

  protected void initThresholds(@Nonnull DatabaseSessionEmbedded session) {
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
  public int hashCode() {
    int result = delegate.getClass().hashCode();
    for (var rid : delegate) {
      result = 31 * result + Objects.hashCode(rid);
    }
    return result;
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

      RidPair firstElement = firstIter.next();
      RidPair secondElement = secondIter.next();
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
