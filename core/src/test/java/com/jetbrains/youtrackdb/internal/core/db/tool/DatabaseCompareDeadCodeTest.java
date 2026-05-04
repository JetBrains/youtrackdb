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
package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for {@link DatabaseCompare}. PSI all-scope {@code ReferencesSearch} confirms
 * <strong>zero production references</strong> across all modules (core, server, driver,
 * embedded, gremlin-annotations, docker-tests). The 36 references that exist all live in
 * {@code src/test/java}, spread across 11 test files (notably
 * {@code DatabaseCompareReadRetryTest}, the {@code StorageBackup*} family, the
 * {@code LocalPaginatedStorageRestore*} family, and the {@code DbImport*Test} family in the
 * {@code tests} module). The class is therefore <strong>test-only-reachable</strong> — its
 * deletion in the deferred-cleanup track is contingent on either deleting those tests
 * outright or migrating the comparison logic into a test helper.
 *
 * <p>Until the retargeting decision lands, the class still has to compile and the existing
 * tests still have to drive its surface — so this pin is reflection-only, focused on
 * structural shape (modifiers, signatures, declared field set). A behavioral pin would
 * require two live databases and would duplicate work the existing tests already do.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@link DatabaseCompare} together with this
 * test file. Retargeting contingency: the 11 test files listed below either get deleted
 * outright (if the equivalence check is no longer needed) or migrate to a test-commons
 * helper modeled on {@link com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper#hasSameContentOf}
 * which already provides the entity-level equivalence the tool wraps. Test files that
 * reference this class today (per PSI all-scope {@code ReferencesSearch}):
 * {@code DatabaseCompareReadRetryTest}, {@code DbImportExportLinkBagTest},
 * {@code DbImportExportTest}, {@code DbImportStreamExportTest},
 * {@code LocalPaginatedStorageRestoreFromWALAndAddAdditionalRecords},
 * {@code LocalPaginatedStorageRestoreFromWALIT}, {@code LocalPaginatedStorageRestoreTx},
 * {@code StorageBackupMTIT}, {@code StorageBackupMTStateTest}, {@code StorageBackupTest},
 * {@code StorageBackupTestWithLuceneIndex}.
 *
 * <p>Standalone — no database session needed; pure {@link Class}-level reflection.
 */
public class DatabaseCompareDeadCodeTest {

  // The complete declared-method surface this test-only-reachable tool offers, as a sorted
  // set of names. Pinning the set (rather than the count) catches both a method dropped
  // silently and a method renamed in place — either would shift the resulting set.
  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "run",
      "compare",
      "compareSchema",
      "compareIndexes",
      "compareIndexStreams",
      "compareCollections",
      "compareRecords",
      "skipRecord",
      "readRecordWithRetry",
      "setCompareIndexMetadata",
      "setCompareEntriesForAutomaticIndexes",
      "convertSchemaDoc"));

  @Test
  public void classIsPublicConcreteAndExtendsDatabaseImpExpAbstract() {
    var clazz = DatabaseCompare.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertFalse("must NOT be abstract", Modifier.isAbstract(clazz.getModifiers()));
    assertSame("must extend DatabaseImpExpAbstract — pin the inheritance for retargeting",
        DatabaseImpExpAbstract.class, clazz.getSuperclass());
  }

  @Test
  public void declaresExactlyTheExpectedDeclaredMethodNames() {
    var actual = new TreeSet<String>();
    for (Method m : DatabaseCompare.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("declared method-name set must match the pinned test-only-reachable surface",
        EXPECTED_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void hasSinglePublicThreeArgConstructorWithExpectedSignature() {
    Constructor<?>[] ctors = DatabaseCompare.class.getDeclaredConstructors();
    assertEquals("must declare exactly one constructor", 1, ctors.length);

    Constructor<?> ctor = ctors[0];
    assertTrue("ctor must be public", Modifier.isPublic(ctor.getModifiers()));
    assertArrayEquals(
        "ctor signature must remain (sessionOne, sessionTwo, listener) — test files build against this",
        new Class<?>[] {
            DatabaseSessionEmbedded.class,
            DatabaseSessionEmbedded.class,
            CommandOutputListener.class},
        ctor.getParameterTypes());
  }

  @Test
  public void compareReturnsBooleanForCallerDispatch() throws Exception {
    // Test files use `assertTrue(comparator.compare())` / `assertFalse(comparator.compare())`
    // as the load-bearing assertion. Pin the public boolean return so a refactor to void
    // (which would silently pass) fails loudly.
    Method compare = DatabaseCompare.class.getDeclaredMethod("compare");
    int mods = compare.getModifiers();
    assertTrue("compare() must be public", Modifier.isPublic(mods));
    assertSame("compare() must return boolean", boolean.class, compare.getReturnType());
    assertEquals("compare() must take no parameters", 0, compare.getParameterCount());
  }

  @Test
  public void runOverridesRunnableAndIsVoid() throws Exception {
    Method run = DatabaseCompare.class.getDeclaredMethod("run");
    int mods = run.getModifiers();
    assertTrue("run() must be public (Runnable contract)", Modifier.isPublic(mods));
    assertSame("run() must return void", void.class, run.getReturnType());
  }

  @Test
  public void exposesTwoSetterFlagsThatExistingTestsToggle() throws Exception {
    // Existing test files set these via setCompareIndexMetadata / setCompareEntriesForAutomaticIndexes
    // before calling compare(). Pin both setter signatures so a renamed setter (silent
    // breakage of existing tests) fails this assertion.
    Method m1 = DatabaseCompare.class.getDeclaredMethod(
        "setCompareIndexMetadata", boolean.class);
    Method m2 = DatabaseCompare.class.getDeclaredMethod(
        "setCompareEntriesForAutomaticIndexes", boolean.class);
    for (Method m : new Method[] {m1, m2}) {
      assertTrue(m.getName() + " must be public", Modifier.isPublic(m.getModifiers()));
      assertSame(m.getName() + " must return void", void.class, m.getReturnType());
    }
  }

  @Test
  public void declaresExpectedInstanceFieldSet() {
    // Pin the declared field set so a future refactor that adds a new comparison option
    // (which would silently change tool semantics) is recognized as a deliberate change.
    Set<String> expectedFieldNames = Set.of(
        "sessionOne",
        "sessionTwo",
        "compareEntriesForAutomaticIndexes",
        "autoDetectExportImportMap",
        "differences",
        "compareIndexMetadata",
        "excludeIndexes",
        "collectionDifference");
    var actual = new HashSet<String>();
    for (Field f : DatabaseCompare.class.getDeclaredFields()) {
      if (f.isSynthetic()) {
        continue;
      }
      actual.add(f.getName());
    }
    assertEquals("declared instance-field name set must remain stable", expectedFieldNames, actual);
  }

  @Test
  public void retargetingContingency_existingTestsResolveAtBuildTime() {
    // Sanity-pin: the canonical test files referenced in the class Javadoc above must
    // resolve on the test classpath (i.e. compile against this version of DatabaseCompare).
    // If a future refactor renames or moves DatabaseCompare without retargeting these
    // tests, the compile will fail at the test-file edit — but pinning the class-name
    // here documents the dependency for human readers reviewing the deletion plan.
    String thisPackage = DatabaseCompare.class.getPackageName();
    assertNotNull("package name must remain non-null", thisPackage);
    assertTrue("class must remain in core/db/tool — test files import via FQN: " + thisPackage,
        thisPackage.endsWith(".db.tool"));
  }
}
