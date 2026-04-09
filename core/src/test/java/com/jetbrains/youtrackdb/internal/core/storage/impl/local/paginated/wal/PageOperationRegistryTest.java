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
   * toStream → fromStream. Each type is instantiated with its no-arg constructor,
   * serialized, deserialized, and verified to be the correct class.
   */
  @Test
  public void testAllRegisteredTypesRoundtrip() {
    var initialLsn = new LogSequenceNumber(1, 100);

    // Build an array of (id, instance) pairs for all 18 types
    PageOperation[] ops = {
        new PaginatedCollectionStateV2SetFileSizeOp(0, 0, 0, initialLsn, 42),
        new PaginatedCollectionStateV2SetApproxRecordsCountOp(0, 0, 0, initialLsn, 100),
        new CollectionPageInitOp(0, 0, 0, initialLsn),
        new CollectionPageDeleteRecordOp(0, 0, 0, initialLsn, 5, false),
        new CollectionPageSetRecordVersionOp(0, 0, 0, initialLsn, 3, 7),
        new CollectionPageDoDefragmentationOp(0, 0, 0, initialLsn),
        new CollectionPageAppendRecordOp(0, 0, 0, initialLsn, 1, new byte[] {1, 2, 3}, 0),
        new CollectionPositionMapBucketInitOp(0, 0, 0, initialLsn),
        new CollectionPositionMapBucketAllocateOp(0, 0, 0, initialLsn),
        new CollectionPositionMapBucketSetOp(0, 0, 0, initialLsn, 0, 0, 0, 0),
        new CollectionPositionMapBucketRemoveOp(0, 0, 0, initialLsn, 1, 0),
        new CollectionPositionMapBucketUpdateVersionOp(0, 0, 0, initialLsn, 2, 5),
        new FreeSpaceMapPageInitOp(0, 0, 0, initialLsn),
        new FreeSpaceMapPageUpdateOp(0, 0, 0, initialLsn, 0, 0),
        new DirtyPageBitSetPageInitOp(0, 0, 0, initialLsn),
        new DirtyPageBitSetPageSetBitOp(0, 0, 0, initialLsn, 0),
        new DirtyPageBitSetPageClearBitOp(0, 0, 0, initialLsn, 0),
        new MapEntryPointSetFileSizeOp(0, 0, 0, initialLsn, 10),
    };

    for (PageOperation op : ops) {
      ByteBuffer serialized = WALRecordsFactory.toStream(op);
      var content = new byte[serialized.limit()];
      serialized.get(0, content);

      WriteableWALRecord deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

      Assert.assertNotNull(
          "Deserialization returned null for " + op.getClass().getSimpleName(), deserialized);
      Assert.assertEquals(
          "Roundtrip class mismatch for ID " + op.getId(),
          op.getClass(), deserialized.getClass());
      Assert.assertEquals(
          "Roundtrip ID mismatch", op.getId(), deserialized.getId());
    }
  }

  /** Verifies the expected total count of registered types — catches accidentally omitted types. */
  @Test
  public void testRegisteredTypeCount() {
    // IDs 201-218 = 18 types
    int registeredCount = 0;
    for (int id = WALRecordTypes.PAGE_OPERATION_ID_BASE + 1;
        id <= WALRecordTypes.PAGE_OPERATION_ID_BASE + 18; id++) {
      try {
        // Construct a minimal serialized record with this ID to test if factory can handle it
        var testOp = createMinimalRecord(id);
        if (testOp != null) {
          registeredCount++;
        }
      } catch (IllegalStateException e) {
        // Not registered — this is a gap
        Assert.fail("WAL record ID " + id + " is not registered: " + e.getMessage());
      }
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
      default -> null;
    };
  }
}
