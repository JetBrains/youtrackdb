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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Dead-code pin tests for the abstract instance methods on {@link RecordSerializerStringAbstract}.
 *
 * <p>The class's only concrete subclass was {@link RecordSerializerCSVAbstract} (also abstract).
 * After commit {@code 24d5a3d967} (YTDB-86) removed the only concrete CSV subclass
 * ({@code RecordSerializerSchemaAware2CSV}), the entire instance-method chain became
 * unreachable. Cross-module grep performed during this track's Phase A confirmed:
 *
 * <pre>
 *   grep -rn 'extends RecordSerializerStringAbstract' --include='*.java'
 *       core server driver embedded gremlin-annotations tests test-commons docker-tests
 *     -- only RecordSerializerCSVAbstract; CSVAbstract has no concrete subclass either
 * </pre>
 *
 * <p>The class's static helpers ({@link RecordSerializerStringAbstract#getType(String)},
 * {@link RecordSerializerStringAbstract#getTypeValue}, and the {@code simpleValue*} family)
 * are <strong>live</strong> (called from {@code SQLHelper}, {@code EntityHelper},
 * {@code CommandRequestTextAbstract}); they are excluded from this dead-code pin and are
 * covered by Step 4 of this track. The {@code fieldTypeFromStream}, {@code convertValue},
 * and {@code fieldTypeToString} statics are reachable only from instance code that itself
 * has no concrete subclass — they are pinned indirectly via the instance-method shape below.
 *
 * <p>Because the class is {@code abstract} and has no concrete subclass, the instance methods
 * are pinned by reflection. A future deletion of any pinned method will fail the
 * {@code getDeclaredMethod} lookup; a refactor that changes a parameter type will fail
 * compilation here when the pinned parameter class disappears.
 *
 * <p>WHEN-FIXED: delete the abstract instance API of {@link RecordSerializerStringAbstract}
 * ({@code fromString}, {@code toString} both arities, {@code fromStream}, {@code toStream},
 * {@code getSupportBinaryEvaluate}). The static helpers stay.
 */
public class RecordSerializerStringAbstractDeadCodeTest {

  // ---------------------------------------------------------------------------
  // Class-level invariants
  // ---------------------------------------------------------------------------

  @Test
  public void classRemainsAbstract() {
    assertTrue("RecordSerializerStringAbstract must remain abstract — no concrete subclass exists",
        Modifier.isAbstract(RecordSerializerStringAbstract.class.getModifiers()));
  }

  // ---------------------------------------------------------------------------
  // Instance-method signature pins (dead surface — no concrete subclass exists)
  // ---------------------------------------------------------------------------

  @Test
  public void fromStringFourArgSignaturePinnedAndAbstract() throws NoSuchMethodException {
    // The four-argument fromString is the abstract entry point. Pin existence + abstract
    // modifier so any concrete-subclass introduction or method removal becomes loud.
    final var m = RecordSerializerStringAbstract.class.getDeclaredMethod(
        "fromString",
        DatabaseSessionEmbedded.class,
        String.class,
        RecordAbstract.class,
        String[].class);
    assertNotNull(m);
    assertTrue("fromString(4) must remain abstract",
        Modifier.isAbstract(m.getModifiers()));
    assertTrue("public visibility expected", Modifier.isPublic(m.getModifiers()));
  }

  @Test
  public void fromStringTwoArgSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerStringAbstract.class.getDeclaredMethod(
        "fromString",
        DatabaseSessionEmbedded.class,
        String.class);
    assertNotNull(m);
    assertFalse("fromString(2) is concrete (delegates to abstract 4-arg)",
        Modifier.isAbstract(m.getModifiers()));
    assertTrue(Modifier.isPublic(m.getModifiers()));
  }

  @Test
  public void toStringFourArgSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerStringAbstract.class.getDeclaredMethod(
        "toString",
        DatabaseSessionEmbedded.class,
        DBRecord.class,
        StringWriter.class,
        String.class);
    assertNotNull(m);
    assertEquals(StringWriter.class, m.getReturnType());
    assertFalse(Modifier.isAbstract(m.getModifiers()));
  }

  @Test
  public void toStringFiveArgSignaturePinnedAndAbstract() throws NoSuchMethodException {
    // The five-argument toString is the protected abstract method that the four-arg toString
    // delegates to. Pin existence + protected + abstract modifiers.
    final var m = RecordSerializerStringAbstract.class.getDeclaredMethod(
        "toString",
        DatabaseSessionEmbedded.class,
        DBRecord.class,
        StringWriter.class,
        String.class,
        boolean.class);
    assertNotNull(m);
    assertEquals(StringWriter.class, m.getReturnType());
    assertTrue("toString(5) must remain abstract",
        Modifier.isAbstract(m.getModifiers()));
    assertTrue("toString(5) is protected (not public)",
        Modifier.isProtected(m.getModifiers()));
  }

  @Test
  public void fromStreamSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerStringAbstract.class.getDeclaredMethod(
        "fromStream",
        DatabaseSessionEmbedded.class,
        byte[].class,
        RecordAbstract.class,
        String[].class);
    assertNotNull(m);
    assertEquals(RecordAbstract.class, m.getReturnType());
    assertFalse(Modifier.isStatic(m.getModifiers()));
  }

  @Test
  public void toStreamInstanceSignaturePinned() throws NoSuchMethodException {
    // Note: toStream is the instance method (not the static `simpleValueToStream`).
    final var m = RecordSerializerStringAbstract.class.getDeclaredMethod(
        "toStream",
        DatabaseSessionEmbedded.class,
        RecordAbstract.class);
    assertNotNull(m);
    assertEquals(byte[].class, m.getReturnType());
    assertFalse("instance method must not be static", Modifier.isStatic(m.getModifiers()));
  }

  @Test
  public void getSupportBinaryEvaluateSignatureAndDefault() throws NoSuchMethodException {
    final var m =
        RecordSerializerStringAbstract.class.getDeclaredMethod("getSupportBinaryEvaluate");
    assertNotNull(m);
    assertEquals(boolean.class, m.getReturnType());
    assertFalse(Modifier.isAbstract(m.getModifiers()));
    // The default body is `return false;` — pin that via a test-local concrete subclass that
    // implements only the abstract methods and inherits the rest. This also pins that the
    // instance can be constructed at all (the abstract count is precisely the two abstracts
    // pinned above; if a future refactor adds another abstract method, this constructor call
    // will fail at compile time, signalling that the dead surface has grown).
    final var instance = new ConcreteForReflection();
    assertFalse("default getSupportBinaryEvaluate must return false",
        instance.getSupportBinaryEvaluate());
  }

  // ---------------------------------------------------------------------------
  // Unused public statics — fieldTypeFromStream / convertValue / fieldTypeToString
  //
  // These three statics have zero non-self callers across core/, server/, driver/, embedded/,
  // gremlin-annotations/, tests/, test-commons/, docker-tests/. The simpleValue* family is
  // explicitly excluded because they are live (`getTypeValue` / `simpleValueFromStream`
  // chain into them from CommandRequestTextAbstract, EntityHelper, SQLHelper).
  // ---------------------------------------------------------------------------

  @Test
  public void fieldTypeFromStreamSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerStringAbstract.class.getDeclaredMethod(
        "fieldTypeFromStream",
        DatabaseSessionEmbedded.class,
        EntityImpl.class,
        PropertyTypeInternal.class,
        Object.class);
    assertNotNull(m);
    assertEquals(Object.class, m.getReturnType());
    assertTrue("public visibility expected", Modifier.isPublic(m.getModifiers()));
    assertTrue("static expected", Modifier.isStatic(m.getModifiers()));
  }

  @Test
  public void convertValueSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerStringAbstract.class.getDeclaredMethod(
        "convertValue",
        DatabaseSessionEmbedded.class,
        String.class,
        PropertyTypeInternal.class);
    assertNotNull(m);
    assertEquals(Object.class, m.getReturnType());
    assertTrue(Modifier.isPublic(m.getModifiers()));
    assertTrue(Modifier.isStatic(m.getModifiers()));
  }

  @Test
  public void fieldTypeToStringSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerStringAbstract.class.getDeclaredMethod(
        "fieldTypeToString",
        DatabaseSessionEmbedded.class,
        StringWriter.class,
        PropertyTypeInternal.class,
        Object.class);
    assertNotNull(m);
    assertEquals(void.class, m.getReturnType());
    assertTrue(Modifier.isPublic(m.getModifiers()));
    assertTrue(Modifier.isStatic(m.getModifiers()));
  }

  // ---------------------------------------------------------------------------
  // Drift detector — mirrors RecordSerializerCsvAbstractDeadCodeTest's invariant
  // ---------------------------------------------------------------------------

  @Test
  public void declaredInstanceMethodsAreExactlyTheKnownDeadSet() {
    // If a future refactor adds a new public/protected instance method here, it must either
    // (a) become a new entry in this dead-code pin (if it has zero callers) or (b) be moved
    // out to a callable site. This test fails when the declared instance-method set drifts,
    // forcing the maintainer to make that decision rather than silently growing the dead
    // surface — same invariant the sibling CSV dead-code test enforces.
    final Set<String> expected = Set.of(
        "fromString",
        "toString",
        "fromStream",
        "toStream",
        "getSupportBinaryEvaluate");
    final Set<String> actual = new HashSet<>();
    for (final Method m : RecordSerializerStringAbstract.class.getDeclaredMethods()) {
      if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) {
        continue;
      }
      if (!Modifier.isPublic(m.getModifiers()) && !Modifier.isProtected(m.getModifiers())) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("dead-instance-API surface drifted; update the pin set above", expected, actual);
  }

  @Test
  public void twoArgFromStringDelegatesToFourArg() {
    // Pin the concrete fromString(2-arg) -> abstract fromString(4-arg) delegation by counting
    // invocations on a test-local subclass. A regression that broke the delegation (e.g., the
    // 2-arg method became a no-op or returned null directly) would not be caught by reflective
    // signature pins alone.
    final var instance = new ConcreteForReflection();
    instance.fromString(null, "anything");
    assertEquals("two-arg fromString must delegate to the abstract four-arg",
        1, instance.fourArgInvocations);
  }

  @Test
  public void fourArgToStringDelegatesToFiveArg() {
    // Same as above but for toString(4) -> protected abstract toString(5).
    final var instance = new ConcreteForReflection();
    instance.toString(null, null, new StringWriter(), null);
    assertEquals("four-arg toString must delegate to the abstract five-arg",
        1, instance.fiveArgToStringInvocations);
  }

  /**
   * Test-local minimal concrete subclass — exists only to invoke the inherited concrete
   * instance methods so JaCoCo records coverage on them, and to pin the abstract-method count
   * (a new abstract method on the base would fail to compile here). Counter fields verify the
   * delegation contract from the concrete two-arg / four-arg overloads to their abstract
   * counterparts.
   */
  private static final class ConcreteForReflection extends RecordSerializerStringAbstract {
    int fourArgInvocations;
    int fiveArgToStringInvocations;

    @Override
    public <T extends DBRecord> T fromString(
        final DatabaseSessionEmbedded session,
        final String iContent,
        final RecordAbstract iRecord,
        final String[] iFields) {
      fourArgInvocations++;
      return null;
    }

    @Override
    protected StringWriter toString(
        final DatabaseSessionEmbedded session,
        final DBRecord iRecord,
        final StringWriter iOutput,
        final String iFormat,
        final boolean autoDetectCollectionType) {
      fiveArgToStringInvocations++;
      return iOutput;
    }
  }
}
