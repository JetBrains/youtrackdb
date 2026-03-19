package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Verifies that the DiskStorage known file extensions array includes all
 * required extensions, including the histogram stats file extension.
 *
 * <p>The {@code ALL_FILE_EXTENSIONS} array controls which files are deleted
 * during {@code DiskStorage.drop()} and storage cleanup. Missing extensions
 * cause stale files to remain on disk after database deletion.
 */
public class DiskStorageFileExtensionsTest {

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
