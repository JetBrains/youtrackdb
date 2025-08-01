package com.jetbrains.youtrack.db.internal.core.gremlin.io;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBagDelegate;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LinkBagStub extends LinkBag {

  private final ArrayList<RID> ridArrayList = new ArrayList<>();

  @Override
  public boolean contains(RID rid) {
    return ridArrayList.contains(rid);
  }

  @Override
  public void addAll(Collection<RID> values) {
    ridArrayList.addAll(values);
  }

  @Override
  public void add(RID identifiable) {
    ridArrayList.add(identifiable);
  }

  @Override
  public boolean remove(RID identifiable) {
    return ridArrayList.remove(identifiable);
  }

  @Override
  public boolean isEmbeddedContainer() {
    return true;
  }

  @Override
  public boolean isEmpty() {
    return ridArrayList.isEmpty();
  }

  @Nonnull
  @Override
  public Iterator<RID> iterator() {
    return ridArrayList.iterator();
  }

  @Nonnull
  @Override
  public Stream<RID> stream() {
    return ridArrayList.stream();
  }

  @Override
  public int size() {
    return ridArrayList.size();
  }

  @Override
  public boolean isEmbedded() {
    return true;
  }

  @Override
  public boolean isToSerializeEmbedded() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public void checkAndConvert() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public String toString() {
    return ridArrayList.toString();
  }

  @Override
  public void delete() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public Object returnOriginalState(FrontendTransaction transaction,
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents) {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public void setOwner(RecordElement owner) {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public LinkBagPointer getPointer() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  protected void initThresholds(@Nonnull DatabaseSessionInternal session) {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  protected void init() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public LinkBagDelegate getDelegate() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public boolean equals(Object other) {
    if (other == null) {
      return false;
    }
    if (other == this) {
      return true;
    }
    if (!(other instanceof LinkBag otherLinkBag)) {
      return false;
    }

    @SuppressWarnings("ObjectInstantiationInEqualsHashCode")
    var otherRids = new ArrayList<RID>(otherLinkBag.size());
    //noinspection ObjectInstantiationInEqualsHashCode
    for (var e : otherLinkBag) {
      otherRids.add(e);
    }

    return ridArrayList.equals(otherRids);
  }

  @Override
  public void enableTracking(RecordElement parent) {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public void disableTracking(RecordElement entity) {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public void transactionClear() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public boolean isModified() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public MultiValueChangeTimeLine<? extends RID, ? extends RID> getTimeLine() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public void setDirty() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public void setDirtyNoChanged() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public RecordElement getOwner() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public boolean isTransactionModified() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public MultiValueChangeTimeLine<? extends RID, ? extends RID> getTransactionTimeLine() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public void addOwner(RID e) {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public void removeOwner(RID oldValue) {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public boolean assertIfNotActive() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Nullable
  @Override
  public EntityImpl getOwnerEntity() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public Set<RecordElement> getOwnersSet() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Override
  public boolean isOneOfOwners(RecordElement element) {
    throw new UnsupportedOperationException("Not supported for stubs");
  }

  @Nullable
  @Override
  public DatabaseSessionInternal getSession() {
    throw new UnsupportedOperationException("Not supported for stubs");
  }
}
