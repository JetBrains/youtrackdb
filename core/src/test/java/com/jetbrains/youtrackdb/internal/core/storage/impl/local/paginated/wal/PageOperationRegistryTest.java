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
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2DecrementEntriesCountOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2IncrementEntriesCountOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2InitOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2SetLeftSiblingOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2SetRightSiblingOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2SwitchBucketTypeOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVEntryPointV2InitOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVEntryPointV2SetEntryIdOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVEntryPointV2SetPagesSizeOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVEntryPointV2SetTreeSizeOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVNullBucketV2AddValueOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVNullBucketV2DecrementSizeOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVNullBucketV2IncrementSizeOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVNullBucketV2InitOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVNullBucketV2RemoveValueOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3AddAllOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3AddLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3AddNonLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3InitOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3RemoveLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3RemoveNonLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3SetLeftSiblingOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3SetNextFreeListPageOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3SetRightSiblingOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3ShrinkOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3SwitchBucketTypeOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3UpdateKeyOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVBucketV3UpdateValueOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVEntryPointV3InitOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVEntryPointV3SetFreeListHeadOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVEntryPointV3SetPagesSizeOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVEntryPointV3SetTreeSizeOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVNullBucketV3InitOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVNullBucketV3RemoveValueOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTreeSVNullBucketV3SetValueOp;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests that {@link PageOperationRegistry#registerAll(WALRecordsFactory)} correctly registers
 * all 53 Track 2-3, Track 5, and Track 6 PageOperation types so they can be deserialized by the
 * factory during recovery.
 */
public class PageOperationRegistryTest {

  @BeforeClass
  public static void register() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  /**
   * Verifies that all 53 registered record IDs survive a full WALRecordsFactory roundtrip:
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
        // Track 2-3: Collection types (18 ops)
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

        // Track 5: CellBTreeSingleValueEntryPointV3 (4 ops)
        new BTreeSVEntryPointV3InitOp(pageIndex, fileId, opUnitId, initialLsn),
        new BTreeSVEntryPointV3SetTreeSizeOp(
            pageIndex, fileId, opUnitId, initialLsn, 123456789L),
        new BTreeSVEntryPointV3SetPagesSizeOp(pageIndex, fileId, opUnitId, initialLsn, 42),
        new BTreeSVEntryPointV3SetFreeListHeadOp(pageIndex, fileId, opUnitId, initialLsn, 5),

        // Track 5: CellBTreeSingleValueV3NullBucket (3 ops)
        new BTreeSVNullBucketV3InitOp(pageIndex, fileId, opUnitId, initialLsn),
        new BTreeSVNullBucketV3SetValueOp(
            pageIndex, fileId, opUnitId, initialLsn, (short) 23, 456L),
        new BTreeSVNullBucketV3RemoveValueOp(pageIndex, fileId, opUnitId, initialLsn),

        // Track 5: CellBTreeSingleValueBucketV3 simple (6 ops)
        new BTreeSVBucketV3InitOp(pageIndex, fileId, opUnitId, initialLsn, true),
        new BTreeSVBucketV3SwitchBucketTypeOp(pageIndex, fileId, opUnitId, initialLsn),
        new BTreeSVBucketV3SetLeftSiblingOp(pageIndex, fileId, opUnitId, initialLsn, 100L),
        new BTreeSVBucketV3SetRightSiblingOp(pageIndex, fileId, opUnitId, initialLsn, 200L),
        new BTreeSVBucketV3SetNextFreeListPageOp(pageIndex, fileId, opUnitId, initialLsn, 3),
        new BTreeSVBucketV3UpdateValueOp(
            pageIndex, fileId, opUnitId, initialLsn, 0, new byte[] {10, 20}, 4),

        // Track 5: CellBTreeSingleValueBucketV3 entry (5 ops)
        new BTreeSVBucketV3AddLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn, 0,
            new byte[] {1, 0, 0, 0}, new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100}),
        new BTreeSVBucketV3AddNonLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn, 0, 1, 2, new byte[] {1, 0, 0, 0}),
        new BTreeSVBucketV3RemoveLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn, 0, new byte[] {1, 0, 0, 0}),
        new BTreeSVBucketV3RemoveNonLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn, 0, new byte[] {1, 0, 0, 0}, true),
        new BTreeSVBucketV3UpdateKeyOp(
            pageIndex, fileId, opUnitId, initialLsn, 0, new byte[] {2, 0, 0, 0}, 4),

        // Track 5: CellBTreeSingleValueBucketV3 bulk (2 ops)
        new BTreeSVBucketV3AddAllOp(
            pageIndex, fileId, opUnitId, initialLsn,
            List.of(new byte[] {1, 2, 3}, new byte[] {4, 5, 6})),
        new BTreeSVBucketV3ShrinkOp(
            pageIndex, fileId, opUnitId, initialLsn,
            List.of(new byte[] {7, 8, 9})),

        // Track 6: CellBTreeMultiValueV2EntryPoint (4 ops)
        new BTreeMVEntryPointV2InitOp(pageIndex, fileId, opUnitId, initialLsn),
        new BTreeMVEntryPointV2SetTreeSizeOp(
            pageIndex, fileId, opUnitId, initialLsn, 987654321L),
        new BTreeMVEntryPointV2SetPagesSizeOp(pageIndex, fileId, opUnitId, initialLsn, 55),
        new BTreeMVEntryPointV2SetEntryIdOp(
            pageIndex, fileId, opUnitId, initialLsn, 111222333L),

        // Track 6: CellBTreeMultiValueV2NullBucket (5 ops)
        new BTreeMVNullBucketV2InitOp(pageIndex, fileId, opUnitId, initialLsn, 42L),
        new BTreeMVNullBucketV2AddValueOp(
            pageIndex, fileId, opUnitId, initialLsn, (short) 5, 1000L),
        new BTreeMVNullBucketV2RemoveValueOp(
            pageIndex, fileId, opUnitId, initialLsn, (short) 5, 1000L),
        new BTreeMVNullBucketV2IncrementSizeOp(pageIndex, fileId, opUnitId, initialLsn),
        new BTreeMVNullBucketV2DecrementSizeOp(pageIndex, fileId, opUnitId, initialLsn),

        // Track 6: CellBTreeMultiValueV2Bucket simple (6 ops)
        new BTreeMVBucketV2InitOp(pageIndex, fileId, opUnitId, initialLsn, true),
        new BTreeMVBucketV2SwitchBucketTypeOp(pageIndex, fileId, opUnitId, initialLsn),
        new BTreeMVBucketV2SetLeftSiblingOp(pageIndex, fileId, opUnitId, initialLsn, 100L),
        new BTreeMVBucketV2SetRightSiblingOp(pageIndex, fileId, opUnitId, initialLsn, 200L),
        new BTreeMVBucketV2IncrementEntriesCountOp(pageIndex, fileId, opUnitId, initialLsn, 3),
        new BTreeMVBucketV2DecrementEntriesCountOp(pageIndex, fileId, opUnitId, initialLsn, 5),
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
    // IDs 201-253 = 53 types (18 Track 2-3 + 20 Track 5 + 15 Track 6). Each ID must have both a
    // createOpForId entry and a factory registration. createOpForId throws for unknown IDs,
    // so any gap causes immediate failure.
    int registeredCount = 0;
    for (int id = WALRecordTypes.PAGE_OPERATION_ID_BASE + 1;
        id <= WALRecordTypes.PAGE_OPERATION_ID_BASE + 53; id++) {
      var testOp = createMinimalRecord(id);
      Assert.assertNotNull("WAL record ID " + id + " failed to roundtrip", testOp);
      registeredCount++;
    }
    Assert.assertEquals("Expected 53 registered PageOperation types", 53, registeredCount);
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
      // Track 2-3: Collection types (18 ops)
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

      // Track 5: CellBTreeSingleValueEntryPointV3 (4 ops)
      case WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_INIT_OP ->
          new BTreeSVEntryPointV3InitOp(0, 0, 0, lsn);
      case WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_TREE_SIZE_OP ->
          new BTreeSVEntryPointV3SetTreeSizeOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_PAGES_SIZE_OP ->
          new BTreeSVEntryPointV3SetPagesSizeOp(0, 0, 0, lsn, 0);
      case WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_FREE_LIST_HEAD_OP ->
          new BTreeSVEntryPointV3SetFreeListHeadOp(0, 0, 0, lsn, 0);

      // Track 5: CellBTreeSingleValueV3NullBucket (3 ops)
      case WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_INIT_OP ->
          new BTreeSVNullBucketV3InitOp(0, 0, 0, lsn);
      case WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_SET_VALUE_OP ->
          new BTreeSVNullBucketV3SetValueOp(0, 0, 0, lsn, (short) 0, 0L);
      case WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_REMOVE_VALUE_OP ->
          new BTreeSVNullBucketV3RemoveValueOp(0, 0, 0, lsn);

      // Track 5: CellBTreeSingleValueBucketV3 simple (6 ops)
      case WALRecordTypes.BTREE_SV_BUCKET_V3_INIT_OP ->
          new BTreeSVBucketV3InitOp(0, 0, 0, lsn, true);
      case WALRecordTypes.BTREE_SV_BUCKET_V3_SWITCH_BUCKET_TYPE_OP ->
          new BTreeSVBucketV3SwitchBucketTypeOp(0, 0, 0, lsn);
      case WALRecordTypes.BTREE_SV_BUCKET_V3_SET_LEFT_SIBLING_OP ->
          new BTreeSVBucketV3SetLeftSiblingOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.BTREE_SV_BUCKET_V3_SET_RIGHT_SIBLING_OP ->
          new BTreeSVBucketV3SetRightSiblingOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.BTREE_SV_BUCKET_V3_SET_NEXT_FREE_LIST_PAGE_OP ->
          new BTreeSVBucketV3SetNextFreeListPageOp(0, 0, 0, lsn, 0);
      case WALRecordTypes.BTREE_SV_BUCKET_V3_UPDATE_VALUE_OP ->
          new BTreeSVBucketV3UpdateValueOp(0, 0, 0, lsn, 0, new byte[] {}, 0);

      // Track 5: CellBTreeSingleValueBucketV3 entry (5 ops)
      case WALRecordTypes.BTREE_SV_BUCKET_V3_ADD_LEAF_ENTRY_OP ->
          new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 0, new byte[] {}, new byte[] {});
      case WALRecordTypes.BTREE_SV_BUCKET_V3_ADD_NON_LEAF_ENTRY_OP ->
          new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 0, 0, 0, new byte[] {});
      case WALRecordTypes.BTREE_SV_BUCKET_V3_REMOVE_LEAF_ENTRY_OP ->
          new BTreeSVBucketV3RemoveLeafEntryOp(0, 0, 0, lsn, 0, new byte[] {});
      case WALRecordTypes.BTREE_SV_BUCKET_V3_REMOVE_NON_LEAF_ENTRY_OP ->
          new BTreeSVBucketV3RemoveNonLeafEntryOp(0, 0, 0, lsn, 0, new byte[] {}, false);
      case WALRecordTypes.BTREE_SV_BUCKET_V3_UPDATE_KEY_OP ->
          new BTreeSVBucketV3UpdateKeyOp(0, 0, 0, lsn, 0, new byte[] {}, 0);

      // Track 5: CellBTreeSingleValueBucketV3 bulk (2 ops)
      case WALRecordTypes.BTREE_SV_BUCKET_V3_ADD_ALL_OP ->
          new BTreeSVBucketV3AddAllOp(0, 0, 0, lsn, List.of());
      case WALRecordTypes.BTREE_SV_BUCKET_V3_SHRINK_OP ->
          new BTreeSVBucketV3ShrinkOp(0, 0, 0, lsn, List.of());

      // Track 6: CellBTreeMultiValueV2EntryPoint (4 ops)
      case WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_INIT_OP ->
          new BTreeMVEntryPointV2InitOp(0, 0, 0, lsn);
      case WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_TREE_SIZE_OP ->
          new BTreeMVEntryPointV2SetTreeSizeOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_PAGES_SIZE_OP ->
          new BTreeMVEntryPointV2SetPagesSizeOp(0, 0, 0, lsn, 1);
      case WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_ENTRY_ID_OP ->
          new BTreeMVEntryPointV2SetEntryIdOp(0, 0, 0, lsn, 0L);

      // Track 6: CellBTreeMultiValueV2NullBucket (5 ops)
      case WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_INIT_OP ->
          new BTreeMVNullBucketV2InitOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_ADD_VALUE_OP ->
          new BTreeMVNullBucketV2AddValueOp(0, 0, 0, lsn, (short) 0, 0L);
      case WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_REMOVE_VALUE_OP ->
          new BTreeMVNullBucketV2RemoveValueOp(0, 0, 0, lsn, (short) 0, 0L);
      case WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_INCREMENT_SIZE_OP ->
          new BTreeMVNullBucketV2IncrementSizeOp(0, 0, 0, lsn);
      case WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_DECREMENT_SIZE_OP ->
          new BTreeMVNullBucketV2DecrementSizeOp(0, 0, 0, lsn);

      // Track 6: CellBTreeMultiValueV2Bucket simple (6 ops)
      case WALRecordTypes.BTREE_MV_BUCKET_V2_INIT_OP ->
          new BTreeMVBucketV2InitOp(0, 0, 0, lsn, true);
      case WALRecordTypes.BTREE_MV_BUCKET_V2_SWITCH_BUCKET_TYPE_OP ->
          new BTreeMVBucketV2SwitchBucketTypeOp(0, 0, 0, lsn);
      case WALRecordTypes.BTREE_MV_BUCKET_V2_SET_LEFT_SIBLING_OP ->
          new BTreeMVBucketV2SetLeftSiblingOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.BTREE_MV_BUCKET_V2_SET_RIGHT_SIBLING_OP ->
          new BTreeMVBucketV2SetRightSiblingOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.BTREE_MV_BUCKET_V2_INCREMENT_ENTRIES_COUNT_OP ->
          new BTreeMVBucketV2IncrementEntriesCountOp(0, 0, 0, lsn, 0);
      case WALRecordTypes.BTREE_MV_BUCKET_V2_DECREMENT_ENTRIES_COUNT_OP ->
          new BTreeMVBucketV2DecrementEntriesCountOp(0, 0, 0, lsn, 0);

      default -> throw new IllegalArgumentException("Unknown PageOperation ID: " + id);
    };
  }
}
