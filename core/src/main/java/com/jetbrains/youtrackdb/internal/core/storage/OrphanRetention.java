package com.jetbrains.youtrackdb.internal.core.storage;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One lock-free retention pass. Inputs belong to the submitting physical open, not mutable storage
 * state. This class owns no thread and does not change storage lifecycle state.
 */
public class OrphanRetention {
  private static final Logger logger = LoggerFactory.getLogger(OrphanRetention.class);
  static final long RUN_LIMIT_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
  private static final Pattern NAME =
      Pattern.compile("[0-9]{8}T[0-9]{9}Z-[0-9a-f]{32}");

  private final Clock wallClock;
  private final LongSupplier nanoTime;
  private final BooleanSupplier stop;

  public OrphanRetention(Clock wallClock, LongSupplier nanoTime, BooleanSupplier stop) {
    this.wallClock = wallClock;
    this.nanoTime = nanoTime;
    this.stop = stop;
  }

  /** Returns null for anything outside the complete, strict producer grammar. */
  static Instant folderTime(String name) {
    if (!NAME.matcher(name).matches()) {
      return null;
    }
    try {
      return Instant.from(StorageFileNames.QUARANTINE_TIMESTAMP.parse(name.substring(0, 19)));
    } catch (DateTimeParseException invalidDate) {
      return null;
    }
  }

  public void run(Path storagePath, String currentFolderName, int retentionDays) {
    final long started = nanoTime.getAsLong();
    if (retentionDays <= 0) {
      return;
    }
    final var root = storagePath.resolve(StorageFileNames.ORPHANS_DIRECTORY);
    final var budget = new Budget(started);
    try {
      budget.check();
      // The captured storage directory is trusted. All secure lookups below use child names only.
      try (var storage = openStorage(storagePath)) {
        runFromStorage(root, secure(storage), currentFolderName, retentionDays, budget);
      }
    } catch (Stopped stopped) {
      // Only resource release remains. No further entry operation is admitted.
    } catch (NoSuchFileException missing) {
      // A missing root is normal when no files were quarantined, or drop removed it.
    } catch (DirectoryIteratorException failure) {
      warn(root, failure.getCause());
    } catch (IOException failure) {
      warn(root, failure);
    }
  }

  private void runFromStorage(Path root, SecureDirectoryStream<Path> storage,
      String currentFolderName, int retentionDays, Budget budget) throws IOException {
    budget.check();
    final var attributes = readAttributes(root, storage);
    if (!realDirectory(attributes)) {
      logger.warn("Orphan retention rejected non-directory or linked root {}", root);
      return;
    }
    final var now = wallClock.instant();
    budget.check();
    try (var entries = openDirectory(root, storage)) {
      // Keep the storage provider's mode for the whole walk, including the path-based fallback.
      final var rootHandle = storage == null ? null : secure(entries);
      final var iterator = entries.iterator();
      while (hasNext(iterator, budget)) {
        budget.check();
        final var candidate = iterator.next();
        final var name = candidate.getFileName().toString();
        if (name.equals(currentFolderName)) {
          continue;
        }
        final var time = folderTime(name);
        if (time == null) {
          continue;
        }
        try {
          budget.check();
          if (!realDirectory(readAttributes(candidate, rootHandle))) {
            continue;
          }
          if (time.isAfter(now)) {
            logger.warn("Orphan quarantine folder has a future timestamp: {}", candidate);
            continue;
          }
          // An int number of days fits Instant even for the largest four-digit name year.
          if (!time.plus(retentionDays, ChronoUnit.DAYS).isAfter(now)) {
            deleteFolder(candidate, rootHandle, budget);
          }
        } catch (NoSuchFileException missing) {
          // Drop and the owner pass can remove entries concurrently.
        } catch (IOException failure) {
          warn(candidate, failure);
        }
      }
    }
  }

  private static boolean realDirectory(BasicFileAttributes attributes) {
    return attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther();
  }

  /**
   * Iterative postorder traversal keeps Java stack usage constant. Only single-entry operations
   * are used. Secure providers bind lookups, no-follow opens and deletion to parent handles.
   * Providers without secure streams use path operations with NOFOLLOW classification.
   */
  private void deleteFolder(Path folder, SecureDirectoryStream<Path> parent, Budget budget) {
    final var stack = new ArrayDeque<Frame>();
    long deletedFiles = 0;
    try {
      budget.check();
      stack.push(new Frame(folder, parent, openDirectory(folder, parent)));
      while (!stack.isEmpty()) {
        final var frame = stack.peek();
        try {
          if (hasNext(frame.iterator, budget)) {
            budget.check();
            final var child = frame.iterator.next();
            try {
              budget.check();
              final var attributes = readAttributes(child, frame.handle);
              if (realDirectory(attributes)) {
                budget.check();
                stack.push(new Frame(child, frame.handle,
                    openDirectory(child, frame.handle)));
              } else {
                budget.check();
                deleteEntry(child, frame.handle, false);
                deletedFiles++;
              }
            } catch (NoSuchFileException missing) {
              // Concurrent deletion needs no retry or warning.
            } catch (IOException failure) {
              warn(child, failure);
            }
          } else {
            stack.pop();
            frame.entries.close();
            budget.check();
            deleteEntry(frame.path, frame.parent, true);
            if (stack.isEmpty()) {
              logger.warn("Deleted orphan quarantine folder {} with {} files", folder,
                  deletedFiles);
            }
          }
        } catch (NoSuchFileException missing) {
          // A concurrent drop can remove a whole subtree.
        } catch (DirectoryIteratorException failure) {
          warn(frame.path, failure.getCause());
          stack.pop().entries.close();
        } catch (IOException failure) {
          warn(frame.path, failure);
        }
      }
    } catch (NoSuchFileException missing) {
      // The selected folder disappeared before opening.
    } catch (IOException failure) {
      warn(folder, failure);
    } finally {
      // Closing already-open handles releases resources even after stop or the deadline.
      for (var frame : stack) {
        try {
          frame.entries.close();
        } catch (IOException failure) {
          warn(frame.path, failure);
        }
      }
    }
  }

  private static void warn(Path path, IOException failure) {
    logger.warn("Orphan retention failed for {}", path, failure);
  }

  private static boolean hasNext(Iterator<Path> iterator, Budget budget) {
    budget.check();
    return iterator.hasNext();
  }

  // Single-operation seams allow deterministic concurrent-removal and I/O-failure tests.
  DirectoryStream<Path> openStorage(Path path) throws IOException {
    return Files.newDirectoryStream(path);
  }

  BasicFileAttributes readAttributes(Path path, SecureDirectoryStream<Path> parent)
      throws IOException {
    if (parent != null) {
      return parent.getFileAttributeView(path.getFileName(), BasicFileAttributeView.class,
          LinkOption.NOFOLLOW_LINKS).readAttributes();
    }
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  DirectoryStream<Path> openDirectory(Path path, SecureDirectoryStream<Path> parent)
      throws IOException {
    if (parent != null) {
      return parent.newDirectoryStream(path.getFileName(), LinkOption.NOFOLLOW_LINKS);
    }
    return Files.newDirectoryStream(path);
  }

  void deleteEntry(Path path, SecureDirectoryStream<Path> parent, boolean directory)
      throws IOException {
    if (parent == null) {
      Files.delete(path);
    } else if (directory) {
      parent.deleteDirectory(path.getFileName());
    } else {
      parent.deleteFile(path.getFileName());
    }
  }

  private static SecureDirectoryStream<Path> secure(DirectoryStream<Path> entries) {
    if (entries instanceof SecureDirectoryStream<Path> secureEntries) {
      return secureEntries;
    }
    return null;
  }

  private final class Budget {
    private final long started;

    private Budget(long started) {
      this.started = started;
    }

    private void check() {
      if (stop.getAsBoolean() || nanoTime.getAsLong() - started >= RUN_LIMIT_NANOS) {
        throw new Stopped();
      }
    }
  }

  private static final class Stopped extends RuntimeException {
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  private static final class Frame {
    private final Path path;
    private final SecureDirectoryStream<Path> parent;
    private final SecureDirectoryStream<Path> handle;
    private final DirectoryStream<Path> entries;
    private final Iterator<Path> iterator;

    private Frame(Path path, SecureDirectoryStream<Path> parent, DirectoryStream<Path> entries) {
      this.path = path;
      this.parent = parent;
      this.handle = parent == null ? null : secure(entries);
      this.entries = entries;
      this.iterator = entries.iterator();
    }
  }
}
