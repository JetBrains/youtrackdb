package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Holds one existing file lock for a bounded cross-process restart-deletion test. */
public final class ForeignFileLockHolder {

  private ForeignFileLockHolder() {
  }

  public static void main(final String[] arguments) throws Exception {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("Expected the path of one existing lock file");
    }
    final var lockPath = Path.of(arguments[0]);
    try (var channel =
        FileChannel.open(lockPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
        var ignored = channel.lock();
        var input = new BufferedReader(new InputStreamReader(System.in))) {
      System.out.println("READY");
      System.out.flush();
      if (!"release".equals(input.readLine())) {
        throw new IllegalStateException("Parent did not request lock release");
      }
    }
  }
}
