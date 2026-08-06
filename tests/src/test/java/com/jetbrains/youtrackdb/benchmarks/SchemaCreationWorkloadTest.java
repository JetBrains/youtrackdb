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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.benchmarks.SchemaCreationWorkload.Configuration;
import com.jetbrains.youtrackdb.benchmarks.SchemaCreationWorkload.Phase;
import com.jetbrains.youtrackdb.benchmarks.SchemaCreationWorkload.Policy;
import com.jetbrains.youtrackdb.benchmarks.SchemaCreationWorkload.PropertyPath;
import com.jetbrains.youtrackdb.benchmarks.SchemaCreationWorkload.RunResult;
import com.jetbrains.youtrackdb.benchmarks.SchemaCreationWorkload.SecurityFilter;
import com.jetbrains.youtrackdb.benchmarks.SchemaCreationWorkload.VerificationState;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SessionListener;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import com.jetbrains.youtrackdb.junit.SuiteLifecycleExtension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that every benchmark mode produces the same persistent schema and exposes its actual
 * transaction and unsafe-property paths.
 */
@ExtendWith(SuiteLifecycleExtension.class)
public class SchemaCreationWorkloadTest {

  private static final String CSV_HEADER =
      "schemaVersion,timestamp,runId,label,policy,batchSize,propertyPath,phase,classes,properties,"
          + "indexes,compositeIndexes,compositeIndexWidth,securityFilter,uniquePropertyNames,"
          + "schemaWorkNs,durabilityBarrierNs,durabilityBarrierRan,totalNs,shutdownNs,schemaWorkMs,"
          + "durabilityBarrierMs,totalMs,shutdownMs,topLevelTransactions,unsafePropertyCreations,"
          + "verificationState,jvmArgs,assertionsEnabled,storageCallFsync,"
          + "storageFullCheckpointAfterCreate,osOpenFileSoftLimit,osOpenFileHardLimit,"
          + "resolvedOpenFilesLimit,gitCommit,gitDirty,coreImplementationVersion,coreBuildNumber,"
          + "coreBuildVersion,coreCodeSource,javaVersion,osName,garbageCollectors,hostName,"
          + "availableProcessors,maxHeapBytes,effectiveDatabasePath";
  private static final String DETAIL_HEADER =
      "schemaVersion,runId,phase,completedClasses,cumulativeSchemaWorkNs";
  private static final String EXPECTED_MANIFEST = """
      class TestClass0
        property TestClass0Prop0 type=STRING
        property TestClass0Prop1 type=STRING
        property TestClass0Prop2 type=STRING
        index TestIndex_0_0 type=UNIQUE fields=TestClass0Prop0
        index TestIndex_0_1 type=UNIQUE fields=TestClass0Prop1
        index TestIndex_0_2 type=UNIQUE fields=TestClass0Prop2
      class TestClass1
        property TestClass1Prop0 type=STRING
        property TestClass1Prop1 type=STRING
        property TestClass1Prop2 type=STRING
        index TestIndex_1_0 type=UNIQUE fields=TestClass1Prop0
        index TestIndex_1_1 type=UNIQUE fields=TestClass1Prop1
        index TestIndex_1_2 type=UNIQUE fields=TestClass1Prop2
      class TestClass2
        property TestClass2Prop0 type=STRING
        property TestClass2Prop1 type=STRING
        property TestClass2Prop2 type=STRING
        index TestIndex_2_0 type=UNIQUE fields=TestClass2Prop0
        index TestIndex_2_1 type=UNIQUE fields=TestClass2Prop1
        index TestIndex_2_2 type=UNIQUE fields=TestClass2Prop2
      """;

  @TempDir
  Path temporaryDirectory;

  @Test
  void allModesAndSplitPhasesCreateIdenticalVerifiedSchemas() throws IOException {
    var completedRuns = new ArrayList<RunResult>();
    completedRuns.add(runCase("none-safe", Policy.NONE, 50, PropertyPath.SAFE, 0, 0));
    completedRuns.add(runCase("per-class-safe", Policy.PER_CLASS, 50, PropertyPath.SAFE, 3, 0));
    completedRuns.add(runCase("batch-safe", Policy.BATCH, 2, PropertyPath.SAFE, 2, 0));
    completedRuns.add(runCase("all-safe", Policy.ALL, 50, PropertyPath.SAFE, 1, 0));
    completedRuns.add(runCase("per-class-unsafe", Policy.PER_CLASS, 50,
        PropertyPath.UNSAFE, 3, 9));
    completedRuns.add(runCase("all-unsafe", Policy.ALL, 50, PropertyPath.UNSAFE, 1, 9));
    var barrierOff =
        runCase("all-safe-no-barrier", Policy.ALL, 50, PropertyPath.SAFE, 1, 0, false);
    completedRuns.add(barrierOff);
    for (var phaseResult : barrierOff.phases()) {
      assertFalse(phaseResult.durabilityBarrierRan());
      assertEquals(0, phaseResult.durabilityBarrierNs());
    }
    var barrierOffRows = Files.readAllLines(
        temporaryDirectory.resolve("all-safe-no-barrier/results.csv"), StandardCharsets.UTF_8);
    assertTrue(barrierOffRows.stream().skip(1).allMatch(row -> csvField(row, 16).equals("0")));
    assertTrue(barrierOffRows.stream().skip(1).allMatch(row -> csvField(row, 17).equals("false")));

    var splitDirectory = temporaryDirectory.resolve("split");
    var splitCsv = splitDirectory.resolve("results.csv");
    var phaseAConfig = configuration(
        splitDirectory.resolve("database"),
        "splitDatabase",
        splitCsv,
        splitDirectory.resolve("manifest-a.txt"),
        Policy.ALL,
        50,
        PropertyPath.SAFE,
        Phase.A,
        3,
        3,
        3);
    var phaseA = SchemaCreationWorkload.run(phaseAConfig);
    assertEquals(1, phaseA.phases().size(), "phase A must report exactly one result");
    assertPhase(phaseA, Phase.A, 1, 0);
    assertFalse(phaseA.functionalIndexProofRan(), "phase A must not insert index-proof records");

    var phaseBConfig = configuration(
        splitDirectory.resolve("database"),
        "splitDatabase",
        splitCsv,
        splitDirectory.resolve("manifest-b.txt"),
        Policy.ALL,
        50,
        PropertyPath.SAFE,
        Phase.B,
        3,
        3,
        3);
    var phaseB = SchemaCreationWorkload.run(phaseBConfig);
    assertPhase(phaseB, Phase.B, 1, 0);
    assertCsvAndDetails(phaseB, splitCsv, 2, 6);

    for (var result : completedRuns) {
      assertManifestsEqual(EXPECTED_MANIFEST, result.manifestText().orElseThrow());
      assertEquals(result.manifestText().orElseThrow(),
          Files.readString(result.manifestFile(), StandardCharsets.UTF_8));
    }
    assertManifestsEqual(EXPECTED_MANIFEST, phaseB.manifestText().orElseThrow());
    assertIndexesUsableIndependently(
        temporaryDirectory.resolve("all-safe/database"), "all_safe", 3, 3);
  }

  @Test
  void databaseListenerObservesIndependentCommitsForEveryPolicyAndPhase() throws IOException {
    assertObservedCommits("observed-none", Policy.NONE, 2, 0);
    assertObservedCommits("observed-per-class", Policy.PER_CLASS, 2, 3);
    assertObservedCommits("observed-batch", Policy.BATCH, 2, 2);
    assertObservedCommits("observed-all", Policy.ALL, 2, 1);
  }

  @Test
  void phaseGuardRejectsAnAlreadyActiveTransaction() {
    var directory = temporaryDirectory.resolve("active-transaction-guard");
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.toString())) {
      manager.create("guardDatabase", DatabaseType.DISK, "admin", "admin", "admin");
      try (var session = manager.open("guardDatabase", "admin", "admin")) {
        session.begin();
        var config = configuration(
            directory,
            "guardDatabase",
            temporaryDirectory.resolve("guard/results.csv"),
            temporaryDirectory.resolve("guard/manifest.txt"),
            Policy.ALL,
            1,
            PropertyPath.SAFE,
            Phase.A,
            1,
            1,
            0);
        var failure = assertThrows(IllegalStateException.class,
            () -> SchemaCreationWorkload.runPhaseForTesting(config, session, Phase.A));
        assertTrue(failure.getMessage().contains("no active transaction at its start"));
        session.rollback();
      }
    }
  }

  @Test
  void lowerAndZeroIndexCountsPreservePropertyToIndexMapping() throws IOException {
    var lower = runCustomCase("lower-index-count", 2, 3, 2);
    var lowerManifest = lower.manifestText().orElseThrow();
    assertTrue(lowerManifest.contains("property TestClass0Prop2 type=STRING"));
    assertTrue(lowerManifest.contains("index TestIndex_0_1 type=UNIQUE fields=TestClass0Prop1"));
    assertFalse(lowerManifest.contains("TestIndex_0_2"));
    assertIndexesUsableIndependently(
        temporaryDirectory.resolve("lower-index-count/database"), "lower_index_count", 2, 2);

    var zero = runCustomCase("zero-index-count", 2, 3, 0);
    var zeroManifest = zero.manifestText().orElseThrow();
    assertTrue(zeroManifest.contains("property TestClass0Prop2 type=STRING"));
    assertFalse(zeroManifest.contains("  index "));
  }

  @Test
  void compositeIndexesUseStableDistinctFieldsAtWidthsTwoAndThree() throws IOException {
    var widthTwo = runCompositeCase(
        "composite-width-two", 1, 6, 0, 2, 2, SecurityFilter.NONE);
    var widthTwoManifest = widthTwo.manifestText().orElseThrow();
    assertTrue(widthTwoManifest.contains(
        "index TestCompositeIndex_0_0 type=UNIQUE fields=TestClass0Prop0,TestClass0Prop1"));
    assertTrue(widthTwoManifest.contains(
        "index TestCompositeIndex_0_1 type=UNIQUE fields=TestClass0Prop2,TestClass0Prop3"));
    assertEquals(
        VerificationState.COMPLETED_COMPOSITE_INDEX_PROOF, widthTwo.verificationState());

    var widthThree = runCompositeCase(
        "composite-width-three", 1, 6, 0, 2, 3, SecurityFilter.NONE);
    var widthThreeManifest = widthThree.manifestText().orElseThrow();
    assertTrue(widthThreeManifest.contains(
        "index TestCompositeIndex_0_0 type=UNIQUE fields=TestClass0Prop0,TestClass0Prop1,"
            + "TestClass0Prop2"));
    assertTrue(widthThreeManifest.contains(
        "index TestCompositeIndex_0_1 type=UNIQUE fields=TestClass0Prop3,TestClass0Prop4,"
            + "TestClass0Prop5"));
  }

  @Test
  void compositeAndSingleIndexesCanBeMeasuredTogether() throws IOException {
    var result = runCompositeCase(
        "mixed-indexes", 1, 4, 2, 1, 2, SecurityFilter.NONE);
    var manifest = result.manifestText().orElseThrow();
    assertTrue(manifest.contains("index TestIndex_0_0 type=UNIQUE fields=TestClass0Prop0"));
    assertTrue(manifest.contains("index TestIndex_0_1 type=UNIQUE fields=TestClass0Prop1"));
    assertTrue(manifest.contains(
        "index TestCompositeIndex_0_0 type=UNIQUE fields=TestClass0Prop0,TestClass0Prop1"));
  }

  @Test
  void absentNewOptionsPreserveTheLegacyWorkloadShape() throws IOException {
    var defaults = Configuration.fromProperties(
        name -> null, Policy.NONE, PropertyPath.SAFE);
    assertEquals(100, defaults.classes());
    assertEquals(20, defaults.properties());
    assertEquals(20, defaults.indexes());
    assertEquals(0, defaults.compositeIndexes());
    assertEquals(2, defaults.compositeIndexWidth());
    assertEquals(SecurityFilter.NONE, defaults.securityFilter());

    var legacy = runCustomCase("legacy-default-options", 1, 3, 3);
    var expected = EXPECTED_MANIFEST.lines()
        .takeWhile(line -> !line.equals("class TestClass1"))
        .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    assertEquals(expected, legacy.manifestText().orElseThrow(),
        "omitting every new option must preserve the legacy manifest byte for byte");
  }

  @Test
  void verificationRejectsCompositeIndexesWithWrongFieldsOrWidth() {
    assertMalformedCompositeRejected(
        "wrong-composite-fields",
        List.of("TestClass0Prop0", "TestClass0Prop2"),
        "expected [TestClass0Prop0, TestClass0Prop1] (width 2)");
    assertMalformedCompositeRejected(
        "wrong-composite-width",
        List.of("TestClass0Prop0", "TestClass0Prop1", "TestClass0Prop2"),
        "width 3");
  }

  @Test
  void incompatibleResultAndDetailHeadersAreRejectedBeforeWorkStarts() throws IOException {
    var resultDirectory = temporaryDirectory.resolve("bad-result-header");
    var resultFile = resultDirectory.resolve("results.csv");
    Files.createDirectories(resultDirectory);
    Files.writeString(resultFile, "legacy-result-header\n", StandardCharsets.UTF_8);
    var resultFailure = assertThrows(IOException.class,
        () -> SchemaCreationWorkload.run(configuration(
            resultDirectory.resolve("database"),
            "bad_result_header",
            resultFile,
            resultDirectory.resolve("manifest.txt"),
            Policy.ALL,
            1,
            PropertyPath.SAFE,
            Phase.AB,
            1,
            1,
            1)));
    assertTrue(resultFailure.getMessage().contains("legacy-result-header"));
    assertTrue(resultFailure.getMessage().contains(CSV_HEADER));
    assertFalse(Files.exists(resultDirectory.resolve("database")),
        "header validation must happen before database work");

    var detailDirectory = temporaryDirectory.resolve("bad-detail-header");
    var detailResult = detailDirectory.resolve("results.csv");
    var detailFile = detailResult.resolveSibling(detailResult.getFileName() + ".detail.csv");
    Files.createDirectories(detailDirectory);
    Files.writeString(detailResult, CSV_HEADER + "\n", StandardCharsets.UTF_8);
    Files.writeString(detailFile, "legacy-detail-header\n", StandardCharsets.UTF_8);
    var detailFailure = assertThrows(IOException.class,
        () -> SchemaCreationWorkload.run(configuration(
            detailDirectory.resolve("database"),
            "bad_detail_header",
            detailResult,
            detailDirectory.resolve("manifest.txt"),
            Policy.ALL,
            1,
            PropertyPath.SAFE,
            Phase.AB,
            1,
            1,
            1)));
    assertTrue(detailFailure.getMessage().contains("legacy-detail-header"));
    assertTrue(detailFailure.getMessage().contains(DETAIL_HEADER));
  }

  @Test
  void unrelatedSecurityRulesReachCompositeCheckWithoutBlockingCreation() throws IOException {
    var result = runCompositeCase(
        "unrelated-security", 2, 4, 1, 1, 2, SecurityFilter.UNRELATED);
    assertEquals(
        VerificationState.COMPLETED_COMPOSITE_INDEX_PROOF, result.verificationState());

    var databasePath = temporaryDirectory.resolve("unrelated-security/database");
    try (var manager = (YouTrackDBImpl) YourTracks.instance(databasePath.toString());
        var session = manager.open("unrelated_security", "admin", "admin")) {
      var filtered = session.getSharedContext().getSecurity().getAllFilteredProperties(session);
      for (var classIndex = 0; classIndex < 2; classIndex++) {
        var className = "TestClass" + classIndex;
        assertTrue(filtered.stream().anyMatch(resource -> className.equals(resource.getClassName())
            && "SecurityUnrelatedProperty".equals(resource.getPropertyName())));
        var coveredFields =
            session.getSharedContext().getIndexManager().getIndexes(session).stream()
                .filter(index -> index.getDefinition() != null)
                .filter(index -> className.equals(index.getDefinition().getClassName()))
                .flatMap(index -> index.getDefinition().getFieldsToIndex().stream())
                .toList();
        assertFalse(coveredFields.contains("SecurityUnrelatedProperty"));
      }
    }
  }

  @Test
  void singleIndexAliasHasExplicitPrecedenceAndRejectsContradictions() {
    assertEquals(20, parsedConfiguration(Map.of()).indexes());
    assertEquals(7, parsedConfiguration(Map.of("bench.indexes", "7")).indexes());
    assertEquals(6, parsedConfiguration(Map.of("bench.singleIndexes", "6")).indexes());
    assertEquals(5, parsedConfiguration(Map.of(
        "bench.indexes", "5", "bench.singleIndexes", "5")).indexes());
    assertEquals(4, parsedConfiguration(Map.of(
        "bench.indexes", "", "bench.singleIndexes", "4")).indexes());

    var contradiction = assertThrows(IllegalArgumentException.class,
        () -> parsedConfiguration(Map.of(
            "bench.indexes", "3", "bench.singleIndexes", "4")));
    assertTrue(contradiction.getMessage().contains("bench.singleIndexes=4"));
    assertTrue(contradiction.getMessage().contains("bench.indexes=3"));
  }

  @Test
  void invalidAndMissingInputsFailInsteadOfContinuing() {
    var missing = configuration(
        temporaryDirectory.resolve("missing/database"),
        "doesNotExist",
        temporaryDirectory.resolve("missing/results.csv"),
        temporaryDirectory.resolve("missing/manifest.txt"),
        Policy.ALL,
        1,
        PropertyPath.SAFE,
        Phase.B,
        1,
        1,
        1);
    assertThrows(IllegalStateException.class, () -> SchemaCreationWorkload.run(missing));

    var invalid = configuration(
        temporaryDirectory.resolve("invalid/database"),
        "invalid",
        temporaryDirectory.resolve("invalid/results.csv"),
        temporaryDirectory.resolve("invalid/manifest.txt"),
        Policy.ALL,
        1,
        PropertyPath.SAFE,
        Phase.A,
        0,
        1,
        1);
    assertThrows(IllegalArgumentException.class, () -> SchemaCreationWorkload.run(invalid));

    var mismatch = assertThrows(IllegalStateException.class,
        () -> SchemaCreationWorkload.requireEqualManifests("class Expected\n", "class Actual\n"));
    assertTrue(mismatch.getMessage().contains("Manifest differs at line 1"));
  }

  private RunResult runCompositeCase(
      String name,
      int classes,
      int properties,
      int indexes,
      int compositeIndexes,
      int compositeIndexWidth,
      SecurityFilter securityFilter) throws IOException {
    var directory = temporaryDirectory.resolve(name);
    var resultFile = directory.resolve("results.csv");
    var result = SchemaCreationWorkload.run(configurationWithIndexes(
        directory.resolve("database"),
        name.replace('-', '_'),
        resultFile,
        directory.resolve("manifest.txt"),
        Phase.AB,
        classes,
        properties,
        indexes,
        compositeIndexes,
        compositeIndexWidth,
        securityFilter));
    assertPhase(result, Phase.A, 1, 0);
    assertPhase(result, Phase.B, 1, 0);
    assertCsvAndDetails(result, resultFile, 2, classes * 2);
    return result;
  }

  private void assertMalformedCompositeRejected(
      String name, List<String> actualFields, String expectedMessage) {
    var directory = temporaryDirectory.resolve(name);
    var databaseName = name.replace('-', '_');
    try (var manager = (YouTrackDBImpl) YourTracks.instance(directory.toString())) {
      manager.create(databaseName, DatabaseType.DISK, "admin", "admin", "admin");
      try (var session = manager.open(databaseName, "admin", "admin")) {
        var schemaClass = session.getSchema().createClass("TestClass0");
        for (var propertyIndex = 0; propertyIndex < 3; propertyIndex++) {
          schemaClass.createProperty("TestClass0Prop" + propertyIndex, PropertyType.STRING);
        }
        schemaClass.createIndex(
            "TestCompositeIndex_0_0",
            SchemaClass.INDEX_TYPE.UNIQUE,
            actualFields.toArray(String[]::new));
        var config = configurationWithIndexes(
            directory,
            databaseName,
            temporaryDirectory.resolve(name + ".csv"),
            temporaryDirectory.resolve(name + ".txt"),
            Phase.B,
            1,
            3,
            0,
            1,
            2,
            SecurityFilter.NONE);
        var failure = assertThrows(IllegalStateException.class,
            () -> SchemaCreationWorkload.proveIndexes(config, session));
        assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
      }
    }
  }

  private static Configuration parsedConfiguration(Map<String, String> values) {
    return Configuration.fromProperties(values::get, Policy.NONE, PropertyPath.SAFE);
  }

  private void assertObservedCommits(
      String name, Policy policy, int batchSize, int expectedCommits) throws IOException {
    var directory = temporaryDirectory.resolve(name);
    var csv = directory.resolve("results.csv");
    var phaseAListener = new SchemaCommitListener();
    SchemaCreationWorkload.run(configuration(
        directory.resolve("database"),
        name.replace('-', '_'),
        csv,
        directory.resolve("manifest-a.txt"),
        policy,
        batchSize,
        PropertyPath.SAFE,
        Phase.A,
        3,
        1,
        1), phaseAListener);
    assertEquals(expectedCommits, phaseAListener.commits,
        "database listener must observe phase-A top-level schema commits for " + policy);

    var phaseBListener = new SchemaCommitListener();
    SchemaCreationWorkload.run(configuration(
        directory.resolve("database"),
        name.replace('-', '_'),
        csv,
        directory.resolve("manifest-b.txt"),
        policy,
        batchSize,
        PropertyPath.SAFE,
        Phase.B,
        3,
        1,
        1), phaseBListener);
    assertEquals(expectedCommits, phaseBListener.commits,
        "database listener must observe phase-B top-level schema commits for " + policy);
  }

  private RunResult runCase(
      String name,
      Policy policy,
      int batchSize,
      PropertyPath propertyPath,
      int expectedTransactions,
      int expectedUnsafeProperties) throws IOException {
    return runCase(
        name,
        policy,
        batchSize,
        propertyPath,
        expectedTransactions,
        expectedUnsafeProperties,
        true);
  }

  private RunResult runCase(
      String name,
      Policy policy,
      int batchSize,
      PropertyPath propertyPath,
      int expectedTransactions,
      int expectedUnsafeProperties,
      boolean durabilityBarrier) throws IOException {
    var directory = temporaryDirectory.resolve(name);
    var csv = directory.resolve("results.csv");
    var result = SchemaCreationWorkload.run(configuration(
        directory.resolve("database"),
        name.replace('-', '_'),
        csv,
        directory.resolve("manifest.txt"),
        policy,
        batchSize,
        propertyPath,
        Phase.AB,
        3,
        3,
        3,
        durabilityBarrier));
    assertEquals(2, result.phases().size(), "AB must report one result for each phase");
    assertPhase(result, Phase.A, expectedTransactions, expectedUnsafeProperties);
    assertPhase(result, Phase.B, expectedTransactions, 0);
    for (var phaseResult : result.phases()) {
      assertEquals(durabilityBarrier, phaseResult.durabilityBarrierRan());
      if (durabilityBarrier) {
        assertTrue(phaseResult.durabilityBarrierNs() > 0,
            "an executed barrier must have a positive duration");
      } else {
        assertEquals(0, phaseResult.durabilityBarrierNs());
      }
    }
    assertTrue(result.shutdownNs() > 0, "shutdown must have a positive duration");
    assertCsvAndDetails(result, csv, 2, 6);
    return result;
  }

  private RunResult runCustomCase(String name, int classes, int properties, int indexes)
      throws IOException {
    var directory = temporaryDirectory.resolve(name);
    var csv = directory.resolve("results.csv");
    var result = SchemaCreationWorkload.run(configuration(
        directory.resolve("database"),
        name.replace('-', '_'),
        csv,
        directory.resolve("manifest.txt"),
        Policy.ALL,
        classes,
        PropertyPath.SAFE,
        Phase.AB,
        classes,
        properties,
        indexes));
    assertPhase(result, Phase.A, 1, 0);
    assertPhase(result, Phase.B, 1, 0);
    assertCsvAndDetails(result, csv, 2, classes * 2);
    return result;
  }

  private static void assertPhase(
      RunResult result, Phase phase, int transactions, int unsafeProperties) {
    var phaseResult = result.phases().stream()
        .filter(candidate -> candidate.phase() == phase)
        .findFirst()
        .orElseThrow();
    assertEquals(transactions, phaseResult.topLevelTransactions());
    assertEquals(unsafeProperties, phaseResult.unsafePropertyCreations());
    assertEquals(phaseResult.schemaWorkNs() + phaseResult.durabilityBarrierNs(),
        phaseResult.totalNs());
  }

  private static Configuration configurationWithIndexes(
      Path databasePath,
      String databaseName,
      Path csv,
      Path manifest,
      Phase phase,
      int classes,
      int properties,
      int indexes,
      int compositeIndexes,
      int compositeIndexWidth,
      SecurityFilter securityFilter) {
    return new Configuration(
        classes,
        properties,
        indexes,
        compositeIndexes,
        compositeIndexWidth,
        securityFilter,
        Policy.ALL,
        classes,
        PropertyPath.SAFE,
        phase,
        true,
        databasePath,
        databaseName,
        csv,
        manifest,
        true,
        true,
        "smoke");
  }

  private static Configuration configuration(
      Path databasePath,
      String databaseName,
      Path csv,
      Path manifest,
      Policy policy,
      int batchSize,
      PropertyPath propertyPath,
      Phase phase,
      int classes,
      int properties,
      int indexes) {
    return configuration(
        databasePath,
        databaseName,
        csv,
        manifest,
        policy,
        batchSize,
        propertyPath,
        phase,
        classes,
        properties,
        indexes,
        true);
  }

  private static Configuration configuration(
      Path databasePath,
      String databaseName,
      Path csv,
      Path manifest,
      Policy policy,
      int batchSize,
      PropertyPath propertyPath,
      Phase phase,
      int classes,
      int properties,
      int indexes,
      boolean durabilityBarrier) {
    return new Configuration(
        classes,
        properties,
        indexes,
        0,
        2,
        SecurityFilter.NONE,
        policy,
        batchSize,
        propertyPath,
        phase,
        true,
        databasePath,
        databaseName,
        csv,
        manifest,
        true,
        durabilityBarrier,
        "smoke");
  }

  private static void assertCsvAndDetails(
      RunResult result, Path csv, int expectedDataRows, int expectedDetailRows) throws IOException {
    var lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
    assertEquals(CSV_HEADER, lines.getFirst(), "CSV header must be written exactly once");
    assertEquals(expectedDataRows + 1, lines.size(), "CSV must contain one row per phase");
    assertEquals(1, lines.stream().filter(CSV_HEADER::equals).count(),
        "CSV header must occur exactly once");
    assertTrue(lines.stream().skip(1).allMatch(line -> csvField(line, 0).equals("2")),
        "every result row must identify CSV schema version 2");
    assertTrue(lines.stream().skip(1)
        .allMatch(line -> csvField(line, 26).equals(result.verificationState().name())),
        "verified runs must record their exact verification state");
    assertTrue(result.shutdownNs() > 0, "the returned shutdown time must be positive");
    for (var line : lines.stream()
        .skip(1)
        .filter(row -> csvField(row, 2).equals(result.runId()))
        .toList()) {
      var phase = Phase.valueOf(csvField(line, 7));
      var phaseResult = result.phases().stream()
          .filter(candidate -> candidate.phase() == phase)
          .findFirst()
          .orElseThrow();
      assertEquals(phaseResult.durabilityBarrierNs(), Long.parseLong(csvField(line, 16)));
      assertEquals(
          phaseResult.durabilityBarrierRan(), Boolean.parseBoolean(csvField(line, 17)));
      assertEquals(result.shutdownNs(), Long.parseLong(csvField(line, 19)));
      assertTrue(Long.parseLong(csvField(line, 19)) > 0,
          "the CSV shutdown time must be positive");
    }

    var details = Files.readAllLines(result.detailFile(), StandardCharsets.UTF_8);
    assertEquals(DETAIL_HEADER, details.getFirst());
    assertEquals(expectedDetailRows + 1, details.size());
    assertTrue(details.stream().skip(1).allMatch(line -> csvField(line, 0).equals("2")),
        "every detail row must identify CSV schema version 2");
    var currentRunDetailRows = result.phases().stream()
        .mapToInt(phase -> phase.timingDetails().size())
        .sum();
    assertEquals(currentRunDetailRows,
        details.stream().skip(1).filter(line -> line.contains(result.runId())).count());
  }

  private static String csvField(String row, int index) {
    var fields = row.substring(1, row.length() - 1).split("\"[,]\"", -1);
    return fields[index].replace("\"\"", "\"");
  }

  private static void assertIndexesUsableIndependently(
      Path databasePath, String databaseName, int classes, int indexes) {
    try (var manager = (YouTrackDBImpl) YourTracks.instance(databasePath.toString());
        var session = manager.open(databaseName, "admin", "admin")) {
      for (var classIndex = 0; classIndex < classes; classIndex++) {
        for (var indexNumber = 0; indexNumber < indexes; indexNumber++) {
          var indexName = "TestIndex_" + classIndex + "_" + indexNumber;
          var index = session.getSharedContext().getIndexManager().getIndex(session, indexName);
          assertNotNull(index, "index manager must publish " + indexName);
          assertTrue(index.getIndexId() >= 0, "index must have a real engine id");
          assertTrue(((AbstractStorage) session.getStorage()).loadIndexEngine(indexName) >= 0,
              "storage must load the index engine");
          var value = "value_" + classIndex + "_" + indexNumber;
          var found = session.computeInTx(
              tx -> index.getRids(session, value).findAny().isPresent());
          assertTrue(found, "index must find the independently queried proof value");
        }
      }
    }
  }

  private static final class SchemaCommitListener implements SessionListener {

    private int commits;

    @Override
    public void onAfterTxCommit(Transaction transaction, @Nullable Map<RID, RID> ridMapping) {
      if (transaction instanceof FrontendTransaction frontend
          && frontend.getCustomData(DatabaseSessionEmbedded.TX_SCHEMA_STATE_KEY) != null) {
        commits++;
      }
    }
  }

  private static void assertManifestsEqual(String expected, String actual) {
    if (expected.equals(actual)) {
      return;
    }
    var expectedLines = expected.lines().toList();
    var actualLines = actual.lines().toList();
    var commonLines = Math.min(expectedLines.size(), actualLines.size());
    for (var line = 0; line < commonLines; line++) {
      if (!expectedLines.get(line).equals(actualLines.get(line))) {
        fail("Manifest differs at line " + (line + 1) + ": expected <" + expectedLines.get(line)
            + "> but was <" + actualLines.get(line) + ">");
      }
    }
    fail("Manifest differs at line " + (commonLines + 1) + ": one manifest ended early");
  }
}
