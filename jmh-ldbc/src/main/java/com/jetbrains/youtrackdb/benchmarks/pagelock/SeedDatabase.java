package com.jetbrains.youtrackdb.benchmarks.pagelock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Closed seeds and disposable run copies. Call these methods only outside measured time. */
public final class SeedDatabase {

  @FunctionalInterface
  public interface Generator {
    /** Creates the workload under root and closes every database handle before returning. */
    void createAndClose(Path root) throws Exception;
  }

  private final Path seed;

  private SeedDatabase(Path seed) {
    this.seed = seed;
  }

  /**
   * The caller gives each workload and generator version a distinct path.
   * One setup process owns this path. An incomplete generation is never published as a seed.
   */
  public static SeedDatabase prepare(Path seed, Generator generator) throws Exception {
    seed = seed.toAbsolutePath();
    if (!Files.exists(seed)) {
      Files.createDirectories(seed.getParent());
      Path staging = Files.createTempDirectory(seed.getParent(), "seed-building-");
      try {
        generator.createAndClose(staging);
        Files.move(staging, seed);
      } finally {
        deleteTree(staging);
      }
    }
    return new SeedDatabase(seed);
  }

  /** GNU cp uses reflinks where supported and copies bytes otherwise. It never makes hard links. */
  public Path freshCopy(Path runParent) throws IOException, InterruptedException {
    Files.createDirectories(runParent);
    Path copy = Files.createTempDirectory(runParent, "run-");
    try {
      Process process = new ProcessBuilder("cp", "-R", "--reflink=auto", "--",
          seed + "/.", copy.toString()).inheritIO().start();
      try {
        if (process.waitFor() != 0) {
          throw new IOException("Seed copy failed: " + seed);
        }
      } catch (InterruptedException e) {
        // Stop the copier before removing its destination.
        process.destroyForcibly().onExit().join();
        throw e;
      }
      return copy;
    } catch (IOException | InterruptedException e) {
      deleteTree(copy);
      throw e;
    }
  }

  /** Call after closing the run database. Cleanup errors stop the caller. */
  public static void deleteTree(Path root) throws IOException {
    if (Files.exists(root)) {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.delete(path);
        }
      }
    }
  }
}
