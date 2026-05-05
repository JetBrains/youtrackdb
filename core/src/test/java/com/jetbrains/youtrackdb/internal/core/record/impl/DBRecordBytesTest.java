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

package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DBRecordBytesTest extends DbTestBase {

  private static final int SMALL_ARRAY = 3;
  private static final int BIG_ARRAY = 7;
  private static final int FULL_ARRAY = 5;
  private InputStream inputStream;
  private InputStream emptyStream;

  private static void assertArrayEquals(byte[] actual, byte[] expected) {
    assert actual.length == expected.length;
    for (var i = 0; i < expected.length; i++) {
      assert actual[i] == expected[i];
    }
  }

  private static Object getFieldValue(Object source, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    final var clazz = source.getClass();
    final var field = getField(clazz, fieldName);
    field.setAccessible(true);
    return field.get(source);
  }

  private static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
    if (clazz == null) {
      throw new NoSuchFieldException(fieldName);
    }
    for (var item : clazz.getDeclaredFields()) {
      if (item.getName().equals(fieldName)) {
        return item;
      }
    }
    return getField(clazz.getSuperclass(), fieldName);
  }

  @Before
  public void setUp() throws Exception {
    inputStream = new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5});
    emptyStream = new ByteArrayInputStream(new byte[] {});

  }

  @Test
  public void testFromInputStream_ReadEmpty() throws Exception {
    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(emptyStream, SMALL_ARRAY);
    Assert.assertEquals(0, result);
    final var source = (byte[]) getFieldValue(blob, "source");
    Assert.assertEquals(0, source.length);
    session.rollback();
  }

  @Test
  public void testFromInputStream_ReadSmall() throws Exception {
    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(inputStream, SMALL_ARRAY);
    Assert.assertEquals(SMALL_ARRAY, result);
    final var source = (byte[]) getFieldValue(blob, "source");
    Assert.assertEquals(SMALL_ARRAY, source.length);
    for (var i = 1; i < SMALL_ARRAY + 1; i++) {
      Assert.assertEquals(source[i - 1], i);
    }
    session.rollback();
  }

  @Test
  public void testFromInputStream_ReadBig() throws Exception {
    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(inputStream, BIG_ARRAY);
    Assert.assertEquals(FULL_ARRAY, result);
    final var source = (byte[]) getFieldValue(blob, "source");
    Assert.assertEquals(FULL_ARRAY, source.length);
    for (var i = 1; i < FULL_ARRAY + 1; i++) {
      Assert.assertEquals(source[i - 1], i);
    }
    session.rollback();
  }

  @Test
  public void testFromInputStream_ReadFull() throws Exception {
    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(inputStream, FULL_ARRAY);
    Assert.assertEquals(FULL_ARRAY, result);
    final var source = (byte[]) getFieldValue(blob, "source");
    Assert.assertEquals(FULL_ARRAY, source.length);
    for (var i = 1; i < FULL_ARRAY + 1; i++) {
      Assert.assertEquals(source[i - 1], i);
    }
    session.rollback();
  }

  @Test
  public void testReadFromInputStreamWithWait() throws Exception {
    final var data = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    final InputStream is = new NotFullyAvailableAtTheTimeInputStream(data, 5);

    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(is);
    Assert.assertEquals(result, data.length);
    Assert.assertEquals(getFieldValue(blob, "size"), data.length);

    final var source = (byte[]) getFieldValue(blob, "source");
    assertArrayEquals(source, data);
    session.rollback();
  }

  @Test
  public void testReadFromInputStreamWithWaitSizeLimit() throws Exception {
    final var data = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    final InputStream is = new NotFullyAvailableAtTheTimeInputStream(data, 5);

    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(is, 10);
    Assert.assertEquals(result, data.length);
    Assert.assertEquals(getFieldValue(blob, "size"), data.length);

    final var source = (byte[]) getFieldValue(blob, "source");
    assertArrayEquals(source, data);
    session.rollback();
  }

  @Test
  public void testReadFromInputStreamWithWaitSizeTooBigLimit() throws Exception {
    final var data = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    final InputStream is = new NotFullyAvailableAtTheTimeInputStream(data, 5);

    session.begin();
    var blob = session.newBlob();
    final var result = blob.fromInputStream(is, 15);
    Assert.assertEquals(result, data.length);
    Assert.assertEquals(getFieldValue(blob, "size"), data.length);

    final var source = (byte[]) getFieldValue(blob, "source");
    assertArrayEquals(source, data);
    session.rollback();
  }

  @Test
  public void testReadFromInputStreamWithWaitSizeTooSmallLimit() throws Exception {
    final var data = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    final var expected = Arrays.copyOf(data, 8);
    final InputStream is = new NotFullyAvailableAtTheTimeInputStream(data, 5);

    session.begin();
    var testedInstance = session.newBlob();
    final var result = testedInstance.fromInputStream(is, 8);
    Assert.assertEquals(result, expected.length);
    Assert.assertEquals(getFieldValue(testedInstance, "size"), expected.length);

    final var source = (byte[]) getFieldValue(testedInstance, "source");
    assertArrayEquals(source, expected);
    session.rollback();
  }

  // =========================================================================
  // Live RecordBytes shape coverage — constructor, getRecord round-trip,
  // toStream/fromStream byte-array equality, the 1-arg fromInputStream path
  // (production-live; reached from JSONSerializerJackson Blob deserialization),
  // toOutputStream, the as*-cast contract, isBlob/isEntity/isVertex/isEdge
  // flags, the setOwner-throws contract, and getRecordType. These pin the
  // class's external behaviour observable through the {@link Blob} interface
  // and guard against accidental regressions in the live (non-test-only)
  // surface.
  // =========================================================================

  /**
   * The 1-arg {@code fromInputStream(InputStream)} overload reads the entire
   * stream into the {@code source} buffer. After the call, {@code toStream()}
   * must return a byte-for-byte equal array of the input (round-trip
   * fidelity). This overload is production-live (reached from
   * {@code JSONSerializerJackson} during JSON-to-Blob deserialization).
   */
  @Test
  public void testFromInputStreamOneArgRoundTripsViaToStream() throws Exception {
    final var data = new byte[] {10, 20, 30, 40, 50};
    session.begin();
    var blob = session.newBlob();
    final var read = blob.fromInputStream(new ByteArrayInputStream(data));
    Assert.assertEquals("1-arg fromInputStream must read entire stream",
        data.length, read);
    final var out = blob.toStream();
    Assert.assertArrayEquals("toStream() must return byte-for-byte equal data",
        data, out);
    session.rollback();
  }

  /**
   * {@code fromInputStream(InputStream)} on an empty stream must yield a
   * zero-length {@code source} buffer, not null. Pinned because the
   * {@link MemoryStream} scratch buffer in the body must flush to an empty
   * array on EOF before the very first read.
   */
  @Test
  public void testFromInputStreamOneArgEmptyStreamYieldsEmptyBuffer() throws Exception {
    session.begin();
    var blob = session.newBlob();
    final var read = blob.fromInputStream(new ByteArrayInputStream(new byte[] {}));
    Assert.assertEquals(0, read);
    Assert.assertEquals(
        "empty stream must yield empty (not null) source",
        0, ((byte[]) getFieldValue(blob, "source")).length);
    Assert.assertArrayEquals(new byte[] {}, blob.toStream());
    session.rollback();
  }

  /**
   * {@code toOutputStream} writes the {@code source} buffer verbatim to the
   * sink stream. After the call, the sink must contain a byte-for-byte equal
   * copy.
   */
  @Test
  public void testToOutputStreamWritesSourceVerbatim() throws Exception {
    final var data = new byte[] {1, 2, 3, 4, 5, 6, 7};
    session.begin();
    var blob = session.newBlob();
    blob.fromInputStream(new ByteArrayInputStream(data));

    final var sink = new java.io.ByteArrayOutputStream();
    blob.toOutputStream(sink);
    Assert.assertArrayEquals(data, sink.toByteArray());
    session.rollback();
  }

  /**
   * {@code toOutputStream} with a zero-length source must produce no writes
   * to the sink (the source-length guard short-circuits the write).
   */
  @Test
  public void testToOutputStreamSkipsEmptySource() throws Exception {
    session.begin();
    var blob = session.newBlob();
    blob.fromInputStream(new ByteArrayInputStream(new byte[] {}));
    final var sink = new java.io.ByteArrayOutputStream();
    blob.toOutputStream(sink);
    Assert.assertEquals(0, sink.size());
    session.rollback();
  }

  /**
   * {@code getRecordType()} must return {@code Blob.RECORD_TYPE} ({@code 'b'}).
   * Pinned because the byte tag is the on-disk discriminator that distinguishes
   * blob records from entities; a typo there would silently corrupt new
   * persisted records.
   */
  @Test
  public void testGetRecordTypeIsBlobTag() {
    session.begin();
    var blob = session.newBlob();
    Assert.assertEquals(
        "RecordBytes must report Blob.RECORD_TYPE ('b')",
        com.jetbrains.youtrackdb.internal.core.db.record.record.Blob.RECORD_TYPE,
        ((RecordBytes) blob).getRecordType());
    session.rollback();
  }

  /**
   * The boolean-shape methods {@code isBlob}/{@code isEntity}/{@code isVertex}/
   * {@code isEdge} must report the canonical flag set for a blob: only
   * {@code isBlob} is true. Pinned because the dispatch site at
   * {@code DatabaseSessionEmbedded#newBlob} returns these as {@link
   * com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord} — callers
   * branch on the flags.
   */
  @Test
  public void testTypeFlagsReportBlobShape() {
    session.begin();
    var blob = session.newBlob();
    Assert.assertTrue("isBlob must be true", blob.isBlob());
    Assert.assertFalse("isEntity must be false", blob.isEntity());
    Assert.assertFalse("isVertex must be false", blob.isVertex());
    Assert.assertFalse("isEdge must be false", blob.isEdge());
    session.rollback();
  }

  /**
   * The {@code as*} casts on a blob: {@code asBlob} / {@code asBlobOrNull}
   * return self; {@code asEntity} / {@code asEdge} / {@code asVertex} throw
   * {@link IllegalStateException}; their {@code -OrNull} variants return null.
   * Pinned to lock in the live cast contract observable to all callers.
   */
  @Test
  public void testAsCastsHonourBlobShape() {
    session.begin();
    var blob = session.newBlob();
    Assert.assertSame("asBlob must return self", blob, blob.asBlob());
    Assert.assertSame("asBlobOrNull must return self", blob, blob.asBlobOrNull());
    Assert.assertNull("asEntityOrNull must return null", blob.asEntityOrNull());
    Assert.assertNull("asEdgeOrNull must return null", blob.asEdgeOrNull());
    Assert.assertNull("asVertexOrNull must return null", blob.asVertexOrNull());

    try {
      blob.asEntity();
      Assert.fail("asEntity must throw on a blob");
    } catch (IllegalStateException expected) {
      Assert.assertTrue(expected.getMessage().contains("not an Entity"));
    }
    try {
      blob.asEdge();
      Assert.fail("asEdge must throw on a blob");
    } catch (IllegalStateException expected) {
      Assert.assertTrue(expected.getMessage().contains("not an Edge"));
    }
    try {
      blob.asVertex();
      Assert.fail("asVertex must throw on a blob");
    } catch (IllegalStateException expected) {
      Assert.assertTrue(expected.getMessage().contains("not a Vertex"));
    }
    session.rollback();
  }

  /**
   * {@code setOwner} is unsupported on a blob — calling it must throw
   * {@link UnsupportedOperationException}. Pinned because attempting to chain
   * a blob inside another record's ownership graph would otherwise corrupt
   * the embedded-record model silently.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void testSetOwnerThrowsOnBlob() {
    session.begin();
    try {
      var blob = session.newBlob();
      // RecordBytes implements Blob and inherits setOwner from RecordElement;
      // newBlob() returns Blob (the public face) so the runtime instance is a
      // RecordBytes — cast to call the inherited method.
      ((RecordBytes) blob).setOwner(null);
    } finally {
      session.rollback();
    }
  }

  /**
   * Round-trip: persist a blob, reload it via the loaded RID, the reloaded
   * payload must equal the source byte-array. This is the central live
   * contract a downstream consumer depends on.
   */
  @Test
  public void testPersistAndReloadByteArrayEquality() {
    final var payload = new byte[] {9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
    final var blobId = session.computeInTx(tx -> tx.newBlob(payload).getIdentity());

    session.executeInTx(tx -> {
      final var loaded = tx.loadBlob(blobId);
      Assert.assertArrayEquals("reloaded payload must equal stored payload",
          payload, loaded.toStream());
    });
  }

  @Test
  public void testBlobLinkSameTx() {

    final var entityId = session.computeInTx(tx -> {
      final var newBlob = tx.newBlob(new byte[] {1, 2, 3});
      final var entity = tx.newEntity();
      entity.setProperty("blob", newBlob);
      return entity.getIdentity();
    });

    session.executeInTx(tx -> {
      final var entity = tx.loadEntity(entityId);
      assertThat(entity.getBlob("blob").toStream()).isEqualTo(new byte[] {1, 2, 3});
    });
  }

  @Test
  public void testBlobLinkDifferentTx() {
    final var blobId =
        session.computeInTx(tx -> tx.newBlob(new byte[] {1, 2, 3}).getIdentity());

    final var entityId =
        session.computeInTx(tx -> {
          final var entity = tx.newEntity();
          entity.setLink("blob", tx.loadBlob(blobId));
          return entity.getIdentity();
        });

    session.executeInTx(tx -> {
      final var entity = tx.loadEntity(entityId);
      assertThat(entity.getLink("blob")).isEqualTo(blobId);
      assertThat(entity.getBlob("blob").toStream()).isEqualTo(new byte[] {1, 2, 3});
    });
  }

  private static final class NotFullyAvailableAtTheTimeInputStream extends InputStream {

    private final byte[] data;
    private int pos = -1;
    private final int interrupt;

    private NotFullyAvailableAtTheTimeInputStream(byte[] data, int interrupt) {
      this.data = data;
      this.interrupt = interrupt;
      assert interrupt < data.length;
    }

    @Override
    public int read() {
      pos++;
      if (pos < interrupt) {
        return data[pos] & 0xFF;
      } else if (pos == interrupt) {
        return -1;
      } else if (pos <= data.length) {
        return data[pos - 1] & 0xFF;
      } else {
        return -1;
      }
    }
  }
}
