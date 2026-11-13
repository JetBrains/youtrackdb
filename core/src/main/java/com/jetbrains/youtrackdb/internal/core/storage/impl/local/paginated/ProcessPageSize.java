package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;

/** Fixes the page size when the process first uses storage or its shared buffers. */
public final class ProcessPageSize {

  private static final int KIBIBYTES =
      GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger();

  private ProcessPageSize() {
  }

  public static int kibibytes() {
    return KIBIBYTES;
  }

  public static int bytes() {
    return KIBIBYTES * 1024;
  }

  /** Rejects overrides instead of changing the size of shared process resources. */
  public static void checkConfiguration(ContextConfiguration configuration) {
    int global = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger();
    if (global != KIBIBYTES) {
      throw new IllegalArgumentException(
          "The setting youtrackdb.storage.diskCache.pageSize changed after the process fixed"
              + " its page size at " + KIBIBYTES + " KB. Configured page size is " + global
              + " KB. Restart the process to use another value.");
    }
    if (configuration != null) {
      int configured = configuration.getValueAsInteger(GlobalConfiguration.DISK_CACHE_PAGE_SIZE);
      if (configured != KIBIBYTES) {
        throw new IllegalArgumentException(
            "The configuration passed for this database sets youtrackdb.storage.diskCache.pageSize"
                + " to " + configured + " KB. The process page size is " + KIBIBYTES
                + " KB. The value can come from the database configuration or the instance"
                + " configuration. Remove that value from the configuration or set it to the"
                + " process page size.");
      }
    }
  }

  public static void requireDatabaseSize(int database, boolean assumed) {
    if (database != KIBIBYTES) {
      throw new IllegalArgumentException(
          "Database page size is " + database + " KB"
              + (assumed ? " (8 KB is assumed for an old-format record)" : "")
              + ". Configured page size is " + KIBIBYTES
              + " KB. Set youtrackdb.storage.diskCache.pageSize to the database page size."
              + " The process reads this setting at start."
              + " Restart the process after you change the setting."
              + (assumed
                  ? " Do not set 8 for a database created with another page size."
                  : ""));
    }
  }
}
