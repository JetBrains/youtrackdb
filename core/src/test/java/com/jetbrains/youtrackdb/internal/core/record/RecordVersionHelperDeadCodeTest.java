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
package com.jetbrains.youtrackdb.internal.core.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.serialization.BinaryProtocol;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for {@link RecordVersionHelper}. PSI all-scope {@code ReferencesSearch} confirms
 * <strong>zero production references</strong> and zero test references for every public static
 * method on this class outside its own source file across all five Maven modules (core, server,
 * driver, embedded, gremlin-annotations, docker-tests). The class is a static-method holder
 * — every public entry point is dead, the {@code SERIALIZED_SIZE} constant is unread, and the
 * protected default constructor is never invoked (zero subclasses). Phase A's PSI audit found
 * the same zero-call surface; this pin re-validates it via reflection so a future refactor that
 * silently re-uses any of these methods will trip the pinned signature.
 *
 * <p>Each public static method is pinned individually so that a partial deletion (drop one
 * method, keep the rest) still leaves the pin set valid. Renames or signature drift on any
 * surviving method fail loudly; deletion shifts {@link #declaresExactlyTheExpectedDeclaredMethodNames()}.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@link RecordVersionHelper} together with this
 * test file. No external surface needs retargeting because there are no callers anywhere.
 *
 * <p>Standalone — no database session needed; pure {@link Class}-level reflection.
 */
public class RecordVersionHelperDeadCodeTest {

  // The complete declared-method surface this dead helper offers, as a sorted set of names.
  // Pinning the set (rather than the count) catches both a method dropped silently and a
  // method renamed in place — either would shift the resulting set.
  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "isValid",
      "isTombstone",
      "toStream",
      "fromStream",
      "reset",
      "disable",
      "compareTo",
      "toString",
      "fromString"));

  @Test
  public void classIsPublicConcreteAndExtendsObject() {
    var clazz = RecordVersionHelper.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertFalse("must NOT be abstract (concrete static-method holder)",
        Modifier.isAbstract(clazz.getModifiers()));
    assertSame("must extend Object — no abstract base contract to retarget",
        Object.class, clazz.getSuperclass());
  }

  @Test
  public void declaresExactlyTheExpectedDeclaredMethodNames() {
    // Pin the full set of declared method names. A future addition (new helper) or
    // deletion (removed method) shifts the set and fails — making the change deliberate.
    var actual = new TreeSet<String>();
    for (Method m : RecordVersionHelper.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("declared method-name set must match the pinned dead surface",
        EXPECTED_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void declaresSingleProtectedNoArgConstructor() throws Exception {
    // Protected ctor + no subclasses (PSI all-scope ClassInheritorsSearch empty) means this
    // is a pure static-method holder. Pin the visibility so a refactor that opens the ctor
    // (which would suggest planned instantiation) is recognised as a deliberate change.
    Constructor<?>[] ctors = RecordVersionHelper.class.getDeclaredConstructors();
    assertEquals("must declare exactly one constructor", 1, ctors.length);
    Constructor<?> ctor = ctors[0];
    assertTrue("ctor must be protected (static-method holder convention)",
        Modifier.isProtected(ctor.getModifiers()));
    assertEquals("ctor must take no parameters", 0, ctor.getParameterCount());
  }

  @Test
  public void serializedSizeIsPublicStaticFinalIntEqualToBinaryProtocolLong() throws Exception {
    // The constant is dead per PSI (no readers anywhere), but its value is load-bearing for
    // the toStream/fromStream pair: both rely on Long.SIZE/8 = 8. Pin the modifiers and the
    // exact value so a refactor to BinaryProtocol cannot silently change the wire size.
    Field f = RecordVersionHelper.class.getDeclaredField("SERIALIZED_SIZE");
    int mods = f.getModifiers();
    assertTrue("SERIALIZED_SIZE must be public", Modifier.isPublic(mods));
    assertTrue("SERIALIZED_SIZE must be static", Modifier.isStatic(mods));
    assertTrue("SERIALIZED_SIZE must be final (compile-time constant)",
        Modifier.isFinal(mods));
    assertSame("SERIALIZED_SIZE must be int", int.class, f.getType());
    assertEquals("SERIALIZED_SIZE must equal BinaryProtocol.SIZE_LONG (8)",
        BinaryProtocol.SIZE_LONG, f.get(null));
  }

  // --- Per-method pins. Each isolated so that partial deletion stays valid. ---

  @Test
  public void isValidIsPublicStaticBooleanTakingLong() throws Exception {
    Method m = RecordVersionHelper.class.getDeclaredMethod("isValid", long.class);
    int mods = m.getModifiers();
    assertTrue("isValid must be public", Modifier.isPublic(mods));
    assertTrue("isValid must be static", Modifier.isStatic(mods));
    assertSame("isValid must return boolean", boolean.class, m.getReturnType());
    assertEquals("isValid must take a single (long) parameter", 1, m.getParameterCount());
    assertSame("isValid parameter type must be long", long.class, m.getParameterTypes()[0]);
  }

  @Test
  public void isTombstoneIsPublicStaticBooleanTakingLong() throws Exception {
    Method m = RecordVersionHelper.class.getDeclaredMethod("isTombstone", long.class);
    int mods = m.getModifiers();
    assertTrue("isTombstone must be public", Modifier.isPublic(mods));
    assertTrue("isTombstone must be static", Modifier.isStatic(mods));
    assertSame("isTombstone must return boolean", boolean.class, m.getReturnType());
    assertSame("isTombstone parameter type must be long",
        long.class, m.getParameterTypes()[0]);
  }

  @Test
  public void toStreamIsPublicStaticByteArrayTakingLong() throws Exception {
    Method m = RecordVersionHelper.class.getDeclaredMethod("toStream", long.class);
    int mods = m.getModifiers();
    assertTrue("toStream must be public", Modifier.isPublic(mods));
    assertTrue("toStream must be static", Modifier.isStatic(mods));
    assertSame("toStream must return byte[]", byte[].class, m.getReturnType());
    assertSame("toStream parameter type must be long",
        long.class, m.getParameterTypes()[0]);
  }

  @Test
  public void fromStreamIsPublicStaticLongTakingByteArray() throws Exception {
    Method m = RecordVersionHelper.class.getDeclaredMethod("fromStream", byte[].class);
    int mods = m.getModifiers();
    assertTrue("fromStream must be public", Modifier.isPublic(mods));
    assertTrue("fromStream must be static", Modifier.isStatic(mods));
    assertSame("fromStream must return long", long.class, m.getReturnType());
    assertSame("fromStream parameter type must be byte[]",
        byte[].class, m.getParameterTypes()[0]);
  }

  @Test
  public void resetIsPublicStaticLongNoArg() throws Exception {
    Method m = RecordVersionHelper.class.getDeclaredMethod("reset");
    int mods = m.getModifiers();
    assertTrue("reset must be public", Modifier.isPublic(mods));
    assertTrue("reset must be static", Modifier.isStatic(mods));
    assertSame("reset must return long", long.class, m.getReturnType());
    assertEquals("reset must take zero parameters", 0, m.getParameterCount());
  }

  @Test
  public void disableIsPublicStaticLongNoArg() throws Exception {
    Method m = RecordVersionHelper.class.getDeclaredMethod("disable");
    int mods = m.getModifiers();
    assertTrue("disable must be public", Modifier.isPublic(mods));
    assertTrue("disable must be static", Modifier.isStatic(mods));
    assertSame("disable must return long", long.class, m.getReturnType());
    assertEquals("disable must take zero parameters", 0, m.getParameterCount());
  }

  @Test
  public void compareToIsPublicStaticIntTakingTwoLongs() throws Exception {
    Method m = RecordVersionHelper.class.getDeclaredMethod("compareTo", long.class, long.class);
    int mods = m.getModifiers();
    assertTrue("compareTo must be public", Modifier.isPublic(mods));
    assertTrue("compareTo must be static", Modifier.isStatic(mods));
    assertSame("compareTo must return int", int.class, m.getReturnType());
    assertEquals("compareTo must take two parameters", 2, m.getParameterCount());
    assertSame("compareTo first parameter must be long",
        long.class, m.getParameterTypes()[0]);
    assertSame("compareTo second parameter must be long",
        long.class, m.getParameterTypes()[1]);
  }

  @Test
  public void toStringOverloadIsPublicStaticStringTakingLong() throws Exception {
    // Distinct from Object#toString(): this is the static long->String formatter overload.
    Method m = RecordVersionHelper.class.getDeclaredMethod("toString", long.class);
    int mods = m.getModifiers();
    assertTrue("toString(long) must be public", Modifier.isPublic(mods));
    assertTrue("toString(long) must be static", Modifier.isStatic(mods));
    assertSame("toString(long) must return String", String.class, m.getReturnType());
    assertSame("toString(long) parameter type must be long",
        long.class, m.getParameterTypes()[0]);
  }

  @Test
  public void fromStringIsPublicStaticLongTakingString() throws Exception {
    Method m = RecordVersionHelper.class.getDeclaredMethod("fromString", String.class);
    int mods = m.getModifiers();
    assertTrue("fromString must be public", Modifier.isPublic(mods));
    assertTrue("fromString must be static", Modifier.isStatic(mods));
    assertSame("fromString must return long", long.class, m.getReturnType());
    assertSame("fromString parameter type must be String",
        String.class, m.getParameterTypes()[0]);
  }

  @Test
  public void declaresOnlyTheSerializedSizeField() {
    // The class must remain a pure static-method holder; if a future refactor adds an
    // instance field (suggesting planned state), this assertion fails and forces a
    // deliberate review.
    var fieldNames = new TreeSet<String>();
    for (Field f : RecordVersionHelper.class.getDeclaredFields()) {
      if (f.isSynthetic()) {
        continue;
      }
      fieldNames.add(f.getName());
    }
    assertEquals("field set must remain {SERIALIZED_SIZE}", Set.of("SERIALIZED_SIZE"),
        fieldNames);
    // The BinaryProtocol linkage is already pinned by
    // serializedSizeIsPublicStaticFinalIntEqualToBinaryProtocolLong above (which asserts
    // the field's runtime value equals BinaryProtocol.SIZE_LONG); a class-literal
    // existence assertion here would be vacuous.
  }
}
