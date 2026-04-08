package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.nio.ByteBuffer;

/**
 * Minimal concrete {@link PageOperation} subclass for testing the WAL factory serialization
 * pipeline. Carries a single {@code int testValue} field to validate that subclass-specific
 * data survives the full serialize/deserialize roundtrip through {@link WALRecordsFactory}.
 *
 * <p>This class must be public with a public no-arg constructor so that
 * {@link WALRecordsFactory#registerNewRecord(int, Class)} can instantiate it via reflection.
 */
public class TestPageOperation extends PageOperation {

  public static final int TEST_RECORD_ID = WALRecordTypes.PAGE_OPERATION_ID_BASE;

  private int testValue;

  public TestPageOperation() {
  }

  public TestPageOperation(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int testValue) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.testValue = testValue;
  }

  @Override
  public void redo(DurablePage page) {
    // no-op — this is a test-only record
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

  public int getTestValue() {
    return testValue;
  }
}
