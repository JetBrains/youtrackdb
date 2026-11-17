package com.jetbrains.youtrackdb.benchmarks.telemetry;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/// Separate launch entry point. No metadata work runs inside measured methods.
public final class SetupMetadata {

  private SetupMetadata() {
  }

  static Properties properties() {
    var values = new Properties();
    values.setProperty("count.1", "1");
    values.setProperty("count.10000", "10000");
    for (var path : List.of("sql", "translated", "fallback")) {
      values.setProperty(path + ".query", path.equals("sql") ? OffBenchmark.SQL
          : "g.V().hasLabel(\"" + OffBenchmark.LABEL + "\")");
      values.setProperty(path + ".data",
          "unindexed Fixture vertices, ordinal=0..rows-1, memory database");
      values.setProperty(path + ".path", path);
      values.setProperty(path + ".scenario", "off");
      values.setProperty(path + ".transaction", "open, consume and close, rollback");
      values.setProperty(path + ".cache", "query-result cache disabled");
    }
    return values;
  }

  public static void main(String[] args) throws Exception {
    try (OutputStream output = Files.newOutputStream(Path.of(args[0]))) {
      properties().storeToXML(output, "Candidate-reported fixture setup");
    }
  }
}
