package com.jetbrains.youtrackdb.benchmarks.pagelock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/** Boundary-only sampling and measured-iteration protocol accounting. */
public final class IterationCounters {

  private final CounterProbes probes;
  private final boolean unavailable;
  private Map<String, OptionalLong> before;
  private long pageSet;
  private long mutating;

  public IterationCounters(CounterProbes probes, boolean unavailable) {
    this.probes = probes;
    this.unavailable = unavailable;
    checkSide(probes.snapshot(), unavailable);
  }

  static void checkSide(Map<String, OptionalLong> values, boolean unavailable) {
    if (values.size() != 17 || values.values().stream()
        .anyMatch(value -> value.isPresent() == unavailable)) {
      throw new IllegalStateException(
          "Missing, partial, or unexpected counter API for expected side");
    }
  }

  public void start() {
    before = probes.snapshot();
  }

  public Map<String, String> finish(boolean measured) {
    var values = CounterProbes.iteration(before, probes.snapshot());
    if (measured && !unavailable) {
      add(values);
    }
    return values;
  }

  void add(Map<String, String> values) {
    values.forEach((name, value) -> {
      if (name.startsWith("commit.") && !name.equals("commit.ZERO_CHANGE")) {
        long count = Long.parseLong(value);
        if (count < 0) {
          throw new IllegalStateException("Counter decreased: " + name);
        }
        mutating += count;
        if (name.equals("commit.PAGE_SET")) {
          pageSet += count;
        }
      }
    });
  }

  /** Share for this boundary only. ZERO_CHANGE is not a mutating commit. */
  static String iterationShare(Map<String, String> values) {
    long total = 0;
    long pages = 0;
    for (var entry : values.entrySet()) {
      if (entry.getKey().startsWith("commit.")
          && !entry.getKey().equals("commit.ZERO_CHANGE")) {
        if (entry.getValue().equals("not available")) {
          return "not available";
        }
        long count = Long.parseLong(entry.getValue());
        total += count;
        if (entry.getKey().equals("commit.PAGE_SET")) {
          pages = count;
        }
      }
    }
    return total == 0 ? "not available" : Double.toString((double) pages / total);
  }

  public String share(boolean required) {
    if (unavailable) {
      return "not available";
    }
    double share = mutating == 0 ? 0 : (double) pageSet / mutating;
    if (required && (mutating == 0 || share < 0.99)) {
      throw new IllegalStateException("Invalid measured page-set share: " + share);
    }
    return Double.toString(share);
  }

  /** One JSON object per boundary. Values are strings, including unavailable measurements. */
  public static String json(Map<String, String> metadata, Map<String, String> counters) {
    var fields = new LinkedHashMap<>(metadata);
    fields.putAll(counters);
    return fields.entrySet().stream().map(e -> quote(e.getKey()) + ":" + quote(e.getValue()))
        .collect(Collectors.joining(",", "{", "}"));
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
  }
}
