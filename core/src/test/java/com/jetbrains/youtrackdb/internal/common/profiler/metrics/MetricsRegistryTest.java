package com.jetbrains.youtrackdb.internal.common.profiler.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.After;
import org.junit.Test;

/**
 * Tests for {@link MetricsRegistry} — registry for database metrics exposed via JMX. Also covers
 * MetricsMBean (getAttribute, getAttributes, getMBeanInfo, invoke/setAttribute throw).
 */
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
    registry = null;
  }

  /** globalMetric returns a working metric instance. */
  @Test
  public void globalMetricReturnsWorkingInstance() {
    registry = new MetricsRegistry(ticker);
    Gauge<Long> gauge = registry.databaseMetric(CoreMetrics.OLDEST_TX_AGE, "testDb");
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
