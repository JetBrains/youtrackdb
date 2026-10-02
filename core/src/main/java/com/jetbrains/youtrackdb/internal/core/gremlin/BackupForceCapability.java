package com.jetbrains.youtrackdb.internal.core.gremlin;

import java.io.IOException;

/**
 * Optional durability capability of an output stream returned by a backup output supplier.
 *
 * <p>A call returns only when every byte written so far and the unit's file length are durable.
 * It fails with an error if it cannot guarantee this. It never returns normally without that
 * guarantee.
 *
 * <p>The stream also guarantees that one write request wholly inside one aligned 512-byte block
 * of the unit reaches durable storage completely or not at all. Blocks are counted from the start
 * of the unit. A stream that cannot guarantee both durability and this publication rule must not
 * implement this interface. The writer then records an absent barrier flag. The next extension
 * checks the full unit.
 */
public interface BackupForceCapability {

  /** Makes all bytes written so far and the file length durable, or fails. */
  void forceBackupData() throws IOException;
}
