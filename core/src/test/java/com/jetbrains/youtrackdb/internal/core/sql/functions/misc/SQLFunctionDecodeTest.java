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
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionDecode} — base64 decode (the only supported format). Standalone.
 *
 * <p>Notes on the "unknown format" path:
 *
 * <ul>
 *   <li>On the unknown-format branch the production code builds a {@link DatabaseException}
 *       via {@code iContext.getDatabaseSession()}. With a {@link BasicCommandContext} that has
 *       no attached session, {@code getDatabaseSession()} itself throws a {@link DatabaseException}
 *       with message "No database session found in SQL context" — the test therefore asserts
 *       the exception type but NOT the intended "unknowned format" message. The same contract
 *       mismatch was documented for sibling functions in Track 6 Step 3 (Difference,
 *       Intersect).
 *   <li>WHEN-FIXED: the typo in the production message ("unknowned" vs "unknown") should also
 *       be corrected when the DB-session dependency is removed.
 * </ul>
 */
public class SQLFunctionDecodeTest {

  @Test
  public void base64DecodesRoundTripString() {
    final var fn = new SQLFunctionDecode();
    final var ctx = new BasicCommandContext();

    final var original = "hello youtrack";
    final var encoded =
        Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));

    final var result = fn.execute(null, null, null, new Object[] {encoded, "base64"}, ctx);

    assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), (byte[]) result);
  }

  @Test
  public void base64DecodesRoundTripBinary() {
    final var fn = new SQLFunctionDecode();
    final var ctx = new BasicCommandContext();

    final var original = new byte[] {0, 1, 2, 3, (byte) 0xFF, (byte) 0xFE};
    final var encoded = Base64.getEncoder().encodeToString(original);

    final var result = fn.execute(null, null, null, new Object[] {encoded, "base64"}, ctx);

    assertArrayEquals(original, (byte[]) result);
  }

  @Test
  public void formatMatchIsCaseInsensitive() {
    // equalsIgnoreCase: BASE64, Base64, BASE64 all route to the decoder path.
    final var fn = new SQLFunctionDecode();
    final var ctx = new BasicCommandContext();

    final var encoded = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
    assertArrayEquals("x".getBytes(StandardCharsets.UTF_8),
        (byte[]) fn.execute(null, null, null, new Object[] {encoded, "BASE64"}, ctx));
    assertArrayEquals("x".getBytes(StandardCharsets.UTF_8),
        (byte[]) fn.execute(null, null, null, new Object[] {encoded, "Base64"}, ctx));
  }

  @Test
  public void nonStringInputsAreToStringCoerced() {
    // iParams[0] and iParams[1] are converted via .toString() — StringBuilder is a drift guard
    // against refactors that hard-cast to String.
    final var fn = new SQLFunctionDecode();
    final var ctx = new BasicCommandContext();

    final var original = "y";
    final var encoded =
        Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));

    // Wrap in StringBuilder — toString() yields the same encoded string.
    final var candidate = new StringBuilder(encoded);
    final var format = new StringBuilder("base64");

    final var result = fn.execute(null, null, null, new Object[] {candidate, format}, ctx);
    assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), (byte[]) result);
  }

  @Test(expected = IllegalArgumentException.class)
  public void invalidBase64PayloadPropagatesIllegalArgumentException() {
    // Base64.getDecoder().decode throws IllegalArgumentException on non-base64 characters;
    // SQLFunctionDecode does not catch it, so it propagates.
    final var fn = new SQLFunctionDecode();
    fn.execute(null, null, null, new Object[] {"!!!not-base64!!!", "base64"},
        new BasicCommandContext());
  }

  @Test
  public void emptyStringCandidateDecodesToEmptyByteArray() {
    // Boundary: "" is a valid base64 encoding of the empty byte array.
    final var fn = new SQLFunctionDecode();
    final var result = (byte[]) fn.execute(null, null, null,
        new Object[] {"", "base64"}, new BasicCommandContext());
    assertArrayEquals(new byte[0], result);
  }

  @Test
  public void unknownFormatThrowsDatabaseException() {
    final var fn = new SQLFunctionDecode();
    final var ctx = new BasicCommandContext();
    try {
      fn.execute(null, null, null, new Object[] {"abc", "hex"}, ctx);
      fail("Expected DatabaseException");
    } catch (DatabaseException e) {
      // With BasicCommandContext and no session, the getDatabaseSession() call throws
      // BEFORE the intended "unknowned format" DatabaseException can be built — documented
      // contract mismatch. The "No database session found" message fires here.
      // WHEN-FIXED: either move the format check above getDatabaseSession(), or make the
      // DatabaseException constructor tolerant of a null session.
      assertEquals("No database session found in SQL context", e.getMessage());
    }
  }

  @Test(expected = NullPointerException.class)
  public void nullCandidateThrowsNullPointerExceptionFromToString() {
    // iParams[0].toString() NPEs when candidate is null — documented behaviour.
    final var fn = new SQLFunctionDecode();
    fn.execute(null, null, null, new Object[] {null, "base64"}, new BasicCommandContext());
  }

  @Test(expected = NullPointerException.class)
  public void nullFormatThrowsNullPointerExceptionFromToString() {
    final var fn = new SQLFunctionDecode();
    final var ctx = new BasicCommandContext();
    fn.execute(null, null, null, new Object[] {"YQ==", null}, ctx);
  }

  @Test
  public void metadataSurfaceIsPinned() {
    final var fn = new SQLFunctionDecode();
    assertEquals("decode", fn.getName(null));
    assertEquals(2, fn.getMinParams());
    assertEquals(2, fn.getMaxParams(null));
    assertEquals("decode(<binaryfield>, <format>)", fn.getSyntax(null));
  }
}
