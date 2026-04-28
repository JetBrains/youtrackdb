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
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
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

  /**
   * Test-local minimal concrete subclass — exists only to invoke the inherited concrete
   * instance methods so JaCoCo records coverage on them, and to pin the abstract-method count.
   * If a new abstract method is ever added to {@link RecordSerializerStringAbstract}, this
   * subclass will fail to compile and force the maintainer to consider whether the dead
   * surface has grown.
   */
  private static final class ConcreteForReflection extends RecordSerializerStringAbstract {
    @Override
    public <T extends DBRecord> T fromString(
        final DatabaseSessionEmbedded session,
        final String iContent,
        final RecordAbstract iRecord,
        final String[] iFields) {
      return null;
    }

    @Override
    protected StringWriter toString(
        final DatabaseSessionEmbedded session,
        final DBRecord iRecord,
        final StringWriter iOutput,
        final String iFormat,
        final boolean autoDetectCollectionType) {
      return iOutput;
    }
  }
}
