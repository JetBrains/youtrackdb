package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPageAppendRecordOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPageDeleteRecordOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPageDoDefragmentationOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPageInitOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPageSetRecordVersionOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucketAllocateOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucketInitOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucketRemoveOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucketSetOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucketUpdateVersionOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.DirtyPageBitSetPageClearBitOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.DirtyPageBitSetPageInitOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.DirtyPageBitSetPageSetBitOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.FreeSpaceMapPageInitOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.FreeSpaceMapPageUpdateOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.MapEntryPointSetFileSizeOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionStateV2SetApproxRecordsCountOp;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionStateV2SetFileSizeOp;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests that {@link PageOperationRegistry#registerAll(WALRecordsFactory)} correctly registers
 * all 18 Track 2-3 PageOperation types so they can be deserialized by the factory during recovery.
 */
public class PageOperationRegistryTest {

  @BeforeClass
  public static void register() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  /**
   * Verifies that all 18 registered record IDs survive a full WALRecordsFactory roundtrip:
   * toStream → fromStream. Uses non-zero field values for all parameters (including parent
   * fields) and verifies full field-level equality via equals(), not just class/ID match.
   */
  @Test
  public void testAllRegisteredTypesRoundtrip() {
    var initialLsn = new LogSequenceNumber(1, 100);
    // Use non-zero values for parent fields to verify they survive the factory pipeline
    long pageIndex = 7;
    long fileId = 13;
    long opUnitId = 99;

    PageOperation[] ops = {
        new PaginatedCollectionStateV2SetFileSizeOp(pageIndex, fileId, opUnitId, initialLsn, 42),
        new PaginatedCollectionStateV2SetApproxRecordsCountOp(
            pageIndex, fileId, opUnitId, initialLsn, 100),
        new CollectionPageInitOp(pageIndex, fileId, opUnitId, initialLsn),
        new CollectionPageDeleteRecordOp(pageIndex, fileId, opUnitId, initialLsn, 5, true),
        new CollectionPageSetRecordVersionOp(pageIndex, fileId, opUnitId, initialLsn, 3, 7),
        new CollectionPageDoDefragmentationOp(pageIndex, fileId, opUnitId, initialLsn),
        new CollectionPageAppendRecordOp(
            pageIndex, fileId, opUnitId, initialLsn, 1, new byte[] {1, 2, 3}, 4),
        new CollectionPositionMapBucketInitOp(pageIndex, fileId, opUnitId, initialLsn),
        new CollectionPositionMapBucketAllocateOp(pageIndex, fileId, opUnitId, initialLsn),
        new CollectionPositionMapBucketSetOp(
            pageIndex, fileId, opUnitId, initialLsn, 2, 3, 4, 5),
        new CollectionPositionMapBucketRemoveOp(pageIndex, fileId, opUnitId, initialLsn, 1, 6),
        new CollectionPositionMapBucketUpdateVersionOp(
            pageIndex, fileId, opUnitId, initialLsn, 2, 5),
        new FreeSpaceMapPageInitOp(pageIndex, fileId, opUnitId, initialLsn),
        new FreeSpaceMapPageUpdateOp(pageIndex, fileId, opUnitId, initialLsn, 3, 128),
        new DirtyPageBitSetPageInitOp(pageIndex, fileId, opUnitId, initialLsn),
        new DirtyPageBitSetPageSetBitOp(pageIndex, fileId, opUnitId, initialLsn, 17),
        new DirtyPageBitSetPageClearBitOp(pageIndex, fileId, opUnitId, initialLsn, 23),
        new MapEntryPointSetFileSizeOp(pageIndex, fileId, opUnitId, initialLsn, 10),
    };

    for (PageOperation op : ops) {
      ByteBuffer serialized = WALRecordsFactory.toStream(op);
      var content = new byte[serialized.limit()];
      serialized.get(0, content);

      WriteableWALRecord deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

      Assert.assertNotNull(
          "Deserialization returned null for " + op.getClass().getSimpleName(), deserialized);
      Assert.assertEquals(
          "Full field-level equality failed for " + op.getClass().getSimpleName(),
          op, deserialized);
    }
  }

  /** Verifies the expected total count of registered types — catches accidentally omitted types. */
  @Test
  public void testRegisteredTypeCount() {
    // IDs 201-218 = 18 types. Each ID must have both a createOpForId entry and a factory
    // registration. createOpForId throws for unknown IDs, so any gap causes immediate failure.
    int registeredCount = 0;
    for (int id = WALRecordTypes.PAGE_OPERATION_ID_BASE + 1;
        id <= WALRecordTypes.PAGE_OPERATION_ID_BASE + 18; id++) {
      var testOp = createMinimalRecord(id);
      Assert.assertNotNull("WAL record ID " + id + " failed to roundtrip", testOp);
      registeredCount++;
    }
    Assert.assertEquals("Expected 18 registered PageOperation types", 18, registeredCount);
  }

  /**
   * Verifies that calling registerAll twice is safe (idempotent) — the second call
   * simply overwrites with the same mappings.
   */
  @Test
  public void testRegisterAllIdempotent() {
    // Call again — should not throw
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);

    // Verify a roundtrip still works after double registration
    var initialLsn = new LogSequenceNumber(1, 1);
    var op = new CollectionPageInitOp(0, 0, 0, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(op);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    WriteableWALRecord deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertEquals(CollectionPageInitOp.class, deserialized.getClass());
  }

  /**
   * Creates and roundtrips a minimal record for the given ID by serializing a no-arg instance
   * through the factory. Returns the deserialized record, or throws if the ID is unregistered.
   */
  private WriteableWALRecord createMinimalRecord(int id) {
    // Find the type that was registered for this ID by trying a factory roundtrip.
    // We use a known PageOperation with that ID — match ID to known class.
    PageOperation op = createOpForId(id);
    if (op == null) {
      return null;
    }
    ByteBuffer serialized = WALRecordsFactory.toStream(op);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);
    return WALRecordsFactory.INSTANCE.fromStream(content);
  }

  private PageOperation createOpForId(int id) {
    var lsn = new LogSequenceNumber(0, 0);
    return switch (id) {
      case WALRecordTypes.PAGINATED_COLLECTION_STATE_V2_SET_FILE_SIZE_OP ->
          new PaginatedCollectionStateV2SetFileSizeOp(0, 0, 0, lsn, 0);
      case WALRecordTypes.PAGINATED_COLLECTION_STATE_V2_SET_APPROX_RECORDS_COUNT_OP ->
          new PaginatedCollectionStateV2SetApproxRecordsCountOp(0, 0, 0, lsn, 0);
      case WALRecordTypes.COLLECTION_PAGE_INIT_OP ->
          new CollectionPageInitOp(0, 0, 0, lsn);
      case WALRecordTypes.COLLECTION_PAGE_DELETE_RECORD_OP ->
          new CollectionPageDeleteRecordOp(0, 0, 0, lsn, 0, false);
      case WALRecordTypes.COLLECTION_PAGE_SET_RECORD_VERSION_OP ->
          new CollectionPageSetRecordVersionOp(0, 0, 0, lsn, 0, 0);
      case WALRecordTypes.COLLECTION_PAGE_DO_DEFRAGMENTATION_OP ->
          new CollectionPageDoDefragmentationOp(0, 0, 0, lsn);
      case WALRecordTypes.COLLECTION_PAGE_APPEND_RECORD_OP ->
          new CollectionPageAppendRecordOp(0, 0, 0, lsn, 0, new byte[] {}, 0);
      case WALRecordTypes.POSITION_MAP_BUCKET_INIT_OP ->
          new CollectionPositionMapBucketInitOp(0, 0, 0, lsn);
      case WALRecordTypes.POSITION_MAP_BUCKET_ALLOCATE_OP ->
          new CollectionPositionMapBucketAllocateOp(0, 0, 0, lsn);
      case WALRecordTypes.POSITION_MAP_BUCKET_SET_OP ->
          new CollectionPositionMapBucketSetOp(0, 0, 0, lsn, 0, 0, 0, 0);
      case WALRecordTypes.POSITION_MAP_BUCKET_REMOVE_OP ->
          new CollectionPositionMapBucketRemoveOp(0, 0, 0, lsn, 0, 0);
      case WALRecordTypes.POSITION_MAP_BUCKET_UPDATE_VERSION_OP ->
          new CollectionPositionMapBucketUpdateVersionOp(0, 0, 0, lsn, 0, 0);
      case WALRecordTypes.FREE_SPACE_MAP_PAGE_INIT_OP ->
          new FreeSpaceMapPageInitOp(0, 0, 0, lsn);
      case WALRecordTypes.FREE_SPACE_MAP_PAGE_UPDATE_OP ->
          new FreeSpaceMapPageUpdateOp(0, 0, 0, lsn, 0, 0);
      case WALRecordTypes.DIRTY_PAGE_BIT_SET_PAGE_INIT_OP ->
          new DirtyPageBitSetPageInitOp(0, 0, 0, lsn);
      case WALRecordTypes.DIRTY_PAGE_BIT_SET_PAGE_SET_BIT_OP ->
          new DirtyPageBitSetPageSetBitOp(0, 0, 0, lsn, 0);
      case WALRecordTypes.DIRTY_PAGE_BIT_SET_PAGE_CLEAR_BIT_OP ->
          new DirtyPageBitSetPageClearBitOp(0, 0, 0, lsn, 0);
      case WALRecordTypes.MAP_ENTRY_POINT_SET_FILE_SIZE_OP ->
          new MapEntryPointSetFileSizeOp(0, 0, 0, lsn, 0);
      default -> throw new IllegalArgumentException("Unknown PageOperation ID: " + id);
    };
  }
}
