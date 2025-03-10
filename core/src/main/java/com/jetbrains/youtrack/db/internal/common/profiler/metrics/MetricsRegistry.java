package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.profiler.Ticker;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.MetricScope.Class;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.MetricScope.Database;
import com.jetbrains.youtrack.db.internal.common.profiler.metrics.MetricScope.Global;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanConstructorInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;

/**
 * Registry for database metrics.
 * <p>
 * There are two types of metrics: global and per-database. Per-database metrics are further divided
 * into database-global and per-class metrics. (However per-class metrics are not collected at the
 * moment).
 * <p>
 * All collected metrics are exposed via JMX. Global metrics are registered under the name
 * {@code com.jetbrains.youtrack.db.metrics:scope=Global}, database metrics are registered under the
 * name {@code com.jetbrains.youtrack.db.metrics:scope=Database,databaseName=<databaseName>}.
 * <p>
 * Metrics that are listed in {@link CoreMetrics} are registered automatically.
 */
public class MetricsRegistry {

  private final Ticker ticker;

  private final GlobalMetrics globalMetrics;
  private final ConcurrentMap<String, DatabaseMetrics> perDatabaseMetrics = new ConcurrentHashMap<>();
  private final Set<ObjectName> registeredMBeans = ConcurrentHashMap.newKeySet();

  private final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
  private volatile boolean closed = false;

  public MetricsRegistry(Ticker ticker) {
    this.ticker = ticker;
    this.globalMetrics = new GlobalMetrics();
  }

  /**
   * Return a global metric, registering it if necessary.
   */
  public <T extends Metric<?>> T globalMetric(MetricDefinition<Global, T> metric) {
    return globalMetrics.mGroup.init(null, metric);
  }

  /**
   * Return a per-database metric, registering it if necessary.
   */
  public <T extends Metric<?>> T databaseMetric(
      MetricDefinition<Database, T> metric,
      String databaseName
  ) {
    return initDatabaseMetrics(databaseName).mGroup.init(null, metric);
  }

  /**
   * Return a per-class metric, registering it if necessary.
   */
  public <T extends Metric<?>> T classMetric(
      MetricDefinition<Class, T> metric,
      String databaseName,
      String className
  ) {
    return initDatabaseMetrics(databaseName).mGroup.init("class." + className, metric);
  }

  /**
   * Shutdown the registry, unregistering all MBeans.
   */
  public void shutdown() {
    closed = true;
    for (Iterator<ObjectName> iterator = registeredMBeans.iterator(); iterator.hasNext(); ) {
      ObjectName mBeanName = iterator.next();
      try {
        mBeanServer.unregisterMBean(mBeanName);
      } catch (JMException ex) {
        LogManager.instance()
            .error(this, "Failed to unregister MBean " + mBeanName.getCanonicalName(), ex);
      }
      iterator.remove();
    }
  }

  private DatabaseMetrics initDatabaseMetrics(String databaseName) {
    return perDatabaseMetrics.computeIfAbsent(databaseName, k -> new DatabaseMetrics(databaseName));
  }

  private final class MetricsGroup {

    private final ConcurrentMap<String, Metric<?>> metrics = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MetricDefinition<?, ?>> definitions = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    <T extends Metric<?>> T init(String namePrefix, MetricDefinition<?, T> def) {
      if (closed || !def.enabled()) {
        return def.type().noop();
      }

      return (T) metrics.computeIfAbsent(
          namePrefix == null ? def.name() : namePrefix + "." + def.name(),
          k -> {
            definitions.put(k, def);
            return def.type().create(ticker);
          }
      );
    }

  }

  private final class GlobalMetrics {

    private final MetricsGroup mGroup = new MetricsGroup();

    public GlobalMetrics() {
      CoreMetrics.GLOBAL_METRICS.forEach(globalMetric -> mGroup.init(null, globalMetric));

      new MetricsMBean(mGroup, "YouTrackDB global metrics")
          .register(mBeanServer, "scope=Global");
    }
  }

  private final class DatabaseMetrics {

    private final MetricsGroup mGroup = new MetricsGroup();

    public DatabaseMetrics(String databaseName) {
      CoreMetrics.DATABASE_METRICS.forEach(databaseMetric -> mGroup.init(null, databaseMetric));

      new MetricsMBean(mGroup, "Database metrics for " + databaseName)
          .register(mBeanServer, "scope=Database,databaseName=" + databaseName);
    }
  }

  public class MetricsMBean implements DynamicMBean {

    private final MetricsGroup metricsGroup;
    private final String description;

    MetricsMBean(MetricsGroup metricsGroup, String description) {
      this.metricsGroup = metricsGroup;
      this.description = description;
    }

    public void register(MBeanServer server, String properties) {

      try {
        final var objectName =
            new ObjectName("com.jetbrains.youtrack.db.metrics:" + properties);
        if (registeredMBeans.add(objectName)) {
          server.registerMBean(this, objectName);
        }
      } catch (JMException ex) {
        LogManager.instance().error(this,
            "Failed to register database metrics MBean, properties: " + properties, ex);
      }
    }

    @Override
    public MBeanInfo getMBeanInfo() {

      final var attributes = new ArrayList<MBeanAttributeInfo>();
      for (final var md : metricsGroup.definitions.entrySet()) {
        attributes.add(
            new MBeanAttributeInfo(
                md.getKey(),
                md.getValue().type().valueType().getName(),
                md.getValue().description(),
                true,
                false,
                false
            )
        );
      }

      return new MBeanInfo(
          this.getClass().getName(),
          description,
          attributes.toArray(new MBeanAttributeInfo[0]),
          new MBeanConstructorInfo[0],
          new MBeanOperationInfo[0],
          new MBeanNotificationInfo[0]
      );
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature)
        throws MBeanException, ReflectionException {
      throw new UnsupportedOperationException("invoke");
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
      throw new UnsupportedOperationException("setAttributes");
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
      final var aList = new AttributeList(attributes.length);
      for (String attribute : attributes) {
        aList.add(new Attribute(attribute, getAttribute(attribute)));
      }
      return aList;
    }

    @Override
    public void setAttribute(Attribute attribute) {
      throw new UnsupportedOperationException("setAttribute");
    }

    @Override
    public Object getAttribute(String attribute) {
      final var metric = metricsGroup.metrics.get(attribute);
      return metric == null ? null : metric.getValue();
    }
  }

}
