package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Maintains request-driven disk-usage values for one embedded database manager. */
final class DatabaseDiskUsage implements AutoCloseable {

  private static final AtomicLong NEXT_MANAGER_ID = new AtomicLong();

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final long cacheDurationNanos;
  private final DirectorySizer directorySizer;
  private final LongSupplier nanoTime;
  private final MetricsRegistry metricsRegistry;
  private final long managerId = NEXT_MANAGER_ID.incrementAndGet();
  private boolean closed;

  DatabaseDiskUsage(long cacheDurationMillis, MetricsRegistry metricsRegistry) {
    this(cacheDurationMillis, metricsRegistry, DatabaseDiskUsage::measureDirectory,
        System::nanoTime);
  }

  DatabaseDiskUsage(long cacheDurationMillis, MetricsRegistry metricsRegistry,
      DirectorySizer directorySizer, LongSupplier nanoTime) {
    if (cacheDurationMillis <= 0) {
      throw new IllegalArgumentException("Database disk usage cache duration must be positive");
    }

    this.cacheDurationNanos = TimeUnit.MILLISECONDS.toNanos(cacheDurationMillis);
    this.metricsRegistry = metricsRegistry;
    this.directorySizer = directorySizer;
    this.nanoTime = nanoTime;
  }

  synchronized Entry register(String databaseName, LongSupplier valueSupplier) {
    if (closed) {
      return null;
    }

    var entry = entries.computeIfAbsent(databaseName, ignored -> new Entry());
    if (metricsRegistry != null) {
      metricsRegistry.registerDatabaseDiskUsage(managerId, databaseName, valueSupplier);
    }
    return entry;
  }

  long get(Entry entry, Path databaseDirectory) {
    if (entry == null) {
      throw new DatabaseException("Database manager is closed");
    }
    return entry.get(databaseDirectory);
  }

  synchronized void remove(String databaseName) {
    entries.remove(databaseName);
    if (metricsRegistry != null) {
      metricsRegistry.unregisterDatabaseDiskUsage(managerId, databaseName);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (metricsRegistry != null) {
      for (var databaseName : entries.keySet()) {
        metricsRegistry.unregisterDatabaseDiskUsage(managerId, databaseName);
      }
    }
    entries.clear();
  }

  long managerId() {
    return managerId;
  }

  static long measureDirectory(Path directory) throws IOException {
    var visitor = new DirectorySizeVisitor();
    Files.walkFileTree(directory, visitor);
    return visitor.size();
  }

  static final class DirectorySizeVisitor extends SimpleFileVisitor<Path> {

    private long size;

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
      if (attributes.isRegularFile()) {
        try {
          size = Math.addExact(size, attributes.size());
        } catch (ArithmeticException e) {
          throw new IOException("Database disk usage exceeds the supported size", e);
        }
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
      if (failure instanceof NoSuchFileException) {
        return FileVisitResult.CONTINUE;
      }
      throw failure;
    }

    long size() {
      return size;
    }
  }

  @FunctionalInterface
  interface DirectorySizer {

    long size(Path directory) throws IOException;
  }

  final class Entry {

    private boolean initialized;
    private long value;
    private long refreshedAt;
    private CompletableFuture<Long> refresh;

    long get(Path databaseDirectory) {
      final CompletableFuture<Long> currentRefresh;
      final boolean refreshOwner;
      synchronized (this) {
        var now = nanoTime.getAsLong();
        if (initialized && now - refreshedAt < cacheDurationNanos) {
          return value;
        }
        if (refresh == null) {
          refresh = new CompletableFuture<>();
          refreshOwner = true;
        } else {
          refreshOwner = false;
        }
        currentRefresh = refresh;
      }

      if (refreshOwner) {
        refresh(currentRefresh, databaseDirectory);
      }
      try {
        return currentRefresh.join();
      } catch (CompletionException e) {
        if (e.getCause() instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        if (e.getCause() instanceof Error error) {
          throw error;
        }
        throw e;
      }
    }

    private void refresh(CompletableFuture<Long> currentRefresh, Path databaseDirectory) {
      try {
        var refreshedValue = databaseDirectory == null ? 0 : measure(databaseDirectory);
        synchronized (this) {
          value = refreshedValue;
          refreshedAt = nanoTime.getAsLong();
          initialized = true;
        }
        currentRefresh.complete(refreshedValue);
      } catch (RuntimeException | Error failure) {
        currentRefresh.completeExceptionally(failure);
      } finally {
        synchronized (this) {
          if (refresh == currentRefresh) {
            refresh = null;
          }
        }
      }
    }

    private long measure(Path databaseDirectory) {
      try {
        return directorySizer.size(databaseDirectory);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new DatabaseException(databaseDirectory.toString(),
                "Cannot calculate disk usage for database directory"),
            e,
            databaseDirectory.toString());
      }
    }
  }
}
