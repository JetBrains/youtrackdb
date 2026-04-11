package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramStatsPageWriteEmptyOp;
import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramStatsPageWriteHllToPage1Op;
import com.jetbrains.youtrackdb.internal.core.index.engine.HistogramStatsPageWriteSnapshotOp;
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
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2AddAllOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2AddLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2AddNonLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2InitOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2RemoveLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2RemoveNonLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2SetLeftSiblingOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2SetRightSiblingOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2SetTreeSizeOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2ShrinkOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2SwitchBucketTypeOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeBucketV2UpdateValueOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeNullBucketV2InitOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeNullBucketV2RemoveValueOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2.SBTreeNullBucketV2SetValueOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2AddAllLeafEntriesOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2AddAllNonLeafEntriesOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2AddNonLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2AppendNewLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2CreateMainLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2DecrementEntriesCountOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2IncrementEntriesCountOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2InitOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2RemoveLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2RemoveMainLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2RemoveNonLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2SetLeftSiblingOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2SetRightSiblingOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2ShrinkLeafEntriesOp;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2ShrinkNonLeafEntriesOp;
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
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketAddAllOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketAddLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketAddNonLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketInitOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketRemoveLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketRemoveNonLeafEntryOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketSetLeftSiblingOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketSetRightSiblingOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketShrinkOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketSwitchBucketTypeOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagBucketUpdateValueOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagEntryPointInitOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagEntryPointSetPagesSizeOp;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagEntryPointSetTreeSizeOp;

/**
 * Registers all {@link PageOperation} subclasses with {@link WALRecordsFactory} so that recovery
 * can deserialize logical WAL records. Must be called after WAL initialization but before
 * {@code recoverIfNeeded()} — see {@code AbstractStorage.open()}.
 *
 * <p>Future tracks will add their PageOperation types here as they are implemented.
 */
public final class PageOperationRegistry {

  private PageOperationRegistry() {
  }

  /**
   * Registers all known {@link PageOperation} subclasses with the given factory. Each type is
   * registered with its unique WAL record type ID (see {@link WALRecordTypes}).
   *
   * <p>Currently registers Track 2-3 types (IDs 201-218), Track 5 types (IDs 219-238),
   * Track 6 types (IDs 239-263), Track 7a types (IDs 264-278), and Track 7b types
   * (IDs 279-281):
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
   *   <li>CellBTreeMultiValueV2Bucket simple (6 ops: init, switchType, siblings,
   *       increment/decrement entries count)</li>
   *   <li>CellBTreeMultiValueV2Bucket entry (6 ops: createMainLeaf, removeMainLeaf,
   *       appendNewLeaf, removeLeaf, addNonLeaf, removeNonLeaf)</li>
   *   <li>CellBTreeMultiValueV2Bucket bulk (4 ops: addAllLeaf, addAllNonLeaf,
   *       shrinkLeaf, shrinkNonLeaf)</li>
   *   <li>SBTreeNullBucketV2 (3 ops: init, setValue, removeValue)</li>
   *   <li>SBTreeBucketV2 simple (5 ops: init, switchType, treeSize, siblings)</li>
   *   <li>SBTreeBucketV2 entry (5 ops: addLeaf, addNonLeaf, removeLeaf, removeNonLeaf,
   *       updateValue)</li>
   *   <li>SBTreeBucketV2 bulk (2 ops: addAll, shrink)</li>
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

    // CellBTreeMultiValueV2Bucket simple operations (Track 6)
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_INIT_OP,
        BTreeMVBucketV2InitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_SWITCH_BUCKET_TYPE_OP,
        BTreeMVBucketV2SwitchBucketTypeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_SET_LEFT_SIBLING_OP,
        BTreeMVBucketV2SetLeftSiblingOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_SET_RIGHT_SIBLING_OP,
        BTreeMVBucketV2SetRightSiblingOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_INCREMENT_ENTRIES_COUNT_OP,
        BTreeMVBucketV2IncrementEntriesCountOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_DECREMENT_ENTRIES_COUNT_OP,
        BTreeMVBucketV2DecrementEntriesCountOp.class);

    // CellBTreeMultiValueV2Bucket entry operations (Track 6)
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_CREATE_MAIN_LEAF_ENTRY_OP,
        BTreeMVBucketV2CreateMainLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_MAIN_LEAF_ENTRY_OP,
        BTreeMVBucketV2RemoveMainLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_APPEND_NEW_LEAF_ENTRY_OP,
        BTreeMVBucketV2AppendNewLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_LEAF_ENTRY_OP,
        BTreeMVBucketV2RemoveLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_NON_LEAF_ENTRY_OP,
        BTreeMVBucketV2AddNonLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_NON_LEAF_ENTRY_OP,
        BTreeMVBucketV2RemoveNonLeafEntryOp.class);

    // CellBTreeMultiValueV2Bucket bulk operations (Track 6)
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_ALL_LEAF_ENTRIES_OP,
        BTreeMVBucketV2AddAllLeafEntriesOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_ALL_NON_LEAF_ENTRIES_OP,
        BTreeMVBucketV2AddAllNonLeafEntriesOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_SHRINK_LEAF_ENTRIES_OP,
        BTreeMVBucketV2ShrinkLeafEntriesOp.class);
    factory.registerNewRecord(
        WALRecordTypes.BTREE_MV_BUCKET_V2_SHRINK_NON_LEAF_ENTRIES_OP,
        BTreeMVBucketV2ShrinkNonLeafEntriesOp.class);

    // SBTreeNullBucketV2 operations (Track 7a)
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_NULL_BUCKET_V2_INIT_OP,
        SBTreeNullBucketV2InitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_NULL_BUCKET_V2_SET_VALUE_OP,
        SBTreeNullBucketV2SetValueOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_NULL_BUCKET_V2_REMOVE_VALUE_OP,
        SBTreeNullBucketV2RemoveValueOp.class);

    // SBTreeBucketV2 simple operations (Track 7a)
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_INIT_OP,
        SBTreeBucketV2InitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_SWITCH_BUCKET_TYPE_OP,
        SBTreeBucketV2SwitchBucketTypeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_SET_TREE_SIZE_OP,
        SBTreeBucketV2SetTreeSizeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_SET_LEFT_SIBLING_OP,
        SBTreeBucketV2SetLeftSiblingOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_SET_RIGHT_SIBLING_OP,
        SBTreeBucketV2SetRightSiblingOp.class);

    // SBTreeBucketV2 entry + update operations (Track 7a)
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_ADD_LEAF_ENTRY_OP,
        SBTreeBucketV2AddLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_ADD_NON_LEAF_ENTRY_OP,
        SBTreeBucketV2AddNonLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_REMOVE_LEAF_ENTRY_OP,
        SBTreeBucketV2RemoveLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_REMOVE_NON_LEAF_ENTRY_OP,
        SBTreeBucketV2RemoveNonLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_UPDATE_VALUE_OP,
        SBTreeBucketV2UpdateValueOp.class);

    // SBTreeBucketV2 bulk operations (Track 7a)
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_ADD_ALL_OP,
        SBTreeBucketV2AddAllOp.class);
    factory.registerNewRecord(
        WALRecordTypes.SBTREE_BUCKET_V2_SHRINK_OP,
        SBTreeBucketV2ShrinkOp.class);

    // HistogramStatsPage operations (Track 7b)
    factory.registerNewRecord(
        WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_EMPTY_OP,
        HistogramStatsPageWriteEmptyOp.class);
    factory.registerNewRecord(
        WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_SNAPSHOT_OP,
        HistogramStatsPageWriteSnapshotOp.class);
    factory.registerNewRecord(
        WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_HLL_TO_PAGE1_OP,
        HistogramStatsPageWriteHllToPage1Op.class);

    // Ridbag EntryPoint operations (Track 7b)
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_ENTRY_POINT_INIT_OP,
        RidbagEntryPointInitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_ENTRY_POINT_SET_TREE_SIZE_OP,
        RidbagEntryPointSetTreeSizeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_ENTRY_POINT_SET_PAGES_SIZE_OP,
        RidbagEntryPointSetPagesSizeOp.class);

    // Ridbag Bucket simple operations (Track 7b)
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_INIT_OP,
        RidbagBucketInitOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_SWITCH_BUCKET_TYPE_OP,
        RidbagBucketSwitchBucketTypeOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_SET_LEFT_SIBLING_OP,
        RidbagBucketSetLeftSiblingOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_SET_RIGHT_SIBLING_OP,
        RidbagBucketSetRightSiblingOp.class);

    // Ridbag Bucket entry + bulk + updateValue operations (Track 7b)
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_ADD_LEAF_ENTRY_OP,
        RidbagBucketAddLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_ADD_NON_LEAF_ENTRY_OP,
        RidbagBucketAddNonLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_REMOVE_LEAF_ENTRY_OP,
        RidbagBucketRemoveLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_REMOVE_NON_LEAF_ENTRY_OP,
        RidbagBucketRemoveNonLeafEntryOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_ADD_ALL_OP,
        RidbagBucketAddAllOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_SHRINK_OP,
        RidbagBucketShrinkOp.class);
    factory.registerNewRecord(
        WALRecordTypes.RIDBAG_BUCKET_UPDATE_VALUE_OP,
        RidbagBucketUpdateValueOp.class);
  }
}
