package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.StorageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies that the DiskStorage known file extensions array includes all
 * required extensions, including the histogram stats file extension.
 *
 * <p>The {@code ALL_FILE_EXTENSIONS} array controls which files are deleted
 * during {@code DiskStorage.drop()} and storage cleanup. Missing extensions
 * cause stale files to remain on disk after database deletion.
 */
public class DiskStorageFileExtensionsTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  /** Pins the complete drop suffix set and the unchanged parent fallback, in both directions. */
  @Test
  public void dropSuffixesEqualTheExistingSetPlusTheThreeMissingComponentExtensions() {
    var existing = Set.of(".cm", ".ocf", ".pls", ".pcl", ".oda", ".odh", ".otx", ".ocs",
        ".oef", ".oem", ".oet", ".fl", ".flb", ".iv", ".wal", ".wmr", ".cpm", ".grb",
        ".ccm", ".cd", ".bd", ".nd", ".cbt", ".nbt", ".mbt", ".ixs", ".dwl", ".fsm",
        ".bsm", ".bsm.tmp", ".bsml");
    assertEquals(existing, new HashSet<>(StorageFileNames.PARENT_DROP_FILE_EXTENSIONS));
    var expected = new HashSet<>(existing);
    expected.addAll(Set.of(".dpb", ".fcm", ".dcm"));
    assertEquals(expected, new HashSet<>(getAllFileExtensions()));
    assertEquals(expected.size(), getAllFileExtensions().size());
    assertEquals(expected, new HashSet<>(StorageFileNames.DROP_FILE_EXTENSIONS));
  }

  /**
   * Real collection, configuration, link-bag and both index kinds must create exactly their
   * rosters. Every family entry needs a real witness, including histogram files created by
   * AbstractStorage. Both logical registrations and their physical disk names must match.
   */
  @Test
  public void componentFamiliesEqualTheFilesCreatedByEveryComponentKind() throws Exception {
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(temporaryFolder.getRoot().toString())) {
      ytdb.create("roster", DatabaseType.DISK, "admin", "password", "admin");
      try (var session = (DatabaseSessionEmbedded) ytdb.open("roster", "admin", "password")) {
        var storage = (AbstractStorage) session.getStorage();
        var cache = storage.getWriteCache();
        var witnessed = new HashSet<String>();
        var configuration = new HashSet<String>();
        cache.files().keySet().stream().filter(name -> name.startsWith("config."))
            .forEach(configuration::add);
        assertRoster(configuration, "config", Set.of(".cd", ".ccm", ".fcm", ".dcm", ".bd", ".nd"));
        witnessed.addAll(configuration);

        var before = new HashSet<>(cache.files().keySet());
        var collectionId = session.addCollection("roster_collection");
        var collectionFiles = new HashSet<>(cache.files().keySet());
        collectionFiles.removeAll(before);
        var linkBag = "global_collection_" + collectionId + ".grb";
        assertTrue(collectionFiles.remove(linkBag));
        assertRoster(collectionFiles, "roster_collection", Set.of(".pcl", ".cpm", ".fsm", ".dpb"));
        witnessed.addAll(collectionFiles);
        witnessed.add(linkBag);

        var cls = session.getMetadata().getSchema().createClass("RosterClass");
        cls.createProperty("value", PropertyType.STRING);
        for (var type : List.of(SchemaClass.INDEX_TYPE.UNIQUE, SchemaClass.INDEX_TYPE.NOTUNIQUE)) {
          before = new HashSet<>(cache.files().keySet());
          var indexName = "Roster" + type;
          cls.createIndex(indexName, type, "value");
          var created = new HashSet<>(cache.files().keySet());
          created.removeAll(before);
          var engine =
              (BTreeIndexEngine) storage.getIndexEngine(storage.loadIndexEngine(indexName));
          var stem = AbstractStorage.indexEngineFileStem(engine.getFileBaseId());
          var expected = new HashSet<>(Set.of(stem + ".cbt", stem + ".nbt", stem + ".ixs"));
          if (type == SchemaClass.INDEX_TYPE.NOTUNIQUE) {
            expected.add(stem + "$null.cbt");
            expected.add(stem + "$null.nbt");
          }
          assertEquals(expected, created);
          witnessed.addAll(created);
        }
        var seenFamilies = new HashSet<StorageFileNames.ComponentFamily>();
        for (var logicalName : witnessed) {
          assertTrue(logicalName, StorageFileNames.isComponentFile(logicalName));
          var diskName = cache.nativeFileNameById(cache.files().get(logicalName));
          assertTrue(diskName, Files.exists(cache.getRootDirectory().resolve(diskName)));
          assertTrue(diskName, StorageFileNames.isComponentFile(diskName));
          for (var family : StorageFileNames.COMPONENT_FAMILIES) {
            if (family.matches(logicalName)) {
              seenFamilies.add(family);
              assertTrue(diskName, family.matches(diskName));
            }
          }
        }
        assertEquals(new HashSet<>(StorageFileNames.COMPONENT_FAMILIES), seenFamilies);
      }
    }
  }

  /** Drop on an actually CLOSED storage removes registered files and offline leftovers. */
  @Test
  public void closedStorageDropRemovesItsWholeDirectoryIncludingQuarantine() throws Exception {
    DiskStorage storage;
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(temporaryFolder.getRoot().toString())) {
      ytdb.create("closed", DatabaseType.DISK, "admin", "password", "admin");
      try (var session = (DatabaseSessionEmbedded) ytdb.open("closed", "admin", "password")) {
        storage = (DiskStorage) session.getStorage();
      }
    }
    var directory = storage.getStoragePath();
    Files.createFile(directory.resolve("name_id_map_offline.tmp"));
    for (var folder : StorageFileNames.INFRASTRUCTURE_DIRECTORIES) {
      Files.createDirectories(directory.resolve(folder).resolve("nested"));
      Files.createFile(directory.resolve(folder).resolve("nested/residue"));
    }
    storage.delete();
    assertFalse(Files.exists(directory));
  }

  /** Unknown, legacy, malformed and infrastructure names never enter the component families. */
  @Test
  public void componentFamiliesRejectNamesThatNoCurrentComponentCreates() {
    for (var name : List.of("myIndex.cbt", "ie_abc.cbt", "ie_12$null.ixs",
        "global_collection_x.grb", "ie_12.mbt", ".mbt", "other.cd", "config_x.cd",
        "ie_0.cbt", "global_collection_-1.grb", "ie_12_0.cbt", "ie_12_-1.cbt",
        "../ie_12.cbt", "nested/ie_12.cbt", ".pcl", "name_id_map_v3.cm",
        "name_id_map_123.tmp", "storage-bootstrap-0.bsm", "backup.ibl", "backup.ibu")) {
      assertFalse(name, StorageFileNames.isComponentFile(name));
    }
    for (var extension : List.of(".cbt", ".nbt", ".ixs")) {
      assertTrue(StorageFileNames.isIndexEngineFileExtension(extension));
    }
    for (var extension : List.of(".pcl", ".mbt", ".cd", ".unknown")) {
      assertFalse(StorageFileNames.isIndexEngineFileExtension(extension));
    }
  }

  private static void assertRoster(Set<String> actual, String stem, Set<String> extensions) {
    var expected = new HashSet<String>();
    extensions.forEach(extension -> expected.add(stem + extension));
    assertEquals(expected, actual);
  }

  /**
   * Verifies that the histogram stats extension (.ixs) is registered in
   * DiskStorage's ALL_FILE_EXTENSIONS array. Without this entry, .ixs files
   * would not be cleaned up during database deletion, leaving stale histogram
   * data files on disk.
   */
  @Test
  public void allFileExtensions_containsIxsExtension() {
    var extensions = getAllFileExtensions();
    assertTrue(
        "ALL_FILE_EXTENSIONS must contain the histogram stats extension '"
            + IndexHistogramManager.IXS_EXTENSION + "'",
        extensions.contains(IndexHistogramManager.IXS_EXTENSION));
  }

  /**
   * Verifies that the B-tree null-bucket extension (.nbt) is registered in
   * DiskStorage's ALL_FILE_EXTENSIONS array, so drop removes null-bucket files
   * and can delete the storage directory.
   */
  @Test
  public void allFileExtensions_containsNullBucketExtension() {
    var extensions = getAllFileExtensions();
    assertTrue(
        "ALL_FILE_EXTENSIONS must contain the B-tree null-bucket extension '"
            + BTreeMultiValueIndexEngine.NULL_BUCKET_FILE_EXTENSION + "'",
        extensions.contains(BTreeMultiValueIndexEngine.NULL_BUCKET_FILE_EXTENSION));
  }

  /**
   * Verifies that all entries in the extensions array are non-null and
   * start with a dot, guarding against accidental corruption of the
   * constant array.
   */
  @Test
  public void allFileExtensions_allEntriesAreValidExtensions() {
    var extensions = getAllFileExtensions();
    for (var ext : extensions) {
      assertTrue(
          "Each file extension must start with '.', but found: " + ext,
          ext != null && ext.startsWith("."));
    }
  }

  /**
   * Reads the private static ALL_FILE_EXTENSIONS field from DiskStorage
   * via reflection.
   */
  private static List<String> getAllFileExtensions() {
    try {
      var field = DiskStorage.class.getDeclaredField("ALL_FILE_EXTENSIONS");
      field.setAccessible(true);
      var extensions = (String[]) field.get(null);
      return Arrays.asList(extensions);
    } catch (ReflectiveOperationException e) {
      fail("Could not access DiskStorage.ALL_FILE_EXTENSIONS: " + e.getMessage());
      return List.of(); // unreachable
    }
  }
}
