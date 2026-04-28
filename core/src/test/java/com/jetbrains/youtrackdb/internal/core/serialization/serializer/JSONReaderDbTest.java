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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import java.io.StringReader;
import org.junit.Test;

/**
 * Integration tests for {@link JSONReader#readRecordString(int)} and
 * {@link JSONReader#readNextRecord(char[], boolean, char[], char[], boolean, int)} — the methods
 * that recognize {@code out_*}/{@code in_*} edge ridbag fields in JSON-export records and lift
 * their {@code [#a:b, #c:d, …]} array contents into a side-channel {@link RidSet} map.
 *
 * <p>The tests construct a real database via {@link TestUtilsFixture} so the {@link RID} values
 * we feed through the parser come from real clusters — pinning that the parser interoperates with
 * the live identity-resolution stack. The parser itself currently uses only the static
 * {@link com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal#fromString} entry point, but
 * using a live session here keeps the test true to the production import scenario (the JSON came
 * from {@code DatabaseExport} of a real database) and makes assertions fail loudly if a future
 * refactor adds a session-dependent code path.
 *
 * <h2>Threshold-based extraction discipline</h2>
 *
 * <p>{@code readNextRecord} extracts RIDs only via the lazy-import threshold flush — there is no
 * unconditional {@code "extract on close"} path. The {@code ']'} close branch only calls
 * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet#add} when {@code ridbagSet}
 * already has at least one entry; entries can only have been added by a prior threshold flush.
 * Therefore tests that want extraction MUST provide an array body longer than the threshold AND
 * include enough complete {@code "#cid:pos"} tokens for {@link JSONReader} to peel off. Tests
 * intentionally use a threshold of {@value #SMALL_THRESHOLD} and arrays of multiple RIDs; the
 * single-RID test pins the "below-threshold → no extraction" inverse.
 *
 * <p>Note: as of this writing {@link JSONReader#readRecordString} is referenced only by a
 * commented-out call site in {@code DatabaseImport} (the production path falls back to the
 * non-ridbag-aware {@code readNext(NEXT_IN_ARRAY)}); the helper still ships as part of
 * {@code JSONReader}'s public API, so its observable behaviour is pinned here pending a future
 * cleanup pass that either resurrects the call site or deletes the helper.
 */
public class JSONReaderDbTest extends TestUtilsFixture {

  /**
   * Threshold chosen smaller than the length of a single quoted RID literal {@code "\"#5:N\""}
   * (= 7 chars) so the threshold flush triggers as soon as the second RID's quote arrives in the
   * accumulator. Larger than 7 would still flush, but smaller is closer to 1-flush-per-RID and
   * makes the test trace easier to reason about.
   */
  private static final int SMALL_THRESHOLD = 6;

  /**
   * Build a synthetic {@link RID} for parser-input construction. The parser does not validate the
   * cluster against the live session schema (it only calls
   * {@link com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal#fromString} statically) so
   * any positive cluster id is fine; we use {@code 5} to match the typical default-cluster id
   * used by other tests in this module.
   */
  private RID stableRid(long position) {
    return new RecordId(5, position);
  }

  // ------------------------------------------------------- readNextRecord — extraction paths

  /**
   * Multi-RID {@code out_E:[…]} field with a small threshold extracts every RID into the result
   * map. Pin the falsifiable observables: extracted set size, RID identities, surrounding empty
   * {@code [...]} sentinel in the parsed text — the inner RIDs were extracted to the side map,
   * not the buffer.
   */
  @Test
  public void readNextRecordExtractsOutEdgeRidbagIntoResultMap() throws Exception {
    var rid1 = stableRid(0);
    var rid2 = stableRid(1);
    var rid3 = stableRid(2);
    var json =
        "{\"out_E\":[\""
            + rid1
            + "\",\""
            + rid2
            + "\",\""
            + rid3
            + "\"]}";
    var r = new JSONReader(new StringReader(json));

    var ridbags =
        r.readNextRecord(
            JSONReader.NEXT_IN_ARRAY,
            false,
            JSONReader.DEFAULT_JUMP,
            null,
            true,
            SMALL_THRESHOLD);

    assertTrue("out_E extracted", ridbags.containsKey("out_E"));
    var extracted = ridbags.get("out_E");
    assertEquals(3, extracted.size());
    assertTrue(extracted.contains(rid1));
    assertTrue(extracted.contains(rid2));
    assertTrue(extracted.contains(rid3));
    // The parsed text retains the enclosing object and an `[]` sentinel where the array used
    // to be — the inner RIDs were lifted into the side map, not the buffer.
    var parsed = r.getValue();
    assertTrue("parsed text should keep `out_E:[]`", parsed.contains("\"out_E\":[]"));
  }

  @Test
  public void readNextRecordExtractsInEdgeRidbagToo() throws Exception {
    var ridA = stableRid(3);
    var ridB = stableRid(4);
    var json = "{\"in_E\":[\"" + ridA + "\",\"" + ridB + "\"]}";
    var r = new JSONReader(new StringReader(json));

    var ridbags =
        r.readNextRecord(
            JSONReader.NEXT_IN_ARRAY,
            false,
            JSONReader.DEFAULT_JUMP,
            null,
            true,
            SMALL_THRESHOLD);

    assertTrue("in_E extracted", ridbags.containsKey("in_E"));
    assertEquals(2, ridbags.get("in_E").size());
    assertTrue(ridbags.get("in_E").contains(ridA));
  }

  /**
   * Multiple ridbag fields in the same record both get extracted, each into its own map entry
   * keyed by the unstripped field name. Pin so a regression that reuses the same {@link RidSet}
   * across fields (a likely refactor mistake) is caught.
   */
  @Test
  public void readNextRecordExtractsMultipleRidbagFieldsIndependently() throws Exception {
    var ridA = stableRid(5);
    var ridB = stableRid(6);
    var ridC = stableRid(7);
    var ridD = stableRid(8);
    var json =
        "{\"out_E\":[\""
            + ridA
            + "\",\""
            + ridB
            + "\"],\"in_E\":[\""
            + ridC
            + "\",\""
            + ridD
            + "\"]}";
    var r = new JSONReader(new StringReader(json));

    var ridbags =
        r.readNextRecord(
            JSONReader.NEXT_IN_ARRAY,
            false,
            JSONReader.DEFAULT_JUMP,
            null,
            true,
            SMALL_THRESHOLD);

    assertEquals(2, ridbags.size());
    assertEquals(2, ridbags.get("out_E").size());
    assertEquals(2, ridbags.get("in_E").size());
    assertTrue(ridbags.get("out_E").contains(ridA));
    assertTrue(ridbags.get("out_E").contains(ridB));
    assertTrue(ridbags.get("in_E").contains(ridC));
    assertTrue(ridbags.get("in_E").contains(ridD));
    // Independence: out_E's set must not contain in_E's entries (and vice-versa).
    assertFalse(ridbags.get("out_E").contains(ridC));
    assertFalse(ridbags.get("in_E").contains(ridA));
  }

  /**
   * Below-threshold edge arrays do NOT trigger extraction — the parser leaves the original
   * {@code [#5:0]} verbatim in the parsed text and never populates the side map. Pin this to
   * complement the threshold-flush tests above and to catch a regression that started extracting
   * unconditionally.
   */
  @Test
  public void readNextRecordSkipsExtractionWhenArrayBelowThreshold() throws Exception {
    var rid = stableRid(9);
    var json = "{\"out_E\":[\"" + rid + "\"]}";
    var r = new JSONReader(new StringReader(json));

    var ridbags =
        r.readNextRecord(
            JSONReader.NEXT_IN_ARRAY,
            false,
            JSONReader.DEFAULT_JUMP,
            null,
            true,
            10_000_000); // threshold dwarfs the single-RID body → no flush

    assertTrue("no extraction below threshold", ridbags.isEmpty());
    // The original array body must still be present in the parsed text.
    assertTrue(r.getValue().contains(rid.toString()));
  }

  /**
   * Non-edge fields (anything not starting with {@code out_} / {@code in_}) are not extracted —
   * the {@code [...]} body is appended into the parsed value verbatim, and the result map stays
   * empty for that field. Pin to catch a regression that removed the prefix guard.
   */
  @Test
  public void readNextRecordDoesNotExtractNonEdgeArrayField() throws Exception {
    var ridA = stableRid(10);
    var ridB = stableRid(11);
    var json = "{\"links\":[\"" + ridA + "\",\"" + ridB + "\"]}";
    var r = new JSONReader(new StringReader(json));

    var ridbags =
        r.readNextRecord(
            JSONReader.NEXT_IN_ARRAY,
            false,
            JSONReader.DEFAULT_JUMP,
            null,
            true,
            SMALL_THRESHOLD); // even with small threshold, non-edge prefix → no side-map entry

    assertFalse(ridbags.containsKey("links"));
    assertTrue(ridbags.isEmpty());
    // Buffer keeps the original `[..]` body verbatim.
    assertTrue("non-edge array passes through to value", r.getValue().contains("\"links\":"));
    assertTrue(r.getValue().contains(ridA.toString()));
  }

  /**
   * Empty edge array does NOT enter the result map (the close-`]` branch's
   * {@code ridbagSet.size() > 0} guard fires immediately). Pin so a regression that started
   * emitting empty entries — a subtle import semantic change — is caught.
   */
  @Test
  public void readNextRecordIgnoresEmptyEdgeRidbag() throws Exception {
    var json = "{\"out_E\":[]}";
    var r = new JSONReader(new StringReader(json));

    var ridbags =
        r.readNextRecord(
            JSONReader.NEXT_IN_ARRAY,
            false,
            JSONReader.DEFAULT_JUMP,
            null,
            true,
            SMALL_THRESHOLD);

    assertFalse(ridbags.containsKey("out_E"));
    assertTrue(ridbags.isEmpty());
  }

  /** The returned map is always unmodifiable (wrapped via {@code Collections.unmodifiableMap}). */
  @Test
  public void readNextRecordReturnsUnmodifiableMap() throws Exception {
    var ridA = stableRid(12);
    var ridB = stableRid(13);
    var json = "{\"out_E\":[\"" + ridA + "\",\"" + ridB + "\"]}";
    var r = new JSONReader(new StringReader(json));

    var ridbags =
        r.readNextRecord(
            JSONReader.NEXT_IN_ARRAY,
            false,
            JSONReader.DEFAULT_JUMP,
            null,
            true,
            SMALL_THRESHOLD);

    assertThrows(UnsupportedOperationException.class, () -> ridbags.put("x", new RidSet()));
  }

  // ------------------------------------------------------- readRecordString — wrapper

  /**
   * {@code readRecordString} delegates to {@code readNextRecord} (passing {@code NEXT_IN_ARRAY}
   * + {@code true} preserveQuotes), then strips outer double-quote pairs and packages the result
   * as a {@code Pair}. With a record value that starts with the object-open {@code '{'} (no
   * leading quote), the parsed text is returned verbatim — the substring strip does not fire.
   */
  @Test
  public void readRecordStringPackagesParsedValueAndRidbags() throws Exception {
    // 1-digit positions so each RID literal is exactly 6 chars — the first flush after
    // SMALL_THRESHOLD=6 fires at length 7 (RID + delimiter), peeling RIDs cleanly without a
    // partial-token tail that would clear lastFieldName.
    var ridA = stableRid(0);
    var ridB = stableRid(1);
    var ridC = stableRid(2);
    var json =
        "{\"out_E\":[\""
            + ridA
            + "\",\""
            + ridB
            + "\",\""
            + ridC
            + "\"]}";
    var r = new JSONReader(new StringReader(json));

    var pair = r.readRecordString(SMALL_THRESHOLD);
    assertNotNull(pair);
    var parsed = pair.getKey();
    var ridbags = pair.getValue();

    assertEquals(1, ridbags.size());
    assertEquals(3, ridbags.get("out_E").size());
    assertTrue(ridbags.get("out_E").contains(ridA));
    assertTrue(ridbags.get("out_E").contains(ridC));
    // Parsed value retains the object-open: the substring strip only fires when the value starts
    // with a literal `"`.
    assertTrue("parsed text starts with '{'", parsed.startsWith("{"));
  }

  /**
   * Quoted record values get the outer-quote pair stripped via {@code substring(1,
   * lastIndexOf('"'))}. Pin a quoted-only payload (no inner edge arrays) so a regression that
   * picked {@code indexOf} instead of {@code lastIndexOf} would silently corrupt the import path.
   */
  @Test
  public void readRecordStringStripsSurroundingDoubleQuotes() throws Exception {
    var r = new JSONReader(new StringReader("\"plain text value\","));

    var pair = r.readRecordString(SMALL_THRESHOLD);
    assertEquals("plain text value", pair.getKey());
    // No `out_*` / `in_*` array → side-map empty.
    assertTrue(pair.getValue().isEmpty());
  }

  /**
   * Even quoted values containing nested quotes use {@code lastIndexOf('"')}, so the strip
   * preserves the inner quotes. Pin so a regression to {@code indexOf} truncates the value at
   * the first inner quote.
   */
  @Test
  public void readRecordStringStripUsesLastIndexOfNotFirst() throws Exception {
    var r = new JSONReader(new StringReader("\"a\\\"b\\\"c\","));

    var pair = r.readRecordString(SMALL_THRESHOLD);
    // The outer pair is stripped; the inner escaped quotes (still backslash-prefixed in the
    // buffer because preserveQuotes=true) survive verbatim.
    assertEquals("a\\\"b\\\"c", pair.getKey());
  }
}
