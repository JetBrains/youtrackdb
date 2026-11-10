package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.CollectionDirtyPageBitSet;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.FreeSpaceMap;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;

public class StorageComponentPageFileIdentityTest {
  private AbstractStorage storage;
  private WriteCache cache;
  private AtomicOperation operation;
  private AtomicOperationsManager manager;

  @Before
  public void setUp() {
    storage = mock(AbstractStorage.class);
    cache = mock(WriteCache.class);
    operation = mock(AtomicOperation.class);
    manager = mock(AtomicOperationsManager.class);
    when(storage.getWriteCache()).thenReturn(cache);
    when(storage.getAtomicOperationsManager()).thenReturn(manager);
    when(storage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_28);
    when(cache.getId()).thenReturn(17);
  }

  // All lifecycle helpers must send the exact role name and storage-qualified id to the operation.
  @Test
  public void roleAwareHandlesValidatePresenceOpenCreationAndDeletion() throws IOException {
    var component = new Component(PageFileRole.CONFIG_DATA);
    var identity = identity(PageFileRole.CONFIG_DATA);
    var name = "ytdb-p28-configuration-0-data.cd";
    assertThat(PageFileNames.layout28(identity)).isEqualTo(name);
    var id = qualified(identity);
    when(operation.addFile(name, id, false, false)).thenReturn(id);
    when(operation.loadFile(name, id)).thenReturn(id);
    when(operation.isFileExists(name, id)).thenReturn(true);
    assertThat(component.addFile(operation, "custom.cd", identity)).isEqualTo(id);
    assertThat(component.openFile(operation, name, identity)).isEqualTo(id);
    assertThat(component.isFileExists(operation, "custom.cd", identity)).isTrue();
    component.deleteFile(operation, id, identity);
    verify(operation).deleteFile(id);
    assertThrows(StorageException.class,
        () -> component.deleteFile(operation, qualified(identity(PageFileRole.PROPERTY_DATA)),
            identity));
    when(operation.loadFile(name, id)).thenReturn(id + 1);
    assertThrows(StorageException.class, () -> component.deleteFile(operation, id, identity));
  }

  // Reconstruction is an explicit caller assertion. Only the five rebuildable roles can send it.
  @Test
  public void reconstructionNeverPermitsPrimaryDataOrLinkBags() throws IOException {
    for (var role : PageFileRole.values()) {
      var component = new Component(role);
      var identity = identity(role);
      var name = "custom" + role.layout27Extension();
      switch (role) {
        case FREE_SPACE_MAP, DIRTY_PAGE_BIT_SET, CONFIG_FREE_SPACE_MAP,
            CONFIG_DIRTY_PAGE_BIT_SET, HISTOGRAM -> {
          var canonical = PageFileNames.layout28(identity);
          when(operation.addFile(canonical, qualified(identity), false, true))
              .thenReturn(qualified(identity));
          assertThat(component.reconstructAuxiliary(operation, identity))
              .isEqualTo(qualified(identity));
          verify(operation).addFile(canonical, qualified(identity), false, true);
        }
        default -> assertThrows(StorageException.class,
            () -> component.reconstructAuxiliary(operation, identity));
      }
    }
  }

  // Null identities preserve all legacy name-based handles without checking the format.
  @Test
  public void legacyAndLayout27KeepNameBasedHandles() throws IOException {
    var legacy = new Component(PageFileRole.CONFIG_DATA);
    when(operation.addFile("unusual.control", false)).thenReturn(23L);
    assertThat(legacy.addFile(operation, "unusual.control")).isEqualTo(23);
    var component = new Component(PageFileRole.CONFIG_DATA);
    when(storage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_27);
    when(operation.addFile("custom.cd", false)).thenReturn(24L);
    when(operation.loadFile("custom.cd")).thenReturn(24L);
    when(operation.isFileExists("custom.cd")).thenReturn(true);
    assertThat(component.addFile(operation, "custom.cd", null)).isEqualTo(24);
    assertThat(component.openFile(operation, "custom.cd", null)).isEqualTo(24);
    assertThat(component.isFileExists(operation, "custom.cd", null)).isTrue();
    component.deleteFile(operation, 24, null);
    verify(operation).addFile("custom.cd", false);
    verify(operation).loadFile("custom.cd");
    verify(operation).isFileExists("custom.cd");
    verify(operation).deleteFile(24);
    assertThrows(StorageException.class,
        () -> component.reconstructAuxiliary(operation, null));
  }

  // Configuration shares implementations, not lock domains. Each collection auxiliary shares
  // the exact data lock and epoch. The property tree must use a distinct lock and epoch.
  @Test
  public void configurationFamilySharesOnlyTheCollectionDomain() throws Exception {
    var data = identity(PageFileRole.CONFIG_DATA);
    var property = identity(PageFileRole.PROPERTY_DATA);
    var dataEpoch = new ApplyPhaseEpoch();
    var propertyEpoch = new ApplyPhaseEpoch();
    when(manager.getApplyPhaseEpoch(PageFileNames.layout28(data))).thenReturn(dataEpoch);
    when(manager.getApplyPhaseEpoch(PageFileNames.layout28(property))).thenReturn(propertyEpoch);
    var collection = new PaginatedCollectionV2("config", ".cd", ".ccm", ".fcm", ".dcm",
        storage, data);
    var tree = new BTree<>("config", ".bd", ".nd", storage,
        BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION),
        property,
        identity(PageFileRole.PROPERTY_NULL_BUCKET));
    for (var fieldName : new String[] {"collectionPositionMap", "freeSpaceMap",
        "dirtyPageBitSet"}) {
      var field = PaginatedCollectionV2.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      var auxiliary = (StorageComponent) field.get(collection);
      assertThat(auxiliary.getLockName()).isEqualTo(collection.getLockName());
      assertThat(auxiliary.getApplyPhaseEpoch()).isSameAs(dataEpoch);
    }
    assertThrows(StorageException.class, () -> collection.configure(0, "config", 1));
    var ordinary = new PaginatedCollectionV2("records", ".pcl", ".cpm", ".fsm", ".dpb",
        storage, new PageFileIdentity(PageFileRole.RECORD_DATA, 7));
    assertThrows(StorageException.class, () -> ordinary.configure(0, "records", 8));
    assertThat(collection.getApplyPhaseEpoch()).isSameAs(dataEpoch);
    assertThat(tree.getLockName()).isNotEqualTo(collection.getLockName());
    assertThat(tree.getApplyPhaseEpoch()).isSameAs(propertyEpoch).isNotSameAs(dataEpoch);
    verifyNoInteractions(operation);
  }

  // Constructor boundaries reject a wrong role family, null partner, owner or extension.
  @Test
  public void constructorsRejectWrongFamiliesAndMismatchedTreePartners() {
    var factory =
        BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    var data = identity(PageFileRole.PROPERTY_DATA);
    assertThrows(IllegalArgumentException.class, () -> new PaginatedCollectionV2(
        "config", ".bd", ".ccm", ".fcm", ".dcm", storage, data));
    assertThrows(IllegalArgumentException.class, () -> new CollectionDirtyPageBitSet(
        storage, "config", ".cd", "domain", identity(PageFileRole.CONFIG_DATA)));
    assertThrows(IllegalArgumentException.class, () -> new FreeSpaceMap(
        storage, "config", ".cd", "domain", identity(PageFileRole.CONFIG_DATA)));
    assertThrows(IllegalArgumentException.class, () -> new BTree<>(
        "config", ".wrong", ".nd", storage, factory, data,
        identity(PageFileRole.PROPERTY_NULL_BUCKET)));
    assertThrows(IllegalArgumentException.class, () -> new BTree<>(
        "config", ".bd", ".nd", storage, factory, data, null));
    assertThrows(IllegalArgumentException.class, () -> new BTree<>(
        "config", ".bd", ".nd", storage, factory, data,
        new PageFileIdentity(PageFileRole.PROPERTY_NULL_BUCKET, 1)));
    assertThrows(IllegalArgumentException.class, () -> new BTree<>(
        "config", ".bd", ".wrong", storage, factory, data,
        identity(PageFileRole.PROPERTY_NULL_BUCKET)));
    assertThrows(IllegalArgumentException.class, () -> new BTree<>(
        "config", ".bd", ".nd", storage, factory, null,
        identity(PageFileRole.PROPERTY_NULL_BUCKET)));
    assertThrows(IllegalArgumentException.class, () -> new BTree<>(
        "config", ".cd", ".nd", storage, factory, identity(PageFileRole.CONFIG_DATA),
        identity(PageFileRole.PROPERTY_NULL_BUCKET)));
    // Rejected constructors must not reach file operations.
    verifyNoInteractions(operation);
  }

  // Mockito skips field initializers. All four helpers must still use the legacy overloads.
  @Test
  public void constructorBypassingPartialMockKeepsLegacyHandles() throws IOException {
    var component = mock(Component.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    when(operation.addFile("existing.dpb", true)).thenReturn(23L);
    when(operation.loadFile("existing.dpb")).thenReturn(23L);
    when(operation.isFileExists("existing.dpb")).thenReturn(true);
    assertThat(component.addFile(operation, "existing.dpb")).isEqualTo(23);
    assertThat(component.openFile(operation, "existing.dpb")).isEqualTo(23);
    assertThat(component.isFileExists(operation, "existing.dpb")).isTrue();
    component.deleteFile(operation, 23);
    verify(operation).addFile("existing.dpb", true);
    verify(operation).loadFile("existing.dpb");
    verify(operation).isFileExists("existing.dpb");
    verify(operation).deleteFile(23);
  }

  // Non-durable components keep the flag on normal creation and explicit reconstruction.
  @Test
  public void nonDurableRoleHandlesKeepTheirCreationFlags() throws IOException {
    var component = new StorageComponent(storage, "custom", ".fsm", "domain", false) {
    };
    var identity = identity(PageFileRole.FREE_SPACE_MAP);
    var name = PageFileNames.layout28(identity);
    var id = qualified(identity);
    when(operation.addFile(name, id, true, false)).thenReturn(id);
    when(operation.addFile(name, id, true, true)).thenReturn(id);
    assertThat(component.addFile(operation, "custom.fsm", identity)).isEqualTo(id);
    assertThat(component.reconstructAuxiliary(operation, identity)).isEqualTo(id);
    verify(operation).addFile(name, id, true, false);
    verify(operation).addFile(name, id, true, true);
  }

  // Every explicit constructor rejects unsupported storage formats. Null ownership stays legacy.
  @Test
  public void explicitConstructorsRequireLayout28AndLegacyConstructorsDoNotCheckIt() {
    var factory =
        BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    for (var format : new StorageFormat[] {StorageFormat.LAYOUT_27, null}) {
      when(storage.getStorageFormat()).thenReturn(format);
      assertThrows(StorageException.class, () -> new CollectionDirtyPageBitSet(
          storage, "bits", ".dpb", "domain", identity(PageFileRole.DIRTY_PAGE_BIT_SET)));
      assertThrows(StorageException.class, () -> new FreeSpaceMap(
          storage, "space", ".fsm", "domain", identity(PageFileRole.FREE_SPACE_MAP)));
      assertThrows(StorageException.class, () -> new PaginatedCollectionV2(
          "records", ".pcl", ".cpm", ".fsm", ".dpb", storage,
          identity(PageFileRole.RECORD_DATA)));
      assertThrows(StorageException.class, () -> new BTree<>(
          "tree", ".bd", ".nd", storage, factory, identity(PageFileRole.PROPERTY_DATA),
          identity(PageFileRole.PROPERTY_NULL_BUCKET)));
    }
    org.mockito.Mockito.clearInvocations(storage);
    new CollectionDirtyPageBitSet(storage, "bits", ".dpb", "domain");
    new FreeSpaceMap(storage, "space", ".fsm", "domain");
    new PaginatedCollectionV2("records", storage);
    new BTree<>("tree", ".bd", ".nd", storage, factory);
    verify(storage, org.mockito.Mockito.never()).getStorageFormat();
  }

  // All supported tree families accept exactly their own data and null-bucket pair.
  @Test
  public void treeConstructorsValidateEveryRolePair() {
    var factory =
        BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    for (var role : new PageFileRole[] {PageFileRole.INDEX_DATA, PageFileRole.NULL_KEY_DATA}) {
      var partner = role == PageFileRole.INDEX_DATA
          ? PageFileRole.INDEX_NULL_BUCKET : PageFileRole.NULL_KEY_NULL_BUCKET;
      var wrong = role == PageFileRole.INDEX_DATA
          ? PageFileRole.NULL_KEY_NULL_BUCKET : PageFileRole.INDEX_NULL_BUCKET;
      new BTree<>("index", ".cbt", ".nbt", storage, factory, identity(role), identity(partner));
      assertThrows(IllegalArgumentException.class, () -> new BTree<>(
          "index", ".cbt", ".nbt", storage, factory, identity(role), identity(wrong)));
    }
  }

  private static PageFileIdentity identity(PageFileRole role) {
    return new PageFileIdentity(role, 0);
  }

  private static long qualified(PageFileIdentity identity) {
    return AbstractWriteCache.composeFileId(17, identity.internalId());
  }

  private class Component extends StorageComponent {
    Component(PageFileRole role) {
      super(StorageComponentPageFileIdentityTest.this.storage, "custom", role.layout27Extension(),
          "explicit-domain", true);
    }
  }
}
