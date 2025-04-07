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

package com.jetbrains.youtrack.db.internal.core.storage.ridbag;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RemoteTreeLinkBag extends AbstractLinkBag {

  private String fieldName;
  private LinkBagPointer pointer;

  public RemoteTreeLinkBag(@Nonnull DatabaseSessionInternal session,
      int counterMaxValue, int size) {
    super(session, size, counterMaxValue);
    this.pointer = LinkBagPointer.INVALID;
  }

  public RemoteTreeLinkBag(@Nonnull DatabaseSessionInternal session, LinkBagPointer pointer,
      int counterMaxValue, int size) {
    super(session, size, counterMaxValue);
    this.pointer = pointer;
  }

  @Override
  protected BagChangesContainer createChangesContainer() {
    return new TreeBasedBagChangesContainer();
  }

  @Override
  protected int getAbsoluteValue(RID rid) {
    assert assertIfNotActive();
    var ownerEntity = checkOwner();

    return session.getStorage()
        .getAbsoluteLinkBagCounter(ownerEntity.getIdentity(), fieldName, rid);
  }

  @Nonnull
  private EntityImpl checkOwner() {
    var ownerEntity = getOwnerEntity();

    if (ownerEntity == null) {
      throw new IllegalStateException(
          "Owner entity is null, can not return underlying value from storage");
    }
    return ownerEntity;
  }

  @Nullable
  @Override
  protected Spliterator<ObjectIntPair<RID>> btreeSpliterator() {
    var ownerEntity = checkOwner();

    return new TransformingSpliterator(
        session.query("select expand(@this.field(:fieldName)) from " +
                ownerEntity.getIdentity(), false,
            Map.of("fieldName", fieldName)));
  }

  @Override
  protected Spliterator<ObjectIntPair<RID>> btreeSpliterator(RID after) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object returnOriginalState(
      DatabaseSessionInternal session,
      List<MultiValueChangeEvent<RID, RID>> multiValueChangeEvents) {
    final var reverted = new RemoteTreeLinkBag(session,
        counterMaxValue, 0);

    for (var identifiable : this) {
      reverted.add(identifiable);
    }

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

  @Override
  public void requestDelete() {
    throw new UnsupportedOperationException();
  }


  public void setOwnerFieldName(String fieldName) {
    this.fieldName = fieldName;
  }

  public LinkBagPointer getCollectionPointer() {
    return pointer;
  }

  @Override
  public boolean isSizeable() {
    return false;
  }

  private static final class TransformingSpliterator implements Spliterator<ObjectIntPair<RID>> {

    private final Spliterator<Result> delegate;

    TransformingSpliterator(Spliterator<Result> delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean tryAdvance(Consumer<? super ObjectIntPair<RID>> action) {
      return delegate.tryAdvance(result -> {
        var rid = result.getIdentity();
        if (rid == null) {
          throw new IllegalStateException("RID is null");
        }
        action.accept(new ObjectIntImmutablePair<>(rid, 1));
      });
    }

    @Override
    public Spliterator<ObjectIntPair<RID>> trySplit() {
      return new TransformingSpliterator(delegate.trySplit());
    }

    @Override
    public long estimateSize() {
      return delegate.estimateSize();
    }

    @Override
    public int characteristics() {
      return delegate.characteristics();
    }
  }
}
