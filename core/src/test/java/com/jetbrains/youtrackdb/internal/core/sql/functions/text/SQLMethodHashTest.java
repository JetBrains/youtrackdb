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
package com.jetbrains.youtrackdb.internal.core.sql.functions.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.security.SecurityManager;
import java.security.NoSuchAlgorithmException;
import org.junit.Test;

/**
 * Tests for {@link SQLMethodHash} — computes a hex digest of {@code iThis.toString()} using the
 * given algorithm (defaulting to {@code SecurityManager.HASH_ALGORITHM} when no param is given).
 *
 * <p>Uses {@link DbTestBase} because the {@code NoSuchAlgorithmException} path builds a
 * {@link CommandExecutionException} whose constructor reaches into
 * {@code iContext.getDatabaseSession()}.
 *
 * <p>Covered branches:
 *
 * <ul>
 *   <li>{@code iThis == null} → null short-circuit (no param dereference).
 *   <li>Zero-arg: uses the default {@code SecurityManager.HASH_ALGORITHM} (SHA-256). The result
 *       matches {@code SecurityManager.createHash(input, default)} directly.
 *   <li>Explicit "SHA-256" / "MD5" / "SHA-1" — algorithm string round-trips through toString.
 *   <li>Non-String {@code iThis} is coerced via {@code toString()}.
 *   <li>Empty string is a valid input — produces the canonical SHA-256 hash of "".
 *   <li>Unknown algorithm → {@link CommandExecutionException} wrapping
 *       {@link java.security.NoSuchAlgorithmException}. Assert message references the bad
 *       algorithm.
 *   <li>Metadata (name, min/max, syntax).
 * </ul>
 */
public class SQLMethodHashTest extends DbTestBase {

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  private SQLMethodHash method() {
    return new SQLMethodHash();
  }

  // ---------------------------------------------------------------------------
  // Null short-circuit
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNull() {
    // iThis == null returns null BEFORE the iParams[0] dereference — so passing a null context is
    // safe on this path (we still pass a real one for symmetry).
    assertNull(method().execute(null, null, ctx(), null, new Object[] {}));
  }

  // ---------------------------------------------------------------------------
  // Default algorithm (zero-arg)
  // ---------------------------------------------------------------------------

  @Test
  public void zeroArgUsesDefaultSha256ForNonEmptyString() throws Exception {
    var expected = SecurityManager.createHash("hello", SecurityManager.HASH_ALGORITHM);

    var result = method().execute("hello", null, ctx(), null, new Object[] {});

    assertEquals(expected, result);
  }

  @Test
  public void zeroArgUsesDefaultSha256ForEmptyString() throws Exception {
    // Empty input → canonical SHA-256 of "".
    var expected = SecurityManager.createHash("", SecurityManager.HASH_ALGORITHM);

    var result = method().execute("", null, ctx(), null, new Object[] {});

    assertEquals(expected, result);
    // Sanity: SHA-256("") is the well-known constant — assert via length to avoid hard-coding.
    assertNotNull(result);
    assertEquals("SHA-256 produces a 64-character hex digest", 64, result.toString().length());
  }

  // ---------------------------------------------------------------------------
  // Explicit algorithm strings
  // ---------------------------------------------------------------------------

  @Test
  public void explicitSha256MatchesSecurityManagerDirect() throws Exception {
    var expected = SecurityManager.createHash("abc", "SHA-256");

    var result = method().execute("abc", null, ctx(), null, new Object[] {"SHA-256"});

    assertEquals(expected, result);
  }

  @Test
  public void explicitMd5MatchesSecurityManagerDirect() throws Exception {
    var expected = SecurityManager.createHash("abc", "MD5");

    var result = method().execute("abc", null, ctx(), null, new Object[] {"MD5"});

    assertEquals(expected, result);
  }

  @Test
  public void explicitSha1MatchesSecurityManagerDirect() throws Exception {
    var expected = SecurityManager.createHash("abc", "SHA-1");

    var result = method().execute("abc", null, ctx(), null, new Object[] {"SHA-1"});

    assertEquals(expected, result);
  }

  @Test
  public void nonStringIThisIsCoercedViaToString() throws Exception {
    // Integer.toString(42) = "42". The hash of "42" via the default algorithm.
    var expected = SecurityManager.createHash("42", SecurityManager.HASH_ALGORITHM);

    var result = method().execute(Integer.valueOf(42), null, ctx(), null, new Object[] {});

    assertEquals(expected, result);
  }

  @Test
  public void algorithmParamIsCoercedViaToString() throws Exception {
    // Passing a non-String param: iParams[0].toString() — pins the .toString() branch in the
    // default-resolution ternary.
    var algo = new Object() {
      @Override
      public String toString() {
        return "SHA-256";
      }
    };
    var expected = SecurityManager.createHash("abc", "SHA-256");

    var result = method().execute("abc", null, ctx(), null, new Object[] {algo});

    assertEquals(expected, result);
  }

  // ---------------------------------------------------------------------------
  // Unknown algorithm → wrapped CommandExecutionException
  // ---------------------------------------------------------------------------

  @Test
  public void unknownAlgorithmThrowsCommandExecutionExceptionMentioningBadName() {
    // Production wraps NoSuchAlgorithmException into CommandExecutionException via
    // BaseException.wrapException. Pin all three observables: the message references the bad
    // algorithm, it mentions "hash" context, AND the cause chain preserves the underlying
    // NoSuchAlgorithmException — a future refactor that drops the cause would change debug
    // ergonomics for callers and should be caught here.
    var bogus = "NOT-A-REAL-HASH-ALGO-42";

    try {
      method().execute("abc", null, ctx(), null, new Object[] {bogus});
      fail("expected CommandExecutionException for unknown algorithm");
    } catch (CommandExecutionException e) {
      assertNotNull("exception must carry a message", e.getMessage());
      assertTrue("message must reference the bad algorithm, saw: " + e.getMessage(),
          e.getMessage().contains(bogus));
      assertTrue("message must mention 'hash': " + e.getMessage(),
          e.getMessage().toLowerCase().contains("hash"));
      assertTrue("cause must be NoSuchAlgorithmException, saw: " + e.getCause(),
          e.getCause() instanceof NoSuchAlgorithmException);
    }
  }

  @Test
  public void nullAlgorithmParamThrowsNullPointerException() {
    // WHEN-FIXED: iParams.length > 0 but iParams[0] is null → iParams[0].toString() NPEs on
    // line 56 of production before we ever reach the algorithm lookup. A sensible fix would
    // either fall back to the default algorithm or throw CommandExecutionException; this pin
    // makes either fix visible in the test diff.
    try {
      method().execute("abc", null, ctx(), null, new Object[] {null});
      fail("expected NullPointerException for null algorithm param");
    } catch (NullPointerException expected) {
      // pinned
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchContract() {
    var m = method();

    assertEquals("hash", SQLMethodHash.NAME);
    assertEquals("hash", m.getName());
    assertEquals(0, m.getMinParams());
    assertEquals(1, m.getMaxParams(null));
    assertEquals("hash([<algorithm>])", m.getSyntax());
  }
}
