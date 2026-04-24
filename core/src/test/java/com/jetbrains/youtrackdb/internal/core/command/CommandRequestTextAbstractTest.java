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
package com.jetbrains.youtrackdb.internal.core.command;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.MemoryStream;
import java.util.List;
import javax.annotation.Nonnull;
import org.junit.Test;

/**
 * Standalone unit tests for {@link CommandRequestTextAbstract}. The only production subclass is
 * {@link com.jetbrains.youtrackdb.internal.core.command.script.CommandScript}, which is dead code
 * pinned for Track 22 deletion — so this suite uses a local {@link StubTextRequest} to cover the
 * text-handling contract without depending on the dead subclass.
 *
 * <p>The {@code fromStream(session, ..., RecordSerializerNetwork)} / {@code toStream(session,
 * RecordSerializerNetwork)} public overloads take a {@link
 * com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork}
 * which has zero concrete implementations in {@code core} (see Track 9 Step 1 episode). Round-trip
 * coverage is therefore not feasible today; pinned in the dead-code tests. This test still
 * exercises the internal protected {@code toStream(MemoryStream, session)} empty-parameters path,
 * which is the only stream path that does not require a serializer or session.
 */
public class CommandRequestTextAbstractTest {

  // ---------------------------------------------------------------------------
  // Minimal concrete subclass used only to instantiate the abstract class.
  // Both the no-arg and text-arg constructors must be reachable from a subclass
  // because they are protected on the abstract parent.
  // ---------------------------------------------------------------------------

  /**
   * Minimal subclass of {@link CommandRequestTextAbstract}. execute is a no-op; idempotence is
   * fixed to {@code true}. A package-visible wrapper exposes the protected
   * {@code toStream(MemoryStream, session)} so tests can drive the empty-parameters branch
   * without needing a live RecordSerializerNetwork.
   */
  static final class StubTextRequest extends CommandRequestTextAbstract {

    StubTextRequest() {
      super();
    }

    StubTextRequest(String iText) {
      super(iText);
    }

    @Override
    public List<EntityImpl> execute(@Nonnull DatabaseSessionEmbedded querySession,
        Object... iArgs) {
      return List.of();
    }

    @Override
    public boolean isIdempotent() {
      return true;
    }

    /**
     * Exposes the protected {@code toStream(MemoryStream, session)} so the empty-parameters
     * branch at lines 92-96 can be exercised without a serializer.
     */
    byte[] exposeInnerToStream(MemoryStream buffer, DatabaseSessionEmbedded session) {
      return toStream(buffer, session);
    }
  }

  // ---------------------------------------------------------------------------
  // Constructors — null text rejection, trim on non-null.
  // Source: CommandRequestTextAbstract.java:45-54.
  // ---------------------------------------------------------------------------

  /**
   * The text-accepting constructor rejects {@code null} with {@link IllegalArgumentException} to
   * prevent silent-empty scripts from propagating downstream (line 49-51).
   */
  @Test
  public void constructorRejectsNullText() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new StubTextRequest(null));
    assertEquals("Text cannot be null", ex.getMessage());
  }

  /**
   * Non-null text is trimmed on construction (line 53): leading and trailing whitespace (including
   * newlines and tabs) must be removed. Internal whitespace must survive.
   */
  @Test
  public void constructorTrimsNonNullText() {
    var req = new StubTextRequest("  \tselect from V\n ");
    assertEquals("select from V", req.getText());
  }

  /**
   * Empty-string text is accepted (the guard only rejects {@code null}). After trim the text is
   * still the empty string.
   */
  @Test
  public void constructorAcceptsEmptyString() {
    var req = new StubTextRequest("");
    assertEquals("", req.getText());
  }

  /**
   * TC6 iter-2 boundary pin: whitespace-only input ({@code "   \t\n   "}) trims to the empty
   * string — same observable shape as the empty-string case, but via a different production
   * code path (trim actually strips chars). Pin this equivalence so a future refactor that
   * replaced {@code trim()} with {@code strip()} (Unicode-aware) is a deliberate change.
   */
  @Test
  public void constructorWhitespaceOnlyTextTrimsToEmptyString() {
    var req = new StubTextRequest("   \t\n\r   ");
    assertEquals(
        "whitespace-only input must trim to the empty string via String.trim()",
        "",
        req.getText());
  }

  /**
   * TC6 iter-2 boundary pin: {@link String#trim()} only strips characters {@code <= U+0020}
   * (ASCII whitespace), NOT Unicode whitespace like the non-breaking space {@code U+00A0}.
   * Pin the current ASCII-only-trim semantics so a future migration to {@link String#strip()}
   * (Unicode-aware) is a deliberate, visible change. A regression that accidentally switched
   * to {@code strip()} would fail this assertion by returning {@code "select"} (fully stripped).
   */
  @Test
  public void constructorPreservesUnicodeNonBreakingSpaceBecauseTrimIsAsciiOnly() {
    var req = new StubTextRequest(" select ");
    assertEquals(
        "String.trim() strips only chars <= U+0020; non-breaking space (U+00A0) survives",
        " select ",
        req.getText());
  }

  /**
   * The no-arg constructor leaves {@code text} at its default {@code null} and does not throw.
   * It exists to support lazy-init by subclasses that need to set text after construction.
   */
  @Test
  public void noArgConstructorLeavesTextNull() {
    var req = new StubTextRequest();
    assertEquals("no-arg ctor leaves text null (subclasses must call setText)",
        null, req.getText());
  }

  // ---------------------------------------------------------------------------
  // setText — round-trip, returns this for chaining.
  // Source: CommandRequestTextAbstract.java:62-66.
  // ---------------------------------------------------------------------------

  /**
   * {@code setText} stores the argument verbatim (no trim — only the constructor trims) and
   * returns this for chaining.
   */
  @Test
  public void setTextStoresVerbatimAndReturnsThisForChaining() {
    var req = new StubTextRequest("initial");
    var returned = req.setText("  replacement  ");
    assertSame("setText must return this for chaining", req, returned);
    assertEquals("setText does NOT trim (contrast with constructor)",
        "  replacement  ", req.getText());
  }

  /**
   * {@code setText(null)} replaces the current text with {@code null} (no null-guard exists on
   * the setter — only the text-accepting constructor rejects null).
   */
  @Test
  public void setTextAcceptsNull() {
    var req = new StubTextRequest("was-set");
    req.setText(null);
    assertEquals(null, req.getText());
  }

  // ---------------------------------------------------------------------------
  // toString — contract is "?." + text.
  // Source: CommandRequestTextAbstract.java:85-87.
  // ---------------------------------------------------------------------------

  /**
   * {@code toString} returns {@code "?." + text}. Pin the exact prefix so any format change is
   * caught — downstream logging / debugging relies on this shape.
   */
  @Test
  public void toStringUsesQuestionDotPrefix() {
    var req = new StubTextRequest("select from V");
    assertEquals("?.select from V", req.toString());
  }

  /**
   * {@code toString} with the no-arg ctor appends the {@code null} text literally (Java's
   * {@code StringBuilder} concatenates null as the string {@code "null"}).
   */
  @Test
  public void toStringWithNullTextAppendsLiteralNull() {
    var req = new StubTextRequest();
    assertEquals("?.null", req.toString());
  }

  // ---------------------------------------------------------------------------
  // toStream(MemoryStream, session) — empty-parameters path.
  // Source: CommandRequestTextAbstract.java:89-125.
  //
  // When parameters is null (default) the method writes:
  //   - UTF-8 text
  //   - false (simple params absent)
  //   - false (composite keys absent)
  // and returns the bytes. Reads round-trip via MemoryStream back to verify the on-the-wire shape.
  // ---------------------------------------------------------------------------

  /**
   * {@code toStream} with no parameters writes the text followed by two boolean flags set to
   * {@code false}. This pins the empty-parameters branch (line 92-96) which is the only stream
   * branch testable without a live {@code RecordSerializerNetwork} — the branches at lines
   * 97-122 require {@code session.newEmbeddedEntity()}.
   */
  @Test
  public void toStreamWithoutParametersWritesTextAndTwoFalseFlags() {
    var req = new StubTextRequest("select from V");
    var buffer = new MemoryStream();

    var bytes = req.exposeInnerToStream(buffer, null);

    // Round-trip: read from the produced bytes.
    var reader = new MemoryStream(bytes);
    assertEquals("first field is the UTF-8 text", "select from V", reader.getAsString());
    assertEquals("simple-params flag must be false", false, reader.getAsBoolean());
    assertEquals("composite-key flag must be false", false, reader.getAsBoolean());
  }

  /**
   * Passing a freshly-allocated {@link MemoryStream} produces the same bytes as the default
   * returned buffer — {@code toByteArray} captures the full serialized form.
   */
  @Test
  public void toStreamProducesStableByteArray() {
    var req = new StubTextRequest("x");

    var first = req.exposeInnerToStream(new MemoryStream(), null);
    var second = req.exposeInnerToStream(new MemoryStream(), null);

    assertArrayEquals("repeated serialization must be deterministic", first, second);
  }
}
