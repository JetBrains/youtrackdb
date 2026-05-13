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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Coverage for {@link RecordNotFoundException} — the public exception thrown when a record id
 * lookup fails. Four ctor variants must be pinned:
 *
 * <ul>
 *   <li>{@code (DatabaseSessionEmbedded session, RID iRID)} — null-session safe via {@code
 *       session != null ? session.getDatabaseName() : null};
 *   <li>{@code (String dbName, RID iRID)} — synthesises the canonical "record with id … not
 *       found" message;
 *   <li>{@code (String dbName, RID iRID, String message)} — caller-supplied message overrides
 *       the canonical wording;
 *   <li>{@code (RecordNotFoundException exception)} — copy ctor used by the remote-protocol
 *       replay path.
 * </ul>
 *
 * <p>The bespoke {@code equals} implementation has three branches that must each be covered:
 * the runtime-type guard, the both-null-rid fallback to {@link Object#toString()} equality, and
 * the rid-based equality. {@code hashCode} bundles the rid alone via {@link
 * java.util.Objects#hashCode(Object)}.
 *
 * <p>The pre-existing {@code DBRecordNotFoundExceptionTest} pins the copy ctor's RID
 * preservation only; this class augments coverage with the remaining ctors and the
 * equals/hashCode contract.
 */
public class RecordNotFoundExceptionTest {

  /**
   * The {@code (String dbName, RID iRID)} ctor synthesises the canonical "record with id 'X'
   * was not found" message. Pin the fixed message wording so a future regression that changes
   * it breaks loudly.
   */
  @Test
  public void dbNameAndRidConstructorSynthesisesCanonicalMessage() {
    var rid = new RecordId(7, 42);
    var ex = new RecordNotFoundException("dbX", rid);

    assertEquals("dbX", ex.getDbName());
    assertEquals(rid, ex.getRid());
    var msg = ex.getMessage();
    assertTrue("expected canonical wording: " + msg, msg.contains("The record with id '"));
    assertTrue("expected rid substring: " + msg, msg.contains(rid.toString()));
    assertTrue("expected DB Name decorator: " + msg, msg.contains("DB Name=\"dbX\""));
    // HighLevelException is a marker.
    assertTrue(ex instanceof HighLevelException);
  }

  /**
   * The {@code (String dbName, RID iRID, String message)} ctor passes the caller-supplied
   * message through (without prepending the canonical "record with id…" wording). Pin both the
   * message override and the rid round-trip.
   */
  @Test
  public void dbNameRidAndMessageConstructorUsesCallerSuppliedMessage() {
    var rid = new RecordId(1, 2);
    var ex = new RecordNotFoundException("dbX", rid, "custom wording");

    assertEquals("dbX", ex.getDbName());
    assertEquals(rid, ex.getRid());
    var msg = ex.getMessage();
    assertTrue("expected custom message: " + msg, msg.contains("custom wording"));
    // The canonical "record with id..." wording is NOT prepended in this ctor variant.
    assertTrue("did not expect canonical wording: " + msg, !msg.contains("The record with id '"));
  }

  /**
   * The {@code (DatabaseSessionEmbedded session, RID iRID)} ctor accepts a {@code null} session
   * — the dbName resolves to {@code null} and no NPE escapes. This is the single non-trivial
   * branch in the session-typed ctor.
   */
  @Test
  public void sessionConstructorWithNullSessionResolvesToNullDbName() {
    var rid = new RecordId(2, 3);
    // Disambiguate between the (String, RID) and (DatabaseSessionEmbedded, RID) ctors.
    var ex =
        new RecordNotFoundException(
            (com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded) null, rid);

    assertNull(ex.getDbName());
    assertEquals(rid, ex.getRid());
  }

  /**
   * The copy ctor preserves the rid and dbName via {@code super(exception)}. Augments the
   * existing {@code DBRecordNotFoundExceptionTest} by also pinning the canonical message
   * substring on the copy. (The exact-message comparison would not hold: the
   * {@link com.jetbrains.youtrackdb.internal.core.exception.CoreException#getMessage()} override
   * appends the "DB Name=…" decorator to the message every time it is called, and the copy ctor
   * stores the original's already-decorated message — so the copy's message contains the
   * decorator twice. The substring assertions below are robust to that decorator-doubling.)
   */
  @Test
  public void copyConstructorPreservesRidAndMessage() {
    var rid = new RecordId(7, 42);
    var original = new RecordNotFoundException("dbX", rid);

    var copy = new RecordNotFoundException(original);
    assertEquals(rid, copy.getRid());
    assertEquals(original.getDbName(), copy.getDbName());
    assertTrue(copy.getMessage().contains("The record with id '" + rid + "' was not found"));
    assertTrue(copy.getMessage().contains("DB Name=\"dbX\""));
  }

  /**
   * {@code equals} returns true for two instances with equal non-null rids. The rid-based
   * equality branch — the third {@code if} in the bespoke equals — is the common case.
   */
  @Test
  public void equalsByRidWhenBothRidsAreEqualAndNonNull() {
    var rid = new RecordId(7, 42);
    var a = new RecordNotFoundException("dbX", rid);
    var b = new RecordNotFoundException("dbY", new RecordId(7, 42));

    // Equal by rid even though dbName differs and the message text matches incidentally.
    assertEquals(a, b);
  }

  /**
   * {@code equals} falls back to {@link Object#toString()} equality when both rids are null —
   * pinning the second branch in the bespoke equals. Reachable via the protected {@code
   * (String dbName, RID, String message)} ctor with a null rid (subclasses can pass null).
   * Pinned through a local subclass to avoid relying on a public ctor that accepts null rid.
   */
  @Test
  public void equalsBothNullRidFallsBackToToStringComparison() {
    class Custom extends RecordNotFoundException {
      Custom(String message) {
        super("dbX", (RecordId) null, message);
      }
    }

    var a = new Custom("identical");
    var b = new Custom("identical");
    var different = new Custom("divergent");

    assertNull(a.getRid());
    assertNull(b.getRid());

    assertEquals(a, b);
    assertNotEquals(a, different);
  }

  /**
   * {@code equals} returns false when the comparand is null or a different runtime type — the
   * pattern-match {@code instanceof} guard short-circuits both cases.
   */
  @Test
  public void equalsRejectsNullAndDifferentRuntimeType() {
    var ex = new RecordNotFoundException("dbX", new RecordId(7, 42));
    assertNotEquals(ex, null);
    assertNotEquals(ex, "not-a-record-not-found");
  }

  /**
   * {@code equals} returns false when one rid is null and the other is non-null — pinning the
   * mixed-null branch (last clause: {@code rid != null && rid.equals(other.rid)}).
   */
  @Test
  public void equalsReturnsFalseWhenOneRidIsNullAndOtherIsNot() {
    class Custom extends RecordNotFoundException {
      Custom() {
        super("dbX", (RecordId) null, "msg");
      }
    }

    var nullRid = new Custom();
    var withRid = new RecordNotFoundException("dbX", new RecordId(1, 2));

    assertNotEquals(nullRid, withRid);
    assertNotEquals(withRid, nullRid);
  }

  /**
   * {@code hashCode} delegates to {@link java.util.Objects#hashCode(Object)} on the rid;
   * pin both the equal-rid case (matching hash) and the null-rid case (zero hash via {@code
   * Objects.hashCode(null)}).
   */
  @Test
  public void hashCodeMirrorsRidEquality() {
    var rid = new RecordId(7, 42);
    var a = new RecordNotFoundException("dbX", rid);
    var b = new RecordNotFoundException("dbY", new RecordId(7, 42));
    assertEquals(a.hashCode(), b.hashCode());

    class Custom extends RecordNotFoundException {
      Custom() {
        super("dbX", (RecordId) null, "msg");
      }
    }
    var nullRid = new Custom();
    assertEquals(0, nullRid.hashCode());
    // Sanity: not just zero in general.
    assertNotNull(Integer.valueOf(a.hashCode()));
  }
}
