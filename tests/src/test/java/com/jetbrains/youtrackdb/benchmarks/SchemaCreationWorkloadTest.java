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
import java.util.LinkedHashMap;
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
    assertTrue(barrierOffRows.stream().skip(1)
        .allMatch(row -> csvValue(CSV_HEADER, row, "durabilityBarrierNs").equals("0")));
    assertTrue(barrierOffRows.stream().skip(1)
        .allMatch(row -> csvValue(CSV_HEADER, row, "durabilityBarrierRan").equals("false")));

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
    assertTrue(widthTwo.functionalIndexProofRan(),
        "composite verification must report that its functional proof ran");

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

    var directory = temporaryDirectory.resolve("legacy-default-options");
    var legacyProperties = new LinkedHashMap<String, String>();
    legacyProperties.put("bench.classes", "1");
    legacyProperties.put("bench.properties", "3");
    legacyProperties.put("bench.indexes", "3");
    legacyProperties.put("bench.policy", "ALL");
    legacyProperties.put("bench.batchSize", "1");
    legacyProperties.put("bench.propertyPath", "SAFE");
    legacyProperties.put("bench.phase", "AB");
    legacyProperties.put("bench.uniquePropertyNames", "true");
    legacyProperties.put("bench.dbPath", directory.resolve("database").toString());
    legacyProperties.put("bench.dbName", "legacy_default_options");
    legacyProperties.put("bench.resultFile", directory.resolve("results.csv").toString());
    legacyProperties.put("bench.manifestFile", directory.resolve("manifest.txt").toString());
    legacyProperties.put("bench.verify", "true");
    legacyProperties.put("bench.durabilityBarrier", "true");
    legacyProperties.put("bench.label", "legacy");
    var parsedLegacy = Configuration.fromProperties(
        legacyProperties::get, Policy.NONE, PropertyPath.SAFE);
    assertEquals(0, parsedLegacy.compositeIndexes());
    assertEquals(2, parsedLegacy.compositeIndexWidth());
    assertEquals(SecurityFilter.NONE, parsedLegacy.securityFilter());

    var legacy = SchemaCreationWorkload.run(parsedLegacy);
    var expected = EXPECTED_MANIFEST.lines()
        .takeWhile(line -> !line.equals("class TestClass1"))
        .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    assertEquals(expected, legacy.manifestText().orElseThrow(),
        "the parsed legacy command must preserve its manifest byte for byte");
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
  void preAppendHeaderGuardRejectsMismatchIntroducedAfterPreflight() throws IOException {
    var lateMismatch = temporaryDirectory.resolve("late-header-mismatch.csv");
    Files.writeString(lateMismatch, CSV_HEADER + "\n", StandardCharsets.UTF_8);
    var mismatch = assertThrows(IOException.class,
        () -> SchemaCreationWorkload.appendRow(
            lateMismatch, "changed-after-preflight", List.of("value")));
    assertTrue(mismatch.getMessage().contains(CSV_HEADER));
    assertTrue(mismatch.getMessage().contains("changed-after-preflight"));
  }

  @Test
  void preExistingEmptyResultFileReceivesHeaderAndRows() throws IOException {
    var directory = temporaryDirectory.resolve("empty-result-file");
    Files.createDirectories(directory);
    var emptyResult = directory.resolve("results.csv");
    Files.createFile(emptyResult);
    var result = SchemaCreationWorkload.run(configuration(
        directory.resolve("database"),
        "empty_result_file",
        emptyResult,
        directory.resolve("manifest.txt"),
        Policy.ALL,
        1,
        PropertyPath.SAFE,
        Phase.AB,
        1,
        1,
        1));
    assertCsvAndDetails(result, emptyResult, 2, 2);
  }

  @Test
  void preExistingEmptyDetailFileReceivesHeaderAndRows() throws IOException {
    var directory = temporaryDirectory.resolve("empty-detail-file");
    Files.createDirectories(directory);
    var resultFile = directory.resolve("results.csv");
    var emptyDetail = directory.resolve("results.csv.detail.csv");
    Files.writeString(resultFile, CSV_HEADER + "\n", StandardCharsets.UTF_8);
    Files.createFile(emptyDetail);
    var result = SchemaCreationWorkload.run(configuration(
        directory.resolve("database"),
        "empty_detail_file",
        resultFile,
        directory.resolve("manifest.txt"),
        Policy.ALL,
        1,
        PropertyPath.SAFE,
        Phase.AB,
        1,
        1,
        1));
    assertCsvAndDetails(result, resultFile, 2, 2);
  }

  @Test
  void appendAfterUnterminatedLastLineKeepsRecordsSeparate() throws IOException {
    var missingNewline = temporaryDirectory.resolve("missing-newline.csv");
    Files.writeString(
        missingNewline, CSV_HEADER + "\n\"existing\"", StandardCharsets.UTF_8);
    SchemaCreationWorkload.appendRow(missingNewline, CSV_HEADER, List.of("next"));
    var separatedLines = Files.readAllLines(missingNewline, StandardCharsets.UTF_8);
    assertEquals(3, separatedLines.size(),
        "append must add a separator after an unterminated existing record");
    assertEquals("\"existing\"", separatedLines.get(1));
    assertEquals("\"next\"", separatedLines.get(2));
  }

  @Test
  void resultFilesRejectCrossStratumAppendsButAllowSameStratumSweeps() throws IOException {
    var plainDirectory = temporaryDirectory.resolve("plain-stratum");
    var plainResult = plainDirectory.resolve("results.csv");
    SchemaCreationWorkload.run(configuration(
        plainDirectory.resolve("database-one"),
        "plain_one",
        plainResult,
        plainDirectory.resolve("manifest-one.txt"),
        Policy.ALL,
        1,
        PropertyPath.SAFE,
        Phase.AB,
        1,
        2,
        1));
    SchemaCreationWorkload.run(configuration(
        plainDirectory.resolve("database-two"),
        "plain_two",
        plainResult,
        plainDirectory.resolve("manifest-two.txt"),
        Policy.ALL,
        1,
        PropertyPath.SAFE,
        Phase.AB,
        1,
        2,
        2));
    assertEquals(5, Files.readAllLines(plainResult, StandardCharsets.UTF_8).size(),
        "plain sweeps must append within their stratum");

    var mixedDirectory = temporaryDirectory.resolve("mixed-stratum-attempt");
    var mixedConfig = configurationWithIndexes(
        mixedDirectory.resolve("database"),
        "mixed_stratum_attempt",
        plainResult,
        mixedDirectory.resolve("manifest.txt"),
        Phase.AB,
        1,
        2,
        0,
        1,
        2,
        SecurityFilter.NONE);
    var mixedFailure = assertThrows(IOException.class,
        () -> SchemaCreationWorkload.run(mixedConfig));
    assertTrue(mixedFailure.getMessage().contains(plainResult.toString()));
    assertTrue(mixedFailure.getMessage().contains("compositeIndexes=1"));
    assertTrue(mixedFailure.getMessage().contains("securityFilter=NONE"));
    assertTrue(mixedFailure.getMessage().contains("compositeIndexes=0"));
    assertFalse(Files.exists(mixedDirectory.resolve("database")),
        "stratum validation must reject the run before database work");

    var extendedDirectory = temporaryDirectory.resolve("extended-stratum");
    var extendedResult = extendedDirectory.resolve("results.csv");
    SchemaCreationWorkload.run(configurationWithIndexes(
        extendedDirectory.resolve("database-control"),
        "extended_control",
        extendedResult,
        extendedDirectory.resolve("manifest-control.txt"),
        Phase.AB,
        1,
        2,
        0,
        1,
        2,
        SecurityFilter.NONE));
    SchemaCreationWorkload.run(configurationWithIndexes(
        extendedDirectory.resolve("database-filtered"),
        "extended_filtered",
        extendedResult,
        extendedDirectory.resolve("manifest-filtered.txt"),
        Phase.AB,
        1,
        2,
        0,
        1,
        2,
        SecurityFilter.UNRELATED));
    assertEquals(5, Files.readAllLines(extendedResult, StandardCharsets.UTF_8).size(),
        "paired composite control and filtered runs must share the new stratum");
  }

  @Test
  void noneSecurityModeRejectsFiltersPersistedByAnEarlierRun() throws IOException {
    var filtered = runCompositeCase(
        "persisted-filter-source", 1, 2, 0, 1, 2, SecurityFilter.UNRELATED);
    var retryResult = temporaryDirectory.resolve("persisted-filter-retry/results.csv");
    var retry = configuration(
        temporaryDirectory.resolve("persisted-filter-source/database"),
        "persisted_filter_source",
        retryResult,
        temporaryDirectory.resolve("persisted-filter-retry/manifest.txt"),
        Policy.ALL,
        1,
        PropertyPath.SAFE,
        Phase.B,
        1,
        2,
        0);
    var failure = assertThrows(IllegalStateException.class,
        () -> SchemaCreationWorkload.run(retry));
    assertTrue(failure.getMessage().contains("bench.securityFilter=NONE"));
    assertTrue(failure.getMessage().contains("TestClass0.SecurityUnrelatedProperty"));
    assertFalse(Files.exists(retryResult),
        "a misleading NONE result row must not be written");
    assertEquals(VerificationState.COMPLETED_COMPOSITE_INDEX_PROOF,
        filtered.verificationState());
  }

  @Test
  void unrelatedSecurityModeRequiresTheReaderRole() throws IOException {
    var directory = temporaryDirectory.resolve("missing-reader-role");
    var resultFile = directory.resolve("results.csv");
    var phaseA = configurationWithIndexes(
        directory.resolve("database"),
        "missing_reader_role",
        resultFile,
        directory.resolve("manifest-a.txt"),
        Phase.A,
        1,
        2,
        0,
        1,
        2,
        SecurityFilter.UNRELATED);
    SchemaCreationWorkload.run(phaseA);
    try (var manager = (YouTrackDBImpl) YourTracks.instance(
        directory.resolve("database").toString());
        var session = manager.open("missing_reader_role", "admin", "admin")) {
      assertTrue(session.getSharedContext().getSecurity().dropRole(session, "reader"));
    }

    var phaseB = configurationWithIndexes(
        directory.resolve("database"),
        "missing_reader_role",
        resultFile,
        directory.resolve("manifest-b.txt"),
        Phase.B,
        1,
        2,
        0,
        1,
        2,
        SecurityFilter.UNRELATED);
    var failure = assertThrows(IllegalStateException.class,
        () -> SchemaCreationWorkload.run(phaseB));
    assertTrue(failure.getMessage().contains(
        "bench.securityFilter=UNRELATED requires the reader security role"));
  }

  @Test
  void unrelatedSecurityRulesAreInstalledBeforeCompositeIndexCommit() throws IOException {
    var directory = temporaryDirectory.resolve("unrelated-security");
    var resultFile = directory.resolve("results.csv");
    var configuration = configurationWithIndexes(
        directory.resolve("database"),
        "unrelated_security",
        resultFile,
        directory.resolve("manifest.txt"),
        Phase.AB,
        2,
        4,
        1,
        1,
        2,
        SecurityFilter.UNRELATED);
    var orderingListener = new SecurityOrderingListener();
    var result = SchemaCreationWorkload.run(configuration, orderingListener);
    assertEquals(
        VerificationState.COMPLETED_COMPOSITE_INDEX_PROOF, result.verificationState());
    assertTrue(result.functionalIndexProofRan());
    assertTrue(orderingListener.filtersPresentBeforeIndexCommit,
        "property security filters must be visible before the phase-B schema commit");
    assertCsvAndDetails(result, resultFile, 2, 4, 1, 2, SecurityFilter.UNRELATED);

    var databasePath = directory.resolve("database");
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
  void compositeValidationRejectsEveryNonCompositeBoundary() throws IOException {
    assertInvalidCompositeConfiguration(
        "negative-composite-count", 2, 0, -1, 2,
        "bench.compositeIndexes must not be negative: -1");
    assertInvalidCompositeConfiguration(
        "zero-composite-width", 2, 0, 1, 0,
        "bench.compositeIndexWidth must be at least two: 0");
    assertInvalidCompositeConfiguration(
        "single-composite-width", 2, 0, 1, 1,
        "bench.compositeIndexWidth must be at least two: 1");
    assertInvalidCompositeConfiguration(
        "width-exceeds-properties", 2, 0, 1, 3,
        "bench.compositeIndexWidth=3 must not exceed bench.properties=2");
    assertInvalidCompositeConfiguration(
        "zero-properties-single-index", 0, 1, 0, 2,
        "bench.indexes=1 must be between zero and bench.properties=0");
    assertInvalidCompositeConfiguration(
        "zero-properties-composite-index", 0, 0, 1, 2,
        "bench.compositeIndexWidth=2 must not exceed bench.properties=0");

    var zeroPropertiesDirectory = temporaryDirectory.resolve("zero-properties-no-indexes");
    var validZero = SchemaCreationWorkload.run(configurationWithIndexes(
        zeroPropertiesDirectory.resolve("database"),
        "zero_properties_no_indexes",
        zeroPropertiesDirectory.resolve("results.csv"),
        zeroPropertiesDirectory.resolve("manifest.txt"),
        Phase.AB,
        1,
        0,
        0,
        0,
        2,
        SecurityFilter.NONE));
    assertFalse(validZero.manifestText().orElseThrow().contains("property"));
    assertFalse(validZero.manifestText().orElseThrow().contains("index"));
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

  private void assertInvalidCompositeConfiguration(
      String name,
      int properties,
      int indexes,
      int compositeIndexes,
      int compositeIndexWidth,
      String expectedMessage) {
    var directory = temporaryDirectory.resolve(name);
    var invalid = configurationWithIndexes(
        directory.resolve("database"),
        name.replace('-', '_'),
        directory.resolve("results.csv"),
        directory.resolve("manifest.txt"),
        Phase.AB,
        1,
        properties,
        indexes,
        compositeIndexes,
        compositeIndexWidth,
        SecurityFilter.NONE);
    var failure = assertThrows(IllegalArgumentException.class,
        () -> SchemaCreationWorkload.run(invalid));
    assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
    assertFalse(Files.exists(directory.resolve("database")),
        "invalid dimensions must fail before database work");
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
    assertTrue(result.functionalIndexProofRan(),
        "verified phase B must report its functional index proof");
    assertCsvAndDetails(
        result,
        resultFile,
        2,
        classes * 2,
        compositeIndexes,
        compositeIndexWidth,
        securityFilter);
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
    assertCsvAndDetails(
        result,
        csv,
        expectedDataRows,
        expectedDetailRows,
        0,
        2,
        SecurityFilter.NONE);
  }

  private static void assertCsvAndDetails(
      RunResult result,
      Path csv,
      int expectedDataRows,
      int expectedDetailRows,
      int expectedCompositeIndexes,
      int expectedCompositeIndexWidth,
      SecurityFilter expectedSecurityFilter) throws IOException {
    var lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
    assertEquals(CSV_HEADER, lines.getFirst(), "CSV header must be written exactly once");
    assertEquals(expectedDataRows + 1, lines.size(), "CSV must contain one row per phase");
    assertEquals(1, lines.stream().filter(CSV_HEADER::equals).count(),
        "CSV header must occur exactly once");
    var records = lines.stream().skip(1)
        .map(line -> csvRecord(CSV_HEADER, line))
        .toList();
    assertTrue(records.stream().allMatch(record -> record.get("schemaVersion").equals("2")),
        "every result row must identify CSV schema version 2");
    assertTrue(records.stream().allMatch(record -> record.get("compositeIndexes").equals(
        Integer.toString(expectedCompositeIndexes))),
        "every result row must record the requested composite-index count");
    assertTrue(records.stream().allMatch(record -> record.get("compositeIndexWidth").equals(
        Integer.toString(expectedCompositeIndexWidth))),
        "every result row must record the requested composite-index width");
    assertTrue(records.stream().allMatch(record -> record.get("securityFilter").equals(
        expectedSecurityFilter.name())),
        "every result row must record the requested security-filter mode");
    assertTrue(records.stream().allMatch(record -> record.get("verificationState").equals(
        result.verificationState().name())),
        "verified runs must record their exact verification state");
    assertTrue(result.shutdownNs() > 0, "the returned shutdown time must be positive");
    for (var record : records.stream()
        .filter(row -> row.get("runId").equals(result.runId()))
        .toList()) {
      var phase = Phase.valueOf(record.get("phase"));
      var phaseResult = result.phases().stream()
          .filter(candidate -> candidate.phase() == phase)
          .findFirst()
          .orElseThrow();
      assertEquals(
          phaseResult.durabilityBarrierNs(),
          Long.parseLong(record.get("durabilityBarrierNs")));
      assertEquals(
          phaseResult.durabilityBarrierRan(),
          Boolean.parseBoolean(record.get("durabilityBarrierRan")));
      assertEquals(result.shutdownNs(), Long.parseLong(record.get("shutdownNs")));
      assertTrue(Long.parseLong(record.get("shutdownNs")) > 0,
          "the CSV shutdown time must be positive");
    }

    var details = Files.readAllLines(result.detailFile(), StandardCharsets.UTF_8);
    assertEquals(DETAIL_HEADER, details.getFirst());
    assertEquals(expectedDetailRows + 1, details.size());
    var detailRecords = details.stream().skip(1)
        .map(line -> csvRecord(DETAIL_HEADER, line))
        .toList();
    assertTrue(detailRecords.stream().allMatch(record -> record.get("schemaVersion").equals("2")),
        "every detail row must identify CSV schema version 2");
    var currentRunDetailRows = result.phases().stream()
        .mapToInt(phase -> phase.timingDetails().size())
        .sum();
    assertEquals(
        currentRunDetailRows,
        detailRecords.stream().filter(record -> record.get("runId").equals(result.runId()))
            .count());
  }

  private static String csvValue(String header, String row, String column) {
    return csvRecord(header, row).get(column);
  }

  private static Map<String, String> csvRecord(String header, String row) {
    var columns = List.of(header.split(",", -1));
    var values = parseCsvValues(row);
    assertEquals(columns.size(), values.size(), "CSV row must match its header");
    var record = new LinkedHashMap<String, String>();
    for (var index = 0; index < columns.size(); index++) {
      record.put(columns.get(index), values.get(index));
    }
    return record;
  }

  private static List<String> parseCsvValues(String row) {
    var values = new ArrayList<String>();
    var value = new StringBuilder();
    var quoted = false;
    for (var index = 0; index < row.length(); index++) {
      var character = row.charAt(index);
      if (character == '"') {
        if (quoted && index + 1 < row.length() && row.charAt(index + 1) == '"') {
          value.append('"');
          index++;
        } else {
          quoted = !quoted;
        }
      } else if (character == ',' && !quoted) {
        values.add(value.toString());
        value.setLength(0);
      } else {
        value.append(character);
      }
    }
    assertFalse(quoted, "CSV row must not contain an unterminated quote");
    values.add(value.toString());
    return values;
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

  private static final class SecurityOrderingListener implements SessionListener {

    private int schemaCommits;
    private boolean filtersPresentBeforeIndexCommit;

    @Override
    public void onBeforeTxCommit(Transaction transaction) {
      if (!(transaction instanceof FrontendTransaction frontend)
          || frontend.getCustomData(DatabaseSessionEmbedded.TX_SCHEMA_STATE_KEY) == null) {
        return;
      }
      schemaCommits++;
      if (schemaCommits == 2) {
        filtersPresentBeforeIndexCommit =
            frontend.getDatabaseSession().getSharedContext().getSecurity()
                .getAllFilteredProperties(frontend.getDatabaseSession()).stream()
                .anyMatch(resource -> "TestClass0".equals(resource.getClassName())
                    && "SecurityUnrelatedProperty".equals(resource.getPropertyName()));
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
