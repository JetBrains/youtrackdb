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

/** Shared helpers for {@link BinaryComparatorV0} test classes in this package. */
final class BinaryComparatorV0TestFixture {

  private BinaryComparatorV0TestFixture() {
  }

  /**
   * Allocates a fresh {@link BytesContainer}, serializes {@code value} at offset 0, and wraps the
   * result as a {@link BinaryField} with no collation. Container offset is reset to 0 so the
   * comparator reads from the value's first byte. Field name is {@code null} to match
   * {@code AbstractComparatorTest}'s convention; the comparator does not consume the name.
   */
  static BinaryField field(
      EntitySerializer serializer,
      DatabaseSessionEmbedded session,
      PropertyTypeInternal type,
      Object value) {
    var bytes = new BytesContainer();
    bytes.offset = serializer.serializeValue(session, bytes, value, type, null, null, null);
    return new BinaryField(null, type, new BytesContainer(bytes.bytes, 0), null);
  }
}
