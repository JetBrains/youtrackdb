package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import org.junit.Test;

/**
 * Unit tests for the {@link CollectionBrowseEntry} record. These pin the auto-generated
 * accessor names and the record-equality contract used elsewhere in browse iteration.
 */
public class CollectionBrowseEntryTest {

  /**
   * The constructor-generated accessors must return the values supplied at construction.
   */
  @Test
  public void testRecordAccessorsReturnConstructorArgs() {
    var raw = new RawBuffer(new byte[] {1, 2, 3}, 7L, (byte) 9);
    var entry = new CollectionBrowseEntry(42L, raw);

    assertThat(entry.collectionPosition()).isEqualTo(42L);
    assertThat(entry.buffer()).isSameAs(raw);
  }

  /**
   * A null buffer is permitted by the record (nullness is enforced by callers, not the record
   * itself). Pin this so a defensive null-check addition does not silently break browse paths
   * that rely on lazy buffer materialisation.
   */
  @Test
  public void testNullBufferAllowed() {
    var entry = new CollectionBrowseEntry(0L, null);
    assertThat(entry.buffer()).isNull();
    assertThat(entry.collectionPosition()).isZero();
  }

  /**
   * Records with the same components are equal and have equal hash codes.
   */
  @Test
  public void testRecordEqualityAndHash() {
    var bufferBytes = new byte[] {0, 1, 2};
    var raw1 = new RawBuffer(bufferBytes, 1L, (byte) 0);
    var raw2 = new RawBuffer(bufferBytes, 1L, (byte) 0);

    var a = new CollectionBrowseEntry(10L, raw1);
    var b = new CollectionBrowseEntry(10L, raw2);

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  /**
   * Records with different positions are not equal even when the buffer matches.
   */
  @Test
  public void testRecordInequalityOnPosition() {
    var raw = new RawBuffer(new byte[] {1}, 1L, (byte) 0);
    var a = new CollectionBrowseEntry(1L, raw);
    var b = new CollectionBrowseEntry(2L, raw);

    assertThat(a).isNotEqualTo(b);
  }

  /**
   * The auto-generated {@code toString()} includes both component values so log dumps are
   * informative. A renaming that masks one value would silently degrade debug logs.
   */
  @Test
  public void testToStringContainsComponents() {
    var raw = new RawBuffer(new byte[] {1}, 1L, (byte) 0);
    var entry = new CollectionBrowseEntry(99L, raw);

    var str = entry.toString();
    assertThat(str).contains("99");
    assertThat(str).contains("RawBuffer");
  }
}
