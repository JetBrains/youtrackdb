/**
 * Copyright (c) 2024-2026 JetBrains s.r.o. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jetbrains.youtrackdb.ycsb.workloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.ycsb.Client;
import com.jetbrains.youtrackdb.ycsb.generator.DiscreteGenerator;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.Test;

/**
 * Verifies that each workload property file, when merged with
 * workload-common.properties, produces a valid CoreWorkload configuration
 * with the expected operation mix.
 */
public class WorkloadPropertyFilesTest {

  /**
   * Resolves the workloads directory — works both from the ycsb module root
   * (Maven default) and from the repository root.
   */
  private static Path workloadsDir() {
    Path candidate = Paths.get("workloads");
    if (candidate.toFile().isDirectory()) {
      return candidate;
    }
    candidate = Paths.get("ycsb", "workloads");
    if (candidate.toFile().isDirectory()) {
      return candidate;
    }
    throw new AssertionError(
        "Cannot find workloads directory — expected ./workloads/ or ./ycsb/workloads/");
  }

  /** Loads common properties merged with per-workload properties. */
  private static Properties loadWorkload(String workloadName) throws IOException {
    Path dir = workloadsDir();
    Properties props = new Properties();
    try (FileInputStream common =
        new FileInputStream(dir.resolve("workload-common.properties").toFile())) {
      props.load(common);
    }
    try (FileInputStream wl =
        new FileInputStream(
            dir.resolve("workload-" + workloadName + ".properties").toFile())) {
      props.load(wl);
    }
    return props;
  }

  private enum Op {
    READ, UPDATE, INSERT, SCAN, READMODIFYWRITE
  }

  /**
   * Samples the operation generator N times and returns the observed
   * operation distribution as a map of operation type to count.
   */
  private static Map<Op, Integer> sampleOperations(Properties props, int samples) {
    DiscreteGenerator gen = CoreWorkload.createOperationGenerator(props);
    Map<Op, Integer> counts = new EnumMap<>(Op.class);
    for (Op op : Op.values()) {
      counts.put(op, 0);
    }
    for (int i = 0; i < samples; i++) {
      String opName = gen.nextString();
      Op op = Op.valueOf(opName);
      counts.merge(op, 1, Integer::sum);
    }
    return counts;
  }

  /**
   * Asserts that only the expected operations appear in the distribution
   * and that all expected operations are represented.
   */
  private static void assertOnlyOperations(
      Map<Op, Integer> counts, Set<Op> expected, int samples) {
    for (Op op : Op.values()) {
      if (expected.contains(op)) {
        assertTrue(
            "Expected operation " + op + " to appear but it did not",
            counts.get(op) > 0);
      } else {
        assertEquals(
            "Unexpected operation " + op + " appeared in distribution",
            0, (int) counts.get(op));
      }
    }
    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    assertEquals("Total sample count mismatch", samples, total);
  }

  // ---- Common property verification ----

  @Test
  public void commonPropertiesHaveRequiredFields() throws IOException {
    Properties props = loadWorkload("B");

    assertEquals(
        "com.jetbrains.youtrackdb.ycsb.workloads.CoreWorkload",
        props.getProperty(Client.WORKLOAD_PROPERTY));
    assertEquals(
        "com.jetbrains.youtrackdb.ycsb.binding.YouTrackDBYqlClient",
        props.getProperty(Client.DB_PROPERTY));
    assertEquals("3500000", props.getProperty(Client.RECORD_COUNT_PROPERTY));
    assertEquals("1000000", props.getProperty(Client.OPERATION_COUNT_PROPERTY));
    assertEquals("10", props.getProperty(CoreWorkload.FIELD_COUNT_PROPERTY));
    assertEquals("100", props.getProperty(CoreWorkload.FIELD_LENGTH_PROPERTY));
    assertEquals("usertable", props.getProperty(CoreWorkload.TABLENAME_PROPERTY));
    assertEquals("hdrhistogram", props.getProperty("measurementtype"));
    assertEquals("50,95,99,99.9", props.getProperty("hdrhistogram.percentiles"));
  }

  // ---- Per-workload operation mix tests ----

  @Test
  public void workloadB_readMostlyWith5PercentUpdate() throws IOException {
    Properties props = loadWorkload("B");
    Map<Op, Integer> counts = sampleOperations(props, 10_000);

    assertOnlyOperations(counts, Set.of(Op.READ, Op.UPDATE), 10_000);

    // With 95/5 split over 10K samples, READ should dominate
    double readRatio = counts.get(Op.READ) / 10_000.0;
    assertTrue(
        "Read ratio " + readRatio + " should be between 0.90 and 1.0",
        readRatio >= 0.90 && readRatio <= 1.0);
  }

  @Test
  public void workloadC_readOnly() throws IOException {
    Properties props = loadWorkload("C");
    Map<Op, Integer> counts = sampleOperations(props, 10_000);

    assertOnlyOperations(counts, Set.of(Op.READ), 10_000);
    assertEquals("All operations should be READ", 10_000, (int) counts.get(Op.READ));
  }

  @Test
  public void workloadE_scanHeavyWith5PercentInsert() throws IOException {
    Properties props = loadWorkload("E");
    Map<Op, Integer> counts = sampleOperations(props, 10_000);

    assertOnlyOperations(counts, Set.of(Op.SCAN, Op.INSERT), 10_000);

    double scanRatio = counts.get(Op.SCAN) / 10_000.0;
    assertTrue(
        "Scan ratio " + scanRatio + " should be between 0.90 and 1.0",
        scanRatio >= 0.90 && scanRatio <= 1.0);

    // Verify scan-specific properties
    assertEquals("100", props.getProperty(CoreWorkload.MAX_SCAN_LENGTH_PROPERTY));
    assertEquals(
        "uniform", props.getProperty(CoreWorkload.SCAN_LENGTH_DISTRIBUTION_PROPERTY));
  }

  @Test
  public void workloadW_balancedReadUpdate() throws IOException {
    Properties props = loadWorkload("W");
    Map<Op, Integer> counts = sampleOperations(props, 10_000);

    assertOnlyOperations(counts, Set.of(Op.READ, Op.UPDATE), 10_000);

    // With 50/50 split, both should be roughly equal
    double readRatio = counts.get(Op.READ) / 10_000.0;
    assertTrue(
        "Read ratio " + readRatio + " should be between 0.40 and 0.60",
        readRatio >= 0.40 && readRatio <= 0.60);
  }

  @Test
  public void workloadI_insertBurstWith20PercentRead() throws IOException {
    Properties props = loadWorkload("I");
    Map<Op, Integer> counts = sampleOperations(props, 10_000);

    assertOnlyOperations(counts, Set.of(Op.READ, Op.INSERT), 10_000);

    double insertRatio = counts.get(Op.INSERT) / 10_000.0;
    assertTrue(
        "Insert ratio " + insertRatio + " should be between 0.70 and 0.90",
        insertRatio >= 0.70 && insertRatio <= 0.90);
  }

  @Test
  public void allWorkloadsUseZipfianDistribution() throws IOException {
    for (String name : new String[] {"B", "C", "E", "W", "I"}) {
      Properties props = loadWorkload(name);
      assertEquals(
          "Workload " + name + " should use zipfian distribution",
          "zipfian",
          props.getProperty(CoreWorkload.REQUEST_DISTRIBUTION_PROPERTY));
    }
  }

  @Test
  public void perWorkloadPropertiesOverrideCommonDefaults() throws IOException {
    // Verify that per-workload files properly override the common defaults.
    // CoreWorkload has default readproportion=0.95. Workload I sets it to 0.2.
    Properties props = loadWorkload("I");
    assertEquals("0.2", props.getProperty(CoreWorkload.READ_PROPORTION_PROPERTY));

    // Workload C sets readproportion=1.0
    props = loadWorkload("C");
    assertEquals("1.0", props.getProperty(CoreWorkload.READ_PROPORTION_PROPERTY));
  }
}
