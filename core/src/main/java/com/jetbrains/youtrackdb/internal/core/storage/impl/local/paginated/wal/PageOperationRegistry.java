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
   * <p>Currently registers Track 2-3 types (IDs 201-218):
   * <ul>
   *   <li>PaginatedCollectionStateV2 (2 ops)</li>
   *   <li>CollectionPage (5 ops)</li>
   *   <li>CollectionPositionMapBucket (5 ops)</li>
   *   <li>FreeSpaceMapPage (2 ops)</li>
   *   <li>DirtyPageBitSetPage (3 ops)</li>
   *   <li>MapEntryPoint v2 (1 op)</li>
   * </ul>
   */
  public static synchronized void registerAll(WALRecordsFactory factory) {
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
  }
}
