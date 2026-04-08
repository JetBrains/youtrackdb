package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the {@link PageOperation} abstract base class. Uses a minimal concrete subclass
 * ({@link TestPageOperation}) to verify construction, field handling, and serialization roundtrip.
 */
public class PageOperationTest {

  /** Minimal concrete PageOperation for testing. Carries a single int field. */
  private static final class TestPageOperation extends PageOperation {

    private static final int TEST_RECORD_ID = 200;

    private int testValue;

    TestPageOperation() {
    }

    TestPageOperation(
        long pageIndex, long fileId, long operationUnitId,
        LogSequenceNumber initialLsn, int testValue) {
      super(pageIndex, fileId, operationUnitId, initialLsn);
      this.testValue = testValue;
    }

    @Override
    public void redo(DurablePage page) {
      // no-op for testing
    }

    @Override
    public int getId() {
      return TEST_RECORD_ID;
    }

    @Override
    public int serializedSize() {
      return super.serializedSize() + Integer.BYTES;
    }

    @Override
    protected void serializeToByteBuffer(ByteBuffer buffer) {
      super.serializeToByteBuffer(buffer);
      buffer.putInt(testValue);
    }

    @Override
    protected void deserializeFromByteBuffer(ByteBuffer buffer) {
      super.deserializeFromByteBuffer(buffer);
      testValue = buffer.getInt();
    }

    int getTestValue() {
      return testValue;
    }
  }

  @Test
  public void testConstructionAndGetters() {
    var initialLsn = new LogSequenceNumber(5, 100);
    var op = new TestPageOperation(10, 20, 30, initialLsn, 42);

    Assert.assertEquals(10, op.getPageIndex());
    Assert.assertEquals(20, op.getFileId());
    Assert.assertEquals(30, op.getOperationUnitId());
    Assert.assertEquals(initialLsn, op.getInitialLsn());
    Assert.assertEquals(42, op.getTestValue());
  }

  @Test
  public void testSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 256);
    var original = new TestPageOperation(100, 200, 300, initialLsn, 99);

    // Allocate buffer with 1-byte offset to test non-zero start position
    var arraySize = original.serializedSize() + 1;
    var content = new byte[arraySize];

    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(arraySize, endOffset);

    var deserialized = new TestPageOperation();
    var dEndOffset = deserialized.fromStream(content, 1);
    Assert.assertEquals(arraySize, dEndOffset);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original.getTestValue(), deserialized.getTestValue());
  }

  @Test
  public void testSerializationWithDifferentLsnValues() {
    // Test with large segment/position values to verify no truncation
    var initialLsn = new LogSequenceNumber(Long.MAX_VALUE, Integer.MAX_VALUE);
    var original = new TestPageOperation(0, 0, 0, initialLsn, 0);

    var arraySize = original.serializedSize() + 1;
    var content = new byte[arraySize];
    original.toStream(content, 1);

    var deserialized = new TestPageOperation();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(Long.MAX_VALUE, deserialized.getInitialLsn().getSegment());
    Assert.assertEquals(Integer.MAX_VALUE, deserialized.getInitialLsn().getPosition());
  }

  @Test
  public void testSerializedSizeIncludesInitialLsn() {
    var op = new TestPageOperation(0, 0, 0, new LogSequenceNumber(0, 0), 0);

    // PageOperation adds 12 bytes (8 long + 4 int) for initialLsn over AbstractPageWALRecord.
    // TestPageOperation adds 4 bytes (int) for testValue.
    // operationUnitId: 8 bytes, pageIndex: 8 bytes, fileId: 8 bytes = 24 from parent chain.
    // initialLsn: 8 + 4 = 12 bytes.
    // testValue: 4 bytes.
    // Total: 24 + 12 + 4 = 40 bytes.
    Assert.assertEquals(40, op.serializedSize());
  }

  @Test
  public void testEqualsAndHashCode() {
    var lsn1 = new LogSequenceNumber(1, 10);
    var op1 = new TestPageOperation(5, 10, 15, lsn1, 42);
    var op2 = new TestPageOperation(5, 10, 15, lsn1, 42);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different initialLsn
    var lsn2 = new LogSequenceNumber(2, 20);
    var op3 = new TestPageOperation(5, 10, 15, lsn2, 42);
    Assert.assertNotEquals(op1, op3);
  }

  @SuppressWarnings("EqualsWithItself")
  @Test
  public void testEqualsSameInstance() {
    var op = new TestPageOperation(1, 2, 3, new LogSequenceNumber(1, 1), 10);
    Assert.assertEquals(op, op);
  }

  @SuppressWarnings("ObjectEqualsNull")
  @Test
  public void testEqualsNull() {
    var op = new TestPageOperation(1, 2, 3, new LogSequenceNumber(1, 1), 10);
    Assert.assertNotEquals(null, op);
  }

  @Test
  public void testEqualsWrongType() {
    var op = new TestPageOperation(1, 2, 3, new LogSequenceNumber(1, 1), 10);
    Assert.assertNotEquals("not a page operation", op);
  }

  @Test
  public void testEqualsDifferentPageIndex() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new TestPageOperation(1, 2, 3, lsn, 10);
    var op2 = new TestPageOperation(99, 2, 3, lsn, 10);
    Assert.assertNotEquals(op1, op2);
  }

  @Test
  public void testEqualsDifferentFileId() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new TestPageOperation(1, 2, 3, lsn, 10);
    var op2 = new TestPageOperation(1, 99, 3, lsn, 10);
    Assert.assertNotEquals(op1, op2);
  }

  @Test
  public void testEqualsDifferentOperationUnitId() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new TestPageOperation(1, 2, 3, lsn, 10);
    var op2 = new TestPageOperation(1, 2, 99, lsn, 10);
    Assert.assertNotEquals(op1, op2);
  }

  @Test
  public void testEqualsWithNullInitialLsn() {
    // Both have null initialLsn (created via no-arg constructor, not fully initialized)
    var op1 = new TestPageOperation();
    var op2 = new TestPageOperation();
    // Both have operationUnitId=0, pageIndex=0, fileId=0 (defaults), so super.equals matches
    Assert.assertEquals(op1, op2);
  }

  @Test
  public void testEqualsOneNullInitialLsn() {
    // op1 has null initialLsn, op2 has non-null
    var op1 = new TestPageOperation();
    var op2 = new TestPageOperation(0, 0, 0, new LogSequenceNumber(1, 1), 0);
    Assert.assertNotEquals(op1, op2);
  }

  @Test
  public void testHashCodeWithNullInitialLsn() {
    var op = new TestPageOperation();
    // Should not throw — null initialLsn produces 0 for that component
    op.hashCode();
  }

  @Test
  public void testToString() {
    var op = new TestPageOperation(5, 10, 15, new LogSequenceNumber(1, 100), 42);
    var str = op.toString();
    Assert.assertTrue(str.contains("initialLsn"));
  }

  @Test
  public void testByteBufferStreamRoundtrip() {
    // Test the ByteBuffer-based toStream/fromStream path
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new TestPageOperation(11, 22, 33, initialLsn, 77);

    var size = original.serializedSize();
    var buffer = ByteBuffer.allocate(size).order(java.nio.ByteOrder.nativeOrder());
    original.toStream(buffer);
    buffer.flip();

    // fromStream reads from byte[] with offset, so convert
    var content = new byte[size];
    buffer.get(content);

    var deserialized = new TestPageOperation();
    deserialized.fromStream(content, 0);

    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original.getTestValue(), deserialized.getTestValue());
  }
}
