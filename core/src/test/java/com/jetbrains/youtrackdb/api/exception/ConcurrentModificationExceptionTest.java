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
package com.jetbrains.youtrackdb.api.exception;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Coverage for {@link ConcurrentModificationException} — the public exception thrown when MVCC
 * is enabled and a record cannot be updated or deleted because versions don't match. Bespoke
 * coverage outside the {@code core/exception} parameterized fan because the ctor signature is
 * 5-arg ({@code dbName, RID, dbVersion, recordVersion, recordOperation}) and the
 * {@code makeMessage} helper has two formatting branches that must each be pinned:
 *
 * <ul>
 *   <li>{@code databaseVersion >= 0} — the "version not the latest" wording with the
 *       {@code db=v…} marker;
 *   <li>{@code databaseVersion < 0} — the "deleted by another user" wording without the
 *       {@code db=v…} marker.
 * </ul>
 *
 * <p>Each operation type ({@code CREATE}, {@code UPDATE}, {@code DELETE}) must format
 * correctly via {@link RecordOperation#getName(int)}, exercising the
 * {@code operation.toLowerCase(...).substring(0, len-1) + "ing"} suffix-trimming branch. We pin
 * one operation per ctor variant rather than fan all three — the {@code RecordOperation} test
 * already pins the {@code getName} table.
 */
public class ConcurrentModificationExceptionTest {

  /**
   * The full 5-arg ctor with {@code databaseVersion >= 0} produces the "version not the latest"
   * branch. Pin: dbName / rid / databaseVersion / recordVersion / recordOperation all round-trip
   * via the public getters; the {@link Throwable#getMessage()} contains both the operation name
   * and the {@code db=v…} version marker.
   */
  @Test
  public void fiveArgConstructorWithPositiveDatabaseVersionFormatsVersionMismatchMessage() {
    var rid = new RecordId(7, 42);
    var ex = new ConcurrentModificationException("dbX", rid, 99L, 1L, RecordOperation.UPDATED);

    assertEquals("dbX", ex.getDbName());
    assertEquals(rid, ex.getRid());
    assertEquals(99L, ex.getEnhancedDatabaseVersion());
    assertEquals(1L, ex.getEnhancedRecordVersion());

    var msg = ex.getMessage();
    assertTrue("expected operation name in message: " + msg, msg.contains("UPDATE"));
    assertTrue("expected version-mismatch wording: " + msg,
        msg.contains("the version is not the latest"));
    assertTrue("expected db version marker: " + msg, msg.contains("db=v99"));
    assertTrue("expected your-version marker: " + msg, msg.contains("your=v1"));
    // The CoreException decorator appends the dbName line.
    assertTrue("expected DB Name decorator: " + msg, msg.contains("DB Name=\"dbX\""));
    // HighLevelException is a marker interface — instances of this exception must implement it.
    assertTrue(ex instanceof HighLevelException);
  }

  /**
   * {@code databaseVersion < 0} routes to the "deleted by another user" branch — different
   * wording with only the caller's record-version marker (no {@code db=v…}).
   */
  @Test
  public void fiveArgConstructorWithNegativeDatabaseVersionFormatsDeletedByOtherMessage() {
    var rid = new RecordId(7, 42);
    var ex = new ConcurrentModificationException("dbX", rid, -1L, 5L, RecordOperation.DELETED);

    assertEquals(-1L, ex.getEnhancedDatabaseVersion());

    var msg = ex.getMessage();
    assertTrue("expected operation name: " + msg, msg.contains("DELETE"));
    assertTrue("expected does-not-exist wording: " + msg,
        msg.contains("it does not exist in the database"));
    assertTrue("expected your-version marker: " + msg, msg.contains("your=v5"));
    // No "db=v" marker on the negative branch.
    assertTrue("did not expect db=v marker on negative branch: " + msg, !msg.contains("db=v"));
  }

  /**
   * The CREATE operation exercises the third {@link RecordOperation#getName(int)} table entry,
   * confirming the suffix-trimming branch on a third operation name shape ({@code CREATE} →
   * lowercase trim last char {@code "creat"} → append {@code "ing"} → {@code "creating"}).
   */
  @Test
  public void createOperationProducesCreatingGerundInMessage() {
    var rid = new RecordId(2, 3);
    var ex = new ConcurrentModificationException("dbX", rid, 10L, 2L, RecordOperation.CREATED);

    var msg = ex.getMessage();
    assertTrue("expected CREATE operation: " + msg, msg.contains("CREATE"));
    assertTrue("expected creating gerund: " + msg, msg.contains("creating"));
  }

  /**
   * The copy constructor preserves dbName / rid / versions / operation. Required because the
   * exception is reconstructed on the remote-protocol client side via this constructor.
   */
  @Test
  public void copyConstructorPreservesAllFields() {
    var rid = new RecordId(7, 42);
    var original =
        new ConcurrentModificationException("dbX", rid, 99L, 1L, RecordOperation.UPDATED);
    var copy = new ConcurrentModificationException(original);

    assertEquals(original.getDbName(), copy.getDbName());
    assertEquals(original.getRid(), copy.getRid());
    assertEquals(original.getEnhancedDatabaseVersion(), copy.getEnhancedDatabaseVersion());
    assertEquals(original.getEnhancedRecordVersion(), copy.getEnhancedRecordVersion());
  }

  /**
   * The protected {@code (String dbName, String message)} ctor is reachable through subclassing
   * (it exists for subclass overrides such as YouTrack-internal MVCC variants). Pin the path
   * via a local subclass so a regression that breaks the ctor — e.g., requires non-null
   * arguments — fails loudly. RID/versions/operation are left at their zero defaults; this is
   * the only way coverage can reach the protected ctor.
   */
  @Test
  public void protectedTwoArgConstructorAccessibleToSubclasses() {
    class Custom extends ConcurrentModificationException {
      Custom() {
        super("dbX", "custom message");
      }
    }

    var ex = new Custom();
    assertEquals("dbX", ex.getDbName());
    assertTrue(ex.getMessage().contains("custom message"));
    // RID/versions/operation default to zero; the equals method dereferences rid so we only
    // pin getters here, not equals.
    assertNull(ex.getRid());
    assertEquals(0L, ex.getEnhancedDatabaseVersion());
    assertEquals(0L, ex.getEnhancedRecordVersion());
  }

  /**
   * {@code equals} returns true when two instances share rid / databaseVersion / recordOperation
   * (the recordVersion is excluded from the comparison — pinned by constructing two siblings
   * with identical rid / databaseVersion / recordOperation but different recordVersion and
   * confirming equality). The asymmetry is deliberate: the exception is used in retry loops
   * where the user's version is intentionally stale and would otherwise spuriously diverge.
   */
  @Test
  public void equalsConsidersRidAndDatabaseVersionAndOperationButNotRecordVersion() {
    var rid = new RecordId(7, 42);
    var a = new ConcurrentModificationException("dbX", rid, 99L, 1L, RecordOperation.UPDATED);
    var b = new ConcurrentModificationException("dbX", rid, 99L, 7L, RecordOperation.UPDATED);

    assertEquals(a, b);
  }

  /**
   * {@code equals} returns false when rid differs, when databaseVersion differs, when
   * recordOperation differs, or when the other object is null / a different type. Each
   * inequality branch is pinned in a separate sub-assertion so a regression in any of the
   * field-equality clauses fails distinctly.
   */
  @Test
  public void equalsReturnsFalseOnFieldOrTypeMismatch() {
    var rid = new RecordId(7, 42);
    var base = new ConcurrentModificationException("dbX", rid, 99L, 1L, RecordOperation.UPDATED);

    // Different rid.
    assertNotEquals(
        base,
        new ConcurrentModificationException("dbX", new RecordId(7, 99), 99L, 1L,
            RecordOperation.UPDATED));
    // Different databaseVersion (the second nested if returns false).
    assertNotEquals(
        base,
        new ConcurrentModificationException("dbX", rid, 100L, 1L, RecordOperation.UPDATED));
    // Different recordOperation (top-level && short-circuits).
    assertNotEquals(
        base,
        new ConcurrentModificationException("dbX", rid, 99L, 1L, RecordOperation.DELETED));
    // Different runtime type — pattern-match instanceof returns false on the first guard.
    assertNotEquals("not-an-exception", base);
    // Null comparand — pattern-match instanceof returns false on null.
    assertNotEquals(base, null);
  }

  /**
   * {@code hashCode} bundles rid / databaseVersion / recordVersion / recordOperation through
   * {@link java.util.Objects#hash}. Pin that two instances with identical fields produce the
   * same hash; this guards against accidental field-set changes that would silently desync
   * equals/hashCode contracts.
   */
  @Test
  public void hashCodeIsConsistentForEqualFieldSets() {
    var rid = new RecordId(7, 42);
    var a = new ConcurrentModificationException("dbX", rid, 99L, 1L, RecordOperation.UPDATED);
    var b = new ConcurrentModificationException("dbX", rid, 99L, 1L, RecordOperation.UPDATED);

    assertEquals(a.hashCode(), b.hashCode());
    // Sanity: hash function actually mixes inputs rather than collapsing to a
    // constant zero. assertNotNull on Integer.valueOf(int) is tautological
    // (the boxing result is never null) — assert non-zero instead.
    assertNotEquals(
        "hashCode must not collapse to a constant zero — sanity-check the hash mixes inputs",
        0,
        a.hashCode());
  }
}
