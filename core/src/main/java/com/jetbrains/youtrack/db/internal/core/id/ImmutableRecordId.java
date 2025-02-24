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
package com.jetbrains.youtrack.db.internal.core.id;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.serialization.MemoryStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Immutable RID implementation. To be really immutable fields must not be public anymore. TODO!
 */
public class ImmutableRecordId extends RecordId {
  public static final RID EMPTY_RECORD_ID = new ImmutableRecordId();

  public ImmutableRecordId() {
    super();
  }

  public ImmutableRecordId(final int iClusterId, final long iClusterPosition) {
    super(iClusterId, iClusterPosition);
  }

  public ImmutableRecordId(final RecordId iRID) {
    super(iRID);
  }

  @Override
  public RecordId fromStream(byte[] iBuffer) {
    throw new UnsupportedOperationException("fromStream");
  }

  @Override
  public RecordId fromStream(MemoryStream iStream) {
    throw new UnsupportedOperationException("fromStream");
  }

  @Override
  public RecordId fromStream(InputStream iStream) throws IOException {
    throw new UnsupportedOperationException("fromStream");
  }

  @Override
  public void fromString(String iRecordId) {
    throw new UnsupportedOperationException("fromString");
  }

  @Override
  public void reset() {
    throw new UnsupportedOperationException("reset");
  }
}
