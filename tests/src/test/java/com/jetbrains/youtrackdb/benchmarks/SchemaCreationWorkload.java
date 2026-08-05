/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.benchmarks;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.jnr.Native;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SessionListener;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Shared workload for measuring schema creation.
 *
 * <p>The mode combines {@code bench.policy} with {@code bench.propertyPath}. The policies are
 * {@code NONE}, {@code PER_CLASS}, {@code BATCH}, and {@code ALL}. The property paths are {@code
 * SAFE} and {@code UNSAFE}. {@code UNSAFE} skips the persistent-type check and the migration query.
 * It is a measurement control, not a product option.
 *
 * <p>Phase A creates classes and properties. Phase B creates indexes. The phases never share a
 * transaction. The harness checks for an active transaction at both boundaries of every phase.
 *
 * <table>
 *   <caption>Benchmark properties</caption>
 *   <tr><th>Property</th><th>Built-in default</th><th>Meaning</th></tr>
 *   <tr><td>{@code bench.mainClass}</td><td>{@code SchemaCreationBenchmark}</td><td>Selects the
 *       entry point. Use {@code com.jetbrains.youtrackdb.benchmarks.SchemaCreationBenchmark} for
 *       the non-transactional entry point. Use {@code
 *       com.jetbrains.youtrackdb.benchmarks.TxSchemaCreationBenchmark} for the transactional entry
 *       point.</td></tr>
 *   <tr><td>{@code bench.jvmBaseArgs}</td><td>{@code -Xms4g -Xmx4g}</td><td>Replaces the base JVM
 *       arguments.</td></tr>
 *   <tr><td>{@code bench.jvmAddArgs}</td><td>empty</td><td>Appends JVM arguments. Put profiler
 *       agents here.</td></tr>
 *   <tr><td>{@code bench.classes}</td><td>{@code 100}</td><td>Sets the class count.</td></tr>
 *   <tr><td>{@code bench.properties}</td><td>{@code 20}</td><td>Sets the properties per class.</td></tr>
 *   <tr><td>{@code bench.indexes}</td><td>{@code 20}</td><td>Sets the indexes per class. This value
 *       must not exceed {@code bench.properties}.</td></tr>
 *   <tr><td>{@code bench.policy}</td><td>{@code NONE} for {@code SchemaCreationBenchmark}; {@code
 *       ALL} for {@code TxSchemaCreationBenchmark}</td><td>Selects the transaction policy.</td></tr>
 *   <tr><td>{@code bench.batchSize}</td><td>{@code 50}</td><td>Sets the classes per {@code BATCH}
 *       transaction.</td></tr>
 *   <tr><td>{@code bench.propertyPath}</td><td>{@code SAFE} for both entry points</td><td>Selects
 *       {@code SAFE} or {@code UNSAFE} property creation.</td></tr>
 *   <tr><td>{@code bench.phase}</td><td>{@code AB}</td><td>Selects phase {@code A}, {@code B}, or
 *       {@code AB}.</td></tr>
 *   <tr><td>{@code bench.uniquePropertyNames}</td><td>{@code true}</td><td>Uses class-qualified
 *       property names when true.</td></tr>
 *   <tr><td>{@code bench.dbPath}</td><td>{@code
 *       ./target/databases/benchmarks/schemaCreationBenchmark}</td><td>Sets the database-manager
 *       directory.</td></tr>
 *   <tr><td>{@code bench.dbName}</td><td>{@code schemaBenchmark}</td><td>Sets the database name.</td></tr>
 *   <tr><td>{@code bench.resultFile}</td><td>{@code
 *       target/benchmark-results/schema-creation.csv}</td><td>Sets the appended result file.</td></tr>
 *   <tr><td>{@code bench.manifestFile}</td><td>{@code
 *       target/benchmark-results/manifest.txt}</td><td>Sets the canonical manifest file.</td></tr>
 *   <tr><td>{@code bench.verify}</td><td>{@code true}</td><td>Verifies persistence. It also proves
 *       indexes after phase B.</td></tr>
 *   <tr><td>{@code bench.durabilityBarrier}</td><td>{@code true}</td><td>Synchronizes storage after
 *       each phase when true. When false, deferred work may land in the next phase or at
 *       shutdown.</td></tr>
 *   <tr><td>{@code bench.label}</td><td>empty</td><td>Sets the free-text result label.</td></tr>
 * </table>
 *
 * <p>An explicitly set value wins. Otherwise the entry point's default applies. Otherwise the
 * built-in default applies. An empty value counts as not set. The Maven profile forwards empty
 * values so that each entry point keeps its own defaults.
 *
 * <p>Use these commands exactly. The {@code -am} flag builds the upstream modules and prevents a
 * stale core jar from entering the run.
 *
 * <p>Plain non-transactional timing:
 * <pre>{@code ./mvnw -pl tests -am -Pbench -DskipTests verify}</pre>
 *
 * <p>Plain transactional timing:
 * <pre>{@code ./mvnw -pl tests -am -Pbench -DskipTests -Dbench.mainClass=com.jetbrains.youtrackdb.benchmarks.TxSchemaCreationBenchmark verify}</pre>
 *
 * <p>One scale-sweep point:
 * <pre>{@code ./mvnw -pl tests -am -Pbench -DskipTests -Dbench.classes=200 -Dbench.label=n200 verify}</pre>
 *
 * <p>One phase:
 * <pre>{@code ./mvnw -pl tests -am -Pbench -DskipTests -Dbench.phase=A verify}</pre>
 *
 * <p>One sampling profile:
 * <pre>{@code ./mvnw -pl tests -am -Pbench -DskipTests -Dbench.verify=false -Dbench.jvmAddArgs="-agentpath:/path/to/libasyncProfiler.so=start,event=cpu,file=cpu.jfr" verify}</pre>
 *
 * <p>One instrumentation count:
 * <pre>{@code ./mvnw -pl tests -am -Pbench -DskipTests -Dbench.verify=false -Dbench.jvmAddArgs="-agentpath:/path/to/libasyncProfiler.so=start,event=alloc,interval=0,file=alloc.jfr" verify}</pre>
 *
 * <p>The schema-work time is the latency a user feels while the schema is created. This number
 * matches the reported complaint. The barrier time and the shutdown time show where deferred work
 * lands. {@code totalNs} contains the schema-work time and the barrier time. It does not contain
 * the shutdown time. Their sum with the shutdown time shows whether a policy removes work or only
 * moves it. No single number is the answer. A conclusion must name which of the three it uses and
 * why.
 *
 * <p>The harness measures shutdown once per run. It repeats that value on every phase row in the
 * run. Deduplicate shutdown by {@code runId} before summing rows, or the sum counts shutdown more
 * than once.
 *
 * <p>Turn verification off for every profiling run. Otherwise the profile includes manifest work,
 * database reopen, proof-record insertion, and index lookup. Use one profiler event per run. Run
 * sampling and instrumentation separately. Never report an instrumentation run as a timing.
 *
 * <p>Measure phase A for every policy and property-path pair. The property path does not affect
 * phase B. Run phase B once per transaction policy. The non-transactional index path pays listener
 * overhead that the transactional path does not.
 *
 * <p>Flush the operating-system page cache between sweep points when the environment permits it.
 * Otherwise wait until input-output settles. Dirty pages from one point can affect the next point.
 */
public final class SchemaCreationWorkload {

  private static final String USER = "admin";
  private static final String PASSWORD = "admin";
  private static final String CLASS_PREFIX = "TestClass";
  private static final String CSV_HEADER =
      "timestamp,runId,label,policy,batchSize,propertyPath,phase,classes,properties,indexes,"
          + "uniquePropertyNames,schemaWorkNs,durabilityBarrierNs,durabilityBarrierRan,totalNs,"
          + "shutdownNs,schemaWorkMs,durabilityBarrierMs,totalMs,shutdownMs,topLevelTransactions,"
          + "unsafePropertyCreations,verificationState,jvmArgs,"
          + "assertionsEnabled,storageCallFsync,storageFullCheckpointAfterCreate,"
          + "osOpenFileSoftLimit,osOpenFileHardLimit,resolvedOpenFilesLimit,gitCommit,gitDirty,"
          + "coreImplementationVersion,coreBuildNumber,coreBuildVersion,coreCodeSource,javaVersion,"
          + "osName,garbageCollectors,"
          + "hostName,availableProcessors,maxHeapBytes,effectiveDatabasePath";
  private static final String DETAIL_HEADER =
      "runId,phase,completedClasses,cumulativeSchemaWorkNs";

  private SchemaCreationWorkload() {
  }

  public static void runMain(Policy defaultPolicy, PropertyPath defaultPropertyPath) {
    try {
      run(Configuration.fromSystemProperties(defaultPolicy, defaultPropertyPath));
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  /**
   * Runs one explicitly configured workload without changing global system properties. Runtime,
   * operating-system, process, and build metadata are still observed for the result files; missing
   * environmental values are recorded with explicit markers and never fail a completed workload.
   *
   * @return phase timings and, when verification is enabled, the canonical manifest
   */
  public static RunResult run(Configuration configuration) throws IOException {
    return run(configuration, null);
  }

  static RunResult run(Configuration configuration, SessionListener listener) throws IOException {
    configuration.validate();
    return new SchemaCreationWorkload().runInternal(configuration, listener);
  }

  static PhaseResult runPhaseForTesting(
      Configuration configuration, DatabaseSessionEmbedded session, Phase phase) {
    if (phase == Phase.AB) {
      throw new IllegalArgumentException("A test phase must be A or B");
    }
    var workload = new SchemaCreationWorkload();
    return workload.runPhase(
        configuration,
        session,
        phase,
        phase == Phase.A ? workload::createClassAndProperties : workload::createIndexes);
  }

  private RunResult runInternal(Configuration config, SessionListener listener) throws IOException {
    var runId = UUID.randomUUID().toString();
    var phaseResults = new ArrayList<PhaseResult>();
    StorageEnvironment storageEnvironment = null;
    YouTrackDBImpl manager = null;
    DatabaseSessionEmbedded session = null;
    Throwable workFailure = null;
    Throwable closeFailure = null;
    long shutdownNs;
    try {
      manager = manager(config.dbPath);
      if (config.phase != Phase.B) {
        if (manager.exists(config.dbName)) {
          manager.drop(config.dbName);
        }
        manager.create(config.dbName, DatabaseType.DISK, USER, PASSWORD, USER);
      } else if (!manager.exists(config.dbName)) {
        throw new IllegalStateException("Phase B requires existing database " + config.dbName);
      }

      session = manager.open(config.dbName, USER, PASSWORD);
      if (listener != null) {
        session.registerListener(listener);
      }
      storageEnvironment = captureStorageEnvironment(session);
      if (config.phase.runsA()) {
        phaseResults.add(runPhase(config, session, Phase.A, this::createClassAndProperties));
      }
      if (config.phase.runsB()) {
        phaseResults.add(runPhase(config, session, Phase.B, this::createIndexes));
      }
    } catch (Throwable failure) {
      workFailure = failure;
    } finally {
      var shutdownStart = System.nanoTime();
      closeFailure = closeFirstSessionAndManager(session, manager);
      shutdownNs = System.nanoTime() - shutdownStart;
    }

    if (workFailure != null) {
      if (closeFailure != null) {
        workFailure.addSuppressed(closeFailure);
      }
      rethrow(workFailure);
    }

    var manifest = Optional.<String>empty();
    var verificationState = VerificationState.NOT_REQUESTED;
    if (config.verify) {
      if (closeFailure == null) {
        manifest = Optional.of(verify(config));
        verificationState = VerificationState.COMPLETED;
      } else {
        verificationState = VerificationState.SKIPPED_CLOSE_FAILED;
      }
    }
    var runtimeEnvironment = captureRuntimeEnvironment();
    var detailFile = detailFile(config.resultFile);
    for (var result : phaseResults) {
      appendCsv(
          config,
          runId,
          result,
          shutdownNs,
          verificationState,
          storageEnvironment,
          runtimeEnvironment);
      appendTimingDetails(detailFile, runId, result);
    }
    if (closeFailure != null) {
      rethrow(closeFailure);
    }
    return new RunResult(
        runId,
        List.copyOf(phaseResults),
        manifest,
        config.manifestFile,
        detailFile,
        shutdownNs,
        verificationState,
        verificationState == VerificationState.COMPLETED && config.phase.runsB());
  }

  private PhaseResult runPhase(
      Configuration config, DatabaseSessionEmbedded session, Phase phase,
      ClassOperation operation) {
    requireNoActiveTransaction(session, phase, "start");
    if (config.policy == Policy.BATCH && config.batchSize >= config.classes) {
      System.err.printf(
          "WARNING: BATCH size %,d is not smaller than %,d classes; phase %s is equivalent to ALL.%n",
          config.batchSize, config.classes, phase);
    }
    System.out.printf("Starting phase %s: %,d classes%n", phase, config.classes);
    var progress = new Progress(config.classes, phase);
    var details = new ArrayList<TimingDetail>();
    var counters = new PhaseCounters();
    var start = System.nanoTime();
    var transactionCount = executePolicy(config, session, classIndex -> {
      counters.unsafePropertyCreations += operation.run(config, session, classIndex);
      var completed = classIndex + 1;
      if (progress.completed(completed)) {
        details.add(new TimingDetail(completed, System.nanoTime() - start));
      }
    });
    var schemaWorkNs = System.nanoTime() - start;
    requireNoActiveTransaction(session, phase, "end");
    var finalDetail = new TimingDetail(config.classes, schemaWorkNs);
    if (details.isEmpty() || details.getLast().completedClasses != config.classes) {
      details.add(finalDetail);
    } else {
      // The callback for the final class runs before its enclosing transaction commits. Replace
      // that sample so the final cumulative point exactly matches the complete schema-work timer.
      details.set(details.size() - 1, finalDetail);
    }

    // AbstractStorage.synch() freezes writes, flushes index engines and dirty histograms, flushes
    // all storage data and the WAL, then unfreezes writes. Disabling it preserves the natural flow
    // in which deferred work may land in the next phase or in the measured shutdown.
    var durabilityBarrierNs = 0L;
    var durabilityBarrierRan = false;
    if (config.durabilityBarrier) {
      var durabilityStart = System.nanoTime();
      session.getStorage().synch();
      durabilityBarrierNs = System.nanoTime() - durabilityStart;
      durabilityBarrierRan = true;
    }
    var totalNs = schemaWorkNs + durabilityBarrierNs;
    System.out.printf(
        "Finished phase %s: schema %,d ms, durability %,d ms, total %,d ms%n",
        phase,
        TimeUnit.NANOSECONDS.toMillis(schemaWorkNs),
        TimeUnit.NANOSECONDS.toMillis(durabilityBarrierNs),
        TimeUnit.NANOSECONDS.toMillis(totalNs));
    return new PhaseResult(
        phase,
        schemaWorkNs,
        durabilityBarrierNs,
        durabilityBarrierRan,
        totalNs,
        transactionCount,
        counters.unsafePropertyCreations,
        List.copyOf(details));
  }

  private static void requireNoActiveTransaction(
      DatabaseSessionEmbedded session, Phase phase, String boundary) {
    if (session.getTransactionInternal().isActive()) {
      throw new IllegalStateException(
          "Phase " + phase + " must have no active transaction at its " + boundary);
    }
  }

  private int executePolicy(
      Configuration config, DatabaseSessionEmbedded session, ClassIndexOperation operation) {
    if (config.policy == Policy.NONE) {
      for (var classIndex = 0; classIndex < config.classes; classIndex++) {
        operation.run(classIndex);
      }
      return 0;
    }

    var groupSize = switch (config.policy) {
      case PER_CLASS -> 1;
      case BATCH -> config.batchSize;
      case ALL -> config.classes;
      case NONE -> throw new IllegalStateException("NONE was handled above");
    };
    var transactions = 0;
    for (var first = 0; first < config.classes; first += groupSize) {
      var end = Math.min(config.classes, first + groupSize);
      session.begin();
      try {
        for (var classIndex = first; classIndex < end; classIndex++) {
          operation.run(classIndex);
        }
        session.commit();
        transactions++;
      } catch (Throwable failure) {
        session.rollback();
        throw failure;
      }
    }
    return transactions;
  }

  private int createClassAndProperties(
      Configuration config, DatabaseSessionEmbedded session, int classIndex) {
    var schemaClass = session.getSchema().createClass(className(classIndex));
    var unsafePropertyCreations = 0;
    for (var propertyIndex = 0; propertyIndex < config.properties; propertyIndex++) {
      var propertyName = propertyName(config, classIndex, propertyIndex);
      if (config.propertyPath == PropertyPath.SAFE) {
        schemaClass.createProperty(propertyName, PropertyType.STRING);
      } else {
        ((SchemaClassInternal) schemaClass)
            .createProperty(
                propertyName,
                PropertyTypeInternal.convertFromPublicType(PropertyType.STRING),
                (PropertyTypeInternal) null,
                true);
        unsafePropertyCreations++;
      }
    }
    return unsafePropertyCreations;
  }

  private int createIndexes(
      Configuration config, DatabaseSessionEmbedded session, int classIndex) {
    var schemaClass = session.getSchema().getClass(className(classIndex));
    if (schemaClass == null) {
      throw new IllegalStateException("Missing class for phase B: " + className(classIndex));
    }
    for (var index = 0; index < config.indexes; index++) {
      var propertyName = propertyName(config, classIndex, index);
      if (!schemaClass.existsProperty(propertyName)) {
        throw new IllegalStateException(
            "Missing property for phase B: " + className(classIndex) + "." + propertyName);
      }
      schemaClass.createIndex(
          indexName(classIndex, index), SchemaClass.INDEX_TYPE.UNIQUE, propertyName);
    }
    return 0;
  }

  private String verify(Configuration config) throws IOException {
    String beforeRestart;
    try (var manager = manager(config.dbPath);
        var session = manager.open(config.dbName, USER, PASSWORD)) {
      beforeRestart = buildManifest(session);
    }

    String afterRestart;
    try (var manager = manager(config.dbPath);
        var session = manager.open(config.dbName, USER, PASSWORD)) {
      afterRestart = buildManifest(session);
      requireEqualManifests(beforeRestart, afterRestart);
      if (config.phase.runsB()) {
        proveIndexes(config, session);
      }
    }

    var manifestFile = config.manifestFile;
    createParentDirectories(manifestFile);
    Files.writeString(manifestFile, afterRestart, StandardCharsets.UTF_8);
    return afterRestart;
  }

  private String buildManifest(DatabaseSessionEmbedded session) {
    var classes = session.getSchema().getClasses().stream()
        .filter(schemaClass -> schemaClass.getName().startsWith(CLASS_PREFIX))
        .sorted(Comparator.comparing(SchemaClass::getName))
        .toList();
    var indexes = session.getSharedContext().getIndexManager().getIndexes(session);
    var manifest = new StringBuilder();
    for (var schemaClass : classes) {
      manifest.append("class ").append(schemaClass.getName()).append('\n');
      schemaClass.getProperties().stream()
          .sorted(Comparator.comparing(property -> property.getName()))
          .forEach(property -> manifest.append("  property ").append(property.getName())
              .append(" type=").append(property.getType()).append('\n'));
      indexes.stream()
          .filter(index -> index.getDefinition() != null)
          .filter(index -> schemaClass.getName().equals(index.getDefinition().getClassName()))
          .sorted(Comparator.comparing(index -> index.getName()))
          .forEach(index -> manifest.append("  index ").append(index.getName())
              .append(" type=").append(index.getType()).append(" fields=")
              .append(String.join(",", index.getDefinition().getFieldsToIndex())).append('\n'));
    }
    return manifest.toString();
  }

  static void requireEqualManifests(String expected, String actual) {
    if (expected.equals(actual)) {
      return;
    }
    var expectedLines = expected.lines().toList();
    var actualLines = actual.lines().toList();
    var commonSize = Math.min(expectedLines.size(), actualLines.size());
    for (var line = 0; line < commonSize; line++) {
      if (!expectedLines.get(line).equals(actualLines.get(line))) {
        throw new IllegalStateException("Manifest differs at line " + (line + 1) + ": expected <"
            + expectedLines.get(line) + "> but was <" + actualLines.get(line) + ">");
      }
    }
    throw new IllegalStateException("Manifest differs at line " + (commonSize + 1)
        + ": one manifest ended early");
  }

  private void proveIndexes(Configuration config, DatabaseSessionEmbedded session) {
    session.begin();
    try {
      for (var classIndex = 0; classIndex < config.classes; classIndex++) {
        var entity = session.newEntity(className(classIndex));
        for (var propertyIndex = 0; propertyIndex < config.indexes; propertyIndex++) {
          entity.setProperty(
              propertyName(config, classIndex, propertyIndex),
              indexedValue(classIndex, propertyIndex));
        }
      }
      session.commit();
    } catch (Throwable failure) {
      session.rollback();
      throw failure;
    }

    for (var classIndex = 0; classIndex < config.classes; classIndex++) {
      for (var indexNumber = 0; indexNumber < config.indexes; indexNumber++) {
        var name = indexName(classIndex, indexNumber);
        var index = session.getSharedContext().getIndexManager().getIndex(session, name);
        if (index == null) {
          throw new IllegalStateException("Index manager cannot find " + name);
        }
        if (index.getIndexId() < 0) {
          throw new IllegalStateException("Index has no real identifier: " + name);
        }
        if (((AbstractStorage) session.getStorage()).loadIndexEngine(name) < 0) {
          throw new IllegalStateException("Storage cannot load index engine: " + name);
        }
        var value = indexedValue(classIndex, indexNumber);
        var found = session.computeInTx(tx -> index.getRids(session, value).findAny().isPresent());
        if (!found) {
          throw new IllegalStateException("Index " + name + " cannot find inserted value " + value);
        }
      }
    }
  }

  private void appendCsv(
      Configuration config,
      String runId,
      PhaseResult result,
      long shutdownNs,
      VerificationState verificationState,
      StorageEnvironment storage,
      RuntimeEnvironment runtime) throws IOException {
    var values = List.of(
        Instant.now().toString(),
        runId,
        config.label,
        config.policy.name(),
        Integer.toString(config.batchSize),
        config.propertyPath.name(),
        result.phase.name(),
        Integer.toString(config.classes),
        Integer.toString(config.properties),
        Integer.toString(config.indexes),
        Boolean.toString(config.uniquePropertyNames),
        Long.toString(result.schemaWorkNs),
        Long.toString(result.durabilityBarrierNs),
        Boolean.toString(result.durabilityBarrierRan),
        Long.toString(result.totalNs),
        Long.toString(shutdownNs),
        Long.toString(TimeUnit.NANOSECONDS.toMillis(result.schemaWorkNs)),
        Long.toString(TimeUnit.NANOSECONDS.toMillis(result.durabilityBarrierNs)),
        Long.toString(TimeUnit.NANOSECONDS.toMillis(result.totalNs)),
        Long.toString(TimeUnit.NANOSECONDS.toMillis(shutdownNs)),
        Integer.toString(result.topLevelTransactions),
        Integer.toString(result.unsafePropertyCreations),
        verificationState.name(),
        runtime.jvmArgs,
        Boolean.toString(runtime.assertionsEnabled),
        Boolean.toString(storage.callFsync),
        Boolean.toString(storage.fullCheckpointAfterCreate),
        runtime.openFileLimits.soft,
        runtime.openFileLimits.hard,
        runtime.resolvedOpenFilesLimit,
        runtime.git.commit,
        runtime.git.dirty,
        runtime.coreImplementationVersion,
        runtime.coreBuildNumber,
        runtime.coreBuildVersion,
        runtime.coreCodeSource,
        runtime.javaVersion,
        runtime.osName,
        runtime.garbageCollectors,
        runtime.hostName,
        Integer.toString(runtime.availableProcessors),
        Long.toString(runtime.maxHeapBytes),
        config.dbPath.toAbsolutePath().normalize().resolve(config.dbName).toString());
    appendRow(config.resultFile, CSV_HEADER, values);
  }

  private void appendTimingDetails(Path detailFile, String runId, PhaseResult result)
      throws IOException {
    for (var detail : result.timingDetails) {
      appendRow(detailFile, DETAIL_HEADER, List.of(
          runId,
          result.phase.name(),
          Integer.toString(detail.completedClasses),
          Long.toString(detail.cumulativeSchemaWorkNs)));
    }
  }

  private static void appendRow(Path file, String header, List<String> values) throws IOException {
    createParentDirectories(file);
    var newFile = Files.notExists(file) || Files.size(file) == 0;
    try (var writer = Files.newBufferedWriter(
        file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      if (newFile) {
        writer.write(header);
        writer.newLine();
      }
      writer
          .write(values.stream().map(SchemaCreationWorkload::csv).collect(Collectors.joining(",")));
      writer.newLine();
    }
  }

  private static StorageEnvironment captureStorageEnvironment(DatabaseSessionEmbedded session) {
    var context = session.getStorage().getContextConfiguration();
    return new StorageEnvironment(
        context.getValueAsBoolean(GlobalConfiguration.STORAGE_CALL_FSYNC),
        context.getValueAsBoolean(GlobalConfiguration.STORAGE_MAKE_FULL_CHECKPOINT_AFTER_CREATE));
  }

  private static RuntimeEnvironment captureRuntimeEnvironment() {
    var limits = readOpenFileLimits();
    var implementationVersion = coreImplementationVersion();
    var codeSourceLocation = coreCodeSource();
    var garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans().stream()
        .map(collector -> collector.getName())
        .sorted()
        .collect(Collectors.joining(";"));
    return new RuntimeEnvironment(
        String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()),
        assertionsEnabled(),
        limits,
        resolvedOpenFilesLimit(),
        readGitIdentity(),
        implementationVersion,
        coreBuildNumber(),
        coreBuildVersion(),
        codeSourceLocation,
        safeSystemProperty("java.version"),
        safeSystemProperty("os.name"),
        valueOrMarker(garbageCollectors, "unavailable:no-garbage-collector"),
        hostName(),
        Runtime.getRuntime().availableProcessors(),
        Runtime.getRuntime().maxMemory());
  }

  private static String resolvedOpenFilesLimit() {
    var configured = GlobalConfiguration.OPEN_FILES_LIMIT.getValueAsInteger();
    if (configured > 0) {
      return Integer.toString(configured);
    }
    // Call the same resolver and use the same constants as EngineLocalPaginated. The prefix makes
    // clear that this is a repeated derivation from OS limits, not an accessor on the live engine.
    var resolved = Native.instance().getOpenFilesLimit(false, 256 * 1024, 512);
    return "derived:" + resolved;
  }

  private static OpenFileLimits readOpenFileLimits() {
    var limitsFile = Path.of("/proc/self/limits");
    if (!Files.isRegularFile(limitsFile)) {
      return new OpenFileLimits("unknown", "unknown");
    }
    try {
      for (var line : Files.readAllLines(limitsFile, StandardCharsets.UTF_8)) {
        if (line.startsWith("Max open files")) {
          var fields = line.trim().split("\\s+");
          return fields.length >= 5
              ? new OpenFileLimits(fields[3], fields[4])
              : new OpenFileLimits("unknown", "unknown");
        }
      }
    } catch (IOException ignored) {
      // Environmental metadata must not prevent the benchmark result from being recorded.
    }
    return new OpenFileLimits("unknown", "unknown");
  }

  private static GitIdentity readGitIdentity() {
    var commit = runCommand("git", "rev-parse", "--short=12", "HEAD");
    var status = runCommand("git", "status", "--porcelain", "--untracked-files=no");
    var dirty = status.startsWith("unavailable:") ? status : Boolean.toString(!status.isBlank());
    return new GitIdentity(commit.isBlank() ? "unavailable:empty-commit" : commit, dirty);
  }

  private static String runCommand(String... command) {
    try {
      var process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return "unavailable:command-timeout";
      }
      var output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return process.exitValue() == 0
          ? output
          : "unavailable:command-exit-" + process.exitValue() + ":" + output;
    } catch (IOException failure) {
      return "unavailable:command-io-" + failure.getClass().getSimpleName();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return "unavailable:command-interrupted";
    }
  }

  private static String hostName() {
    try {
      return valueOrMarker(InetAddress.getLocalHost().getHostName(), "unavailable:no-host-name");
    } catch (Exception failure) {
      return "unavailable:host-" + failure.getClass().getSimpleName();
    }
  }

  private static String coreImplementationVersion() {
    try {
      var corePackage = YourTracks.class.getPackage();
      return corePackage == null
          ? "unavailable:no-package"
          : valueOrMarker(
              corePackage.getImplementationVersion(), "unavailable:no-package-version");
    } catch (RuntimeException failure) {
      return "unavailable:package-" + failure.getClass().getSimpleName();
    }
  }

  private static String coreBuildNumber() {
    try {
      return valueOrMarker(
          YouTrackDBConstants.getBuildNumber(), "unavailable:no-core-build-number");
    } catch (RuntimeException failure) {
      return "unavailable:core-build-number-" + failure.getClass().getSimpleName();
    }
  }

  private static String coreBuildVersion() {
    try {
      return valueOrMarker(
          YouTrackDBConstants.getVersion(), "unavailable:no-core-build-version");
    } catch (RuntimeException failure) {
      return "unavailable:core-build-version-" + failure.getClass().getSimpleName();
    }
  }

  private static String coreCodeSource() {
    try {
      var protectionDomain = YourTracks.class.getProtectionDomain();
      var codeSource = protectionDomain == null ? null : protectionDomain.getCodeSource();
      return codeSource == null || codeSource.getLocation() == null
          ? "unavailable:no-code-source"
          : codeSource.getLocation().toExternalForm();
    } catch (RuntimeException failure) {
      return "unavailable:code-source-" + failure.getClass().getSimpleName();
    }
  }

  private static String safeSystemProperty(String name) {
    try {
      return valueOrMarker(System.getProperty(name), "unavailable:missing-" + name);
    } catch (SecurityException failure) {
      return "unavailable:property-security-" + name;
    }
  }

  private static String valueOrMarker(String value, String marker) {
    return value == null || value.isBlank() ? marker : value;
  }

  private static boolean assertionsEnabled() {
    return SchemaCreationWorkload.class.desiredAssertionStatus();
  }

  private static Path detailFile(Path resultFile) {
    return resultFile.resolveSibling(resultFile.getFileName() + ".detail.csv");
  }

  private static String csv(String value) {
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  private static YouTrackDBImpl manager(Path databasePath) {
    return (YouTrackDBImpl) YourTracks.instance(databasePath.toString());
  }

  private static Throwable closeFirstSessionAndManager(
      DatabaseSessionEmbedded session, YouTrackDBImpl manager) {
    Throwable failure = null;
    if (session != null) {
      try {
        session.close();
      } catch (Throwable closeFailure) {
        failure = closeFailure;
      }
    }
    if (manager != null) {
      try {
        manager.close();
      } catch (Throwable closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
    }
    return failure;
  }

  private static void rethrow(Throwable failure) throws IOException {
    if (failure instanceof IOException ioFailure) {
      throw ioFailure;
    }
    if (failure instanceof RuntimeException runtimeFailure) {
      throw runtimeFailure;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    throw new IOException("Benchmark failed", failure);
  }

  private static void createParentDirectories(Path file) throws IOException {
    var parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }

  private static String className(int classIndex) {
    return CLASS_PREFIX + classIndex;
  }

  private static String propertyName(
      Configuration config, int classIndex, int propertyIndex) {
    return config.uniquePropertyNames
        ? className(classIndex) + "Prop" + propertyIndex
        : "TestProperty" + propertyIndex;
  }

  private static String indexName(int classIndex, int indexNumber) {
    return "TestIndex_" + classIndex + "_" + indexNumber;
  }

  private static String indexedValue(int classIndex, int propertyIndex) {
    return "value_" + classIndex + "_" + propertyIndex;
  }

  public enum Policy {
    NONE, PER_CLASS, BATCH, ALL
  }

  public enum PropertyPath {
    SAFE, UNSAFE
  }

  public enum VerificationState {
    NOT_REQUESTED, COMPLETED, SKIPPED_CLOSE_FAILED
  }

  public enum Phase {
    A, B, AB;

    private boolean runsA() {
      return this == A || this == AB;
    }

    private boolean runsB() {
      return this == B || this == AB;
    }
  }

  @FunctionalInterface
  private interface ClassOperation {

    int run(Configuration config, DatabaseSessionEmbedded session, int classIndex);
  }

  @FunctionalInterface
  private interface ClassIndexOperation {

    void run(int classIndex);
  }

  public record TimingDetail(int completedClasses, long cumulativeSchemaWorkNs) {
  }

  public record PhaseResult(
      Phase phase,
      long schemaWorkNs,
      long durabilityBarrierNs,
      boolean durabilityBarrierRan,
      long totalNs,
      int topLevelTransactions,
      int unsafePropertyCreations,
      List<TimingDetail> timingDetails) {

    public long schemaWorkMs() {
      return TimeUnit.NANOSECONDS.toMillis(schemaWorkNs);
    }

    public long durabilityBarrierMs() {
      return TimeUnit.NANOSECONDS.toMillis(durabilityBarrierNs);
    }

    public long totalMs() {
      return TimeUnit.NANOSECONDS.toMillis(totalNs);
    }
  }

  public record RunResult(
      String runId,
      List<PhaseResult> phases,
      Optional<String> manifestText,
      Path manifestFile,
      Path detailFile,
      long shutdownNs,
      VerificationState verificationState,
      boolean functionalIndexProofRan) {
  }

  private record OpenFileLimits(String soft, String hard) {
  }

  private record StorageEnvironment(boolean callFsync, boolean fullCheckpointAfterCreate) {
  }

  private record GitIdentity(String commit, String dirty) {
  }

  private record RuntimeEnvironment(
      String jvmArgs,
      boolean assertionsEnabled,
      OpenFileLimits openFileLimits,
      String resolvedOpenFilesLimit,
      GitIdentity git,
      String coreImplementationVersion,
      String coreBuildNumber,
      String coreBuildVersion,
      String coreCodeSource,
      String javaVersion,
      String osName,
      String garbageCollectors,
      String hostName,
      int availableProcessors,
      long maxHeapBytes) {
  }

  private static final class PhaseCounters {

    private int unsafePropertyCreations;
  }

  public record Configuration(
      int classes,
      int properties,
      int indexes,
      Policy policy,
      int batchSize,
      PropertyPath propertyPath,
      Phase phase,
      boolean uniquePropertyNames,
      Path dbPath,
      String dbName,
      Path resultFile,
      Path manifestFile,
      boolean verify,
      boolean durabilityBarrier,
      String label) {

    private static Configuration fromSystemProperties(
        Policy defaultPolicy, PropertyPath defaultPropertyPath) {
      return new Configuration(
          integer("bench.classes", 100),
          integer("bench.properties", 20),
          integer("bench.indexes", 20),
          enumeration("bench.policy", defaultPolicy, Policy.class),
          integer("bench.batchSize", 50),
          enumeration("bench.propertyPath", defaultPropertyPath, PropertyPath.class),
          enumeration("bench.phase", Phase.AB, Phase.class),
          Boolean.parseBoolean(systemProperty("bench.uniquePropertyNames", "true")),
          Path.of(systemProperty(
              "bench.dbPath", "./target/databases/benchmarks/schemaCreationBenchmark")),
          systemProperty("bench.dbName", "schemaBenchmark"),
          Path.of(systemProperty(
              "bench.resultFile", "target/benchmark-results/schema-creation.csv")),
          Path.of(systemProperty(
              "bench.manifestFile", "target/benchmark-results/manifest.txt")),
          Boolean.parseBoolean(systemProperty("bench.verify", "true")),
          Boolean.parseBoolean(systemProperty("bench.durabilityBarrier", "true")),
          systemProperty("bench.label", ""));
    }

    private void validate() {
      if (classes <= 0) {
        throw new IllegalArgumentException("bench.classes must be positive");
      }
      if (properties < 0) {
        throw new IllegalArgumentException("bench.properties must not be negative");
      }
      if (indexes < 0 || indexes > properties) {
        throw new IllegalArgumentException(
            "bench.indexes must be between zero and bench.properties");
      }
      if (batchSize <= 0) {
        throw new IllegalArgumentException("bench.batchSize must be positive");
      }
    }

    private static int integer(String name, int defaultValue) {
      return Integer.parseInt(systemProperty(name, Integer.toString(defaultValue)));
    }

    private static <E extends Enum<E>> E enumeration(
        String name, E defaultValue, Class<E> type) {
      return Enum.valueOf(
          type, systemProperty(name, defaultValue.name()).toUpperCase(Locale.ROOT));
    }

    private static String systemProperty(String name, String defaultValue) {
      var value = System.getProperty(name);
      return value == null || value.isBlank() ? defaultValue : value;
    }
  }

  private static final class Progress {

    private final int total;
    private final Phase phase;
    private final int interval;
    private int next;

    private Progress(int total, Phase phase) {
      this.total = total;
      this.phase = phase;
      interval = Math.max(1, (int) Math.ceil(total / 10.0));
      next = interval;
    }

    private boolean completed(int count) {
      if (count < next && count < total) {
        return false;
      }
      if (count < total) {
        System.out.printf("Phase %s progress: %,d of %,d classes%n", phase, count, total);
      }
      while (next <= count) {
        next += interval;
      }
      return true;
    }
  }
}
