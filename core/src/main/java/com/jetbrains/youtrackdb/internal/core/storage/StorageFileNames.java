package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogGL;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMap;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.CollectionDirtyPageBitSet;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.FreeSpaceMap;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Shared naming rules for component ownership and drop-only storage infrastructure. */
public final class StorageFileNames {

  public static final String NAME_ID_MAP_EXTENSION = ".cm";
  public static final String MAP_TEMP_PREFIX = "name_id_map_";
  public static final String TEMP_SUFFIX = ".tmp";
  public static final String MAP_TEMP_GLOB = MAP_TEMP_PREFIX + "*" + TEMP_SUFFIX;
  public static final String DIRTY_FILE_EXTENSION = ".fl";
  public static final String DIRTY_BACKUP_EXTENSION = ".flb";
  public static final String DIRTY_FILE_NAME = "dirty" + DIRTY_FILE_EXTENSION;
  public static final String DIRTY_BACKUP_NAME = "dirty" + DIRTY_BACKUP_EXTENSION;
  public static final String IV_EXTENSION = ".iv";
  public static final String BOOTSTRAP_EXTENSION = ".bsm";
  public static final String BOOTSTRAP_TEMP_EXTENSION = BOOTSTRAP_EXTENSION + TEMP_SUFFIX;
  public static final String BOOTSTRAP_LOCK_EXTENSION = ".bsml";
  public static final String ORPHANS_DIRECTORY = "orphans";
  public static final String WAL_RESTORE_DIRECTORY = "walIncrementalBackupRestoreDirectory";
  public static final String INDEX_DATA_EXTENSION = BTreeMultiValueIndexEngine.DATA_FILE_EXTENSION;
  public static final String INDEX_NULL_EXTENSION =
      BTreeMultiValueIndexEngine.NULL_BUCKET_FILE_EXTENSION;

  private static final String POSITIVE_ID = "[1-9][0-9]*";
  private static final String ENGINE_STEM =
      Pattern.quote(AbstractStorage.INDEX_ENGINE_FILE_STEM_PREFIX) + POSITIVE_ID;
  private static final String TREE_STEM = ENGINE_STEM
      + "(?:" + Pattern.quote(AbstractStorage.NULL_TREE_SUFFIX) + ")?";

  /** A current logical filename shape, also accepting WOWCache's physical _<id> suffix. */
  public record ComponentFamily(String extension, Pattern fileNamePattern) {

    public boolean matches(String fileName) {
      return fileNamePattern.matcher(fileName).matches();
    }
  }

  // Keep full shapes separate from extension-only drop rules. Legacy index names and malformed
  // numeric stems must not become candidates for the ownership pass or the unregistered scan.
  public static final List<ComponentFamily> COMPONENT_FAMILIES = List.of(
      family("[^/\\\\]+", PaginatedCollection.DEF_EXTENSION),
      family("[^/\\\\]+", CollectionPositionMap.DEF_EXTENSION),
      family("[^/\\\\]+", FreeSpaceMap.DEF_EXTENSION),
      family("[^/\\\\]+", CollectionDirtyPageBitSet.DEF_EXTENSION),
      family(Pattern.quote(LinkCollectionsBTreeManagerShared.FILE_NAME_PREFIX)
          + "(?:0|" + POSITIVE_ID + ")", LinkCollectionsBTreeManagerShared.FILE_EXTENSION),
      family(TREE_STEM, INDEX_DATA_EXTENSION),
      family(TREE_STEM, INDEX_NULL_EXTENSION),
      family(ENGINE_STEM, IndexHistogramManager.IXS_EXTENSION),
      configurationFamily(CollectionBasedStorageConfiguration.DATA_FILE_EXTENSION),
      configurationFamily(CollectionBasedStorageConfiguration.MAP_FILE_EXTENSION),
      configurationFamily(CollectionBasedStorageConfiguration.FREE_MAP_FILE_EXTENSION),
      configurationFamily(CollectionBasedStorageConfiguration.DIRTY_PAGE_FILE_EXTENSION),
      configurationFamily(CollectionBasedStorageConfiguration.TREE_DATA_FILE_EXTENSION),
      configurationFamily(CollectionBasedStorageConfiguration.TREE_NULL_FILE_EXTENSION));

  /** Drop-only suffix rules, including known legacy storage formats. */
  public static final List<String> INFRASTRUCTURE_FILE_EXTENSIONS = List.of(
      NAME_ID_MAP_EXTENSION, ".ocf", ".pls", ".oda", ".odh", ".otx", ".ocs", ".oef", ".oem",
      ".oet", BTreeMultiValueIndexEngine.M_CONTAINER_EXTENSION,
      WriteAheadLog.WAL_SEGMENT_EXTENSION, WriteAheadLog.MASTER_RECORD_EXTENSION,
      DoubleWriteLogGL.EXTENSION, IV_EXTENSION, BOOTSTRAP_EXTENSION, BOOTSTRAP_TEMP_EXTENSION,
      BOOTSTRAP_LOCK_EXTENSION, DIRTY_FILE_EXTENSION, DIRTY_BACKUP_EXTENSION);
  public static final List<String> INFRASTRUCTURE_FILE_PATTERNS = List.of(MAP_TEMP_GLOB);
  public static final List<String> INFRASTRUCTURE_DIRECTORIES =
      List.of(ORPHANS_DIRECTORY, WAL_RESTORE_DIRECTORY);
  public static final List<String> DROP_FILE_EXTENSIONS = Stream.concat(
      COMPONENT_FAMILIES.stream().map(ComponentFamily::extension),
      INFRASTRUCTURE_FILE_EXTENSIONS.stream()).distinct().toList();

  // The parent fallback is YTDB-1443. Do not widen its deletion scope with new suffixes or folders.
  public static final List<String> PARENT_DROP_FILE_EXTENSIONS = DROP_FILE_EXTENSIONS.stream()
      .filter(extension -> !List.of(CollectionDirtyPageBitSet.DEF_EXTENSION,
          CollectionBasedStorageConfiguration.FREE_MAP_FILE_EXTENSION,
          CollectionBasedStorageConfiguration.DIRTY_PAGE_FILE_EXTENSION).contains(extension))
      .toList();

  private StorageFileNames() {
  }

  public static boolean isComponentFile(String fileName) {
    return COMPONENT_FAMILIES.stream().anyMatch(family -> family.matches(fileName));
  }

  public static boolean isIndexEngineFileExtension(String extension) {
    return INDEX_DATA_EXTENSION.equals(extension) || INDEX_NULL_EXTENSION.equals(extension)
        || IndexHistogramManager.IXS_EXTENSION.equals(extension);
  }

  public static boolean isMapTemporaryFile(String fileName) {
    return fileName.startsWith(MAP_TEMP_PREFIX) && fileName.endsWith(TEMP_SUFFIX);
  }

  private static ComponentFamily configurationFamily(String extension) {
    return family(Pattern.quote(CollectionBasedStorageConfiguration.COMPONENT_NAME), extension);
  }

  private static ComponentFamily family(String stemPattern, String extension) {
    return new ComponentFamily(extension, Pattern.compile(
        stemPattern + "(?:_" + POSITIVE_ID + ")?" + Pattern.quote(extension)));
  }
}
