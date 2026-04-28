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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.Test;

/**
 * Dead-code pin tests for the {@link Streamable} interface. Cross-module grep performed during
 * this track's Phase A confirmed zero production implementors:
 *
 * <pre>
 *   grep -rn 'implements Streamable' --include='*.java'
 *       core server driver embedded gremlin-annotations tests test-commons
 *     -- zero hits
 * </pre>
 *
 * <p>The interface is referenced only by {@link StreamableHelper} (also dead — see its own
 * dead-code test) for type dispatch in its {@code toStream}/{@code fromStream} switch arms;
 * with no implementors, the {@code STREAMABLE} branch in {@link StreamableHelper#toStream}
 * is unreachable.
 *
 * <p>This test pins the interface's method signatures so that a removal in the final sweep
 * fails this file's compilation. The test-local implementor below also exercises a positive
 * round-trip path so a refactor that quietly changed the signatures (e.g., dropping the
 * {@code throws IOException} clause or renaming a method) would fail at runtime here too.
 *
 * <p>WHEN-FIXED: delete {@link Streamable} (zero implementors, zero non-self callers). The
 * deletion will cascade into the {@code STREAMABLE} branch of
 * {@link StreamableHelper#toStream(DataOutput, Object)} / {@code #fromStream(DataInput)} (also
 * dead — see {@code StreamableHelperDeadCodeTest}).
 */
public class StreamableInterfaceDeadCodeTest {

  /** Test-local implementor that exists solely to prove the interface still has its expected
   * shape. The class exists only inside this test class — there are no production implementors. */
  private static final class TestStreamable implements Streamable {
    private int value;

    TestStreamable(final int value) {
      this.value = value;
    }

    @Override
    public void toStream(final DataOutput out) throws IOException {
      out.writeInt(value);
    }

    @Override
    public void fromStream(final DataInput in) throws IOException {
      value = in.readInt();
    }
  }

  // ---------------------------------------------------------------------------
  // Method-signature pins via test-local implementor
  // ---------------------------------------------------------------------------

  @Test
  public void toStreamMethodSignatureMatches() throws NoSuchMethodException {
    // Pin: Streamable.toStream(DataOutput) throws IOException. Removing the method, renaming it,
    // or dropping the throws clause will fail compilation of the test-local implementor or of
    // this reflective lookup.
    final var m = Streamable.class.getDeclaredMethod("toStream", DataOutput.class);
    assertNotNull(m);
    final var declared = m.getExceptionTypes();
    var declaresIOException = false;
    for (final var e : declared) {
      if (e == IOException.class) {
        declaresIOException = true;
      }
    }
    assertTrue("toStream must throw IOException", declaresIOException);
  }

  @Test
  public void fromStreamMethodSignatureMatches() throws NoSuchMethodException {
    // Pin: Streamable.fromStream(DataInput) throws IOException.
    final var m = Streamable.class.getDeclaredMethod("fromStream", DataInput.class);
    assertNotNull(m);
    final var declared = m.getExceptionTypes();
    var declaresIOException = false;
    for (final var e : declared) {
      if (e == IOException.class) {
        declaresIOException = true;
      }
    }
    assertTrue("fromStream must throw IOException", declaresIOException);
  }

  @Test
  public void interfaceHasExactlyTwoDeclaredMethods() {
    // Pin: the interface is intentionally tiny — only toStream + fromStream. A regression that
    // grew the interface would expand the dead surface and should be loud here.
    assertEquals(2, Streamable.class.getDeclaredMethods().length);
  }

  // ---------------------------------------------------------------------------
  // Round-trip via test-local implementor
  // ---------------------------------------------------------------------------

  @Test
  public void roundTripViaTestLocalImplementorPreservesValue() throws IOException {
    // A positive round-trip: write an int via toStream, read it back via fromStream. The
    // test-local implementor is the only Streamable in the JVM during this test; this proves
    // the interface contract still works as documented even though no production class
    // implements it.
    final var out = new ByteArrayOutputStream();
    try (var dout = new DataOutputStream(out)) {
      new TestStreamable(0xDEADBEEF).toStream(dout);
    }

    final var in = new TestStreamable(0);
    try (var din = new DataInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      in.fromStream(din);
    }
    assertEquals(0xDEADBEEF, in.value);
  }

  @Test
  public void interfaceClassReferenceCompilesAndIsLoadable() {
    // Trivial pin — compile-time class reference. Removing Streamable will fail compilation
    // here regardless of whether any other test method exercises a method.
    assertSame(Streamable.class, Streamable.class);
  }
}
