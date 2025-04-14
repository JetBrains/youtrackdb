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

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrack.db.internal.core.record.impl.SimpleMultiValueTracker;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.Change;
import java.util.Collection;
import java.util.Spliterator;
import java.util.stream.Stream;

public interface LinkBagDelegate
    extends Iterable<RID>,
    Sizeable,
    TrackedMultiValue<RID, RID>,
    RecordElement {

  void addAll(Collection<RID> values);

  boolean add(RID rid);

  boolean remove(RID rid);

  boolean isEmpty();

  @Override
  default boolean isEmbeddedContainer() {
    return false;
  }

  void requestDelete();

  /**
   * THIS IS VERY EXPENSIVE METHOD AND CAN NOT BE CALLED IN REMOTE STORAGE.
   *
   * @param identifiable Object to check.
   * @return true if ridbag contains at leas one instance with the same rid as passed in
   * identifiable.
   */
  boolean contains(RID identifiable);

  @Override
  void setOwner(RecordElement owner);

  @Override
  RecordElement getOwner();

  String toString();

  Stream<RawPair<RID, Change>> getChanges();

  SimpleMultiValueTracker<RID, RID> getTracker();

  void setTracker(SimpleMultiValueTracker<RID, RID> tracker);

  void setTransactionModified(boolean transactionModified);

  Stream<RID> stream();

  @Override
  Spliterator<RID> spliterator();
}
