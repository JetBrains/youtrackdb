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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Standalone unit tests for {@link FieldTypesString} — the field-type-letter helper that the
 * legacy CSV record serializer used to encode per-field type hints in the {@code @fieldTypes}
 * attribute.
 *
 * <p>The class has two parallel char→type mappings:
 * {@link FieldTypesString#getType(String, char)} (which length-discriminates between BYTE and
 * BINARY for the {@code 'b'} suffix) and {@link FieldTypesString#getOTypeFromChar(char)} (which
 * always returns BINARY). The two parsers ({@code loadFieldTypesV0},
 * {@code loadFieldTypes}) split a comma-separated {@code field=letter} string into a map.
 *
 * <p>Tests use single-letter cases (rather than the more compact loop-over-chars idiom) to make
 * each char-to-type binding individually falsifiable: a regression that swaps any two letters
 * would fail exactly the affected tests.
 */
public class FieldTypesStringTest {

  // ---------------------------------------------------------------- getType

  @Test
  public void getTypeReturnsFloatForLetterF() {
    assertEquals(PropertyTypeInternal.FLOAT, FieldTypesString.getType("0", 'f'));
  }

  @Test
  public void getTypeReturnsDecimalForLetterC() {
    assertEquals(PropertyTypeInternal.DECIMAL, FieldTypesString.getType("0", 'c'));
  }

  @Test
  public void getTypeReturnsLongForLetterL() {
    assertEquals(PropertyTypeInternal.LONG, FieldTypesString.getType("0", 'l'));
  }

  @Test
  public void getTypeReturnsDoubleForLetterD() {
    assertEquals(PropertyTypeInternal.DOUBLE, FieldTypesString.getType("0", 'd'));
  }

  /** {@code 'b'} on a 1-char value resolves to BYTE per the length-discriminated branch. */
  @Test
  public void getTypeReturnsByteForLetterBOnOneCharValue() {
    assertEquals(PropertyTypeInternal.BYTE, FieldTypesString.getType("1", 'b'));
  }

  /** {@code 'b'} on a 3-char value still resolves to BYTE (upper inclusive boundary). */
  @Test
  public void getTypeReturnsByteForLetterBOnThreeCharValue() {
    assertEquals(PropertyTypeInternal.BYTE, FieldTypesString.getType("123", 'b'));
  }

  /** {@code 'b'} on a 4-char value flips to BINARY (just past the BYTE upper boundary). */
  @Test
  public void getTypeReturnsBinaryForLetterBOnFourCharValue() {
    assertEquals(PropertyTypeInternal.BINARY, FieldTypesString.getType("1234", 'b'));
  }

  /**
   * {@code 'b'} on an empty value goes to BINARY because the inclusive lower-bound check
   * {@code length() >= 1} rejects empties. This pins the documented behavior so a regression
   * that removed the lower bound (e.g., changed to {@code length() <= 3}) would be caught.
   */
  @Test
  public void getTypeReturnsBinaryForLetterBOnEmptyValue() {
    assertEquals(PropertyTypeInternal.BINARY, FieldTypesString.getType("", 'b'));
  }

  @Test
  public void getTypeReturnsDateForLetterA() {
    assertEquals(PropertyTypeInternal.DATE, FieldTypesString.getType("0", 'a'));
  }

  @Test
  public void getTypeReturnsDatetimeForLetterT() {
    assertEquals(PropertyTypeInternal.DATETIME, FieldTypesString.getType("0", 't'));
  }

  @Test
  public void getTypeReturnsShortForLetterS() {
    assertEquals(PropertyTypeInternal.SHORT, FieldTypesString.getType("0", 's'));
  }

  @Test
  public void getTypeReturnsEmbeddedSetForLetterE() {
    assertEquals(PropertyTypeInternal.EMBEDDEDSET, FieldTypesString.getType("0", 'e'));
  }

  @Test
  public void getTypeReturnsLinkBagForLetterG() {
    assertEquals(PropertyTypeInternal.LINKBAG, FieldTypesString.getType("0", 'g'));
  }

  @Test
  public void getTypeReturnsLinkListForLetterZ() {
    assertEquals(PropertyTypeInternal.LINKLIST, FieldTypesString.getType("0", 'z'));
  }

  @Test
  public void getTypeReturnsLinkMapForLetterM() {
    assertEquals(PropertyTypeInternal.LINKMAP, FieldTypesString.getType("0", 'm'));
  }

  @Test
  public void getTypeReturnsLinkForLetterX() {
    assertEquals(PropertyTypeInternal.LINK, FieldTypesString.getType("0", 'x'));
  }

  @Test
  public void getTypeReturnsLinkSetForLetterN() {
    assertEquals(PropertyTypeInternal.LINKSET, FieldTypesString.getType("0", 'n'));
  }

  /** Unrecognized letter falls through to STRING. Any other behavior is a regression. */
  @Test
  public void getTypeFallsBackToStringForUnknownLetter() {
    assertEquals(PropertyTypeInternal.STRING, FieldTypesString.getType("0", 'q'));
  }

  /** Capitalization is significant — uppercase letters are NOT recognized. */
  @Test
  public void getTypeIsCaseSensitiveAndUppercaseFallsBackToString() {
    assertEquals(PropertyTypeInternal.STRING, FieldTypesString.getType("0", 'F'));
    assertEquals(PropertyTypeInternal.STRING, FieldTypesString.getType("0", 'L'));
    assertEquals(PropertyTypeInternal.STRING, FieldTypesString.getType("0", 'B'));
  }

  // -------------------------------------------------------- getOTypeFromChar
  //
  // getOTypeFromChar is the value-length-agnostic companion: it always returns BINARY for 'b'
  // (no length check) and otherwise mirrors getType.

  @Test
  public void getOTypeFromCharReturnsBinaryForLetterBRegardlessOfLengthBranch() {
    // Different from getType: no length parameter, no BYTE branch.
    assertEquals(PropertyTypeInternal.BINARY, FieldTypesString.getOTypeFromChar('b'));
  }

  @Test
  public void getOTypeFromCharLetterBindingsMatchGetType() {
    assertEquals(PropertyTypeInternal.FLOAT, FieldTypesString.getOTypeFromChar('f'));
    assertEquals(PropertyTypeInternal.DECIMAL, FieldTypesString.getOTypeFromChar('c'));
    assertEquals(PropertyTypeInternal.LONG, FieldTypesString.getOTypeFromChar('l'));
    assertEquals(PropertyTypeInternal.DOUBLE, FieldTypesString.getOTypeFromChar('d'));
    assertEquals(PropertyTypeInternal.DATE, FieldTypesString.getOTypeFromChar('a'));
    assertEquals(PropertyTypeInternal.DATETIME, FieldTypesString.getOTypeFromChar('t'));
    assertEquals(PropertyTypeInternal.SHORT, FieldTypesString.getOTypeFromChar('s'));
    assertEquals(PropertyTypeInternal.EMBEDDEDSET, FieldTypesString.getOTypeFromChar('e'));
    assertEquals(PropertyTypeInternal.LINKBAG, FieldTypesString.getOTypeFromChar('g'));
    assertEquals(PropertyTypeInternal.LINKLIST, FieldTypesString.getOTypeFromChar('z'));
    assertEquals(PropertyTypeInternal.LINKMAP, FieldTypesString.getOTypeFromChar('m'));
    assertEquals(PropertyTypeInternal.LINK, FieldTypesString.getOTypeFromChar('x'));
    assertEquals(PropertyTypeInternal.LINKSET, FieldTypesString.getOTypeFromChar('n'));
  }

  @Test
  public void getOTypeFromCharFallsBackToStringForUnknownLetter() {
    assertEquals(PropertyTypeInternal.STRING, FieldTypesString.getOTypeFromChar('q'));
  }

  // ----------------------------------------------------------- loadFieldTypes

  @Test
  public void loadFieldTypesParsesSingleEntry() {
    final Map<String, Character> result = FieldTypesString.loadFieldTypes("foo=l");
    assertEquals(1, result.size());
    assertEquals(Character.valueOf('l'), result.get("foo"));
  }

  @Test
  public void loadFieldTypesParsesMultipleEntries() {
    final Map<String, Character> result = FieldTypesString.loadFieldTypes("a=f,b=l,c=d");
    assertEquals(3, result.size());
    assertEquals(Character.valueOf('f'), result.get("a"));
    assertEquals(Character.valueOf('l'), result.get("b"));
    assertEquals(Character.valueOf('d'), result.get("c"));
  }

  /**
   * Entries without a {@code =} are silently dropped — pinned to catch a regression that
   * either threw or accepted partial entries.
   */
  @Test
  public void loadFieldTypesSilentlyDropsEntriesWithoutEqualsSign() {
    final Map<String, Character> result = FieldTypesString.loadFieldTypes("a=f,broken,c=l");
    assertEquals(2, result.size());
    assertEquals(Character.valueOf('f'), result.get("a"));
    assertEquals(Character.valueOf('l'), result.get("c"));
    assertTrue("broken-entry must NOT appear as a key", !result.containsKey("broken"));
  }

  /**
   * Empty input still produces a map (never null) — useful for callers that unconditionally
   * iterate the result.
   */
  @Test
  public void loadFieldTypesEmptyInputReturnsEmptyMap() {
    final Map<String, Character> result = FieldTypesString.loadFieldTypes("");
    assertTrue(result.isEmpty());
  }

  /**
   * The V0 overload is the workhorse; the wrapper {@code loadFieldTypes} just allocates a
   * fresh map and delegates. When the supplied map is non-null, V0 must mutate it in place
   * and return it.
   */
  @Test
  public void loadFieldTypesV0MutatesSuppliedMapInPlace() {
    final Map<String, Character> supplied = new HashMap<>();
    supplied.put("preexisting", 'x');
    final Map<String, Character> returned =
        FieldTypesString.loadFieldTypesV0(supplied, "a=f,b=l");

    assertEquals("returned reference must be the supplied map", supplied, returned);
    assertEquals(3, supplied.size());
    assertEquals(Character.valueOf('x'), supplied.get("preexisting"));
    assertEquals(Character.valueOf('f'), supplied.get("a"));
    assertEquals(Character.valueOf('l'), supplied.get("b"));
  }

  /**
   * The V0 overload allocates a fresh map when supplied with {@code null} — pins the
   * lazy-allocation branch.
   */
  @Test
  public void loadFieldTypesV0AllocatesMapWhenSuppliedIsNull() {
    final Map<String, Character> result = FieldTypesString.loadFieldTypesV0(null, "a=f");
    assertEquals(1, result.size());
    assertEquals(Character.valueOf('f'), result.get("a"));
  }

  /**
   * Sanity check on the exposed attribute name constant. A regression that renamed the
   * attribute would silently break legacy {@code @fieldTypes} reads — pin the literal.
   */
  @Test
  public void attributeFieldTypesConstantIsExpectedLiteral() {
    assertEquals("@fieldTypes", FieldTypesString.ATTRIBUTE_FIELD_TYPES);
  }

  /**
   * Trailing comma is preserved by {@link String#split(String, int)} with a {@code -1} limit,
   * resulting in an empty final entry that the parser silently drops via the
   * {@code part.length == 2} guard. Pinned so a switch to limit {@code 0} would be caught.
   */
  @Test
  public void loadFieldTypesTrailingCommaIsTolerated() {
    final Map<String, Character> result = FieldTypesString.loadFieldTypes("a=f,");
    assertEquals(1, result.size());
    assertEquals(Character.valueOf('f'), result.get("a"));
    assertNull(result.get(""));
  }
}
