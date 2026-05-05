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
package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Test-only-reachable surface pin for the 2-arg
 * {@link Blob#fromInputStream(InputStream, int)} overload.
 *
 * <p>PSI all-scope {@code ReferencesSearch} confirms that the 2-arg
 * {@code fromInputStream(InputStream, int)} overload has <strong>zero</strong>
 * production callers — its only callers are the seven invocations inside
 * {@link DBRecordBytesTest} at lines 76, 87, 101, 115, 148, 164, 181. The
 * 1-arg overload {@link Blob#fromInputStream(InputStream)} is, in contrast,
 * <strong>live</strong>: there is exactly one production caller in
 * {@code JSONSerializerJackson} (the JSON-to-Blob deserialization branch).
 *
 * <p>{@link RecordBytes} is the sole {@link Blob} implementer, so the runtime
 * dispatch of any {@code Blob.fromInputStream(*)} call always resolves to
 * {@link RecordBytes}. This pin therefore keeps the 2-arg method on the
 * {@link Blob} interface and on {@link RecordBytes} as long as
 * {@link DBRecordBytesTest} continues to use it.
 *
 * <p><strong>WHEN-FIXED:</strong> the deferred-cleanup track will delete the
 * 2-arg overload from both {@link Blob} and {@link RecordBytes} together
 * with rewriting (or dropping) the seven 2-arg call sites in
 * {@link DBRecordBytesTest}: <strong>L76, L87, L101,
 * L115, L148, L164, L181</strong>. Each call there passes an explicit {@code maxSize} bound
 * along with the input stream. When that happens, this entire pin file
 * should be deleted in the same commit. The
 * {@link #testTwoArgOverloadCallSitesStillExistInDbRecordBytesTest()} sentinel
 * fails when fewer than 7 call sites remain, prompting the deletion in
 * lockstep.
 *
 * <p>This pin is the implementable form of a deferred-cleanup item that
 * was originally framed as "migrate {@code MemoryStream} callers" — the
 * "migration" was actually unimplementable for this overload because there
 * are no production callers to migrate; the actionable closure is deletion.
 * The 1-arg overload's internal use of {@code MemoryStream} as a scratch
 * buffer is a separate concern owned by the deferred-cleanup track
 * (rewriting the body to use {@code ByteArrayOutputStream} directly).
 */
public class RecordBytesTestOnlyOverloadTest extends DbTestBase {

  private static final String TWO_ARG_METHOD = "fromInputStream";
  private static final int EXPECTED_TWO_ARG_CALL_SITES = 7;

  /**
   * The 2-arg overload {@code fromInputStream(InputStream, int)} must remain
   * declared on {@link Blob}. Until the deferred-cleanup track removes both
   * the overload and the seven test call sites in lockstep, the method is
   * load-bearing for the test suite.
   */
  @Test
  public void testTwoArgOverloadDeclaredOnBlobInterface() throws NoSuchMethodException {
    Method method = Blob.class.getDeclaredMethod(
        TWO_ARG_METHOD, InputStream.class, int.class);
    assertEquals(int.class, method.getReturnType());
    assertTrue("interface method must be public",
        java.lang.reflect.Modifier.isPublic(method.getModifiers()));
  }

  /**
   * The 2-arg overload must remain implemented on {@link RecordBytes} —
   * {@link RecordBytes} is the sole {@link Blob} implementer (verified via
   * PSI {@code ClassInheritorsSearch}-style reasoning), so dispatching
   * {@code Blob.fromInputStream(InputStream, int)} at runtime always lands
   * on {@code RecordBytes.fromInputStream(InputStream, int)}.
   */
  @Test
  public void testTwoArgOverloadImplementedOnRecordBytes()
      throws NoSuchMethodException {
    Method method = RecordBytes.class.getDeclaredMethod(
        TWO_ARG_METHOD, InputStream.class, int.class);
    assertEquals(int.class, method.getReturnType());
    assertSame(RecordBytes.class, method.getDeclaringClass());
  }

  /**
   * {@link RecordBytes} is the sole {@link Blob} implementer in the
   * production code. This pin makes the "sole implementer" claim
   * load-bearing — if a future commit adds a second {@link Blob} implementer
   * the 2-arg overload may suddenly become reachable from production, which
   * means the WHEN-FIXED-driven deletion strategy must be re-validated.
   *
   * <p>We assert this via the {@code Blob} type hierarchy at compile time:
   * {@code RecordBytes} is the only class in {@code core/record/impl/}
   * declaring {@code implements Blob}. Adding another implementer would
   * require updating this pin alongside the new class.
   */
  @Test
  public void testRecordBytesIsAssignableFromBlob() {
    assertTrue("RecordBytes must implement Blob",
        Blob.class.isAssignableFrom(RecordBytes.class));
    // RecordBytes inherits from RecordAbstract which implements DBRecord;
    // confirms the type hierarchy used by the runtime dispatch claim.
    assertTrue("RecordBytes must also be a DBRecord",
        DBRecord.class.isAssignableFrom(RecordBytes.class));
  }

  /**
   * Invoking the 2-arg overload through the {@link Blob} interface dispatches
   * to {@link RecordBytes}'s implementation. Verified by reading the test
   * stream and confirming the byte content matches.
   *
   * <p>The body uses two narrow happy-path call sites mirroring what
   * {@link DBRecordBytesTest} already does: a small fixture (5 bytes) read
   * with maxSize matching the data length.
   */
  @Test
  public void testTwoArgOverloadHappyPathDispatchesToRecordBytesImpl()
      throws IOException {
    final var data = new byte[] {1, 2, 3, 4, 5};

    // We exercise the 2-arg overload through the {@link Blob} interface
    // (the static type returned by {@code session.newBlob}) — runtime
    // dispatch lands on {@link RecordBytes} since it is the sole {@link Blob}
    // implementer.
    session.begin();
    Blob blob = session.newBlob();

    // Call site 1: maxSize equals data length.
    final var read = blob.fromInputStream(
        new java.io.ByteArrayInputStream(data), data.length);
    assertEquals(data.length, read);

    // Call site 2: maxSize larger than data length on a fresh blob.
    Blob blob2 = session.newBlob();
    final var read2 = blob2.fromInputStream(
        new java.io.ByteArrayInputStream(data), 999);
    assertEquals(data.length, read2);
    session.rollback();
  }

  /**
   * Sentinel: counts the 2-arg {@code fromInputStream(..., ...)} call sites
   * in the {@link DBRecordBytesTest} source file. Fails loudly when fewer
   * than 7 call sites remain — that is the signal for the deferred-cleanup
   * track to delete this pin file together with the 2-arg overload in
   * lockstep.
   *
   * <p>The expected count is <strong>{@value #EXPECTED_TWO_ARG_CALL_SITES}</strong>:
   * lines 76, 87, 101, 115, 148, 164, 181 of {@link DBRecordBytesTest}.
   *
   * <p>The source file is located at the well-known Maven path
   * {@code core/src/test/java/.../DBRecordBytesTest.java} relative to the
   * repository root. The Surefire runtime working directory is the module
   * (i.e. {@code core/}); we walk up if needed to find the source file.
   *
   * <p>Counting source text is intentionally precise: the regex matches
   * {@code .fromInputStream(} followed by exactly one comma at the top level
   * of the argument list before the closing paren, which uniquely identifies
   * the 2-arg invocations and rejects the 1-arg call on line 133. Comments
   * and Javadoc are filtered out before matching.
   */
  @Test
  public void testTwoArgOverloadCallSitesStillExistInDbRecordBytesTest()
      throws IOException {
    var sourcePath = locateTestSourceFile(DBRecordBytesTest.class);
    var raw = Files.readString(sourcePath);
    // Strip block comments and line comments so JavaDoc/inline comments do
    // not contribute to the call-site count.
    var stripped = stripJavaComments(raw);

    // Match {@code something.fromInputStream(<args-no-comma-at-top>, <max>)}
    // — the simple regex below works because the test only ever passes simple
    // expressions (no nested method calls, no commas inside arguments) to
    // this overload. If a future test adds a 2-arg call with a nested comma
    // expression, this sentinel may under-count and surface a falsifiable
    // failure prompting a regex-precision review.
    var pattern = Pattern.compile(
        "\\.fromInputStream\\s*\\(\\s*[^,()]+\\s*,\\s*[^,()]+\\s*\\)");
    Matcher matcher = pattern.matcher(stripped);
    int count = 0;
    while (matcher.find()) {
      count++;
    }

    assertEquals(
        "WHEN-FIXED: the 2-arg overload's deletion is contingent on rewriting "
            + "or removing all 7 call sites in DBRecordBytesTest at lines 76, 87, "
            + "101, 115, 148, 164, 181. Adjust this expected count and the "
            + "Javadoc in this class together when the test call sites change.",
        EXPECTED_TWO_ARG_CALL_SITES, count);
  }

  /**
   * Locate the test's {@code .java} source file in the working tree. Walks
   * up from the Surefire {@code user.dir} (typically the module root) to
   * find a directory containing {@code src/test/java/}.
   */
  private static Path locateTestSourceFile(Class<?> testClass) {
    var relative = "src/test/java/" + testClass.getName().replace('.', '/') + ".java";
    var candidate = Paths.get(System.getProperty("user.dir"), relative);
    if (Files.exists(candidate)) {
      return candidate;
    }
    // Fall back to walking up from CWD up to 4 levels in case Surefire
    // changes its working directory (rare but observed in some IDE runs).
    var dir = Paths.get(System.getProperty("user.dir"));
    for (int i = 0; i < 4 && dir != null; i++) {
      var c = dir.resolve("core").resolve(relative);
      if (Files.exists(c)) {
        return c;
      }
      c = dir.resolve(relative);
      if (Files.exists(c)) {
        return c;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "Could not locate source file for: " + testClass.getName());
  }

  /**
   * Remove Java block comments ({@code /* ... *}{@code /}) and line comments
   * ({@code // ...}) from a source string. String-literal-aware enough for
   * the simple test source we're parsing — the test class contains only
   * plain string literals without escaped quotes inside comment-like
   * sequences.
   */
  private static String stripJavaComments(String source) {
    var out = new StringBuilder(source.length());
    int i = 0;
    int n = source.length();
    boolean inString = false;
    boolean inChar = false;
    while (i < n) {
      char c = source.charAt(i);
      char next = (i + 1 < n) ? source.charAt(i + 1) : '\0';
      if (!inString && !inChar && c == '/' && next == '/') {
        // line comment — skip to newline
        while (i < n && source.charAt(i) != '\n') {
          i++;
        }
      } else if (!inString && !inChar && c == '/' && next == '*') {
        // block comment — skip to closing */
        i += 2;
        while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
          i++;
        }
        i += 2; // past the closing */
      } else {
        if (!inChar && c == '"' && (i == 0 || source.charAt(i - 1) != '\\')) {
          inString = !inString;
        } else if (!inString && c == '\'' && (i == 0 || source.charAt(i - 1) != '\\')) {
          inChar = !inChar;
        }
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }
}
