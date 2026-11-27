package com.jetbrains.youtrackdb.benchmarks.manual;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.MBeanServerDelegate;
import javax.management.MBeanServerNotification;
import javax.management.MalformedObjectNameException;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import org.openjdk.jmh.infra.Control;
import org.openjdk.jmh.runner.IterationType;

final class IterationMetrics {

  private static final long SAMPLE_MS = 20;
  private static final String DOMAIN = "com.jetbrains.youtrackdb.metrics:";
  private static final String[] GLOBAL = {"DataFileFsyncCount"};
  private static final String[] DATABASE = {"WalQueueWaitCount", "WalQueueWaitTimeNanos",
      "WalVacuumRunCount", "WalVacuumTimeNanos", "WalVacuumRemovalRunCount",
      "WalBlockWaitCount", "WalBlockWaitTimeNanos"};
  private static final String ENGINE = "DirectMemoryBytes";
  private static final String WAL = "WalDirectMemoryBytes";
  private static final Pattern SEGMENT = Pattern.compile(".+\\.[0-9]+\\.wal");
  private static final long NA = -1;
  private final List<Path> databasePaths;
  private final List<ObjectName> beans = new ArrayList<>();
  private final ConcurrentHashMap<ObjectName, Long> registrations = new ConcurrentHashMap<>();
  private final NotificationListener lifecycle = (event, handback) -> {
    if (event instanceof MBeanServerNotification change) {
      registrations.computeIfPresent(change.getMBeanName(), (bean, epoch) -> epoch + 1);
    }
  };
  private final boolean tracking;
  private final WalFileEvents files;
  private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
  private final OperatingSystemMXBean os =
      ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean process
          ? process : null;
  private final List<GarbageCollectorMXBean> collectors =
      ManagementFactory.getGarbageCollectorMXBeans();
  private Thread sampler;
  private Frame first;
  private Frame last;
  private long[] maxima;
  private boolean directContinuous;
  private int samples;
  private int iteration;

  IterationMetrics(Path root, List<String> names, boolean fileEvents) {
    databasePaths = names.stream().map(root.toAbsolutePath().normalize()::resolve).toList();
    try {
      beans.add(new ObjectName(DOMAIN + "scope=Global"));
      for (String name : names) {
        beans.add(new ObjectName(DOMAIN + "scope=Database,databaseName=" + name));
      }
    } catch (MalformedObjectNameException failure) {
      throw new IllegalArgumentException("Invalid benchmark database name", failure);
    }
    beans.forEach(bean -> registrations.put(bean, 0L));
    tracking = watchRegistrations();
    files = new WalFileEvents(root, fileEvents, this::isSegment);
  }

  private boolean watchRegistrations() {
    try {
      server.addNotificationListener(MBeanServerDelegate.DELEGATE_NAME, lifecycle, null, null);
      return true;
    } catch (JMException | RuntimeException failure) {
      return false;
    }
  }

  void reportStartup() {
    System.out.println("METRICS_STARTUP DirectMemoryBytes=" + value(attribute(beans.getFirst(),
        ENGINE)));
  }

  void start(Control control) {
    first = null;
    last = null;
    maxima = null;
    directContinuous = true;
    samples = 0;
    iteration++;
    files.start();
    sampler = Thread.ofPlatform().daemon().name("benchmark-metrics").start(() -> sample(control));
  }

  private void sample(Control control) {
    // The fixture starts the observer before JMH sets the flags. No worker calls the observer.
    while (!control.startMeasurement && !control.stopMeasurement
        && !Thread.currentThread().isInterrupted()) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
    while (!control.stopMeasurement && !Thread.currentThread().isInterrupted()) {
      Frame frame = readFrame();
      // Discard a read that crossed the timed boundary, including slow filesystem reads.
      if (control.stopMeasurement || Thread.currentThread().isInterrupted()) {
        break;
      }
      if (first == null) {
        first = frame;
        maxima = frame.gauges.clone();
      } else {
        invalidateDiscontinuousCounters(frame);
      }
      directContinuous &= frame.directEpoch >= 0 && frame.directEpoch == first.directEpoch;
      for (int i = 0; i < maxima.length; i++) {
        maxima[i] = maxima[i] < 0 || frame.gauges[i] < 0
            ? NA : Math.max(maxima[i], frame.gauges[i]);
      }
      last = frame;
      samples++;
      LockSupport.parkNanos(
          Math.max(0, TimeUnit.MILLISECONDS.toNanos(SAMPLE_MS) - (System.nanoTime() - frame.tick)));
    }
  }

  private void invalidateDiscontinuousCounters(Frame next) {
    for (int i = 0; i < beans.size(); i++) {
      Counters from = first.counters.get(i);
      Counters previous = last.counters.get(i);
      Counters current = next.counters.get(i);
      for (int k = 0; k < from.values.length; k++) {
        if (current.epoch != from.epoch || delta(previous.values[k], current.values[k]) < 0) {
          from.values[k] = NA;
        }
      }
    }
  }

  private Frame readFrame() {
    long tick = System.nanoTime();
    files.mark(samples);
    long cpu = os == null ? NA : os.getProcessCpuTime();
    long gcCount = collectors.isEmpty() ? NA : 0;
    long gcMillis = gcCount;
    for (var collector : collectors) {
      gcCount = sum(gcCount, collector.getCollectionCount());
      gcMillis = sum(gcMillis, collector.getCollectionTime());
    }
    List<Counters> counters = new ArrayList<>();
    for (int i = 0; i < beans.size(); i++) {
      ObjectName bean = beans.get(i);
      long epoch = registrations.get(bean);
      String[] keys = i == 0 ? GLOBAL : DATABASE;
      long[] values = new long[keys.length];
      for (int k = 0; k < keys.length; k++) {
        values[k] = attribute(bean, keys[k]);
      }
      counters.add(new Counters(confirmedEpoch(bean, epoch), values));
    }
    // Bound endpoint lag without including the slower reads for the gauges.
    long counterReadNanos = System.nanoTime() - tick;

    var global = beans.getFirst();
    long epoch = registrations.get(global);
    long engine = attribute(global, ENGINE);
    long wal = attribute(global, WAL);
    long directEpoch = confirmedEpoch(global, epoch);
    long[] gauges = {ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
        engine, wal, retainedBytes()};
    return new Frame(tick, counterReadNanos, cpu, gcCount, gcMillis, gauges, directEpoch, counters);
  }

  private long confirmedEpoch(ObjectName bean, long epoch) {
    return tracking && epoch == registrations.get(bean) ? epoch : NA;
  }

  private static long sum(long total, long next) {
    return total < 0 || next < 0 ? NA : total + next;
  }

  private long attribute(ObjectName bean, String key) {
    try {
      return server.getAttribute(bean, key) instanceof Long v ? v : NA;
    } catch (JMException | RuntimeException failure) {
      return NA;
    }
  }

  private boolean isSegment(Path path) {
    return databasePaths.contains(path.getParent())
        && SEGMENT.matcher(path.getFileName().toString()).matches();
  }

  private long retainedBytes() {
    long bytes = 0;
    try {
      for (Path database : databasePaths) {
        try (var paths = Files.list(database)) {
          var segmentPaths = paths.filter(this::isSegment).iterator();
          while (segmentPaths.hasNext()) {
            try {
              bytes += Files.size(segmentPaths.next());
            } catch (NoSuchFileException removed) {
              // Vacuum can remove a file after the directory read.
            }
          }
        }
      }
      return bytes;
    } catch (IOException | UncheckedIOException failure) {
      return NA;
    }
  }

  void report(IterationType phase) throws InterruptedException {
    sampler.interrupt();
    sampler.join();
    sampler = null;
    boolean window = samples >= 2 && last.tick - first.tick > 0;
    long elapsed = window ? last.tick - first.tick : NA;
    long gcMillis = window ? delta(first.gcMillis, last.gcMillis) : NA;
    String gcPercent = gcMillis >= 0
        ? Double.toString(gcMillis * 100_000_000.0 / elapsed) : "not available";
    String fileFigures = files.report(window ? samples : 0);
    // A replacement can be reported after the last frame read the global bean.
    boolean reportDirect = directContinuous && first != null
        && confirmedEpoch(beans.getFirst(), first.directEpoch) >= 0;
    String prefix = " phase=" + phase + " iteration=" + iteration;
    System.out.println("METRICS" + prefix + " samples=" + samples + " sampleIntervalMillis="
        + SAMPLE_MS + " windowNanos=" + value(elapsed)
        + " counterReadMaxNanos="
        + value(window ? Math.max(first.counterReadNanos, last.counterReadNanos) : NA)
        + " processCpuNanos=" + value(window ? delta(first.cpu, last.cpu) : NA)
        + " gcCount=" + value(window ? delta(first.gcCount, last.gcCount) : NA)
        + " gcTimeMillis=" + value(gcMillis) + " gcTimePercent=" + gcPercent
        + " heapUsedObservedMaxBytes=" + maximum(0)
        + " heapMaxMeaning=\"sampled lower bound, not proof of a hard limit\""
        + " " + gauge(ENGINE, 1, reportDirect) + " " + gauge(WAL, 2, reportDirect)
        + " " + gauge("RetainedWalBytesScanEstimate", 3, true)
        + deltas(window, 0, GLOBAL)
        + fileFigures);
    for (int i = 0; i < databasePaths.size(); i++) {
      System.out.println("METRICS_DATABASE" + prefix + " database="
          + databasePaths.get(i).getFileName() + deltas(window, i + 1, DATABASE));
    }
  }

  private String maximum(int index) {
    return value(maxima == null ? NA : maxima[index]);
  }

  private String gauge(String name, int index, boolean continuous) {
    return name + "ObservedMax=" + value(continuous && maxima != null ? maxima[index] : NA)
        + " " + name + "Final=" + value(continuous && last != null ? last.gauges[index] : NA);
  }

  private String deltas(boolean window, int index, String[] keys) {
    Counters a = window ? first.counters.get(index) : null;
    Counters b = window ? last.counters.get(index) : null;
    boolean continuous = window && a.epoch >= 0 && a.epoch == b.epoch;
    var result = new StringBuilder();
    for (int k = 0; k < keys.length; k++) {
      result.append(' ').append(keys[k]).append('=')
          .append(value(continuous ? delta(a.values[k], b.values[k]) : NA));
    }
    return result.toString();
  }

  private static long delta(long before, long after) {
    return before >= 0 && after >= before ? after - before : NA;
  }

  static String value(long number) {
    return number >= 0 ? Long.toString(number) : "not available";
  }

  void close() {
    if (sampler != null) {
      sampler.interrupt();
    }
    files.close();
    if (tracking) {
      try {
        server.removeNotificationListener(MBeanServerDelegate.DELEGATE_NAME, lifecycle);
      } catch (JMException failure) {
        throw new IllegalStateException("Cannot remove benchmark metrics listener", failure);
      }
    }
  }

  private record Frame(long tick, long counterReadNanos, long cpu, long gcCount, long gcMillis,
      long[] gauges, long directEpoch, List<Counters> counters) {
  }

  private record Counters(long epoch, long[] values) {
  }
}
