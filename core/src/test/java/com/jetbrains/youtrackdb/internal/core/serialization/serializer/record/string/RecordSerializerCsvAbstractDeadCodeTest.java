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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Dead-code pin tests for the instance API of {@link RecordSerializerCSVAbstract}.
 *
 * <p>Commit {@code 24d5a3d967} (YTDB-86) removed the only concrete CSV subclass
 * ({@code RecordSerializerSchemaAware2CSV}). Cross-module grep performed during this track's
 * Phase A confirmed zero remaining concrete subclasses and zero non-self callers of the
 * instance methods anywhere in the repository:
 *
 * <pre>
 *   grep -rn 'extends RecordSerializerCSVAbstract' --include='*.java'
 *       core server driver embedded gremlin-annotations tests test-commons docker-tests
 *     -- only the class itself
 *   grep -rn '\.fieldFromStream\b\|\.fieldToStream\b\|\.embeddedCollectionFromStream\b\|
 *             \.embeddedCollectionToStream\b\|\.embeddedMapToStream\b' --include='*.java'
 *       core server driver embedded gremlin-annotations tests test-commons docker-tests
 *     -- only the class itself (instance-method internal recursion)
 * </pre>
 *
 * <p>The static {@link RecordSerializerCSVAbstract#embeddedMapFromStream} helper is
 * <strong>live</strong> ({@code StringSerializerHelper:198}, {@code RecordSerializerStringAbstract:77})
 * and is excluded from this dead-code pin. Step 4 of this track covers it independently.
 *
 * <p>Because the class is {@code abstract} and has no concrete subclass, the instance methods
 * are pinned by reflection over the declared method signatures. A future deletion of any pinned
 * method will fail the {@code getDeclaredMethod} lookup; a refactor that changes a parameter
 * type will fail compilation here when the pinned parameter class disappears.
 *
 * <p>WHEN-FIXED: delete the instance API of {@link RecordSerializerCSVAbstract}
 * ({@code fieldFromStream}, {@code fieldToStream}, {@code embeddedMapToStream},
 * {@code embeddedCollectionFromStream}, {@code embeddedCollectionToStream}). The class's only
 * live surface is the static {@code embeddedMapFromStream}, which can move to
 * {@link StringSerializerHelper} as part of the same sweep.
 */
public class RecordSerializerCsvAbstractDeadCodeTest {

  // ---------------------------------------------------------------------------
  // Class-level invariants
  // ---------------------------------------------------------------------------

  @Test
  public void classRemainsAbstract() {
    assertTrue("RecordSerializerCSVAbstract must remain abstract — no concrete subclass exists",
        Modifier.isAbstract(RecordSerializerCSVAbstract.class.getModifiers()));
  }

  @Test
  public void classExtendsRecordSerializerStringAbstract() {
    assertSame(RecordSerializerStringAbstract.class,
        RecordSerializerCSVAbstract.class.getSuperclass());
  }

  // ---------------------------------------------------------------------------
  // Instance-method signature pins (dead surface)
  // ---------------------------------------------------------------------------

  @Test
  public void fieldFromStreamSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerCSVAbstract.class.getDeclaredMethod(
        "fieldFromStream",
        DatabaseSessionEmbedded.class,
        RecordAbstract.class,
        PropertyTypeInternal.class,
        SchemaClass.class,
        PropertyTypeInternal.class,
        String.class,
        String.class);
    assertNotNull(m);
    assertEquals(Object.class, m.getReturnType());
    assertFalse("instance method must not be static", Modifier.isStatic(m.getModifiers()));
    assertTrue("public visibility expected", Modifier.isPublic(m.getModifiers()));
  }

  @Test
  public void fieldToStreamSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerCSVAbstract.class.getDeclaredMethod(
        "fieldToStream",
        DatabaseSessionEmbedded.class,
        EntityImpl.class,
        StringWriter.class,
        PropertyTypeInternal.class,
        SchemaClass.class,
        PropertyTypeInternal.class,
        String.class,
        Object.class);
    assertNotNull(m);
    assertEquals(void.class, m.getReturnType());
    assertFalse("instance method must not be static", Modifier.isStatic(m.getModifiers()));
  }

  @Test
  public void embeddedCollectionFromStreamSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerCSVAbstract.class.getDeclaredMethod(
        "embeddedCollectionFromStream",
        DatabaseSessionEmbedded.class,
        EntityImpl.class,
        PropertyTypeInternal.class,
        SchemaClass.class,
        PropertyTypeInternal.class,
        String.class);
    assertNotNull(m);
    assertEquals(Object.class, m.getReturnType());
    assertFalse(Modifier.isStatic(m.getModifiers()));
  }

  @Test
  public void embeddedCollectionToStreamSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerCSVAbstract.class.getDeclaredMethod(
        "embeddedCollectionToStream",
        DatabaseSessionEmbedded.class,
        StringWriter.class,
        SchemaClass.class,
        PropertyTypeInternal.class,
        Object.class,
        boolean.class);
    assertNotNull(m);
    assertEquals(void.class, m.getReturnType());
    assertFalse(Modifier.isStatic(m.getModifiers()));
  }

  @Test
  public void embeddedMapToStreamSignaturePinned() throws NoSuchMethodException {
    final var m = RecordSerializerCSVAbstract.class.getDeclaredMethod(
        "embeddedMapToStream",
        DatabaseSessionEmbedded.class,
        StringWriter.class,
        PropertyTypeInternal.class,
        Object.class);
    assertNotNull(m);
    assertEquals(void.class, m.getReturnType());
    assertFalse(Modifier.isStatic(m.getModifiers()));
  }

  // ---------------------------------------------------------------------------
  // Dead-instance-API set is exhaustive — guard against drift if a method is added
  // ---------------------------------------------------------------------------

  @Test
  public void declaredInstanceMethodsAreExactlyTheKnownDeadSet() {
    // If a future refactor adds a new public/protected instance method here, it must either
    // (a) become a new entry in this dead-code pin (if it has zero callers) or (b) move out
    // to a callable site. This test fails when the declared instance method set changes,
    // forcing the maintainer to make that decision rather than silently growing the dead
    // surface.
    final var expected = Set.of(
        "fieldFromStream",
        "fieldToStream",
        "embeddedMapToStream",
        "embeddedCollectionFromStream",
        "embeddedCollectionToStream");
    final var actual = new HashSet<String>();
    for (final var m : RecordSerializerCSVAbstract.class.getDeclaredMethods()) {
      if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) {
        continue;
      }
      // Internal helpers (private/package-private) are not pinned by name; they are an
      // implementation detail of the dead instance API and will be removed alongside it.
      if (!Modifier.isPublic(m.getModifiers()) && !Modifier.isProtected(m.getModifiers())) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("dead-instance-API surface drifted; update the pin set above", expected, actual);
  }
}
