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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;

/**
 * Compares types at binary level: super fast, using of literals as much as it can.
 */
public interface BinaryComparator {

  /**
   * Compares if two binary values are the same.
   *
   * @param db
   * @param iFirstValue  First value to compare
   * @param iSecondValue Second value to compare
   * @return true if they match, otherwise false
   */
  boolean isEqual(DatabaseSessionEmbedded db, BinaryField iFirstValue, BinaryField iSecondValue);

  /**
   * Compares two binary values executing also conversion between types.
   *
   * @param db
   * @param iValue1 First value to compare
   * @param iValue2 Second value to compare
   * @return 0 if they matches, >0 if first value is major than second, <0 in case is minor
   */
  int compare(DatabaseSessionEmbedded db, BinaryField iValue1, BinaryField iValue2);

  /**
   * Returns true if the type is binary comparable
   *
   * @param iType
   * @return
   */
  boolean isBinaryComparable(PropertyTypeInternal iType);
}
