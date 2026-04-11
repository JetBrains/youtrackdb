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
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
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
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests that {@link PageOperationRegistry#registerAll(WALRecordsFactory)} correctly registers
 * all 95 Track 2-3, Track 5, Track 6, Track 7a, and Track 7b PageOperation types so they can be
 * deserialized by the factory during recovery.
 */
public class PageOperationRegistryTest {

  @BeforeClass
  public static void register() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  /**
   * Verifies that all 95 registered record IDs survive a full WALRecordsFactory roundtrip:
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

        // Track 6: CellBTreeMultiValueV2Bucket entry (6 ops)
        new BTreeMVBucketV2CreateMainLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, new byte[] {1, 2, 3}, (short) 5, 1000L, 42L),
        new BTreeMVBucketV2RemoveMainLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn, 0, 3),
        new BTreeMVBucketV2AppendNewLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn, 0, (short) 7, 2000L),
        new BTreeMVBucketV2RemoveLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn, 0, (short) 5, 1000L),
        new BTreeMVBucketV2AddNonLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, new byte[] {4, 5, 6}, 1, 2, true),
        new BTreeMVBucketV2RemoveNonLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn, 0, new byte[] {4, 5, 6}, 3),

        // Track 7a: SBTreeNullBucketV2 (3 ops)
        new SBTreeNullBucketV2InitOp(pageIndex, fileId, opUnitId, initialLsn),
        new SBTreeNullBucketV2SetValueOp(
            pageIndex, fileId, opUnitId, initialLsn, new byte[] {1, 2}),
        new SBTreeNullBucketV2RemoveValueOp(pageIndex, fileId, opUnitId, initialLsn),

        // Track 7a: SBTreeBucketV2 simple (5 ops)
        new SBTreeBucketV2InitOp(pageIndex, fileId, opUnitId, initialLsn, true),
        new SBTreeBucketV2SwitchBucketTypeOp(pageIndex, fileId, opUnitId, initialLsn),
        new SBTreeBucketV2SetTreeSizeOp(pageIndex, fileId, opUnitId, initialLsn, 42L),
        new SBTreeBucketV2SetLeftSiblingOp(pageIndex, fileId, opUnitId, initialLsn, 100L),
        new SBTreeBucketV2SetRightSiblingOp(pageIndex, fileId, opUnitId, initialLsn, 200L),

        // Track 7a: SBTreeBucketV2 entry + update (5 ops)
        new SBTreeBucketV2AddLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, new byte[] {1, 2}, new byte[] {3, 4}),
        new SBTreeBucketV2AddNonLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, new byte[] {1, 2}, 10L, 20L, true),
        new SBTreeBucketV2RemoveLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, new byte[] {1, 2}, new byte[] {3, 4}),
        new SBTreeBucketV2RemoveNonLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, new byte[] {1, 2}, 42),
        new SBTreeBucketV2UpdateValueOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, new byte[] {10, 20}, 4),

        // Track 7a: SBTreeBucketV2 bulk (2 ops)
        new SBTreeBucketV2AddAllOp(
            pageIndex, fileId, opUnitId, initialLsn,
            List.of(new byte[] {1, 2, 3})),
        new SBTreeBucketV2ShrinkOp(
            pageIndex, fileId, opUnitId, initialLsn,
            List.of(new byte[] {4, 5, 6})),

        // Track 7b: HistogramStatsPage (3 ops)
        new HistogramStatsPageWriteEmptyOp(
            pageIndex, fileId, opUnitId, initialLsn, (byte) 5),
        new HistogramStatsPageWriteSnapshotOp(
            pageIndex, fileId, opUnitId, initialLsn,
            (byte) 7, 1000L, 500L, 50L, 10L, 900L,
            1024, new byte[] {1, 2, 3}, new byte[] {4, 5}),
        new HistogramStatsPageWriteHllToPage1Op(
            pageIndex, fileId, opUnitId, initialLsn, new byte[] {10, 20, 30}),

        // Track 7b: Ridbag EntryPoint (3 ops)
        new RidbagEntryPointInitOp(pageIndex, fileId, opUnitId, initialLsn),
        new RidbagEntryPointSetTreeSizeOp(
            pageIndex, fileId, opUnitId, initialLsn, 999L),
        new RidbagEntryPointSetPagesSizeOp(
            pageIndex, fileId, opUnitId, initialLsn, 7),

        // Track 7b: Ridbag Bucket simple (4 ops)
        new RidbagBucketInitOp(pageIndex, fileId, opUnitId, initialLsn, true),
        new RidbagBucketSwitchBucketTypeOp(pageIndex, fileId, opUnitId, initialLsn),
        new RidbagBucketSetLeftSiblingOp(
            pageIndex, fileId, opUnitId, initialLsn, 100L),
        new RidbagBucketSetRightSiblingOp(
            pageIndex, fileId, opUnitId, initialLsn, 200L),

        // Track 7b: Ridbag Bucket entry + bulk + updateValue (7 ops)
        new RidbagBucketAddLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, new byte[] {1, 2}, new byte[] {3, 4}),
        new RidbagBucketAddNonLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, 1, 2, new byte[] {5, 6}, true),
        new RidbagBucketRemoveLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn, 0, 2, 3),
        new RidbagBucketRemoveNonLeafEntryOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, new byte[] {7, 8}, 42),
        new RidbagBucketAddAllOp(
            pageIndex, fileId, opUnitId, initialLsn,
            List.of(new byte[] {1, 2, 3})),
        new RidbagBucketShrinkOp(
            pageIndex, fileId, opUnitId, initialLsn,
            List.of(new byte[] {4, 5, 6})),
        new RidbagBucketUpdateValueOp(
            pageIndex, fileId, opUnitId, initialLsn,
            0, new byte[] {10, 20}, 2),
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
    // IDs 201-295 = 95 types (18 Track 2-3 + 20 Track 5 + 25 Track 6 + 15 Track 7a
    //   + 17 Track 7b).
    // Each ID must have both a createOpForId entry and a factory registration.
    // createOpForId throws for unknown IDs, so any gap causes immediate failure.
    int registeredCount = 0;
    for (int id = WALRecordTypes.PAGE_OPERATION_ID_BASE + 1;
        id <= WALRecordTypes.PAGE_OPERATION_ID_BASE + 95; id++) {
      var testOp = createMinimalRecord(id);
      Assert.assertNotNull("WAL record ID " + id + " failed to roundtrip", testOp);
      registeredCount++;
    }
    Assert.assertEquals("Expected 95 registered PageOperation types", 95, registeredCount);
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

      // Track 6: CellBTreeMultiValueV2Bucket entry (6 ops)
      case WALRecordTypes.BTREE_MV_BUCKET_V2_CREATE_MAIN_LEAF_ENTRY_OP ->
          new BTreeMVBucketV2CreateMainLeafEntryOp(
              0, 0, 0, lsn, 0, new byte[] {}, (short) -1, -1L, 0L);
      case WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_MAIN_LEAF_ENTRY_OP ->
          new BTreeMVBucketV2RemoveMainLeafEntryOp(0, 0, 0, lsn, 0, 1);
      case WALRecordTypes.BTREE_MV_BUCKET_V2_APPEND_NEW_LEAF_ENTRY_OP ->
          new BTreeMVBucketV2AppendNewLeafEntryOp(0, 0, 0, lsn, 0, (short) 0, 0L);
      case WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_LEAF_ENTRY_OP ->
          new BTreeMVBucketV2RemoveLeafEntryOp(0, 0, 0, lsn, 0, (short) 0, 0L);
      case WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_NON_LEAF_ENTRY_OP ->
          new BTreeMVBucketV2AddNonLeafEntryOp(
              0, 0, 0, lsn, 0, new byte[] {}, 0, 0, false);
      case WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_NON_LEAF_ENTRY_OP ->
          new BTreeMVBucketV2RemoveNonLeafEntryOp(0, 0, 0, lsn, 0, new byte[] {}, 0);

      // Track 6: CellBTreeMultiValueV2Bucket bulk (4 ops)
      case WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_ALL_LEAF_ENTRIES_OP ->
          new BTreeMVBucketV2AddAllLeafEntriesOp(0, 0, 0, lsn, List.of());
      case WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_ALL_NON_LEAF_ENTRIES_OP ->
          new BTreeMVBucketV2AddAllNonLeafEntriesOp(0, 0, 0, lsn, List.of());
      case WALRecordTypes.BTREE_MV_BUCKET_V2_SHRINK_LEAF_ENTRIES_OP ->
          new BTreeMVBucketV2ShrinkLeafEntriesOp(0, 0, 0, lsn, List.of());
      case WALRecordTypes.BTREE_MV_BUCKET_V2_SHRINK_NON_LEAF_ENTRIES_OP ->
          new BTreeMVBucketV2ShrinkNonLeafEntriesOp(0, 0, 0, lsn, List.of());

      // Track 7a: SBTreeNullBucketV2 (3 ops)
      case WALRecordTypes.SBTREE_NULL_BUCKET_V2_INIT_OP ->
          new SBTreeNullBucketV2InitOp(0, 0, 0, lsn);
      case WALRecordTypes.SBTREE_NULL_BUCKET_V2_SET_VALUE_OP ->
          new SBTreeNullBucketV2SetValueOp(0, 0, 0, lsn, new byte[] {});
      case WALRecordTypes.SBTREE_NULL_BUCKET_V2_REMOVE_VALUE_OP ->
          new SBTreeNullBucketV2RemoveValueOp(0, 0, 0, lsn);

      // Track 7a: SBTreeBucketV2 simple (5 ops)
      case WALRecordTypes.SBTREE_BUCKET_V2_INIT_OP ->
          new SBTreeBucketV2InitOp(0, 0, 0, lsn, true);
      case WALRecordTypes.SBTREE_BUCKET_V2_SWITCH_BUCKET_TYPE_OP ->
          new SBTreeBucketV2SwitchBucketTypeOp(0, 0, 0, lsn);
      case WALRecordTypes.SBTREE_BUCKET_V2_SET_TREE_SIZE_OP ->
          new SBTreeBucketV2SetTreeSizeOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.SBTREE_BUCKET_V2_SET_LEFT_SIBLING_OP ->
          new SBTreeBucketV2SetLeftSiblingOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.SBTREE_BUCKET_V2_SET_RIGHT_SIBLING_OP ->
          new SBTreeBucketV2SetRightSiblingOp(0, 0, 0, lsn, 0L);

      // Track 7a: SBTreeBucketV2 entry + update (5 ops)
      case WALRecordTypes.SBTREE_BUCKET_V2_ADD_LEAF_ENTRY_OP ->
          new SBTreeBucketV2AddLeafEntryOp(0, 0, 0, lsn, 0, new byte[] {}, new byte[] {});
      case WALRecordTypes.SBTREE_BUCKET_V2_ADD_NON_LEAF_ENTRY_OP ->
          new SBTreeBucketV2AddNonLeafEntryOp(
              0, 0, 0, lsn, 0, new byte[] {}, 0L, 0L, false);
      case WALRecordTypes.SBTREE_BUCKET_V2_REMOVE_LEAF_ENTRY_OP ->
          new SBTreeBucketV2RemoveLeafEntryOp(
              0, 0, 0, lsn, 0, new byte[] {}, new byte[] {});
      case WALRecordTypes.SBTREE_BUCKET_V2_REMOVE_NON_LEAF_ENTRY_OP ->
          new SBTreeBucketV2RemoveNonLeafEntryOp(0, 0, 0, lsn, 0, new byte[] {}, 0);
      case WALRecordTypes.SBTREE_BUCKET_V2_UPDATE_VALUE_OP ->
          new SBTreeBucketV2UpdateValueOp(0, 0, 0, lsn, 0, new byte[] {}, 0);
      case WALRecordTypes.SBTREE_BUCKET_V2_ADD_ALL_OP ->
          new SBTreeBucketV2AddAllOp(0, 0, 0, lsn, List.of());
      case WALRecordTypes.SBTREE_BUCKET_V2_SHRINK_OP ->
          new SBTreeBucketV2ShrinkOp(0, 0, 0, lsn, List.of());

      // Track 7b: HistogramStatsPage (3 ops)
      case WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_EMPTY_OP ->
          new HistogramStatsPageWriteEmptyOp(0, 0, 0, lsn, (byte) 0);
      case WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_SNAPSHOT_OP ->
          new HistogramStatsPageWriteSnapshotOp(
              0, 0, 0, lsn, (byte) 0, 0L, 0L, 0L, 0L, 0L, 0,
              new byte[0], new byte[0]);
      case WALRecordTypes.HISTOGRAM_STATS_PAGE_WRITE_HLL_TO_PAGE1_OP ->
          new HistogramStatsPageWriteHllToPage1Op(0, 0, 0, lsn, new byte[0]);

      // Track 7b: Ridbag EntryPoint (3 ops)
      case WALRecordTypes.RIDBAG_ENTRY_POINT_INIT_OP ->
          new RidbagEntryPointInitOp(0, 0, 0, lsn);
      case WALRecordTypes.RIDBAG_ENTRY_POINT_SET_TREE_SIZE_OP ->
          new RidbagEntryPointSetTreeSizeOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.RIDBAG_ENTRY_POINT_SET_PAGES_SIZE_OP ->
          new RidbagEntryPointSetPagesSizeOp(0, 0, 0, lsn, 0);

      // Track 7b: Ridbag Bucket simple (4 ops)
      case WALRecordTypes.RIDBAG_BUCKET_INIT_OP ->
          new RidbagBucketInitOp(0, 0, 0, lsn, true);
      case WALRecordTypes.RIDBAG_BUCKET_SWITCH_BUCKET_TYPE_OP ->
          new RidbagBucketSwitchBucketTypeOp(0, 0, 0, lsn);
      case WALRecordTypes.RIDBAG_BUCKET_SET_LEFT_SIBLING_OP ->
          new RidbagBucketSetLeftSiblingOp(0, 0, 0, lsn, 0L);
      case WALRecordTypes.RIDBAG_BUCKET_SET_RIGHT_SIBLING_OP ->
          new RidbagBucketSetRightSiblingOp(0, 0, 0, lsn, 0L);

      // Track 7b: Ridbag Bucket entry + bulk + updateValue (7 ops)
      case WALRecordTypes.RIDBAG_BUCKET_ADD_LEAF_ENTRY_OP ->
          new RidbagBucketAddLeafEntryOp(0, 0, 0, lsn, 0, new byte[] {}, new byte[] {});
      case WALRecordTypes.RIDBAG_BUCKET_ADD_NON_LEAF_ENTRY_OP ->
          new RidbagBucketAddNonLeafEntryOp(0, 0, 0, lsn, 0, 0, 0, new byte[] {}, false);
      case WALRecordTypes.RIDBAG_BUCKET_REMOVE_LEAF_ENTRY_OP ->
          new RidbagBucketRemoveLeafEntryOp(0, 0, 0, lsn, 0, 0, 0);
      case WALRecordTypes.RIDBAG_BUCKET_REMOVE_NON_LEAF_ENTRY_OP ->
          new RidbagBucketRemoveNonLeafEntryOp(0, 0, 0, lsn, 0, new byte[] {}, 0);
      case WALRecordTypes.RIDBAG_BUCKET_ADD_ALL_OP ->
          new RidbagBucketAddAllOp(0, 0, 0, lsn, List.of());
      case WALRecordTypes.RIDBAG_BUCKET_SHRINK_OP ->
          new RidbagBucketShrinkOp(0, 0, 0, lsn, List.of());
      case WALRecordTypes.RIDBAG_BUCKET_UPDATE_VALUE_OP ->
          new RidbagBucketUpdateValueOp(0, 0, 0, lsn, 0, new byte[] {}, 0);

      default -> throw new IllegalArgumentException("Unknown PageOperation ID: " + id);
    };
  }
}
