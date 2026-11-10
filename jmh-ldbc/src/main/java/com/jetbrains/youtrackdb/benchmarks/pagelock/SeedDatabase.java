package com.jetbrains.youtrackdb.benchmarks.pagelock;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

/** Closed seeds and disposable run copies. Call these methods only outside measured time. */
public final class SeedDatabase {

  @FunctionalInterface
  public interface Generator {
    /** Creates the workload under root and closes every database handle before returning. */
    void createAndClose(Path root) throws Exception;
  }

  private static final boolean REFLINK_COPY_AVAILABLE = reflinkCopyAvailable(
      System.getProperty("os.name"), new ProcessBuilder("cp", "--reflink=auto", "--version"));
  private static final int COPY_TIMEOUT_SECONDS = 120;

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

  /** Uses GNU cp reflinks on Linux when available, otherwise copies bytes. Never makes hard links. */
  public Path freshCopy(Path runParent) throws IOException, InterruptedException {
    return freshCopy(runParent, REFLINK_COPY_AVAILABLE);
  }

  // The explicit choice lets tests exercise Java copying on every OS.
  Path freshCopy(Path runParent, boolean useReflinkCopy) throws IOException, InterruptedException {
    return freshCopy(runParent, useReflinkCopy ? copy -> new ProcessBuilder(
        "cp", "-R", "--reflink=auto", "--", seed + "/.", copy.toString()).inheritIO().start()
        : null,
        COPY_TIMEOUT_SECONDS);
  }

  @FunctionalInterface
  interface Copier {
    Process start(Path destination) throws IOException;
  }

  // Inject the external command and deadline to test process failure and cancellation portably.
  Path freshCopy(Path runParent, Copier copier, int timeoutSeconds)
      throws IOException, InterruptedException {
    Files.createDirectories(runParent);
    Path copy = Files.createTempDirectory(runParent, "run-");
    Process process = null;
    try {
      if (copier != null) {
        process = copier.start(copy);
        try {
          if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IOException("Seed copy timed out after " + timeoutSeconds
                + " seconds: " + seed);
          }
          if (process.exitValue() != 0) {
            throw new IOException("Seed copy failed: " + seed);
          }
        } finally {
          // Stop the copier before removing its destination, including on interruption.
          if (process.isAlive()) {
            process.destroyForcibly();
          }
        }
      } else {
        Files.walkFileTree(seed, new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(copy.resolve(seed.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(file, copy.resolve(seed.relativize(file)));
            return FileVisitResult.CONTINUE;
          }
        });
      }
      return copy;
    } catch (IOException | InterruptedException e) {
      // A forceful destroy is asynchronous. Do not delete files while cp may still write them.
      if (process != null && process.isAlive() && !process.waitFor(5, TimeUnit.SECONDS)) {
        e.addSuppressed(new IOException("Seed copier did not stop. Copy retained at " + copy));
        throw e;
      }
      try {
        deleteTree(copy);
      } catch (IOException cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    }
  }

  static boolean reflinkCopyAvailable(String osName, ProcessBuilder probe) {
    // Do not invoke Git Bash cp on Windows. Probe GNU option support once on Linux.
    if (!osName.equalsIgnoreCase("Linux")) {
      return false;
    }
    Process process = null;
    try {
      process = probe.redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD).start();
      return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
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
