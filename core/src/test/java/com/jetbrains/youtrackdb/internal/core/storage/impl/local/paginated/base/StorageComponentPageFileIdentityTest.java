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
    when(storage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_26);
    when(cache.getId()).thenReturn(17);
  }

  // Binding is idempotent for equal identities only. Rejection must not change the domain or epoch.
  @Test
  public void bindingRejectsAnotherOwnerOrRoleAndKeepsTheOriginalDomain() {
    var epoch = new ApplyPhaseEpoch();
    when(manager.getApplyPhaseEpoch("explicit-domain")).thenReturn(epoch);
    var component = new Component(PageFileRole.CONFIG_DATA);
    var identity = identity(PageFileRole.CONFIG_DATA);
    component.bindPageFileIdentities(identity);
    component.bindPageFileIdentities(identity);
    assertThrows(StorageException.class, () -> component.bindPageFileIdentities(
        new PageFileIdentity(PageFileRole.CONFIG_DATA, 1)));
    assertThrows(IllegalArgumentException.class, () -> component.bindPageFileIdentities(
        identity(PageFileRole.RECORD_DATA)));
    assertThrows(StorageException.class, () -> component.bindPageFileIdentities(
        identity, identity(PageFileRole.PROPERTY_DATA)));
    assertThrows(IllegalArgumentException.class, () -> component.bindPageFileIdentities());
    assertThrows(IllegalArgumentException.class, () -> component.bindPageFileIdentities(
        identity, identity));
    assertThrows(IllegalArgumentException.class, () -> component.bindPageFileIdentities(
        identity, new PageFileIdentity(PageFileRole.PROPERTY_DATA, 1)));
    assertThrows(IllegalArgumentException.class, () -> component.bindPageFileIdentities(
        identity, identity(PageFileRole.POSITION_MAP)));
    assertThat(component.getLockName()).isEqualTo("explicit-domain");
    assertThat(component.getApplyPhaseEpoch()).isSameAs(epoch);
  }

  // All lifecycle helpers must send the exact role name and storage-qualified id to the operation.
  @Test
  public void roleAwareHandlesValidatePresenceOpenCreationAndDeletion() throws IOException {
    var component = new Component(PageFileRole.CONFIG_DATA);
    var identity = identity(PageFileRole.CONFIG_DATA);
    component.bindPageFileIdentities(identity);
    var name = PageFileNames.layout26(identity);
    var id = qualified(identity);
    when(operation.addFile(name, id, false, false)).thenReturn(id);
    when(operation.loadFile(name, id)).thenReturn(id);
    when(operation.isFileExists(name, id)).thenReturn(true);
    assertThat(component.addFile(operation, "custom.cd")).isEqualTo(id);
    assertThat(component.openFile(operation, name)).isEqualTo(id);
    assertThat(component.isFileExists(operation, "custom.cd")).isTrue();
    component.deleteFile(operation, id);
    verify(operation).deleteFile(id);
    assertThrows(StorageException.class,
        () -> component.deleteFile(operation, qualified(identity(PageFileRole.PROPERTY_DATA))));
    assertThrows(StorageException.class,
        () -> component.openFile(operation, "other.cd"));
    assertThrows(StorageException.class,
        () -> component.openFile(operation,
            PageFileNames.layout26(new PageFileIdentity(PageFileRole.CONFIG_DATA, 2))));
    when(operation.loadFile(name, id)).thenReturn(id + 1);
    assertThrows(StorageException.class, () -> component.deleteFile(operation, id));
  }

  // Reconstruction is an explicit caller assertion. Only the five rebuildable roles can send it.
  @Test
  public void reconstructionNeverPermitsPrimaryDataOrLinkBags() throws IOException {
    for (var role : PageFileRole.values()) {
      var component = new Component(role);
      var identity = identity(role);
      component.bindPageFileIdentities(identity);
      var name = "custom" + role.layout25Extension();
      switch (role) {
        case FREE_SPACE_MAP, DIRTY_PAGE_BIT_SET, CONFIG_FREE_SPACE_MAP,
            CONFIG_DIRTY_PAGE_BIT_SET, HISTOGRAM -> {
          var canonical = PageFileNames.layout26(identity);
          when(operation.addFile(canonical, qualified(identity), false, true))
              .thenReturn(qualified(identity));
          assertThat(component.reconstructAuxiliary(operation, name))
              .isEqualTo(qualified(identity));
          verify(operation).addFile(canonical, qualified(identity), false, true);
        }
        default -> assertThrows(StorageException.class,
            () -> component.reconstructAuxiliary(operation, name));
      }
    }
  }

  // Legacy custom names and layout 25 keep name-based allocation even if ownership is supplied.
  @Test
  public void legacyAndLayout25KeepNameBasedHandles() throws IOException {
    var legacy = new Component(PageFileRole.CONFIG_DATA);
    when(operation.addFile("unusual.control", false)).thenReturn(23L);
    assertThat(legacy.addFile(operation, "unusual.control")).isEqualTo(23);
    var component = new Component(PageFileRole.CONFIG_DATA);
    component.bindPageFileIdentities(identity(PageFileRole.CONFIG_DATA));
    when(storage.getStorageFormat()).thenReturn(StorageFormat.LAYOUT_25);
    when(operation.addFile("custom.cd", false)).thenReturn(24L);
    when(operation.loadFile("custom.cd")).thenReturn(24L);
    when(operation.isFileExists("custom.cd")).thenReturn(true);
    assertThat(component.addFile(operation, "custom.cd")).isEqualTo(24);
    assertThat(component.openFile(operation, "custom.cd")).isEqualTo(24);
    assertThat(component.isFileExists(operation, "custom.cd")).isTrue();
    component.deleteFile(operation, 24);
    verify(operation).deleteFile(24);
    assertThrows(StorageException.class,
        () -> component.reconstructAuxiliary(operation, "custom.cd"));
  }

  // Configuration shares implementations, not lock domains. Each collection auxiliary shares
  // the exact data lock and epoch. The property tree must use a distinct lock and epoch.
  @Test
  public void configurationFamilySharesOnlyTheCollectionDomain() throws Exception {
    var data = identity(PageFileRole.CONFIG_DATA);
    var property = identity(PageFileRole.PROPERTY_DATA);
    var dataEpoch = new ApplyPhaseEpoch();
    var propertyEpoch = new ApplyPhaseEpoch();
    when(manager.getApplyPhaseEpoch(PageFileNames.layout26(data))).thenReturn(dataEpoch);
    when(manager.getApplyPhaseEpoch(PageFileNames.layout26(property))).thenReturn(propertyEpoch);
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
      var role = switch (fieldName) {
        case "collectionPositionMap" -> PageFileRole.CONFIG_POSITION_MAP;
        case "freeSpaceMap" -> PageFileRole.CONFIG_FREE_SPACE_MAP;
        case "dirtyPageBitSet" -> PageFileRole.CONFIG_DIRTY_PAGE_BIT_SET;
        default -> throw new AssertionError();
      };
      assertThrows(StorageException.class,
          () -> auxiliary.bindPageFileIdentities(new PageFileIdentity(role, 1)));
      assertThrows(IllegalArgumentException.class,
          () -> auxiliary.bindPageFileIdentities(identity(PageFileRole.CONFIG_DATA)));
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
  }

  // Direct inherited binding must reject incomplete trees before any file is submitted.
  @Test
  public void directBindingRejectsIncompleteTreeAndWrongConcreteFamilies() {
    var factory =
        BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    var tree = new BTree<>("config", ".bd", ".nd", storage, factory);
    assertThrows(IllegalArgumentException.class,
        () -> tree.bindPageFileIdentities(identity(PageFileRole.PROPERTY_DATA)));
    var bits = new CollectionDirtyPageBitSet(storage, "config", ".cd", "domain");
    assertThrows(IllegalArgumentException.class,
        () -> bits.bindPageFileIdentities(identity(PageFileRole.CONFIG_DATA)));
    var collection = new PaginatedCollectionV2("config", ".bd", ".ccm", ".fcm", ".dcm",
        storage);
    assertThrows(IllegalArgumentException.class,
        () -> collection.bindPageFileIdentities(identity(PageFileRole.PROPERTY_DATA)));
    verifyNoInteractions(operation);
  }

  // Every legacy handle path closes the first-binding window, even a presence probe.
  @Test
  public void firstBindingAfterAnyLegacyHandleIsRejected() throws IOException {
    for (int route = 0; route < 4; route++) {
      var component = new Component(PageFileRole.CONFIG_DATA);
      switch (route) {
        case 0 -> component.addFile(operation, "custom.cd");
        case 1 -> component.openFile(operation, "custom.cd");
        case 2 -> component.isFileExists(operation, "custom.cd");
        case 3 -> component.deleteFile(operation, 23);
        default -> throw new AssertionError();
      }
      var failure = assertThrows(StorageException.class,
          () -> component.bindPageFileIdentities(identity(PageFileRole.CONFIG_DATA)));
      assertThat(failure).hasMessageContaining("legacy file handle");
    }
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
    assertThrows(StorageException.class,
        () -> component.bindPageFileIdentities(identity(PageFileRole.DIRTY_PAGE_BIT_SET)));
  }

  // Shared extensions do not make index null-key and main-tree roles interchangeable.
  @Test
  public void treeBindingChecksBothIndexRolePairsWithoutActivatingCallers() {
    var factory =
        BinarySerializerFactory.create(BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
    for (var role : new PageFileRole[] {PageFileRole.INDEX_DATA, PageFileRole.NULL_KEY_DATA}) {
      var tree = new BTree<>("index", ".cbt", ".nbt", storage, factory);
      var partner = role == PageFileRole.INDEX_DATA
          ? PageFileRole.INDEX_NULL_BUCKET : PageFileRole.NULL_KEY_NULL_BUCKET;
      var wrong = role == PageFileRole.INDEX_DATA
          ? PageFileRole.NULL_KEY_NULL_BUCKET : PageFileRole.INDEX_NULL_BUCKET;
      assertThrows(IllegalArgumentException.class,
          () -> tree.bindPageFileIdentities(identity(role), identity(wrong)));
      tree.bindPageFileIdentities(identity(role), identity(partner));
      tree.bindPageFileIdentities(identity(role), identity(partner));
    }
    verifyNoInteractions(operation);
  }

  // Non-durable components keep the flag on normal creation and explicit reconstruction.
  @Test
  public void nonDurableRoleHandlesKeepTheirCreationFlags() throws IOException {
    var component = new StorageComponent(storage, "custom", ".fsm", "domain", false) {
    };
    var identity = identity(PageFileRole.FREE_SPACE_MAP);
    component.bindPageFileIdentities(identity);
    var name = PageFileNames.layout26(identity);
    var id = qualified(identity);
    when(operation.addFile(name, id, true, false)).thenReturn(id);
    when(operation.addFile(name, id, true, true)).thenReturn(id);
    assertThat(component.addFile(operation, "custom.fsm")).isEqualTo(id);
    assertThat(component.reconstructAuxiliary(operation, "custom.fsm")).isEqualTo(id);
    verify(operation).addFile(name, id, true, false);
    verify(operation).addFile(name, id, true, true);
  }

  private static PageFileIdentity identity(PageFileRole role) {
    return new PageFileIdentity(role, 0);
  }

  private static long qualified(PageFileIdentity identity) {
    return AbstractWriteCache.composeFileId(17, identity.internalId());
  }

  private class Component extends StorageComponent {
    Component(PageFileRole role) {
      super(StorageComponentPageFileIdentityTest.this.storage, "custom", role.layout25Extension(),
          "explicit-domain", true);
    }
  }
}
