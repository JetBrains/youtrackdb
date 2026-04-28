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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Dead-code pin tests for {@link JSONWriter}. Cross-module grep performed during this track's
 * Phase A confirmed zero non-self callers anywhere in the repository:
 *
 * <pre>
 *   grep -rn 'JSONWriter' --include='*.java' core server driver embedded
 *       gremlin-annotations tests test-commons docker-tests
 *     -- only matches are JSONWriter.java itself
 * </pre>
 *
 * <p>Despite living in the same package as the live {@link StringSerializerHelper} and
 * {@link JSONReader}, {@code JSONWriter} (511 LOC) has no production caller and is shadowed by
 * the live Jackson-based path ({@code JSONSerializerJackson}). Every test below exercises a
 * falsifiable observable so a regression that turns one of the public methods into a no-op or
 * changes its return shape will fail; the static-import + direct-call sites also act as a
 * compile-time pin so removing the class fails this test's compilation, exactly the loud signal
 * the final-sweep deletion needs.
 *
 * <p>WHEN-FIXED: delete {@link JSONWriter} (zero callers; superseded by
 * {@code JSONSerializerJackson}). The sibling {@code JSONReader} stays — it has one production
 * caller in {@code DatabaseImport} and is covered by Step 4 of this track.
 */
public class JSONWriterDeadCodeTest {

  // ---------------------------------------------------------------------------
  // Static helpers — encode / mapToJSON / writeValue(Object,...)
  // ---------------------------------------------------------------------------

  @Test
  public void encodeReturnsInputForNonEscapedAscii() {
    // Pin: encode is a passthrough for plain ASCII without quote/backslash characters.
    final var input = "plain-text";
    assertEquals(input, JSONWriter.encode(input));
  }

  @Test
  public void encodeEscapesQuoteCharacter() {
    // The encoder must escape embedded double quotes; a regression that returned the input
    // verbatim would break JSON output for strings containing quotes.
    final var encoded = (String) JSONWriter.encode("a\"b");
    assertTrue("must escape quote", encoded.contains("\\\""));
    assertFalse("must not contain raw unescaped quote",
        encoded.replace("\\\"", "").contains("\""));
  }

  @Test
  public void encodeEscapesBackslashCharacter() {
    final var encoded = (String) JSONWriter.encode("a\\b");
    assertTrue("must escape backslash", encoded.contains("\\\\"));
  }

  @Test
  public void encodePreservesNonStringObjectIdentityForNumbers() {
    // Pin: numbers fall through unchanged (the encode method is intended for string escape).
    final var n = Integer.valueOf(42);
    assertSame(n, JSONWriter.encode(n));
  }

  @Test
  public void mapToJsonEmitsBracesAroundEntries() throws IOException {
    final var buffer = new StringBuilder();
    final Map<Object, Object> map = new LinkedHashMap<>();
    map.put("k", "v");
    JSONWriter.mapToJSON(null, map, "", buffer);
    final var json = buffer.toString();
    assertTrue("braces present: " + json, json.startsWith("{") && json.endsWith("}"));
    assertTrue("key emitted: " + json, json.contains("\"k\""));
    assertTrue("value emitted: " + json, json.contains("\"v\""));
  }

  // ---------------------------------------------------------------------------
  // Instance API — pretty-print flag + fluent begin/end + close
  // ---------------------------------------------------------------------------

  @Test
  public void prettyPrintDefaultsToFalse() throws IOException {
    final var sw = new StringWriter();
    final var w = new JSONWriter(sw);
    assertFalse(w.isPrettyPrint());
    w.close();
  }

  @Test
  public void setPrettyPrintTogglesFlagAndReturnsSelfForChaining() throws IOException {
    final var sw = new StringWriter();
    final var w = new JSONWriter(sw);
    final var ret = w.setPrettyPrint(true);
    assertSame("fluent API must return self", w, ret);
    assertTrue(w.isPrettyPrint());
    w.close();
  }

  @Test
  public void beginObjectAndEndObjectEmitMatchingBraces() throws IOException {
    final var sw = new StringWriter();
    final var w = new JSONWriter(sw);
    w.beginObject();
    w.endObject();
    w.close();
    final var out = sw.toString();
    assertEquals("expected exactly one {, got: " + out,
        1, out.length() - out.replace("{", "").length());
    assertEquals("expected exactly one }, got: " + out,
        1, out.length() - out.replace("}", "").length());
  }

  @Test
  public void appendForwardsTextVerbatim() throws IOException {
    final var sw = new StringWriter();
    final var w = new JSONWriter(sw);
    final var ret = w.append("hello");
    assertSame("fluent API must return self", w, ret);
    w.close();
    assertEquals("hello", sw.toString());
  }

  @Test
  public void resetAttributesIsCallableAfterConstruction() throws IOException {
    // Defensive pin — resetAttributes() is part of the public surface; it must not throw.
    final var sw = new StringWriter();
    final var w = new JSONWriter(sw);
    w.resetAttributes();
    w.close();
  }

  @Test
  public void newlineEmitsCarriageReturnAndLineFeedWhenPrettyPrintEnabled() throws IOException {
    // newline() is a no-op unless the prettyPrint flag is enabled — pin both arms of the
    // branch: with pretty-print on, a CR-LF pair is appended.
    final var sw = new StringWriter();
    final var w = new JSONWriter(sw);
    w.setPrettyPrint(true);
    w.newline();
    w.close();
    assertTrue("expected CR-LF in output, got: " + sw.toString().replace("\r", "<CR>")
        .replace("\n", "<LF>"),
        sw.toString().contains("\r\n"));
  }

  @Test
  public void newlineIsNoOpWhenPrettyPrintDisabled() throws IOException {
    final var sw = new StringWriter();
    final var w = new JSONWriter(sw);
    w.newline();
    w.close();
    assertEquals("newline() must not emit anything when pretty-print is off", "", sw.toString());
  }

  // ---------------------------------------------------------------------------
  // Constructor variants
  // ---------------------------------------------------------------------------

  @Test
  public void constructorWithoutFormatYieldsNonNullWriter() {
    final var w = new JSONWriter(new StringWriter());
    assertNotNull(w);
  }

  @Test
  public void constructorWithEmptyFormatStillConstructs() {
    // The empty-format path skips the prettyPrint enable but does not throw — the constructor
    // calls iJsonFormat.contains("prettyPrint"), which on an empty string returns false.
    final var w = new JSONWriter(new StringWriter(), "");
    assertNotNull(w);
    assertFalse(w.isPrettyPrint());
  }

  @Test
  public void constructorRejectsNullFormatWithNullPointerException() {
    // Pin: the two-arg constructor reads iJsonFormat.contains("prettyPrint") without a null
    // guard, so passing null currently NPEs at construction. Make this contract explicit so
    // a future hardening that adds a null guard becomes loud here.
    assertThrows(
        NullPointerException.class, () -> new JSONWriter(new StringWriter(), null));
  }

  @Test
  public void constructorWithPrettyPrintFormatTokenEnablesFlag() {
    // Pin the format-token detection: the substring "prettyPrint" anywhere in the format
    // string flips the flag on. A regression that broke the substring match (e.g., switched
    // to startsWith) would be caught here.
    final var w = new JSONWriter(new StringWriter(), "indent:2,prettyPrint:true");
    assertTrue(w.isPrettyPrint());
  }

  // ---------------------------------------------------------------------------
  // Static encode null handling
  // ---------------------------------------------------------------------------

  @Test
  public void encodeReturnsNullForNullInput() {
    assertNull(JSONWriter.encode(null));
  }

  // ---------------------------------------------------------------------------
  // Reflective drift detector — guards against partial-deletion regressions on
  // the writing API (writeValue, writeRecord, writeAttribute, writeObjects,
  // beginCollection, endCollection, flush, close, write, writeValue instance,
  // beginObject overloads, endObject overloads). The behavioural pins above
  // exercise about half of the public surface; this test pins the rest by
  // declared-method-name set so that a deletion of any one fails here too.
  // ---------------------------------------------------------------------------

  @Test
  public void declaredPublicMethodsAreExactlyTheKnownDeadSet() {
    final Set<String> expected = Set.of(
        // covered by behavioural tests above
        "encode", "mapToJSON", "isPrettyPrint", "setPrettyPrint", "beginObject", "endObject",
        "append", "resetAttributes", "newline",
        // additional public surface pinned only by name (writing API)
        "writeValue", "writeRecord", "writeAttribute", "writeObjects",
        "beginCollection", "endCollection", "flush", "close", "write");
    final Set<String> actual = new HashSet<>();
    for (final Method m : JSONWriter.class.getDeclaredMethods()) {
      if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
        actual.add(m.getName());
      }
    }
    assertEquals(
        "JSONWriter public method set drifted; update the pin set above and add a behavioural"
            + " test for any genuinely new entry point",
        expected, actual);
  }
}
