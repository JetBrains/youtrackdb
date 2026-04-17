/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.SerializableStream;
import java.io.Serial;
import java.util.Base64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionEncode} — encodes a byte[] / {@code RecordIdInternal} Blob /
 * {@link SerializableStream} to base64.
 *
 * <p>Uses {@link DbTestBase} because the RID branch calls {@code context.getDatabaseSession()
 * .getActiveTransaction().load(rid)}.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>byte[] candidate with {@code "base64"} format → base64 string.
 *   <li>Format is case-insensitive: BASE64 / Base64 / base64 all work.
 *   <li>{@link RecordIdInternal} pointing at a {@link com.jetbrains.youtrackdb.internal.core.db
 *       .record.record.Blob} → base64 of blob bytes.
 *   <li>RID pointing at a non-Blob record (data == null after the RID branch) → null.
 *   <li>Missing RID ({@code RecordNotFoundException}) → null.
 *   <li>{@link SerializableStream} candidate → base64 of {@code toStream()}.
 *   <li>Unrecognised candidate type (e.g. String) with recognised format → null (data stays null).
 *   <li>Unknown format → {@link DatabaseException} with the "unknowned" typo pinned (WHEN-FIXED:
 *       spelling).
 *   <li>Metadata (name, min/max, syntax).
 * </ul>
 */
public class SQLFunctionEncodeTest extends DbTestBase {

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  @Before
  public void setUp() {
    // Schema changes are NOT transactional — create classes BEFORE opening the tx.
    session.createClass("Thing");
    // All RID-branch tests load via session.getActiveTransaction().load(rid), which requires an
    // active transaction. Keep it open across the test; @After rolls back if left active.
    session.begin();
  }

  @After
  public void rollbackIfLeftOpen() {
    if (session.getActiveTransaction().isActive()) {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // byte[] candidate path
  // ---------------------------------------------------------------------------

  @Test
  public void byteArrayCandidateReturnsBase64() {
    var function = new SQLFunctionEncode();
    var bytes = "hello, world".getBytes();

    var result = function.execute(null, null, null, new Object[] {bytes, "base64"}, ctx());

    assertEquals(Base64.getEncoder().encodeToString(bytes), result);
  }

  @Test
  public void emptyByteArrayCandidateReturnsEmptyBase64() {
    // Boundary: empty array → empty base64 (NOT null), pins the data-not-null branch.
    var function = new SQLFunctionEncode();

    var result = function.execute(null, null, null, new Object[] {new byte[0], "base64"}, ctx());

    assertEquals("", result);
  }

  @Test
  public void formatMatchIsCaseInsensitive() {
    // equalsIgnoreCase drives the format match.
    var function = new SQLFunctionEncode();
    var bytes = new byte[] {1, 2, 3};
    var expected = Base64.getEncoder().encodeToString(bytes);

    assertEquals(expected, function.execute(null, null, null,
        new Object[] {bytes, "BASE64"}, ctx()));
    assertEquals(expected, function.execute(null, null, null,
        new Object[] {bytes, "Base64"}, ctx()));
    assertEquals(expected, function.execute(null, null, null,
        new Object[] {bytes, "base64"}, ctx()));
  }

  @Test
  public void formatToStringIsInvokedOnNonStringFormatParam() {
    // iParams[1].toString() — a non-String format argument still works if its toString matches.
    var function = new SQLFunctionEncode();
    var bytes = new byte[] {7, 8, 9};
    var formatAsBuilder = new StringBuilder("base64");

    var result = function.execute(null, null, null, new Object[] {bytes, formatAsBuilder}, ctx());

    assertEquals(Base64.getEncoder().encodeToString(bytes), result);
  }

  // ---------------------------------------------------------------------------
  // RID candidate path
  // ---------------------------------------------------------------------------

  @Test
  public void ridPointingToBlobReturnsBase64OfBlobBytes() {
    // Create a Blob, commit, re-use its RID, reopen a tx and call encode.
    var blobBytes = "blob data".getBytes();
    var blob = session.newBlob(blobBytes);
    session.commit();

    // Capture the persisted RID after commit, reopen tx for the function call.
    var rid = (RecordIdInternal) blob.getIdentity();
    session.begin();

    var function = new SQLFunctionEncode();

    var result = function.execute(null, null, null, new Object[] {rid, "base64"}, ctx());

    assertEquals(Base64.getEncoder().encodeToString(blobBytes), result);
  }

  @Test
  public void ridPointingToNonBlobRecordReturnsNull() {
    // Load resolves, but the loaded record is not a Blob → data stays null → function returns
    // null. Pins the `if (rec instanceof Blob)` gate.
    var entity = session.newEntity("Thing");
    entity.setProperty("name", "x");
    session.commit();

    var rid = (RecordIdInternal) entity.getIdentity();
    session.begin();

    var function = new SQLFunctionEncode();

    var result = function.execute(null, null, null, new Object[] {rid, "base64"}, ctx());

    assertNull(result);
  }

  @Test
  public void missingRidReturnsNull() {
    // transaction.load(rid) throws RecordNotFoundException → caught → return null.
    var function = new SQLFunctionEncode();
    var missing = new RecordId(999, 999);

    var result = function.execute(null, null, null,
        new Object[] {(RecordIdInternal) missing, "base64"}, ctx());

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // SerializableStream candidate path
  // ---------------------------------------------------------------------------

  @Test
  public void serializableStreamCandidateReturnsBase64OfToStreamBytes() {
    var function = new SQLFunctionEncode();
    var payload = "stream-bytes".getBytes();
    var stream = new FixedBytesStream(payload);

    var result = function.execute(null, null, null, new Object[] {stream, "base64"}, ctx());

    assertEquals(Base64.getEncoder().encodeToString(payload), result);
  }

  // ---------------------------------------------------------------------------
  // Unrecognised candidate type — data stays null → null return
  // ---------------------------------------------------------------------------

  @Test
  public void unrecognizedCandidateReturnsNull() {
    // String candidate matches neither byte[] nor RecordIdInternal nor SerializableStream — data
    // stays null → returns null before the format check.
    var function = new SQLFunctionEncode();

    var result = function.execute(null, null, null,
        new Object[] {"not-bytes-nor-rid", "base64"}, ctx());

    assertNull(result);
  }

  @Test
  public void nullCandidateFirstParamReturnsNull() {
    // iParams[0] == null falls through every `instanceof` guard (null is not an instance of
    // anything) — data stays null → returns null. The unknown-format branch is NOT reached.
    var function = new SQLFunctionEncode();

    var result = function.execute(null, null, null, new Object[] {null, "base64"}, ctx());

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // Unknown format — DatabaseException (WHEN-FIXED: "unknowned" typo)
  // ---------------------------------------------------------------------------

  @Test
  public void unknownFormatThrowsDatabaseExceptionWithTypoInMessage() {
    // The production message has the typo "unknowned format :" — pin it verbatim so a refactor
    // that fixes the spelling is picked up here.
    // WHEN-FIXED: correct the typo to "unknown format :" in SQLFunctionEncode.java.
    var function = new SQLFunctionEncode();
    var bytes = new byte[] {1, 2, 3};

    try {
      function.execute(null, null, null, new Object[] {bytes, "hex"}, ctx());
      fail("expected DatabaseException for unknown format");
    } catch (DatabaseException expected) {
      assertNotNull(expected.getMessage());
      assertTrue("message should contain the raw 'unknowned format :' typo, saw: "
          + expected.getMessage(),
          expected.getMessage().contains("unknowned format :hex"));
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchFunctionContract() {
    var function = new SQLFunctionEncode();

    assertEquals("encode", SQLFunctionEncode.NAME);
    assertEquals(SQLFunctionEncode.NAME, function.getName(session));
    assertEquals(2, function.getMinParams());
    assertEquals(2, function.getMaxParams(session));
    assertEquals("encode(<binaryfield>, <format>)", function.getSyntax(session));
    assertEquals("base64", SQLFunctionEncode.FORMAT_BASE64);
  }

  /**
   * Minimal {@link SerializableStream} returning a fixed payload via {@link #toStream()}. Used to
   * exercise the SerializableStream branch without pulling in a full serializer-backed type.
   */
  private static final class FixedBytesStream implements SerializableStream {

    @Serial
    private static final long serialVersionUID = 1L;

    private final byte[] payload;

    FixedBytesStream(byte[] payload) {
      this.payload = payload;
    }

    @Override
    public byte[] toStream() throws SerializationException {
      return payload;
    }

    @Override
    public SerializableStream fromStream(byte[] iStream) throws SerializationException {
      throw new UnsupportedOperationException("not used in tests");
    }
  }
}
