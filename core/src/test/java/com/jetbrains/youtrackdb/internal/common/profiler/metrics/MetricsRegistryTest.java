package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.RuntimeMBeanException;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for {@link MetricsRegistry} — registry for database metrics exposed via JMX. Also covers
 * MetricsMBean (getAttribute, getAttributes, getMBeanInfo, invoke/setAttribute throw).
 *
 * <p>Marked @SequentialTest because MetricsRegistry registers JMX MBeans with fixed ObjectNames
 * (e.g., scope=Global) on the JVM-global MBeanServer. Concurrent tests that start the engine
 * (DbTestBase) create their own MetricsRegistry, causing registration conflicts and potential
 * MBean theft during shutdown.
 */
@Category(SequentialTest.class)
public class MetricsRegistryTest {

  private final StubTicker ticker = new StubTicker(1_000_000);
  private MetricsRegistry registry;

  @After
  public void cleanup() {
    if (registry != null) {
      registry.shutdown();
    }
  }

  // ---------------------------------------------------------------------------
  // MetricsRegistry — global metrics
  // ---------------------------------------------------------------------------

  /** Creating a registry registers global metrics MBean via JMX. */
  @Test
  public void registryRegistersGlobalMBean() throws Exception {
    registry = new MetricsRegistry(ticker);
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("com.jetbrains.youtrackdb.metrics:scope=Global");
    assertThat(mbs.isRegistered(name)).isTrue();
  }

  /** Shutdown unregisters the global MBean. */
  @Test
  public void shutdownUnregistersGlobalMBean() throws Exception {
    registry = new MetricsRegistry(ticker);
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("com.jetbrains.youtrackdb.metrics:scope=Global");
    registry.shutdown();
    assertThat(mbs.isRegistered(name)).isFalse();
    // Prevent @After from calling shutdown() again on the already-closed registry
    registry = null;
  }

  /** globalMetric returns a working metric instance. */
  @Test
  public void globalMetricReturnsWorkingInstance() {
    registry = new MetricsRegistry(ticker);
    Ratio ratio = registry.globalMetric(CoreMetrics.CACHE_HIT_RATIO);
    assertThat(ratio).isNotNull();
  }

  /** databaseMetric returns a working gauge with set/get. */
  @Test
  public void databaseMetricReturnsWorkingGauge() {
    registry = new MetricsRegistry(ticker);
    Gauge<Long> gauge = registry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, "gaugeTestDb");
    assertThat(gauge).isNotNull();
    gauge.setValue(42L);
    assertThat(gauge.getValue()).isEqualTo(42L);
  }

  // ---------------------------------------------------------------------------
  // MetricsRegistry — database metrics
  // ---------------------------------------------------------------------------

  /** databaseMetric registers a database-scoped MBean. */
  @Test
  public void databaseMetricRegistersMBean() throws Exception {
    registry = new MetricsRegistry(ticker);
    registry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, "testDb");
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    ObjectName name =
        new ObjectName(
            "com.jetbrains.youtrackdb.metrics:scope=Database,databaseName=testDb");
    assertThat(mbs.isRegistered(name)).isTrue();
  }

  /** Same metric for the same database returns the same instance. */
  @Test
  public void databaseMetricReturnsSameInstance() {
    registry = new MetricsRegistry(ticker);
    Gauge<Long> gauge1 = registry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, "db1");
    Gauge<Long> gauge2 = registry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, "db1");
    assertThat(gauge1).isSameAs(gauge2);
  }

  // ---------------------------------------------------------------------------
  // MetricsMBean — getAttribute / getAttributes
  // ---------------------------------------------------------------------------

  /** getAttribute returns the metric value via JMX. */
  @Test
  public void getMBeanAttributeReturnsMetricValue() throws Exception {
    registry = new MetricsRegistry(ticker);
    Gauge<Long> gauge = registry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, "attrTestDb");
    gauge.setValue(99L);

    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    ObjectName name =
        new ObjectName(
            "com.jetbrains.youtrackdb.metrics:scope=Database,databaseName=attrTestDb");
    Object value = mbs.getAttribute(name, CoreMetrics.OLDEST_TX_AGE.name());
    assertThat(value).isEqualTo(99L);
  }

  /** getMBeanInfo returns attribute info for registered metrics. */
  @Test
  public void getMBeanInfoReturnsAttributes() throws Exception {
    registry = new MetricsRegistry(ticker);
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("com.jetbrains.youtrackdb.metrics:scope=Global");
    var info = mbs.getMBeanInfo(name);
    assertThat(info.getAttributes()).isNotEmpty();
    assertThat(info.getDescription()).contains("global");
  }

  // ---------------------------------------------------------------------------
  // MetricsMBean — unsupported operations
  // ---------------------------------------------------------------------------

  /** invoke throws UnsupportedOperationException. */
  @Test
  public void mbeanInvokeThrows() throws Exception {
    registry = new MetricsRegistry(ticker);
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    ObjectName name = new ObjectName("com.jetbrains.youtrackdb.metrics:scope=Global");
    assertThatThrownBy(() -> mbs.invoke(name, "anything", null, null))
        .hasCauseInstanceOf(UnsupportedOperationException.class);
  }

  // ---------------------------------------------------------------------------
  // Request-driven database disk usage
  // ---------------------------------------------------------------------------

  /** Disk usage exposes one read-only attribute and ignores duplicate registration attempts. */
  @Test
  public void databaseDiskUsageMBeanIsRequestDrivenAndReadOnly() throws Exception {
    registry = new MetricsRegistry(ticker);
    var value = new AtomicLong(17);
    var name = MetricsRegistry.databaseDiskUsageObjectName(41, "db,name=?*");
    var mbs = ManagementFactory.getPlatformMBeanServer();

    registry.registerDatabaseDiskUsage(41, "db,name=?*", value::get);
    registry.registerDatabaseDiskUsage(41, "db,name=?*", () -> 99);

    assertThat(mbs.getAttribute(name, MetricsRegistry.DISK_USAGE_ATTRIBUTE)).isEqualTo(17L);
    value.set(23);
    var attributes = mbs.getAttributes(
        name, new String[] {MetricsRegistry.DISK_USAGE_ATTRIBUTE, "Unknown"});
    assertThat(attributes).containsExactly(
        new Attribute(MetricsRegistry.DISK_USAGE_ATTRIBUTE, 23L),
        new Attribute("Unknown", null));
    assertThat(mbs.getMBeanInfo(name).getAttributes()).hasSize(1);
    assertThat(mbs.getMBeanInfo(name).getDescription()).contains("db,name=?*");
    assertThat(mbs.getObjectInstance(name).getClassName())
        .isEqualTo(MetricsRegistry.MetricsMBean.class.getName());
    assertThatThrownBy(() -> mbs.setAttribute(
        name, new Attribute(MetricsRegistry.DISK_USAGE_ATTRIBUTE, 1L)))
        .hasCauseInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> mbs.invoke(name, "refresh", null, null))
        .hasCauseInstanceOf(UnsupportedOperationException.class);

    registry.unregisterDatabaseDiskUsage(41, "db,name=?*");
    registry.unregisterDatabaseDiskUsage(41, "db,name=?*");
    assertThat(mbs.isRegistered(name)).isFalse();
  }

  /** Supplier failures propagate through single and bulk generic MBean reads. */
  @Test
  public void databaseDiskUsageSupplierFailureIsNotSwallowed() throws Exception {
    registry = new MetricsRegistry(ticker);
    var name = MetricsRegistry.databaseDiskUsageObjectName(45, "failing-db");
    var failure = new DatabaseException("failing-db", "measurement failed");
    registry.registerDatabaseDiskUsage(45, "failing-db", () -> {
      throw failure;
    });
    var mbs = ManagementFactory.getPlatformMBeanServer();

    assertThatThrownBy(() -> mbs.getAttribute(name, MetricsRegistry.DISK_USAGE_ATTRIBUTE))
        .isInstanceOf(RuntimeMBeanException.class)
        .hasRootCauseInstanceOf(DatabaseException.class)
        .satisfies(error -> assertThat(error.getCause().getMessage())
            .contains("measurement failed"));
    assertThatThrownBy(() -> mbs.getAttributes(
        name, new String[] {MetricsRegistry.DISK_USAGE_ATTRIBUTE}))
        .isInstanceOf(RuntimeMBeanException.class)
        .hasRootCauseInstanceOf(DatabaseException.class)
        .satisfies(error -> assertThat(error.getCause().getMessage())
            .contains("measurement failed"));
  }

  /** Concurrent registration and shutdown cannot publish an MBean after cleanup. */
  @Test
  public void concurrentRegistrationAndShutdownRemainAtomic() throws Exception {
    registry = new MetricsRegistry(ticker);
    var registrationEntered = new CountDownLatch(1);
    var releaseRegistration = new CountDownLatch(1);
    registry.setBeforeDiskUsageMBeanRegistration(() -> {
      registrationEntered.countDown();
      await(releaseRegistration);
    });
    var name = MetricsRegistry.databaseDiskUsageObjectName(44, "concurrent-db");
    var mbs = ManagementFactory.getPlatformMBeanServer();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var registration = executor.submit(
          () -> registry.registerDatabaseDiskUsage(44, "concurrent-db", () -> 1));
      assertThat(registrationEntered.await(10, TimeUnit.SECONDS)).isTrue();

      var shutdownThread = new AtomicReference<Thread>();
      var shutdownStarted = new CountDownLatch(1);
      var shutdown = executor.submit(() -> {
        shutdownThread.set(Thread.currentThread());
        shutdownStarted.countDown();
        registry.shutdown();
      });
      assertThat(shutdownStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitBlocked(shutdownThread.get());

      releaseRegistration.countDown();
      registration.get(10, TimeUnit.SECONDS);
      shutdown.get(10, TimeUnit.SECONDS);
      assertThat(mbs.isRegistered(name)).isFalse();
    } finally {
      releaseRegistration.countDown();
      registry = null;
    }
  }

  /** Registry shutdown blocks later disk usage registration. */
  @Test
  public void registrationAfterShutdownDoesNotPublishMBean() throws Exception {
    registry = new MetricsRegistry(ticker);
    var name = MetricsRegistry.databaseDiskUsageObjectName(43, "late-db");
    var mbs = ManagementFactory.getPlatformMBeanServer();

    registry.shutdown();
    registry.registerDatabaseDiskUsage(43, "late-db", () -> 1);

    assertThat(mbs.isRegistered(name)).isFalse();
    registry = null;
  }

  /** Registry shutdown removes disk usage MBeans alongside existing metric MBeans. */
  @Test
  public void shutdownUnregistersDatabaseDiskUsageMBean() throws Exception {
    registry = new MetricsRegistry(ticker);
    var name = MetricsRegistry.databaseDiskUsageObjectName(42, "shutdown-db");
    var mbs = ManagementFactory.getPlatformMBeanServer();
    registry.registerDatabaseDiskUsage(42, "shutdown-db", () -> 1);

    registry.shutdown();

    assertThat(mbs.isRegistered(name)).isFalse();
    registry = null;
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

  // ---------------------------------------------------------------------------
  // Disabled metrics return noop
  // ---------------------------------------------------------------------------

  /** Disabled metric definition returns a noop instance. */
  @Test
  public void disabledMetricReturnsNoop() {
    registry = new MetricsRegistry(ticker);
    var disabledDef = CoreMetrics.OLDEST_TX_AGE.disable();
    Gauge<Long> gauge = registry.databaseMetric(disabledDef, "testDb2");
    gauge.setValue(42L);
    // Noop gauge returns null for getValue
    assertThat(gauge.getValue()).isNull();
  }
}
