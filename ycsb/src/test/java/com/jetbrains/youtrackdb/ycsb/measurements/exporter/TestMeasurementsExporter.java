/**
 * Copyright (c) 2015 Yahoo! Inc. All rights reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package com.jetbrains.youtrackdb.ycsb.measurements.exporter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrackdb.ycsb.generator.ZipfianGenerator;
import com.jetbrains.youtrackdb.ycsb.measurements.Measurements;
import com.jetbrains.youtrackdb.ycsb.measurements.OneMeasurementHistogram;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;
import org.junit.Test;

public class TestMeasurementsExporter {
  @Test
  public void testJSONArrayMeasurementsExporter() throws IOException {
    Properties props = new Properties();
    props.put(Measurements.MEASUREMENT_TYPE_PROPERTY, "histogram");
    props.put(OneMeasurementHistogram.VERBOSE_PROPERTY, "true");
    Measurements mm = new Measurements(props);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    JSONArrayMeasurementsExporter export = new JSONArrayMeasurementsExporter(out);

    long min = 5000;
    long max = 100000;
    ZipfianGenerator zipfian = new ZipfianGenerator(min, max);
    for (int i = 0; i < 1000; i++) {
      int rnd = zipfian.nextValue().intValue();
      mm.measure("UPDATE", rnd);
    }
    mm.exportMeasurements(export);
    export.close();

    ObjectMapper mapper = new ObjectMapper();
    JsonNode json = mapper.readTree(out.toString("UTF-8"));
    assertTrue(json.isArray());
    assertEquals("Operations", json.get(0).get("measurement").asText());
    assertEquals("MaxLatency(us)", json.get(4).get("measurement").asText());
    assertEquals("4", json.get(11).get("measurement").asText());
  }
}
