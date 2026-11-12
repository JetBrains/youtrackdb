package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.io.File;
import java.util.Map;
import org.junit.Test;

/** Ownership activation checks use real files in both cache implementations. */
public class LinkCollectionsBTreeManagerSharedOwnershipTest {

  private static final String DB = "ownership";

  private static PageFileIdentity identity(int component) {
    return new PageFileIdentity(PageFileRole.LINK_BAG_TREE, component);
  }

  private static SharedLinkBagBTree tree(AbstractStorage storage, int component) {
    return new SharedLinkBagBTree(storage, "unused", ".grb", identity(component));
  }

  private static void underWriteLock(AbstractStorage storage, Runnable action) throws Exception {
    var field = AbstractStorage.class.getDeclaredField("stateLock");
    field.setAccessible(true);
    var lock =
        (com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock) field.get(storage);
    lock.writeLock().lock();
    try {
      action.run();
    } finally {
      lock.writeLock().unlock();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<Integer, SharedLinkBagBTree> loaded(LinkCollectionsBTreeManagerShared manager)
      throws Exception {
    var field = manager.getClass().getDeclaredField("fileIdBTreeMap");
    field.setAccessible(true);
    return (Map<Integer, SharedLinkBagBTree>) field.get(manager);
  }

  private void withStorage(StorageFormat format, CheckedConsumer action) throws Exception {
    for (var type : new DatabaseType[] {DatabaseType.DISK, DatabaseType.MEMORY}) {
      var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-" + type;
      FileUtils.deleteRecursively(new File(directory));
      try (var tracks = (YouTrackDBImpl) YourTracks.instance(directory)) {
        try (var ignored = StorageBirthTestSupport.selectBirthFormat(tracks, DB, format)) {
          tracks.create(DB, type, "admin", "admin", "admin");
        }
        try (var session = (DatabaseSessionEmbedded) tracks.open(DB, "admin", "admin")) {
          action.accept(session);
        }
      } finally {
        FileUtils.deleteRecursively(new File(directory));
      }
    }
  }

  @FunctionalInterface
  private interface CheckedConsumer {
    void accept(DatabaseSessionEmbedded session) throws Exception;
  }

  // Every birth-created collection already has a computed registration and a separate tree lock.
  @Test
  public void birthAndCreationUseComputedTreeIdentityAndDistinctLock() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      session.addCollection("owner");
      for (var collection : storage.getCollectionInstances()) {
        if (collection == null) {
          continue;
        }
        var component =
            ((PaginatedCollection) collection).generateCollectionConfig().getComponentId();
        var expected = identity(component);
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          var owner = manager.captureOwner(collection, op);
          var handle = manager.requireComponent(owner, op);
          assertEquals(expected.internalId(), AbstractWriteCache.extractFileId(handle.getFileId()));
          assertEquals(AbstractWriteCache.extractFileId(
              storage.getWriteCache().fileIdByName(PageFileNames.layout28(expected))),
              AbstractWriteCache.extractFileId(handle.getFileId()));
          assertEquals(PageFileNames.layout28(expected), handle.getLockName());
          assertNotEquals(((PaginatedCollection) collection).getLockName(), handle.getLockName());
          assertNotSame(((PaginatedCollection) collection).getApplyPhaseEpoch(),
              handle.getApplyPhaseEpoch());
          assertFalse(storage.getWriteCache().files()
              .containsKey(LinkCollectionsBTreeManagerShared.generateLockName(collection.getId())));
          assertEquals(expected.internalId(), AbstractWriteCache.extractFileId(
              op.fileIdByName(PageFileNames.layout28(expected))));
        });
      }
    });
  }

  // Configured membership ignores both identity leftovers and legacy-looking registrations.
  // Empty configured trees still restore the maximum stored bag counter.
  @Test
  public void configuredDiscoveryIgnoresLeftoversAndRestoresEmptyTreeCounter() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      int id = session.addCollection("configured");
      var serving =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      var fresh = new LinkCollectionsBTreeManagerShared(storage);
      underWriteLock(storage, () -> {
        try {
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
            var owner = storage.captureCollectionOwner(id, op);
            serving.requireComponent(owner, op).updateRidBagIdCounter(op, 71);
            tree(storage, 100000).create(op);
            new SharedLinkBagBTree(storage, "global_collection_99999", ".grb").create(op);
          });
        } catch (java.io.IOException failure) {
          throw new AssertionError(failure);
        }
      });
      // Normal open has no runtime collections yet. Preserve their objects for fixture cleanup.
      var slotsField = AbstractStorage.class.getDeclaredField("collections");
      slotsField.setAccessible(true);
      var slots = (java.util.List<?>) slotsField.get(storage);
      var savedSlots = new java.util.ArrayList<>(slots);
      slots.clear();
      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(fresh::load);
      } finally {
        @SuppressWarnings("unchecked")
        var mutableSlots = (java.util.List<Object>) slots;
        mutableSlots.addAll(savedSlots);
      }
      // Restore-order loading sees runtime collections and uses the same configuration authority.
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        fresh.load(op);
        assertFalse(loaded(fresh).containsKey(identity(100000).internalId()));
        assertFalse(loaded(fresh).containsKey(AbstractWriteCache.extractFileId(
            op.fileIdByName("global_collection_99999.grb"))));
        var owner = fresh.captureOwner(id,
            storage.captureCollectionOwner(id, op).componentId(), op);
        fresh.requireComponent(owner, op);
        var pointer = fresh.createBTree(id, op, session);
        assertEquals(-72, pointer.linkBagId());
        assertEquals(identity(owner.componentId()).internalId(), pointer.fileId());
      });
    });
  }

  // Reload discards partial loaded maps, creation captures and an earlier image's high counter.
  // Both layouts restore only the counters persisted in trees found by their discovery rules.
  @Test
  public void reloadDiscardsLeftoverStateAndRestoresPersistedCounter() throws Exception {
    for (var format : new StorageFormat[] {StorageFormat.LAYOUT_27, StorageFormat.LAYOUT_28}) {
      withStorage(format, session -> {
        var storage = session.getStorage();
        int collectionId = session.addCollection("reloadowner");
        var fresh = new LinkCollectionsBTreeManagerShared(storage);
        int removedCollectionId = session.addCollection("removedowner");
        var owner = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            op -> storage.captureCollectionOwner(collectionId, op));
        var removedOwner = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            op -> storage.captureCollectionOwner(removedCollectionId, op));
        var counterField = fresh.getClass().getDeclaredField("ridBagIdCounter");
        counterField.setAccessible(true);
        var counter = (java.util.concurrent.atomic.AtomicLong) counterField.get(fresh);
        underWriteLock(storage, () -> {
          try {
            storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
              removedOwner.tree().updateRidBagIdCounter(op, 700);
              owner.tree().updateRidBagIdCounter(op, 71);
            });
            storage.getAtomicOperationsManager().executeInsideAtomicOperation(fresh::load);
            assertEquals(700, counter.get());
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
        session.dropCollection(removedCollectionId);
        underWriteLock(storage, () -> {
          try {
            loaded(fresh).put(Integer.MAX_VALUE, owner.tree());
            var ownersField = fresh.getClass().getDeclaredField("createdOwners");
            ownersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var owners = (Map<Integer,
                LinkCollectionsBTreeManagerShared.CollectionOwner>) ownersField.get(fresh);
            owners.put(100001,
                new LinkCollectionsBTreeManagerShared.CollectionOwner(collectionId, 100001,
                    owner.tree()));
            assertFalse(owners.isEmpty());
            storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
              fresh.load(op);
              assertFalse(loaded(fresh).containsKey(Integer.MAX_VALUE));
              assertTrue(owners.isEmpty());
              assertEquals(71, counter.get());
              assertEquals(-72, fresh.createBTree(collectionId, op, session).linkBagId());
            });
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
      });
    }
  }

  // Both owner-capture entry points reject absent operation-visible configuration without repair.
  @Test
  public void missingConfiguredOwnerFailsStorageCaptureAndBagAllocation() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        int collectionId = 100001;
        assertNull(storage.readCollectionOwner(collectionId, op));
        String message = "No configured collection owner for " + collectionId;
        assertTrue(assertThrows(StorageException.class,
            () -> storage.captureCollectionOwner(collectionId, op)).getMessage().contains(message));
        assertTrue(assertThrows(StorageException.class,
            () -> manager.createBTree(collectionId, op, session)).getMessage().contains(message));
      });
    });
  }

  // A cached owner never authorizes allocation after its registration is removed.
  // Neither required access nor serialization may replace a missing tree with an empty one.
  @Test
  public void missingRegistrationFailsRequiredAccessAllocationAndDiscoveryWithoutReplacement()
      throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      int id = session.addCollection("missing");
      var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      var owner = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> storage.captureCollectionOwner(id, op));
      storage.getAtomicOperationsManager()
          .executeInsideAtomicOperation(op -> op.deleteFile(owner.tree().getFileId()));
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        assertThrows(StorageException.class, () -> manager.requireComponent(owner, op));
        assertThrows(StorageException.class, () -> manager.createBTree(id, op, session));
        assertThrows(StorageException.class, () -> new LinkCollectionsBTreeManagerShared(storage)
            .load(op));
        var check = AbstractStorage.class.getDeclaredMethod("checkRidBagsPresence",
            com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation.class);
        check.setAccessible(true);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> check.invoke(storage, op));
        assertTrue(failure.getCause() instanceof StorageException);
        assertFalse(op.isFileExists(PageFileNames.layout28(identity(owner.componentId()))));
      });
    });
  }

  // A correct registration without loaded state fails before allocation or direct pointer use.
  @Test
  public void missingLoadedStateFailsAllocationAndDirectPointerLookup() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      int id = session.addCollection("unloaded");
      var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      var pointer = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> manager.createBTree(id, op, session));
      loaded(manager).remove((int) pointer.fileId());
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> assertThrows(StorageException.class, () -> manager.createBTree(id, op, session)));
      assertThrows(StorageException.class, () -> manager.loadIsolatedBTree(pointer));
    });
  }

  // Cleanup with an early capture has no tree handle. It still removes only B, not A in its slot.
  @Test
  public void earlyCaptureCleanupUsesIdentityRatherThanReusedSlot() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      int id = session.addCollection("kept");
      var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      underWriteLock(storage, () -> {
        try {
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
            var kept = storage.captureCollectionOwner(id, op);
            var failed = tree(storage, 100001);
            failed.create(op);
            loaded(manager).put(identity(100001).internalId(), failed);
            manager.deleteComponent(op,
                new LinkCollectionsBTreeManagerShared.CollectionOwner(id, 100001, null));
            assertFalse(op.isFileExists(PageFileNames.layout28(identity(100001))));
            assertFalse(loaded(manager).containsKey(identity(100001).internalId()));
            assertSame(kept.tree(), manager.requireComponent(kept, op));
          });
        } catch (java.io.IOException failure) {
          throw new AssertionError(failure);
        }
      });
    });
  }

  // A registered name with another file id is rejected even when a cached owner still exists.
  @Test
  public void wrongRegistrationFailsRequiredAccessAndConfiguredDiscovery() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      int id = session.addCollection("wrong");
      var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      var owner = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> storage.captureCollectionOwner(id, op));
      storage.getAtomicOperationsManager()
          .executeInsideAtomicOperation(op -> op.deleteFile(owner.tree().getFileId()));
      var other = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> op.addFile("unconfigured.grb"));
      storage.getWriteCache().renameFile(other,
          PageFileNames.layout28(identity(owner.componentId())));
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        assertThrows(StorageException.class, () -> manager.requireComponent(owner, op));
        assertThrows(StorageException.class, () -> manager.createBTree(id, op, session));
        assertThrows(StorageException.class,
            () -> new LinkCollectionsBTreeManagerShared(storage).load(op));
      });
    });
  }

  // An unreadable configured entry point fails discovery instead of publishing an empty tree.
  @Test
  public void configuredTreeWithoutEntryPointFailsDiscovery() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      int id = session.addCollection("broken");
      var owner = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> storage.captureCollectionOwner(id, op));
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        var unreadable = org.mockito.Mockito.spy(op);
        org.mockito.Mockito.doThrow(new java.io.IOException("injected unreadable entry point"))
            .when(unreadable).loadFile(org.mockito.ArgumentMatchers.eq(
                PageFileNames.layout28(identity(owner.componentId()))),
                org.mockito.ArgumentMatchers.anyLong());
        assertThrows(StorageException.class,
            () -> new LinkCollectionsBTreeManagerShared(storage).load(unreadable));
        var manager =
            (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
        // Required access uses the loaded handle and a lock-free registration lookup.
        assertSame(owner.tree(), manager.requireComponent(owner, unreadable));
        org.mockito.Mockito.verify(unreadable, org.mockito.Mockito.times(1)).loadFile(
            org.mockito.ArgumentMatchers.eq(PageFileNames.layout28(identity(owner.componentId()))),
            org.mockito.ArgumentMatchers.anyLong());
      });
    });
  }

  // Computed pointers retain their exact file id in both delta reference forms and persisted records.
  // Closing the entire context ensures the record is read from the disk image, not session caches.
  @Test
  public void computedPointersSurviveDeltaFormsAndPersistedRecordColdReopen() throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-pointers";
    FileUtils.deleteRecursively(new File(directory));
    com.jetbrains.youtrackdb.internal.core.db.record.record.RID record;
    LinkBagPointer pointer;
    try {
      try (var tracks = (YouTrackDBImpl) YourTracks.instance(directory)) {
        try (var ignored =
            StorageBirthTestSupport.selectBirthFormat(tracks, DB, StorageFormat.LAYOUT_28)) {
          tracks.create(DB, DatabaseType.DISK, "admin", "admin", "admin");
        }
        try (var session = (DatabaseSessionEmbedded) tracks.open(DB, "admin", "admin")) {
          var cls = session.getMetadata().getSchema().createClass("PointerOwner", 1);
          int id = cls.getCollectionIds()[0];
          var storage = session.getStorage();
          pointer = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> session.getBTreeCollectionManager().createBTree(id, op, session));
          var component = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
              op -> storage.captureCollectionOwner(id, op).componentId());
          assertEquals(identity(component).internalId(), pointer.fileId());
          var kept = pointer;
          record = session.computeInTx(tx -> tx.newEntity("PointerOwner").getIdentity());
          var linked = record;
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(
              op -> session.getBTreeCollectionManager().loadIsolatedBTree(kept).put(op, linked,
                  new com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue(
                      1, linked.getCollectionId(), linked.getCollectionPosition(), false)));
          session.getConfiguration().setValue(
              com.jetbrains.youtrackdb.api.config.GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD,
              -1);
          session.executeInTx(tx -> {
            var entity = tx.loadEntity(record);
            var bag = new com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag(
                session, new BTreeBasedLinkBag(session, kept, 0, Integer.MAX_VALUE));
            entity.setProperty("bag", bag,
                com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.LINKBAG);
            var delta =
                com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializerDelta
                    .instance();
            for (var type : new com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal[] {
                com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal.LINKBAG,
                com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal.LINKSET}) {
              Object value = type
                  == com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal.LINKBAG
                      ? bag
                      : new com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl(
                          session, new BTreeBasedLinkBag(session, kept, 0, 1));
              var bytes =
                  new com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.BytesContainer();
              com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializerDelta
                  .serializeValue(session, bytes, value, type, null);
              assertEquals(2, bytes.bytes[0]);
              var decoded = delta.deserializeValue(session,
                  new com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.BytesContainer(
                      bytes.fitBytes()),
                  type, null);
              var decodedPointer =
                  decoded instanceof com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag decodedBag
                      ? decodedBag.getPointer()
                      : ((com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl) decoded)
                          .getPointer();
              assertEquals(kept, decodedPointer);
            }
          });
        }
      }
      try (var tracks = (YouTrackDBImpl) YourTracks.instance(directory);
          var session = (DatabaseSessionEmbedded) tracks.open(DB, "admin", "admin")) {
        var kept = pointer;
        session.executeInTx(tx -> {
          var entity = tx.loadEntity(record);
          com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag bag =
              entity.getProperty("bag");
          assertEquals(kept, bag.getPointer());
          assertTrue(kept.isValid());
          var op = session.getActiveTransaction().getAtomicOperation();
          // Resolve through the reopened manager, not merely through the encoded pointer.
          assertEquals(1, session.getBTreeCollectionManager().loadIsolatedBTree(kept)
              .get(record, op).counter());
        });
      }
    } finally {
      FileUtils.deleteRecursively(new File(directory));
    }
  }

  // Restoration rejects another owner's captured handle and reloads only when no handle exists.
  @Test
  public void restorationChecksCapturedIdentityAndSupportsEarlyCapture() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      int a = session.addCollection("restorea");
      int b = session.addCollection("restoreb");
      var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        var owner = storage.captureCollectionOwner(a, op);
        var other = storage.captureCollectionOwner(b, op);
        assertThrows(StorageException.class, () -> manager.restoreComponent(op,
            new LinkCollectionsBTreeManagerShared.CollectionOwner(a, owner.componentId(),
                other.tree())));
        var renamed = org.mockito.Mockito.spy(owner.tree());
        org.mockito.Mockito.doReturn("wrong-name.grb").when(renamed).getFullName();
        assertThrows(StorageException.class, () -> manager.restoreComponent(op,
            new LinkCollectionsBTreeManagerShared.CollectionOwner(a, owner.componentId(),
                renamed)));
        manager.restoreComponent(op, new LinkCollectionsBTreeManagerShared.CollectionOwner(
            a, owner.componentId(), null));
        assertNotNull(manager.captureOwner(a, owner.componentId(), op).tree());
        manager.restoreComponent(op, owner);
        assertSame(owner.tree(), manager.requireComponent(owner, op));
        op.deleteFile(owner.tree().getFileId());
        assertThrows(StorageException.class, () -> manager.restoreComponent(op, owner));
      });
    });
  }

  // A published tree stays exclusively locked and epoch-registered until its drop operation ends.
  @Test
  public void deletionRegistersCapturedTreeLockForOperationLifetime() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      int id = session.addCollection("dropowner");
      var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      underWriteLock(storage, () -> {
        try {
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
            var owner = storage.captureCollectionOwner(id, op);
            manager.deleteComponent(op, owner);
            assertEquals(
                com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation.ComponentLockMode.EXCLUSIVE,
                op.lockedObjectMode(owner.tree().getLockName()));
            assertTrue(java.util.stream.StreamSupport.stream(
                op.lockedComponents().spliterator(), false).anyMatch(c -> c == owner.tree()));
          });
        } catch (java.io.IOException failure) {
          throw new AssertionError(failure);
        }
      });
    });
  }

  // Operation-visible configuration wins even when runtime membership still contains the owner.
  @Test
  public void discoveryExcludesRuntimeOwnerRemovedFromCurrentConfiguration() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var storage = session.getStorage();
      int id = session.addCollection("staleruntime");
      var owner = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> storage.captureCollectionOwner(id, op));
      var fresh = new LinkCollectionsBTreeManagerShared(storage);
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        var configField = AbstractStorage.class.getDeclaredField("configuration");
        configField.setAccessible(true);
        var config =
            (com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration) configField
                .get(storage);
        config.dropCollection(op, id);
        assertEquals(id, storage.getCollectionIdByName("staleruntime"));
        fresh.load(op);
        assertFalse(loaded(fresh).containsKey(identity(owner.componentId()).internalId()));
      });
    });
  }

  // Plain entity commits require the tree lock even when no bag is serialized.
  @Test
  public void plainRecordCommitRejectsMissingWrongAndUnloadedTrees() throws Exception {
    for (var fault : new String[] {"missing", "wrong", "unloaded", "unreadable"}) {
      withStorage(StorageFormat.LAYOUT_28, session -> {
        var cls = session.getMetadata().getSchema().createClass("Plain", 1);
        int id = cls.getCollectionIds()[0];
        var rid = session.computeInTx(tx -> {
          var entity = tx.newEntity("Plain");
          entity.setProperty("value", "before");
          return entity.getIdentity();
        });
        var storage = session.getStorage();
        var manager =
            (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
        var owner = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            op -> storage.captureCollectionOwner(id, op));
        var name = PageFileNames.layout28(identity(owner.componentId()));
        var cache = storage.getWriteCache();
        var cacheField = Class.forName(
            "com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationBinaryTracking")
            .getDeclaredField("writeCache");
        cacheField.setAccessible(true);
        if (fault.equals("unloaded")) {
          loaded(manager).remove(identity(owner.componentId()).internalId());
        } else if (fault.equals("unreadable")) {
          var unreadable = org.mockito.Mockito.spy(cache);
          org.mockito.Mockito.doThrow(new StorageException(storage.getName(),
              "injected unreadable registration")).when(unreadable).fileIdByName(name);
          // Install below after begin, into the operation that the plain commit actually uses.
          cache = unreadable;
        } else {
          storage.getAtomicOperationsManager()
              .executeInsideAtomicOperation(op -> op.deleteFile(owner.tree().getFileId()));
          if (fault.equals("wrong")) {
            var other = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
                op -> op.addFile("wrong.grb"));
            cache.renameFile(other, name);
          }
        }
        var files = new java.util.HashMap<>(storage.getWriteCache().files());
        session.begin();
        var op = session.getActiveTransaction().getAtomicOperation();
        if (fault.equals("unreadable")) {
          cacheField.set(op, cache);
        }
        session.getActiveTransaction().loadEntity(rid).setProperty("value", "after");
        var failure = assertThrows(fault, RuntimeException.class, session::commit);
        assertTrue(failure.toString(), hasStorageFailure(failure));
        assertTrue("failure must reach lockLinkBags: " + fault,
            java.util.Arrays.stream(stackOfStorageFailure(failure)).anyMatch(
                frame -> frame.getMethodName().equals("lockLinkBags")));
        assertEquals(files, storage.getWriteCache().files());
        session.executeInTx(tx -> assertEquals("before", tx.loadEntity(rid).getProperty("value")));
      });
    }
  }

  private static boolean hasStorageFailure(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof StorageException) {
        return true;
      }
    }
    return false;
  }

  // Saving a tree-backed bag reaches serialization before the commit lock boundary and must not
  // reconstruct a missing identity-owned file.
  @Test
  public void serializationRejectsMissingTreeWithoutReplacement() throws Exception {
    withStorage(StorageFormat.LAYOUT_28, session -> {
      var cls = session.getMetadata().getSchema().createClass("BagOwner", 1);
      int id = cls.getCollectionIds()[0];
      var rid = session.computeInTx(tx -> tx.newEntity("BagOwner").getIdentity());
      var storage = session.getStorage();
      var owner = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          op -> storage.captureCollectionOwner(id, op));
      storage.getAtomicOperationsManager()
          .executeInsideAtomicOperation(op -> op.deleteFile(owner.tree().getFileId()));
      var files = new java.util.HashMap<>(storage.getWriteCache().files());
      session.getConfiguration().setValue(
          com.jetbrains.youtrackdb.api.config.GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD,
          -1);
      session.begin();
      var entity = session.getActiveTransaction().loadEntity(rid);
      var bag = new com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag(
          session, new BTreeBasedLinkBag(session, Integer.MAX_VALUE));
      entity.setProperty("bag", bag,
          com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.LINKBAG);
      var operations = storage.getAtomicOperationsManager();
      var op = session.getActiveTransaction().getAtomicOperation();
      // Enter the real write phase so a repair-tolerant implementation could create a file.
      // Invoke serialization directly because the commit tree-lock guard runs before serialization.
      operations.startToApplyOperations(op);
      RuntimeException failure;
      try {
        failure = assertThrows(RuntimeException.class,
            () -> new com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.RecordSerializerBinaryV1()
                .serialize(session,
                    (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) entity,
                    new com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.BytesContainer()));
      } finally {
        op.rollbackInProgress();
        operations.endAtomicOperation(op, null);
        session.rollback();
      }
      assertTrue(hasStorageFailure(failure));
      assertTrue("failure must originate in bag serialization",
          java.util.Arrays.stream(stackOfStorageFailure(failure)).anyMatch(
              frame -> frame.getMethodName().equals("writeBTreeBasedLinkBag")));
      assertEquals(files, storage.getWriteCache().files());
    });
  }

  private static StackTraceElement[] stackOfStorageFailure(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof StorageException) {
        return cause.getStackTrace();
      }
    }
    return new StackTraceElement[0];
  }

  // Layout 27 cold open reconstructs a missing tree through open-time repair.
  @Test
  public void legacyColdOpenRepairsMissingTree() throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-legacyrepair";
    FileUtils.deleteRecursively(new File(directory));
    int id;
    try {
      try (var tracks = (YouTrackDBImpl) YourTracks.instance(directory)) {
        try (var ignored = StorageBirthTestSupport.selectBirthFormat(tracks, DB,
            StorageFormat.LAYOUT_27)) {
          tracks.create(DB, DatabaseType.DISK, "admin", "admin", "admin");
        }
        try (var session = (DatabaseSessionEmbedded) tracks.open(DB, "admin", "admin")) {
          id = session.addCollection("repair");
          var storage = session.getStorage();
          storage.getAtomicOperationsManager()
              .executeInsideAtomicOperation(op -> op.deleteFile(op.fileIdByName(
                  LinkCollectionsBTreeManagerShared.generateLockName(id))));
        }
      }
      try (var tracks = (YouTrackDBImpl) YourTracks.instance(directory);
          var session = (DatabaseSessionEmbedded) tracks.open(DB, "admin", "admin")) {
        var storage = session.getStorage();
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
          var manager = (LinkCollectionsBTreeManagerShared) storage
              .getLinkCollectionsBtreeCollectionManager();
          assertNotNull(manager.getComponentByCollectionId(id, op));
          assertTrue(op.isFileExists(LinkCollectionsBTreeManagerShared.generateLockName(id)));
        });
      }
    } finally {
      FileUtils.deleteRecursively(new File(directory));
    }
  }

  // Layout 27 keeps registry discovery and lazy creation for collections without a tree.
  @Test
  public void legacyRegistryDiscoveryAndLazyRepairRemainAvailable() throws Exception {
    withStorage(StorageFormat.LAYOUT_27, session -> {
      var storage = session.getStorage();
      var manager =
          (LinkCollectionsBTreeManagerShared) storage.getLinkCollectionsBtreeCollectionManager();
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        manager.createComponent(op, 99999);
      });
      var fresh = new LinkCollectionsBTreeManagerShared(storage);
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        fresh.load(op);
        assertNotNull(fresh.getComponentByCollectionId(99999, op));
        assertNotNull(fresh.createBTree(99998, op, session));
        assertTrue(op.isFileExists(LinkCollectionsBTreeManagerShared.generateLockName(99998)));
      });
    });
  }
}
