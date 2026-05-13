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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Coverage for {@link RecordDuplicatedException} — the public exception thrown when an attempt
 * is made to insert a duplicate record into a unique index. Bespoke ctor shape:
 * {@code (DatabaseSessionEmbedded, String message, String indexName, RID iRid, Object key)}.
 * The {@code DatabaseSessionEmbedded} parameter is consulted only for {@code getDatabaseName()}
 * (via the {@code CoreException(session, message)} ctor); passing {@code null} as the session
 * is supported and yields {@code dbName == null}.
 *
 * <p>Two ctor branches plus the message-decoration override are pinned. The copy ctor
 * round-trips indexName / rid / key for the remote-protocol replay path.
 */
public class RecordDuplicatedExceptionTest {

  /**
   * The full 5-arg ctor accepts a {@code null} session (delegating to the
   * {@code CoreException(session, message)} ctor which checks for null before dereferencing).
   * indexName / rid / key round-trip via getters; the overridden {@link
   * Throwable#getMessage()} appends the {@code INDEX=…} and {@code RID=…} markers after the
   * supertype's decoration.
   */
  @Test
  public void fiveArgConstructorWithNullSessionDoesNotThrowAndPreservesFields() {
    var rid = new RecordId(7, 42);
    var key = "duplicate-key";

    var ex = new RecordDuplicatedException(null, "duplicate", "Idx", rid, key);

    assertNull("null session yields null dbName", ex.getDbName());
    assertEquals("Idx", ex.getIndexName());
    assertEquals(rid, ex.getRid());
    assertSame(key, ex.getKey());

    var msg = ex.getMessage();
    assertTrue("expected supertype message: " + msg, msg.contains("duplicate"));
    assertTrue("expected INDEX marker: " + msg, msg.contains("INDEX=Idx"));
    assertTrue("expected RID marker: " + msg, msg.contains("RID=" + rid));
    // HighLevelException is a marker — instances of this class must implement it.
    assertTrue(ex instanceof HighLevelException);
  }

  /**
   * The copy ctor preserves indexName / rid / key — the remote-protocol replay path on the
   * client side reconstructs exceptions through this ctor.
   */
  @Test
  public void copyConstructorPreservesIndexNameRidAndKey() {
    var rid = new RecordId(7, 42);
    var key = 12345;
    var original = new RecordDuplicatedException(null, "msg", "IdxA", rid, key);

    var copy = new RecordDuplicatedException(original);
    assertEquals(original.getIndexName(), copy.getIndexName());
    assertEquals(original.getRid(), copy.getRid());
    assertEquals(original.getKey(), copy.getKey());
    // The message decorator runs against the copy's own state, so the markers reappear.
    assertTrue(copy.getMessage().contains("INDEX=IdxA"));
    assertTrue(copy.getMessage().contains("RID=" + rid));
  }

  /**
   * {@code key} accepts arbitrary {@code Object}s; null is permitted (no preconditions) — the
   * message decorator should still produce a stable string. Pin the null-key path so a future
   * regression that requires non-null keys breaks loudly.
   */
  @Test
  public void nullKeyIsPermittedAndDoesNotBreakMessageDecoration() {
    var rid = new RecordId(1, 2);
    var ex = new RecordDuplicatedException(null, "msg", "Idx", rid, null);

    assertNull(ex.getKey());
    // No NPE constructing the message even though key is null.
    var msg = ex.getMessage();
    assertTrue(msg.contains("INDEX=Idx"));
    assertTrue(msg.contains("RID=" + rid));
  }
}
