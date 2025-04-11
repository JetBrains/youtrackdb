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
package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

/**
 * Interface that indicates that index definition is based on collection of values but not on single
 * value.
 *
 * @since 20.12.11
 */
public interface IndexDefinitionMultiValue extends IndexDefinition {

  /**
   * Converts passed in value in the key of single index entry.
   *
   * @param transaction Current database session.
   * @param param       Value to convert.
   * @return Index key.
   */
  Object createSingleValue(FrontendTransaction transaction, final Object... param);

  /**
   * Process event that contains operation on collection and extract values that should be added
   * removed from index to reflect collection changes in the given index.
   *
   * @param transaction
   * @param changeEvent  Event that describes operation that was performed on collection.
   * @param keysToAdd    Values that should be added to related index.
   * @param keysToRemove Values that should be removed to related index.
   */
  void processChangeEvent(
      FrontendTransaction transaction,
      final MultiValueChangeEvent<?, ?> changeEvent,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove);
}
