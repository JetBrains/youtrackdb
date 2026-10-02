package com.jetbrains.youtrackdb.internal.core.gremlin;

import java.io.IOException;

/**
 * Optional bounded read of one backup unit for an incremental backup input supplier.
 *
 * <p>The caller names a unit and a byte count. The result holds the last bytes of that unit,
 * up to the requested count. If the unit is shorter, the result holds all of its bytes. The
 * returned bytes must match the current end of the unit. A call must read no more than the
 * requested number of bytes from the unit. A supplier that cannot meet this limit must not
 * implement this interface. Backup then reads complete units instead.
 *
 * <p>A read failure throws an error. The extension stops and removes no unit.
 */
public interface BackupTailReadCapability {

  /** Returns at most {@code byteCount} bytes from the end of the named backup unit. */
  byte[] readBackupTail(String unitName, int byteCount) throws IOException;
}
