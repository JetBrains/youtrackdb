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

import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;

/**
 * Represents a binary field.
 */
public class BinaryField {

  public final String name;
  public final PropertyTypeInternal type;
  public final BytesContainer bytes;
  public final Collate collate;

  public BinaryField(
      final String iName, final PropertyTypeInternal iType, final BytesContainer iBytes,
      final Collate iCollate) {
    name = iName;
    type = iType;
    bytes = iBytes;
    collate = iCollate;
  }

  public BinaryField copy() {
    return new BinaryField(name, type, bytes.copy(), collate);
  }
}
