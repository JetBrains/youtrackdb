package com.jetbrains.youtrackdb.internal.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

/** Exercises the public embedded operation and its manager-specific Java Management Extensions. */
@Category(SequentialTest.class)
public class YouTrackDBDiskUsageTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /** Disk usage state is initialized before the manager becomes visible to engine shutdown. */
  @Test
  public void diskUsageStateIsReadyBeforeManagerPublication() throws Exception {
    var initializedAtPublication = new AtomicBoolean();
    YouTrackDBInternalEmbedded.setBeforeManagerPublication(
        manager -> initializedAtPublication.set(manager.hasInitializedDiskUsage()));

    try (var manager = YourTracks.instance(
        temporaryFolder.newFolder("publication-order").toPath())) {
      assertThat(initializedAtPublication).isTrue();
    } finally {
      YouTrackDBInternalEmbedded.setBeforeManagerPublication(ignored -> {
      });
    }
  }

  /** Existing disk databases need no session, and local and JMX reads share one cached value. */
  @Test
  public void existingDatabaseIsMeasuredWithoutSessionAndSharesCacheWithJmx() throws Exception {
    var managerDirectory = temporaryFolder.newFolder("manager").toPath();
    var databaseName = "db,name=quoted";
    var databaseDirectory = createDiscoverableDatabase(managerDirectory, databaseName);
    Files.write(databaseDirectory.resolve("first.bin"), new byte[7]);
    var nested = Files.createDirectories(databaseDirectory.resolve("nested"));
    Files.write(nested.resolve("second.bin"), new byte[11]);
    Files.createSymbolicLink(databaseDirectory.resolve("outside-link"), managerDirectory);

    try (var manager = YourTracks.instance(managerDirectory)) {
      var internal = (YouTrackDBInternalEmbedded) ((YouTrackDBImpl) manager).internal;
      var objectName = MetricsRegistry.databaseDiskUsageObjectName(
          internal.diskUsageManagerId(), databaseName);
      var mBeanServer = ManagementFactory.getPlatformMBeanServer();

      assertThat(mBeanServer.isRegistered(objectName)).isTrue();
      var firstValue = manager.diskUsage(databaseName);
      assertThat(firstValue).isEqualTo(21);

      Files.write(databaseDirectory.resolve("after-first-read.bin"), new byte[13]);
      assertThat(mBeanServer.getAttribute(objectName, MetricsRegistry.DISK_USAGE_ATTRIBUTE))
          .isEqualTo(firstValue);
    }
  }

  /** Database-name traversal cannot measure marker-bearing directories outside the manager. */
  @Test
  public void databaseNameCannotEscapeManagerDirectory() throws Exception {
    var parent = temporaryFolder.newFolder("boundary-parent").toPath();
    Files.write(parent.resolve("database.ocf"), new byte[3]);
    var managerDirectory = Files.createDirectory(parent.resolve("manager"));

    try (var manager = YourTracks.instance(managerDirectory)) {
      assertThatThrownBy(() -> manager.diskUsage(".."))
          .isInstanceOf(DatabaseException.class)
          .hasMessageContaining("Invalid database name");
      assertThatThrownBy(() -> manager.diskUsage("."))
          .isInstanceOf(DatabaseException.class)
          .hasMessageContaining("Invalid database name");
    }
  }

  /** A registered custom alias is authoritative even when its text is a traversal segment. */
  @Test
  public void registeredCustomAliasUsesItsAuthoritativePathForLocalAndJmxReads() throws Exception {
    var managerDirectory = temporaryFolder.newFolder("custom-manager").toPath();
    var customDirectory = temporaryFolder.newFolder("custom-parent").toPath().resolve("database");

    try (var manager = YourTracks.instance(managerDirectory)) {
      var internal = (YouTrackDBInternalEmbedded) ((YouTrackDBImpl) manager).internal;
      internal.initCustomStorage("..", customDirectory.toString());
      var objectName = MetricsRegistry.databaseDiskUsageObjectName(
          internal.diskUsageManagerId(), "..");

      var localValue = manager.diskUsage("..");

      assertThat(localValue).isPositive();
      assertThat(ManagementFactory.getPlatformMBeanServer()
          .getAttribute(objectName, MetricsRegistry.DISK_USAGE_ATTRIBUTE)).isEqualTo(localValue);
    }
  }

  /** Startup discovery checks fixed markers without enumerating every database file. */
  @Test
  public void startupDiscoveryUsesBoundedMarkerChecks() throws Exception {
    var managerDirectory = temporaryFolder.newFolder("discovery").toPath();
    var database = Files.createDirectory(managerDirectory.resolve("database"));
    Files.write(database.resolve("database.ocf"), new byte[1]);
    for (var i = 0; i < 100; i++) {
      Files.write(database.resolve("unrelated-" + i), new byte[1]);
    }
    Files.createDirectory(managerDirectory.resolve("not-a-database"));

    assertThat(YouTrackDBInternalEmbedded.discoverDatabaseNames(managerDirectory))
        .containsExactly("database");
    assertThat(YouTrackDBInternalEmbedded.discoverDatabaseNames(
        managerDirectory.resolve("not-a-directory"))).isEmpty();
  }

  /** Memory databases report zero, missing names fail, and a closed manager rejects requests. */
  @Test
  public void memoryMissingAndClosedManagerStatesAreDistinct() throws Exception {
    var managerDirectory = temporaryFolder.newFolder("states").toPath();
    var manager = YourTracks.instance(managerDirectory);
    manager.create("memory", DatabaseType.MEMORY, new String[0]);

    assertThat(manager.diskUsage("memory")).isZero();
    assertThatThrownBy(() -> manager.diskUsage("missing"))
        .isInstanceOf(DatabaseException.class)
        .hasMessageContaining("does not exist");

    manager.close();
    assertThatThrownBy(() -> manager.diskUsage("memory"))
        .isInstanceOf(DatabaseException.class)
        .hasMessageContaining("closed");
  }

  /** Create registration and drop cleanup remain atomic under the manager monitor. */
  @Test
  public void concurrentCreateAndDropCannotPublishStaleMetric() throws Exception {
    var managerDirectory = temporaryFolder.newFolder("concurrent-lifecycle").toPath();
    var manager = YourTracks.instance(managerDirectory);
    var internal = (YouTrackDBInternalEmbedded) ((YouTrackDBImpl) manager).internal;
    var registrationEntered = new CountDownLatch(1);
    var releaseRegistration = new CountDownLatch(1);
    internal.setBeforeDiskUsageRegistration(() -> {
      registrationEntered.countDown();
      await(releaseRegistration);
    });

    try (var executor = Executors.newFixedThreadPool(2)) {
      var create = executor.submit(
          () -> manager.create("concurrent", DatabaseType.MEMORY, new String[0]));
      assertThat(registrationEntered.await(10, TimeUnit.SECONDS)).isTrue();

      var dropThread = new AtomicReference<Thread>();
      var dropStarted = new CountDownLatch(1);
      var drop = executor.submit(() -> {
        dropThread.set(Thread.currentThread());
        dropStarted.countDown();
        manager.drop("concurrent");
      });
      assertThat(dropStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitBlocked(dropThread.get());
      assertThat(drop).isNotDone();

      releaseRegistration.countDown();
      create.get(10, TimeUnit.SECONDS);
      drop.get(10, TimeUnit.SECONDS);

      var objectName = MetricsRegistry.databaseDiskUsageObjectName(
          internal.diskUsageManagerId(), "concurrent");
      assertThat(ManagementFactory.getPlatformMBeanServer().isRegistered(objectName)).isFalse();
    } finally {
      releaseRegistration.countDown();
      manager.close();
    }
  }

  /** Drop and manager closure remove only their manager-specific JMX registrations. */
  @Test
  public void dropAndCloseUnregisterDiskUsageMBeans() throws Exception {
    var managerDirectory = temporaryFolder.newFolder("lifecycle").toPath();
    var manager = YourTracks.instance(managerDirectory);
    manager.create("memory", DatabaseType.MEMORY, new String[0]);
    var internal = (YouTrackDBInternalEmbedded) ((YouTrackDBImpl) manager).internal;
    var objectName = MetricsRegistry.databaseDiskUsageObjectName(
        internal.diskUsageManagerId(), "memory");
    var mBeanServer = ManagementFactory.getPlatformMBeanServer();

    assertThat(mBeanServer.isRegistered(objectName)).isTrue();
    manager.drop("memory");
    assertThat(mBeanServer.isRegistered(objectName)).isFalse();

    manager.create("memory", DatabaseType.MEMORY, new String[0]);
    assertThat(mBeanServer.isRegistered(objectName)).isTrue();
    manager.close();
    assertThat(mBeanServer.isRegistered(objectName)).isFalse();
  }

  /** Equal database names in separate managers receive distinct JMX identities and values. */
  @Test
  public void duplicateDatabaseNamesAcrossManagersRemainIndependent() throws Exception {
    var firstDirectory = temporaryFolder.newFolder("first-manager").toPath();
    var secondDirectory = temporaryFolder.newFolder("second-manager").toPath();
    var firstDatabase = createDiscoverableDatabase(firstDirectory, "shared");
    var secondDatabase = createDiscoverableDatabase(secondDirectory, "shared");
    Files.write(firstDatabase.resolve("value.bin"), new byte[3]);
    Files.write(secondDatabase.resolve("value.bin"), new byte[9]);

    try (var first = YourTracks.instance(firstDirectory);
        var second = YourTracks.instance(secondDirectory)) {
      var firstInternal = (YouTrackDBInternalEmbedded) ((YouTrackDBImpl) first).internal;
      var secondInternal = (YouTrackDBInternalEmbedded) ((YouTrackDBImpl) second).internal;
      var firstName = MetricsRegistry.databaseDiskUsageObjectName(
          firstInternal.diskUsageManagerId(), "shared");
      var secondName = MetricsRegistry.databaseDiskUsageObjectName(
          secondInternal.diskUsageManagerId(), "shared");

      assertThat(firstName).isNotEqualTo(secondName);
      assertThat(ManagementFactory.getPlatformMBeanServer().isRegistered(firstName)).isTrue();
      assertThat(ManagementFactory.getPlatformMBeanServer().isRegistered(secondName)).isTrue();
      assertThat(first.diskUsage("shared")).isEqualTo(6);
      assertThat(second.diskUsage("shared")).isEqualTo(12);
    }
  }

  /** The public cache setting defaults to five minutes. */
  @Test
  public void cacheDurationDefaultsToFiveMinutes() {
    assertThat(GlobalConfiguration.DB_DISK_USAGE_CACHE_DURATION.getDefValue())
        .isEqualTo(300_000L);
  }

  /** Non-positive manager cache durations fail configuration, while the largest value is valid. */
  @Test
  public void managerValidatesConfiguredCacheDuration() throws Exception {
    assertInvalidDuration(0, "zero-duration");
    assertInvalidDuration(-1, "negative-duration");

    var config = new BaseConfiguration();
    config.setProperty("youtrackdb.db.diskUsage.cacheDuration", Long.MAX_VALUE);
    try (var manager = YourTracks.instance(
        temporaryFolder.newFolder("maximum-duration").toPath(), config)) {
      manager.create("memory", DatabaseType.MEMORY, new String[0]);
      assertThat(manager.diskUsage("memory")).isZero();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out while waiting for test release");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for test release", e);
    }
  }

  private static void awaitBlocked(Thread thread) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
  }

  private void assertInvalidDuration(long duration, String directoryName) throws Exception {
    var config = new BaseConfiguration();
    config.setProperty("youtrackdb.db.diskUsage.cacheDuration", duration);
    var path = temporaryFolder.newFolder(directoryName).toPath();

    assertThatThrownBy(() -> YourTracks.instance(path, config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Database disk usage cache duration must be positive");
  }

  private static Path createDiscoverableDatabase(Path managerDirectory, String databaseName)
      throws Exception {
    var databaseDirectory = Files.createDirectories(managerDirectory.resolve(databaseName));
    Files.write(databaseDirectory.resolve("database.ocf"), new byte[3]);
    return databaseDirectory;
  }
}
