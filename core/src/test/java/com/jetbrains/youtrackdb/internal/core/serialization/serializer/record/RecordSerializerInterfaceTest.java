/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.ReadBytesContainer;
import javax.annotation.Nonnull;
import org.junit.Test;

/**
 * Standalone unit tests for the {@link RecordSerializer} interface contract — specifically the
 * default {@code fromStream(... ReadBytesContainer ...)} method that must throw
 * {@link UnsupportedOperationException} for any implementor that does not opt in to the
 * ByteBuffer-backed deserialization path.
 *
 * <p>This closes the {@code core/serialization/serializer/record} package gap (the interface file
 * has a single uncovered default method body otherwise unreachable from the production binary
 * serializer that overrides it).
 *
 * <p>Strategy: declare a minimal in-test stub implementor that does NOT override the default
 * method, then invoke it and assert the UOE is thrown with a message that includes
 * {@link #getName()}'s return so a regression that drops the name from the message is caught.
 */
public class RecordSerializerInterfaceTest {

  /**
   * Minimal implementor: leaves the {@code fromStream(ReadBytesContainer)} default method intact
   * so the interface's UOE branch can be exercised.
   */
  private static final class StubSerializer implements RecordSerializer {

    static final String NAME = "stub-record-serializer";

    @Override
    public void fromStream(@Nonnull DatabaseSessionEmbedded session, @Nonnull byte[] iSource,
        @Nonnull RecordAbstract iRecord, String[] iFields) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public byte[] toStream(@Nonnull DatabaseSessionEmbedded session,
        @Nonnull RecordAbstract iSource) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public int getCurrentVersion() {
      return 0;
    }

    @Override
    public int getMinSupportedVersion() {
      return 0;
    }

    @Override
    public String[] getFieldNames(@Nonnull DatabaseSessionEmbedded session, EntityImpl reference,
        @Nonnull byte[] iSource) {
      return new String[0];
    }

    @Override
    public boolean getSupportBinaryEvaluate() {
      return false;
    }

    @Override
    public String getName() {
      return NAME;
    }
  }

  /**
   * Default method must throw UOE; the message must include the implementor's name so future
   * dispatch errors are diagnosable.
   */
  @Test
  public void readBytesContainerOverloadThrowsUnsupportedByDefault() {
    final RecordSerializer serializer = new StubSerializer();

    final var thrown = assertThrows(
        UnsupportedOperationException.class,
        () -> serializer.fromStream(
            /* session */ null,
            /* serializerVersion */ (byte) 0,
            /* container */ (ReadBytesContainer) null,
            /* iRecord */ null,
            /* iFields */ null));

    assertEquals(
        "ReadBytesContainer deserialization not supported by " + StubSerializer.NAME,
        thrown.getMessage());
  }
}
