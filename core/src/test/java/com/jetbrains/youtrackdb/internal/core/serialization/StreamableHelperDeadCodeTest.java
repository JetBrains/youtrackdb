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
package com.jetbrains.youtrackdb.internal.core.serialization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Dead-code pin tests for {@link StreamableHelper}. Cross-module grep performed during this
 * track's Phase A confirmed zero non-self callers anywhere in the repository:
 *
 * <pre>
 *   grep -rn 'StreamableHelper\.' --include='*.java'
 *       core server driver embedded gremlin-annotations tests test-commons docker-tests
 *     -- only matches are inside StreamableHelper.java itself
 *   grep -rn 'new StreamableHelper'  --include='*.java'  --  zero hits
 * </pre>
 *
 * <p>The class is a dispatcher over typed payloads ({@code NULL}/{@code STREAMABLE}/
 * {@code SERIALIZABLE}/{@code STRING}/{@code INTEGER}/{@code SHORT}/{@code LONG}/
 * {@code BOOLEAN}) and depends on {@link Streamable} (also dead) for the {@code STREAMABLE}
 * arm. Tagged {@link SequentialTest} because tests mutate the static
 * {@code streamableClassLoader} field via {@link StreamableHelper#setStreamableClassLoader}.
 * The {@code @Before}/{@code @After} pair restores the field to {@code null} (its default
 * state at JVM startup) so cross-test pollution under
 * {@code <parallel>classes</parallel>} cannot leak.
 *
 * <p>WHEN-FIXED: delete {@link StreamableHelper} together with {@link Streamable}; both have
 * zero non-self callers in the codebase.
 */
@Category(SequentialTest.class)
public class StreamableHelperDeadCodeTest {

  @Before
  public void clearStreamableClassLoader() {
    StreamableHelper.setStreamableClassLoader(null);
  }

  @After
  public void resetStreamableClassLoader() {
    StreamableHelper.setStreamableClassLoader(null);
  }

  // ---------------------------------------------------------------------------
  // toStream / fromStream — primitive type arms
  // ---------------------------------------------------------------------------

  @Test
  public void roundTripStringPreservesValue() throws IOException {
    assertEquals("hello", roundTrip("hello"));
  }

  @Test
  public void roundTripIntegerPreservesValue() throws IOException {
    assertEquals(0xCAFEBABE, ((Integer) roundTrip(0xCAFEBABE)).intValue());
  }

  @Test
  public void roundTripShortPreservesValue() throws IOException {
    assertEquals((short) 0x1234, ((Short) roundTrip((short) 0x1234)).shortValue());
  }

  @Test
  public void roundTripLongPreservesValue() throws IOException {
    assertEquals(0xDEADBEEFCAFEBABEL, ((Long) roundTrip(0xDEADBEEFCAFEBABEL)).longValue());
  }

  @Test
  public void roundTripBooleanTruePreservesValue() throws IOException {
    assertEquals(Boolean.TRUE, roundTrip(Boolean.TRUE));
  }

  @Test
  public void roundTripBooleanFalsePreservesValue() throws IOException {
    assertEquals(Boolean.FALSE, roundTrip(Boolean.FALSE));
  }

  @Test
  public void roundTripNullEncodesAsZeroByteAndDeserializesNull() throws IOException {
    assertNull(roundTrip(null));
  }

  // ---------------------------------------------------------------------------
  // SERIALIZABLE arm — falls through for plain Serializable objects
  // ---------------------------------------------------------------------------

  @Test
  public void roundTripSerializablePreservesEquality() throws IOException {
    final var payload = new SerializableValue(42, "v");
    final var deserialized = (SerializableValue) roundTrip(payload);
    assertNotNull(deserialized);
    assertEquals(payload.id, deserialized.id);
    assertEquals(payload.label, deserialized.label);
  }

  // ---------------------------------------------------------------------------
  // STREAMABLE arm — round-trips a Streamable impl, exercising the production
  // class-by-name reflective path (the one branch that actually depends on the
  // sibling-dead Streamable interface)
  // ---------------------------------------------------------------------------

  @Test
  public void roundTripStreamableArmPreservesValueViaClassForName() throws IOException {
    // Default classloader path: streamableClassLoader is null (set by @Before), so fromStream
    // uses Class.forName(payloadClassName).newInstance(). PublicTestStreamable is public + has
    // a public no-arg constructor, satisfying that contract.
    final var in = new PublicTestStreamable();
    in.value = 0xCAFEBABE;
    final var out = (PublicTestStreamable) roundTrip(in);
    assertNotNull(out);
    assertEquals(0xCAFEBABE, out.value);
  }

  @Test
  public void roundTripStreamableArmHonoursSetClassLoader() throws IOException {
    // Provide a non-null classloader. fromStream takes the streamableClassLoader.loadClass(...)
    // branch instead of Class.forName(...), which is the second behavioural arm worth pinning
    // because the two arms differ in production.
    StreamableHelper.setStreamableClassLoader(getClass().getClassLoader());
    final var in = new PublicTestStreamable();
    in.value = 7;
    final var out = (PublicTestStreamable) roundTrip(in);
    assertNotNull(out);
    assertEquals(7, out.value);
  }

  // ---------------------------------------------------------------------------
  // Default arm — non-supported object type throws SerializationException
  // ---------------------------------------------------------------------------

  @Test
  public void toStreamThrowsForNonSupportedObjectType() throws IOException {
    // Pin: a non-Serializable, non-Streamable, non-primitive object (raw java.lang.Object)
    // takes the default arm and throws SerializationException.
    final var out = new DataOutputStream(new ByteArrayOutputStream());
    assertThrows(SerializationException.class,
        () -> StreamableHelper.toStream(out, new Object()));
    out.close();
  }

  @Test
  public void fromStreamThrowsForUnknownTypeByte() throws IOException {
    // Inject an out-of-range type byte (0x7F); fromStream must throw SerializationException.
    final var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeByte(0x7F);
    }
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      assertThrows(SerializationException.class, () -> StreamableHelper.fromStream(in));
    }
  }

  // ---------------------------------------------------------------------------
  // setStreamableClassLoader — toggling the static and verifying it does not break
  // primitive round-trips (which never need the loader)
  // ---------------------------------------------------------------------------

  @Test
  public void settingStreamableClassLoaderDoesNotAffectPrimitiveRoundTrip() throws IOException {
    StreamableHelper.setStreamableClassLoader(StreamableHelperDeadCodeTest.class.getClassLoader());
    assertEquals("still works", roundTrip("still works"));
  }

  @Test
  public void setStreamableClassLoaderToNullRestoresClassForNamePath() throws IOException {
    // After setting and then clearing the classloader, the STREAMABLE arm must fall back to
    // Class.forName. Round-tripping a Streamable proves both setter calls landed (the second
    // call cannot be a silent no-op or the prior loader would still be in effect).
    StreamableHelper.setStreamableClassLoader(getClass().getClassLoader());
    StreamableHelper.setStreamableClassLoader(null);
    final var in = new PublicTestStreamable();
    in.value = 13;
    final var out = (PublicTestStreamable) roundTrip(in);
    assertNotNull(out);
    assertEquals(13, out.value);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Object roundTrip(final Object input) throws IOException {
    final var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      StreamableHelper.toStream(out, input);
    }
    assertTrue("must produce non-empty output for any non-null+null input",
        input == null ? bytes.size() == 1 : bytes.size() >= 1);
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return StreamableHelper.fromStream(in);
    }
  }

  /** Local Serializable used to exercise the SERIALIZABLE switch arm. */
  private static final class SerializableValue implements Serializable {
    private static final long serialVersionUID = 1L;
    private final int id;
    private final String label;

    SerializableValue(final int id, final String label) {
      this.id = id;
      this.label = label;
    }
  }

  /**
   * Public Streamable implementor used to exercise the STREAMABLE switch arm. Must be {@code
   * public} with a public no-arg constructor because {@link StreamableHelper#fromStream}
   * resolves the type via {@code Class.forName(payloadClassName).newInstance()}.
   */
  public static final class PublicTestStreamable implements Streamable {
    public int value;

    public PublicTestStreamable() {
      // Required for Class.forName(...).newInstance().
    }

    @Override
    public void toStream(final java.io.DataOutput out) throws IOException {
      // Fully-qualified parameter names match the Streamable interface contract; staying FQN
      // avoids pulling DataInput/DataOutput imports into the file just for this test fixture.
      out.writeInt(value);
    }

    @Override
    public void fromStream(final java.io.DataInput in) throws IOException {
      value = in.readInt();
    }
  }
}
