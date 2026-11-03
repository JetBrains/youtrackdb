package com.jetbrains.youtrackdb.internal.core.storage;

import static org.junit.Assert.assertEquals;

import java.util.Set;
import org.junit.Test;

public class StorageFileNamesTest {

  /** The owner pass must recognize exactly these four collection suffixes, without duplicates. */
  @Test
  public void collectionExtensionsEqualTheOwnerPassSet() {
    assertEquals(Set.of(".pcl", ".cpm", ".fsm", ".dpb"),
        Set.copyOf(StorageFileNames.COLLECTION_FILE_EXTENSIONS));
    assertEquals(4, StorageFileNames.COLLECTION_FILE_EXTENSIONS.size());
  }

  /** The owner pass must own exactly these six configuration suffixes, without duplicates. */
  @Test
  public void configurationExtensionsEqualTheOwnerPassSet() {
    assertEquals(Set.of(".cd", ".ccm", ".fcm", ".dcm", ".bd", ".nd"),
        Set.copyOf(StorageFileNames.CONFIGURATION_FILE_EXTENSIONS));
    assertEquals(6, StorageFileNames.CONFIGURATION_FILE_EXTENSIONS.size());
  }
}
