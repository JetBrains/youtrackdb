package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.function.TxConsumer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * Unit tests for
 * {@link PaginatedCollectionV2#verifyAndTruncateOrphans(AtomicOperation, ReadCache, WriteCache)}.
 *
 * <p>The helper reads PCV2's collection-state page's {@code fileSize} counter (which
 * records the number of data pages in use, <em>not</em> including the state page
 * itself), computes the expected physical file size as
 * {@code max(pageSize, (fileSize + 1) * pageSize)}, and dispatches to
 * {@link ReadCache#shrinkFile(long, long, WriteCache)}.
 *
 * <p>Source anchor: {@code PaginatedCollectionV2.initCollectionState()} at lines
 * 2330-2354 (sets fileSize = 0 on create — the legitimate post-create state).
 *
 * <p>The PCV2 ctor wires up sub-components (cpm, fsm, dpb); to keep this unit test
 * focused on the primary-file truncate helper, the simulated file map uses distinct
 * fileIds per opened file and the helper only operates on PCV2's own {@code fileId}.
 * The sub-components have their own {@code verifyAndTruncateOrphans} suites
 * (CPMV2's is in {@code CollectionPositionMapV2Test}); FSM and CDPB are explicitly
 * out of scope (allocator-only growth loops; no logical/physical split means no
 * partial-flush orphan hazard).
 */
public class PaginatedCollectionV2VerifyAndTruncateOrphansTest {

  // PCV2's own data file (.pcl).
  private static final long FILE_ID = 1L;
  // Sub-component files (cpm, fsm, dpb) get distinct ids so the helper's primary-file
  // assertions don't get crossed with sub-component traffic.
  private static final long CPM_FILE_ID = 2L;
  private static final long FSM_FILE_ID = 3L;
  private static final long DPB_FILE_ID = 4L;

  private static final String COLLECTION_NAME = "testCollection";
  private static final int PAGE_SIZE_BYTES = 8 * 1024;

  private ByteBufferPool bufferPool;
  private ReadCache mockReadCache;
  private WriteCache mockWriteCache;
  private AbstractStorage mockStorage;
  private AtomicOperation atomicOperation;

  // Per-file page count + page-pointer map so reads/writes against the same
  // (fileId, pageIndex) share the same direct-memory buffer (mirrors the real
  // disk cache's referrer-counted shared buffers).
  private final Map<Long, Integer> pageCountByFile = new HashMap<>();
  private final Map<Long, Map<Integer, CachePointer>> pagePointers = new HashMap<>();

  private PaginatedCollectionV2 collection;

  @Before
  public void setUp() throws IOException {
    bufferPool = ByteBufferPool.instance(null);
    mockReadCache = mock(ReadCache.class);
    mockWriteCache = mock(WriteCache.class);
    when(mockWriteCache.pageSize()).thenReturn(PAGE_SIZE_BYTES);
    mockStorage = mock(AbstractStorage.class);

    var mockAtomicOperationsManager = mock(AtomicOperationsManager.class);
    // PCV2.create() routes through executeInsideComponentOperation; install an
    // Answer that runs the consumer so create()'s body actually executes (otherwise
    // PCV2.fileId stays at 0 and the helper would read a fileId=0 file).
    org.mockito.Mockito.doAnswer(inv -> {
      AtomicOperation op = inv.getArgument(0);
      TxConsumer consumer = inv.getArgument(2);
      consumer.accept(op);
      return null;
    }).when(mockAtomicOperationsManager).executeInsideComponentOperation(
        org.mockito.ArgumentMatchers.any(AtomicOperation.class),
        org.mockito.ArgumentMatchers.any(StorageComponent.class),
        org.mockito.ArgumentMatchers.any(TxConsumer.class));

    when(mockStorage.getReadCache()).thenReturn(mockReadCache);
    when(mockStorage.getWriteCache()).thenReturn(mockWriteCache);
    when(mockStorage.getAtomicOperationsManager()).thenReturn(mockAtomicOperationsManager);
    when(mockStorage.getName()).thenReturn("test-storage");
    var componentsFactory =
        new CurrentStorageComponentsFactory(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    when(mockStorage.getComponentsFactory()).thenReturn(componentsFactory);

    pageCountByFile.clear();
    pagePointers.clear();

    atomicOperation = createAtomicOperation();
    collection = new PaginatedCollectionV2(COLLECTION_NAME, mockStorage);
    collection.create(atomicOperation);
  }

  @After
  public void tearDown() {
    for (var perFile : pagePointers.values()) {
      for (var cp : perFile.values()) {
        cp.decrementReferrer();
      }
    }
    pagePointers.clear();
  }

  // Drive the real private auxiliaries through the parent. Every lifecycle handle must use
  // its exact owner and role. Omitting any auxiliary identity must fail these verifications.
  @Test
  public void explicitFamiliesUseExactHandlesForCreateOpenPresenceAndDelete() throws IOException {
    when(mockStorage.getStorageFormat()).thenReturn(
        StorageFormat.LAYOUT_28);
    when(mockWriteCache.getId()).thenReturn(17);
    for (var dataRole : new PageFileRole[] {
        PageFileRole.CONFIG_DATA,
        PageFileRole.RECORD_DATA}) {
      var owner =
          new PageFileIdentity(
              dataRole, 7);
      var identities = new ArrayList<
          PageFileIdentity>();
      for (int role = 0; role < 4; role++) {
        var identity =
            new PageFileIdentity(
                PageFileRole
                    .fromCode(
                        owner.kind(), role),
                owner.componentId());
        identities.add(identity);
        var name = PageFileNames
            .layout28(identity);
        var id =
            AbstractWriteCache.composeFileId(
                17, identity.internalId());
        when(atomicOperation.addFile(name, id, false, false)).thenReturn(id);
        when(atomicOperation.loadFile(name, id)).thenReturn(id);
        when(atomicOperation.isFileExists(name, id)).thenReturn(true);
      }
      var owned = new PaginatedCollectionV2("owned", dataRole.layout27Extension(),
          identities.get(1).role().layout27Extension(),
          identities.get(2).role().layout27Extension(),
          identities.get(3).role().layout27Extension(), mockStorage, owner);
      owned.configure(7, "owned", dataRole ==
          PageFileRole.RECORD_DATA
              ? 7 : null);
      owned.create(atomicOperation);
      owned.setCollectionName("renamed");
      assertThat(owned.getName()).isEqualTo("renamed");
      verify(mockWriteCache, never()).renameFile(anyLong(), anyString());
      assertThat(owned.exists(atomicOperation)).isTrue();
      owned.open(atomicOperation);
      owned.delete(atomicOperation);
      for (var identity : identities) {
        var name = PageFileNames
            .layout28(identity);
        var id =
            AbstractWriteCache.composeFileId(
                17, identity.internalId());
        verify(atomicOperation).addFile(name, id, false, false);
        verify(atomicOperation, org.mockito.Mockito.times(2)).loadFile(name, id);
        verify(atomicOperation).deleteFile(id);
        if (identity.role().code() != 1) {
          verify(atomicOperation).isFileExists(name, id);
        }
      }
    }
  }

  // Missing explicit auxiliaries use reconstruction only after both primary handles are open.
  @Test
  public void configurationReopenReconstructsOnlyMissingAuxiliaries() throws IOException {
    when(mockStorage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_28);
    when(mockWriteCache.getId()).thenReturn(17);
    var roles = new PageFileRole[] {PageFileRole.CONFIG_DATA, PageFileRole.CONFIG_POSITION_MAP,
        PageFileRole.CONFIG_FREE_SPACE_MAP, PageFileRole.CONFIG_DIRTY_PAGE_BIT_SET};
    for (var role : roles) {
      var identity = new PageFileIdentity(role, 0);
      var name = PageFileNames.layout28(identity);
      var id = AbstractWriteCache.composeFileId(17, identity.internalId());
      when(atomicOperation.addFile(name, id, false, false)).thenReturn(id);
      when(atomicOperation.loadFile(name, id)).thenReturn(id);
      when(atomicOperation.addFile(name, id, false, true)).thenReturn(id);
    }
    var owned = new PaginatedCollectionV2("config", ".cd", ".ccm", ".fcm", ".dcm",
        mockStorage, new PageFileIdentity(PageFileRole.CONFIG_DATA, 0));
    owned.create(atomicOperation);
    org.mockito.Mockito.clearInvocations(atomicOperation);
    owned.open(atomicOperation);
    var order = org.mockito.Mockito.inOrder(atomicOperation);
    for (int i = 0; i < roles.length; i++) {
      var identity = new PageFileIdentity(roles[i], 0);
      var name = PageFileNames.layout28(identity);
      var id = AbstractWriteCache.composeFileId(17, identity.internalId());
      if (i < 2) {
        order.verify(atomicOperation).loadFile(name, id);
        verify(atomicOperation, never()).addFile(name, id, false, true);
      } else {
        order.verify(atomicOperation).isFileExists(name, id);
        order.verify(atomicOperation).addFile(name, id, false, true);
        verify(atomicOperation, never()).addFile(name, id, false, false);
      }
    }
  }

  // Each presence error is tested with the other auxiliary valid and present.
  @Test
  public void configurationReopenPropagatesFreeSpaceMapRegistrationError() throws IOException {
    assertConfigurationRegistrationError(PageFileRole.CONFIG_FREE_SPACE_MAP);
  }

  @Test
  public void configurationReopenPropagatesDirtyPageBitSetRegistrationError() throws IOException {
    assertConfigurationRegistrationError(PageFileRole.CONFIG_DIRTY_PAGE_BIT_SET);
  }

  // Ordinary auxiliary identity conflicts must propagate without attempting reconstruction.
  @Test
  public void ordinaryReopenPropagatesFreeSpaceMapRegistrationError() throws IOException {
    assertRegistrationError(PageFileRole.FREE_SPACE_MAP);
  }

  // A wrong dirty-page registration also fails before any empty replacement can be created.
  @Test
  public void ordinaryReopenPropagatesDirtyPageBitSetRegistrationError() throws IOException {
    assertRegistrationError(PageFileRole.DIRTY_PAGE_BIT_SET);
  }

  private void assertConfigurationRegistrationError(PageFileRole failingRole) throws IOException {
    assertRegistrationError(failingRole);
  }

  private void assertRegistrationError(PageFileRole failingRole) throws IOException {
    when(mockStorage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_28);
    when(mockWriteCache.getId()).thenReturn(17);
    var dataRole = PageFileRole.fromCode(failingRole.kind(), 0);
    for (int code = 0; code < 4; code++) {
      var role = PageFileRole.fromCode(failingRole.kind(), code);
      var identity = new PageFileIdentity(role, 0);
      var name = PageFileNames.layout28(identity);
      var id = AbstractWriteCache.composeFileId(17, identity.internalId());
      when(atomicOperation.addFile(name, id, false, false)).thenReturn(id);
      when(atomicOperation.loadFile(name, id)).thenReturn(id);
      when(atomicOperation.isFileExists(name, id)).thenReturn(true);
    }
    var owned = new PaginatedCollectionV2("config", dataRole.layout27Extension(),
        PageFileRole.fromCode(failingRole.kind(), 1).layout27Extension(),
        PageFileRole.fromCode(failingRole.kind(), 2).layout27Extension(),
        PageFileRole.fromCode(failingRole.kind(), 3).layout27Extension(),
        mockStorage, new PageFileIdentity(dataRole, 0));
    owned.configure(7, "config", dataRole == PageFileRole.RECORD_DATA ? 0 : null);
    owned.create(atomicOperation);
    var identity = new PageFileIdentity(failingRole, 0);
    var name = PageFileNames.layout28(identity);
    var id = AbstractWriteCache.composeFileId(17, identity.internalId());
    var failure = new IllegalStateException("wrong " + failingRole + " identity");
    when(atomicOperation.isFileExists(name, id)).thenThrow(failure);
    org.mockito.Mockito.clearInvocations(atomicOperation);
    var thrown = org.junit.Assert.assertThrows(
        IllegalStateException.class, () -> owned.open(atomicOperation));
    org.junit.Assert.assertSame(failure, thrown);
    verify(atomicOperation).isFileExists(name, id);
    verify(atomicOperation, never()).addFile(
        anyString(), anyLong(), anyBoolean(), anyBoolean());
    if (failingRole.code() == 3) {
      var freeSpace = new PageFileIdentity(PageFileRole.fromCode(failingRole.kind(), 2), 0);
      verify(atomicOperation).loadFile(PageFileNames.layout28(freeSpace),
          AbstractWriteCache.composeFileId(17, freeSpace.internalId()));
    }
  }

  // Legacy configuration creation must allocate by name in data, position, free, dirty order.
  @Test
  public void legacyConfigurationAuxiliariesKeepCreationOrder() throws IOException {
    when(mockStorage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_27);
    when(atomicOperation.addFile("config.cd", false)).thenReturn(FILE_ID);
    when(atomicOperation.addFile("config.ccm", false)).thenReturn(CPM_FILE_ID);
    when(atomicOperation.addFile("config.fcm", false)).thenReturn(FSM_FILE_ID);
    when(atomicOperation.addFile("config.dcm", false)).thenReturn(DPB_FILE_ID);
    org.mockito.Mockito.clearInvocations(atomicOperation);
    var legacy = new PaginatedCollectionV2(
        "config", ".cd", ".ccm", ".fcm", ".dcm", mockStorage);
    legacy.create(atomicOperation);
    var order = org.mockito.Mockito.inOrder(atomicOperation);
    order.verify(atomicOperation).addFile("config.cd", false);
    order.verify(atomicOperation).addFile("config.ccm", false);
    order.verify(atomicOperation).addFile("config.fcm", false);
    order.verify(atomicOperation).addFile("config.dcm", false);
  }

  // Legacy reopen keeps name-based initializers for both missing auxiliaries.
  @Test
  public void legacyReopenRebuildsMissingAuxiliariesByName() throws IOException {
    when(atomicOperation.loadFile(COLLECTION_NAME + ".pcl")).thenReturn(FILE_ID);
    when(atomicOperation.loadFile(COLLECTION_NAME + ".cpm")).thenReturn(CPM_FILE_ID);
    org.mockito.Mockito.clearInvocations(atomicOperation);
    collection.open(atomicOperation);
    verify(atomicOperation).addFile(COLLECTION_NAME + ".fsm", false);
    verify(atomicOperation).addFile(COLLECTION_NAME + ".dpb", false);
    verify(atomicOperation, never()).addFile(anyString(), anyLong(), anyBoolean(), anyBoolean());
  }

  // Legacy rename must keep all four display-derived file names and update the component name.
  @Test
  public void legacyCollectionRenameMovesAllFourFiles() throws IOException {
    collection.setCollectionName("renamed");

    assertThat(collection.getName()).isEqualTo("renamed");
    verify(mockWriteCache).renameFile(FILE_ID, "renamed.pcl");
    verify(mockWriteCache).renameFile(CPM_FILE_ID, "renamed.cpm");
    verify(mockWriteCache).renameFile(FSM_FILE_ID, "renamed.fsm");
    verify(mockWriteCache).renameFile(DPB_FILE_ID, "renamed.dpb");
  }

  // Direct auxiliary calls must update names without moving explicitly owned files.
  // Legacy auxiliaries must still rename the registered file to the new full name.
  @Test
  public void directAuxiliaryRenamePreservesExplicitFilesAndRenamesLegacyFiles()
      throws IOException {
    when(mockStorage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_28);
    when(mockWriteCache.getId()).thenReturn(17);
    for (var role : new PageFileRole[] {
        PageFileRole.POSITION_MAP, PageFileRole.FREE_SPACE_MAP, PageFileRole.DIRTY_PAGE_BIT_SET,
        PageFileRole.CONFIG_POSITION_MAP, PageFileRole.CONFIG_FREE_SPACE_MAP,
        PageFileRole.CONFIG_DIRTY_PAGE_BIT_SET}) {
      for (boolean explicit : new boolean[] {true, false}) {
        var identity = explicit ? new PageFileIdentity(role, 8) : null;
        var extension = role.layout27Extension();
        var name = explicit ? PageFileNames.layout28(identity) : "aux" + extension;
        var id = explicit ? AbstractWriteCache.composeFileId(17, identity.internalId()) : 91L;
        if (explicit) {
          when(atomicOperation.loadFile(name, id)).thenReturn(id);
        } else {
          when(atomicOperation.loadFile(name)).thenReturn(id);
        }
        StorageComponent auxiliary;
        switch (role.code()) {
          case 1 -> {
            var map = new CollectionPositionMapV2(mockStorage, "aux", "domain", extension,
                identity);
            map.open(atomicOperation);
            org.mockito.Mockito.clearInvocations(mockWriteCache);
            map.rename("renamedAux");
            auxiliary = map;
          }
          case 2 -> {
            var map = new FreeSpaceMap(mockStorage, "aux", extension, "domain", identity);
            map.open(atomicOperation);
            org.mockito.Mockito.clearInvocations(mockWriteCache);
            map.rename("renamedAux");
            auxiliary = map;
          }
          case 3 -> {
            var bits = new CollectionDirtyPageBitSet(mockStorage, "aux", extension, "domain",
                identity);
            bits.open(atomicOperation);
            org.mockito.Mockito.clearInvocations(mockWriteCache);
            bits.rename("renamedAux");
            auxiliary = bits;
          }
          default -> throw new AssertionError(role);
        }
        assertThat(auxiliary.getName()).isEqualTo("renamedAux");
        assertThat(auxiliary.getFullName()).isEqualTo("renamedAux" + extension);
        if (explicit) {
          org.mockito.Mockito.verifyNoInteractions(mockWriteCache);
        } else {
          verify(mockWriteCache).renameFile(id, "renamedAux" + extension);
        }
      }
    }
  }

  // Position-map construction validates the role, extension and storage format.
  @Test
  public void positionMapConstructorValidatesExplicitIdentity() {
    when(mockStorage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_28);
    org.junit.Assert.assertThrows(IllegalArgumentException.class,
        () -> new CollectionPositionMapV2(mockStorage, "wrong", "domain", ".cd",
            new PageFileIdentity(PageFileRole.CONFIG_DATA, 0)));
    org.junit.Assert.assertThrows(IllegalArgumentException.class,
        () -> new CollectionPositionMapV2(mockStorage, "wrong", "domain", ".wrong",
            new PageFileIdentity(PageFileRole.POSITION_MAP, 0)));
    when(mockStorage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_27);
    org.junit.Assert.assertThrows(
        com.jetbrains.youtrackdb.internal.core.exception.StorageException.class,
        () -> new CollectionPositionMapV2(mockStorage, "wrong", "domain", ".cpm",
            new PageFileIdentity(PageFileRole.POSITION_MAP, 0)));
    org.mockito.Mockito.clearInvocations(mockStorage);
    new CollectionPositionMapV2(mockStorage, "legacy", "domain", ".cpm");
    verify(mockStorage, never()).getStorageFormat();
  }

  // Sanity-check anchor: create() left fileSize == 0 (the legitimate post-create
  // state) and PCV2.fileId == FILE_ID. If this fires the mock setup is broken.
  @Test
  public void createLeavesCollectionInExpectedShape() throws IOException {
    assertThat(collection.getFileId()).isEqualTo(FILE_ID);
    assertThat(readFileSizeFromStatePage()).isEqualTo(0);
  }

  // Orphan-present branch: bump the state page's fileSize to a non-zero value and
  // grow the simulated file past (fileSize + 1). The helper must dispatch shrinkFile
  // at target = (fileSize + 1) * pageSize.
  @Test
  public void verifyAndTruncateOrphansShrinksWhenPhysicalExceedsLogical() throws IOException {
    setStateFileSize(3);
    // physical == fileSize + 1 + 2 orphans == 6 pages.
    makePage(FILE_ID, 4);
    makePage(FILE_ID, 5);
    pageCountByFile.put(FILE_ID, 6);

    collection.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    final long expectedTarget = 4L * PAGE_SIZE_BYTES; // (fileSize=3 + 1) * pageSize
    verify(mockReadCache).shrinkFile(FILE_ID, expectedTarget, mockWriteCache);
  }

  // Clean-shape branch: physical == (fileSize + 1). The helper dispatches shrinkFile
  // at target = (fileSize + 1) * pageSize; the cache layer's pre-flight handles the
  // no-op semantics. With fileSize == 0 (post-create), target = max(pageSize, 1 * pageSize)
  // == pageSize, which matches physical == 1 page (state page only) — a clean dispatch.
  @Test
  public void verifyAndTruncateOrphansNoOpOnFreshCollection() throws IOException {
    // create() left fileSize == 0 and pageCount == 1 (state page only).
    assertThat(readFileSizeFromStatePage()).isEqualTo(0);
    assertThat(pageCountByFile.get(FILE_ID)).isEqualTo(1);

    collection.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    // max(pageSize, (0 + 1) * pageSize) == pageSize. The cache layer's pre-flight
    // makes this a no-op against the in-sync physical == 1 page state.
    verify(mockReadCache).shrinkFile(FILE_ID, (long) PAGE_SIZE_BYTES, mockWriteCache);
  }

  // Boundary case: physical == target exactly. Catches off-by-one in
  // (fileSize + 1) * pageSize.
  @Test
  public void verifyAndTruncateOrphansBoundaryExactTarget() throws IOException {
    setStateFileSize(4);
    makePage(FILE_ID, 1);
    makePage(FILE_ID, 2);
    makePage(FILE_ID, 3);
    makePage(FILE_ID, 4);
    pageCountByFile.put(FILE_ID, 5);

    collection.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    final long expectedTarget = 5L * PAGE_SIZE_BYTES; // (fileSize=4 + 1) * pageSize
    verify(mockReadCache).shrinkFile(FILE_ID, expectedTarget, mockWriteCache);
  }

  // Corruption-signal branch: fileSize == 0 AND physical > 1 page. PCV2's
  // initCollectionState() sets fileSize=0 as the legitimate fresh-collection state, so
  // the guard requires BOTH conditions to fire — a healthy fresh collection
  // (fileSize == 0 && physical == 1 page) does NOT trigger the WARN.
  //
  // WARN-log emission is NOT pinned here. The BTree sibling suite
  // (BTreeVerifyAndTruncateOrphansTest.verifyAndTruncateOrphansSkipsOnCorruptionSignal)
  // carries the WARN-log capture sentinel for budget reasons — the corruption-skip code
  // shape is identical across BTree / SLBB / CPMV2 / PCV2 (pre-format String.format +
  // LogManager.instance().warn(this, msg)), so a regression that nukes the logger
  // entirely or demotes the level would surface there. If the PCV2-specific log
  // substring changes (e.g., the format anchor diverges from "Storage corruption
  // signal: PaginatedCollectionV2 '%s' state page reports fileSize=0 ..."), add a
  // parallel capture rig here.
  @Test
  public void verifyAndTruncateOrphansSkipsOnCorruptionSignal() throws IOException {
    // setStateFileSize(0) is the default post-create state; just extend physical
    // past the state page.
    assertThat(readFileSizeFromStatePage()).isEqualTo(0);
    makePage(FILE_ID, 1);
    pageCountByFile.put(FILE_ID, 2);

    collection.verifyAndTruncateOrphans(atomicOperation, mockReadCache, mockWriteCache);

    verify(mockReadCache, never()).shrinkFile(anyLong(), anyLong(), eq(mockWriteCache));
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private int readFileSizeFromStatePage() throws IOException {
    try (var entry = atomicOperation.loadPageForRead(FILE_ID, 0)) {
      return new PaginatedCollectionStateV2(entry).getFileSize();
    }
  }

  private void setStateFileSize(int size) throws IOException {
    try (var entry = atomicOperation.loadPageForWrite(FILE_ID, 0, 1, false)) {
      new PaginatedCollectionStateV2(entry).setFileSize(size);
    }
  }

  private void makePage(long fileId, int pageIndex) {
    pagePointers
        .computeIfAbsent(fileId, fid -> new HashMap<>())
        .computeIfAbsent(pageIndex, idx -> {
          var pointer = bufferPool.acquireDirect(true, Intention.TEST);
          var cp = new CachePointer(pointer, bufferPool, fileId, idx);
          cp.incrementReferrer();
          return cp;
        });
  }

  /**
   * Creates a mocked {@link AtomicOperation} that simulates four files (PCV2 primary
   * + cpm + fsm + dpb) each backed by its own page-pointer map. addFile dispatches
   * the file id by extension so the sub-component creates land on distinct ids and
   * don't cross-contaminate the helper's primary-file assertions.
   */
  private AtomicOperation createAtomicOperation() throws IOException {
    var op = mock(AtomicOperation.class);

    when(op.addFile(anyString())).thenAnswer((InvocationOnMock inv) -> {
      String fname = inv.getArgument(0);
      return fileIdForName(fname);
    });
    when(op.addFile(anyString(), anyBoolean())).thenAnswer((InvocationOnMock inv) -> {
      String fname = inv.getArgument(0);
      return fileIdForName(fname);
    });

    when(op.filledUpTo(anyLong())).thenAnswer(inv -> {
      long fid = inv.getArgument(0);
      return (long) pageCountByFile.getOrDefault(fid, 0);
    });

    when(op.allocatePageForWrite(anyLong(), anyLong())).thenAnswer(inv -> {
      long fid = inv.getArgument(0);
      int pIdx = ((Long) inv.getArgument(1)).intValue();
      var entry = getOrCreatePage(fid, pIdx);
      int currentPageCount = pageCountByFile.getOrDefault(fid, 0);
      if (pIdx >= currentPageCount) {
        pageCountByFile.put(fid, pIdx + 1);
      }
      return entry;
    });

    when(op.loadPageForWrite(anyLong(), anyLong(), anyInt(), anyBoolean()))
        .thenAnswer(inv -> {
          long fid = inv.getArgument(0);
          int pIdx = ((Long) inv.getArgument(1)).intValue();
          return getOrCreatePage(fid, pIdx);
        });

    when(op.loadPageForRead(anyLong(), anyLong()))
        .thenAnswer(inv -> {
          long fid = inv.getArgument(0);
          int pIdx = ((Long) inv.getArgument(1)).intValue();
          return getOrCreatePage(fid, pIdx);
        });

    return op;
  }

  /**
   * Maps a file name suffix to the simulated file id. PCV2's data file uses
   * {@code .pcl}; the sub-components use their own extensions. Anything else
   * defaults to a fresh id (FILE_ID + 100) so an unexpected addFile call is visible
   * via mismatched assertions rather than silent file-id collision.
   */
  private long fileIdForName(String fname) {
    if (fname.endsWith(".pcl")) {
      return FILE_ID;
    }
    if (fname.endsWith(".cpm")) {
      return CPM_FILE_ID;
    }
    if (fname.endsWith(".fsm")) {
      return FSM_FILE_ID;
    }
    if (fname.endsWith(".dpb")) {
      return DPB_FILE_ID;
    }
    return FILE_ID + 100; // surfaces unexpected sub-files
  }

  private CacheEntry getOrCreatePage(long fileId, int pageIndex) {
    var perFile = pagePointers.computeIfAbsent(fileId, fid -> new HashMap<>());
    var cachePointer = perFile.computeIfAbsent(pageIndex, idx -> {
      var pointer = bufferPool.acquireDirect(true, Intention.TEST);
      var cp = new CachePointer(pointer, bufferPool, fileId, idx);
      cp.incrementReferrer();
      return cp;
    });
    return new CacheEntryImpl(fileId, pageIndex, cachePointer, false, mockReadCache);
  }
}
