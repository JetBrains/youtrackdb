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

/**
 * Registers all {@link PageOperation} subclasses with {@link WALRecordsFactory} so that recovery
 * can deserialize logical WAL records. Must be called after WAL initialization but before
 * {@code recoverIfNeeded()} — see {@code AbstractStorage.open()}.
 *
 * <p>Future tracks (5-7b) will add their PageOperation types here as they are implemented.
 */
public final class PageOperationRegistry {

  private PageOperationRegistry() {
  }

  /**
   * Registers all known {@link PageOperation} subclasses with the given factory. Each type is
   * registered with its unique WAL record type ID (see {@link WALRecordTypes}).
   *
   * <p>Currently registers Track 2-3 types (IDs 201-218), Track 5 types (IDs 219-238),
  * and Track 6 types (IDs 239-247):
   * <ul>
   *   <li>PaginatedCollectionStateV2 (2 ops)</li>
   *   <li>CollectionPage (5 ops)</li>
   *   <li>CollectionPositionMapBucket (5 ops)</li>
   *   <li>FreeSpaceMapPage (2 ops)</li>
   *   <li>DirtyPageBitSetPage (3 ops)</li>
   *   <li>MapEntryPoint v2 (1 op)</li>
   *   <li>CellBTreeSingleValueEntryPointV3 (4 ops)</li>
   *   <li>CellBTreeSingleValueV3NullBucket (3 ops)</li>
   *   <li>CellBTreeSingleValueBucketV3 (13 ops: init, switchType, siblings, freeList,
   *       updateValue, add/remove leaf/nonLeaf, updateKey, addAll, shrink)</li>
   *   <li>CellBTreeMultiValueV2EntryPoint (4 ops)</li>
   *   <li>CellBTreeMultiValueV2NullBucket (5 ops)</li>
   * </ul>
   */
  public static void registerAll(WALRecordsFactory factory) {
    // PaginatedCollectionStateV2 operations (Track 2)
    factory.registerNewRecord(
        WALRecordTypes.PAGINATED_COLLECTION_STATE_V2_SET_FILE_SIZE_OP,
        PaginatedCollectionStateV2SetFileSizeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.PAGINATED_COLLECTION_STATE_V2_SET_APPROX_RECORDS_COUNT_OP,
        PaginatedCollectionStateV2SetApproxRecordsCountOp.class);

    // CollectionPage operations (Track 2)
    factory.registerNewRecord(
        WALRecordTypes.COLLECTION_PAGE_INIT_OP,
        CollectionPageInitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.COLLECTION_PAGE_DELETE_RECORD_OP,
        CollectionPageDeleteRecordOp.class);
    factory.registerNewRecord(
        WALRecordTypes.COLLECTION_PAGE_SET_RECORD_VERSION_OP,
        CollectionPageSetRecordVersionOp.class);
    factory.registerNewRecord(
        WALRecordTypes.COLLECTION_PAGE_DO_DEFRAGMENTATION_OP,
        CollectionPageDoDefragmentationOp.class);
    factory.registerNewRecord(
        WALRecordTypes.COLLECTION_PAGE_APPEND_RECORD_OP,
        CollectionPageAppendRecordOp.class);

    // CollectionPositionMapBucket operations (Track 2)
    factory.registerNewRecord(
        WALRecordTypes.POSITION_MAP_BUCKET_INIT_OP,
        CollectionPositionMapBucketInitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.POSITION_MAP_BUCKET_ALLOCATE_OP,
        CollectionPositionMapBucketAllocateOp.class);
    factory.registerNewRecord(
        WALRecordTypes.POSITION_MAP_BUCKET_SET_OP,
        CollectionPositionMapBucketSetOp.class);
    factory.registerNewRecord(
        WALRecordTypes.POSITION_MAP_BUCKET_REMOVE_OP,
        CollectionPositionMapBucketRemoveOp.class);
    factory.registerNewRecord(
        WALRecordTypes.POSITION_MAP_BUCKET_UPDATE_VERSION_OP,
        CollectionPositionMapBucketUpdateVersionOp.class);

    // FreeSpaceMapPage operations (Track 3)
    factory.registerNewRecord(
        WALRecordTypes.FREE_SPACE_MAP_PAGE_INIT_OP,
        FreeSpaceMapPageInitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.FREE_SPACE_MAP_PAGE_UPDATE_OP,
        FreeSpaceMapPageUpdateOp.class);

    // DirtyPageBitSetPage operations (Track 3)
    factory.registerNewRecord(
        WALRecordTypes.DIRTY_PAGE_BIT_SET_PAGE_INIT_OP,
        DirtyPageBitSetPageInitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.DIRTY_PAGE_BIT_SET_PAGE_SET_BIT_OP,
        DirtyPageBitSetPageSetBitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.DIRTY_PAGE_BIT_SET_PAGE_CLEAR_BIT_OP,
        DirtyPageBitSetPageClearBitOp.class);

    // MapEntryPoint v2 operations (Track 3)
    factory.registerNewRecord(
        WALRecordTypes.MAP_ENTRY_POINT_SET_FILE_SIZE_OP,
        MapEntryPointSetFileSizeOp.class);

    // CellBTreeSingleValueEntryPointV3 operations (Track 5)
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_INIT_OP,
        BTreeSVEntryPointV3InitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_TREE_SIZE_OP,
        BTreeSVEntryPointV3SetTreeSizeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_PAGES_SIZE_OP,
        BTreeSVEntryPointV3SetPagesSizeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_FREE_LIST_HEAD_OP,
        BTreeSVEntryPointV3SetFreeListHeadOp.class);

    // CellBTreeSingleValueV3NullBucket operations (Track 5)
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_INIT_OP,
        BTreeSVNullBucketV3InitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_SET_VALUE_OP,
        BTreeSVNullBucketV3SetValueOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_REMOVE_VALUE_OP,
        BTreeSVNullBucketV3RemoveValueOp.class);

    // CellBTreeSingleValueBucketV3 simple operations (Track 5)
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_INIT_OP,
        BTreeSVBucketV3InitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_SWITCH_BUCKET_TYPE_OP,
        BTreeSVBucketV3SwitchBucketTypeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_SET_LEFT_SIBLING_OP,
        BTreeSVBucketV3SetLeftSiblingOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_SET_RIGHT_SIBLING_OP,
        BTreeSVBucketV3SetRightSiblingOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_SET_NEXT_FREE_LIST_PAGE_OP,
        BTreeSVBucketV3SetNextFreeListPageOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_UPDATE_VALUE_OP,
        BTreeSVBucketV3UpdateValueOp.class);

    // CellBTreeSingleValueBucketV3 entry operations (Track 5)
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_ADD_LEAF_ENTRY_OP,
        BTreeSVBucketV3AddLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_ADD_NON_LEAF_ENTRY_OP,
        BTreeSVBucketV3AddNonLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_REMOVE_LEAF_ENTRY_OP,
        BTreeSVBucketV3RemoveLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_REMOVE_NON_LEAF_ENTRY_OP,
        BTreeSVBucketV3RemoveNonLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_UPDATE_KEY_OP,
        BTreeSVBucketV3UpdateKeyOp.class);

    // CellBTreeSingleValueBucketV3 bulk operations (Track 5)
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_ADD_ALL_OP,
        BTreeSVBucketV3AddAllOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_SV_BUCKET_V3_SHRINK_OP,
        BTreeSVBucketV3ShrinkOp.class);

    // CellBTreeMultiValueV2EntryPoint operations (Track 6)
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_INIT_OP,
        BTreeMVEntryPointV2InitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_TREE_SIZE_OP,
        BTreeMVEntryPointV2SetTreeSizeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_PAGES_SIZE_OP,
        BTreeMVEntryPointV2SetPagesSizeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_ENTRY_ID_OP,
        BTreeMVEntryPointV2SetEntryIdOp.class);

    // CellBTreeMultiValueV2NullBucket operations (Track 6)
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_INIT_OP,
        BTreeMVNullBucketV2InitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_ADD_VALUE_OP,
        BTreeMVNullBucketV2AddValueOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_REMOVE_VALUE_OP,
        BTreeMVNullBucketV2RemoveValueOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_INCREMENT_SIZE_OP,
        BTreeMVNullBucketV2IncrementSizeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_DECREMENT_SIZE_OP,
        BTreeMVNullBucketV2DecrementSizeOp.class);
  }
}
