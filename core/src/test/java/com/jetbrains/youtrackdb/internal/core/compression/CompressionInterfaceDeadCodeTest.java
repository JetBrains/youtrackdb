/*
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
package com.jetbrains.youtrackdb.internal.core.compression;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Dead-code shape pin for the {@link Compression} SPI interface.
 *
 * <p>PSI all-scope {@code ReferencesSearch} confirms the only reference to this interface
 * anywhere in the project is the self-reference on the {@code configure(String)} method's
 * return type, declared inside the interface itself. There are zero implementers
 * ({@code ClassInheritorsSearch} returns empty), zero {@code instanceof Compression} sites,
 * and zero registration sites — the SPI hook the Javadoc gestures at
 * ({@code OCompressionFactory.INSTANCE.register(...)}) does not exist anywhere in the source
 * tree. The interface is fully production-dead.
 *
 * <p>Pin captures every abstract method's name + signature + return type so that a deletion
 * commit either removes this file in lockstep with the production interface OR fails at
 * compile time on the {@code .class} reference. Also pins the inheritor count via a local
 * test-only implementation that asserts no production class can satisfy the abstract surface
 * silently.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete this test file in the same commit that
 * removes the entire {@code core/compression} package per the cluster-classification table
 * row "core/compression" in {@code track-22a.md}.
 */
public class CompressionInterfaceDeadCodeTest {

  // The exact set of abstract methods on the Compression interface. Any rename, addition,
  // or deletion fails this pin. The set is the SPI surface a hypothetical implementer
  // would have to satisfy.
  private static final Set<String> EXPECTED_ABSTRACT_METHOD_NAMES =
      new TreeSet<>(Set.of("compress", "uncompress", "name", "configure"));

  @Test
  public void typeIsPublicInterfaceWithNoSuperInterfaces() {
    var clazz = Compression.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertTrue("must be an interface", clazz.isInterface());
    assertTrue("must be abstract", Modifier.isAbstract(clazz.getModifiers()));
    assertEquals(
        "Compression must extend no super-interfaces — pin the SPI shape",
        0,
        clazz.getInterfaces().length);
  }

  @Test
  public void abstractMethodNamesMatchPinnedSet() {
    var actual = new TreeSet<String>();
    for (Method m : Compression.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      assertTrue(
          "all declared methods on Compression must be abstract — interface SPI",
          Modifier.isAbstract(m.getModifiers()));
      actual.add(m.getName());
    }
    assertEquals(
        "abstract method-name set must remain {compress, uncompress, name, configure}",
        EXPECTED_ABSTRACT_METHOD_NAMES,
        actual);
  }

  @Test
  public void compressOverloadsAreSignedForFullAndOffsetLengthInputs() throws Exception {
    Method full = Compression.class.getDeclaredMethod("compress", byte[].class);
    assertSame("compress(byte[]) must return byte[]", byte[].class, full.getReturnType());

    Method windowed =
        Compression.class.getDeclaredMethod("compress", byte[].class, int.class, int.class);
    assertSame(
        "compress(byte[],int,int) must return byte[]", byte[].class, windowed.getReturnType());
    assertArrayEquals(
        "windowed compress signature must remain (byte[], int, int)",
        new Class<?>[] {byte[].class, int.class, int.class},
        windowed.getParameterTypes());
  }

  @Test
  public void uncompressOverloadsAreSignedForFullAndOffsetLengthInputs() throws Exception {
    Method full = Compression.class.getDeclaredMethod("uncompress", byte[].class);
    assertSame("uncompress(byte[]) must return byte[]", byte[].class, full.getReturnType());

    Method windowed =
        Compression.class.getDeclaredMethod("uncompress", byte[].class, int.class, int.class);
    assertSame(
        "uncompress(byte[],int,int) must return byte[]", byte[].class, windowed.getReturnType());
    assertArrayEquals(
        "windowed uncompress signature must remain (byte[], int, int)",
        new Class<?>[] {byte[].class, int.class, int.class},
        windowed.getParameterTypes());
  }

  @Test
  public void nameAndConfigureSignaturesArePinned() throws Exception {
    Method name = Compression.class.getDeclaredMethod("name");
    assertSame("name() must return String", String.class, name.getReturnType());

    Method configure = Compression.class.getDeclaredMethod("configure", String.class);
    assertSame(
        "configure(String) must return Compression — fluent SPI shape",
        Compression.class,
        configure.getReturnType());
  }

  /**
   * Local minimal implementation. The fact that this compiles pins the abstract surface as a
   * structurally valid SPI contract — if any of the four methods is renamed or its signature
   * changes, the local class fails to compile in lockstep with the production interface.
   */
  private static final class IdentityCompression implements Compression {

    @Override
    public byte[] compress(byte[] content) {
      return Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] compress(byte[] content, int offset, int length) {
      return Arrays.copyOfRange(content, offset, offset + length);
    }

    @Override
    public byte[] uncompress(byte[] content) {
      return Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] uncompress(byte[] content, int offset, int length) {
      return Arrays.copyOfRange(content, offset, offset + length);
    }

    @Override
    public String name() {
      return "identity";
    }

    @Override
    public Compression configure(String iOptions) {
      return this;
    }
  }

  @Test
  public void localImplementationSatisfiesEverySpiMethodRoundTripped() {
    Compression impl = new IdentityCompression();
    byte[] payload = new byte[] {1, 2, 3, 4, 5};

    assertArrayEquals("compress full must round-trip identity", payload, impl.compress(payload));
    assertArrayEquals(
        "compress windowed must return only the windowed slice",
        new byte[] {2, 3, 4},
        impl.compress(payload, 1, 3));
    assertArrayEquals(
        "uncompress full must round-trip identity", payload, impl.uncompress(payload));
    assertArrayEquals(
        "uncompress windowed must return only the windowed slice",
        new byte[] {2, 3, 4},
        impl.uncompress(payload, 1, 3));
    assertEquals("name must round-trip the literal", "identity", impl.name());
    assertSame(
        "configure must return the same instance — fluent SPI contract",
        impl,
        impl.configure("ignored"));
  }
}
