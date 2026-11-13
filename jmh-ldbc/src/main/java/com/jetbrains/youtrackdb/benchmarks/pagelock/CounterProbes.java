package com.jetbrains.youtrackdb.benchmarks.pagelock;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/** Resolve once at trial setup. Sample only at measured iteration boundaries. */
public final class CounterProbes {

  private static final String CACHE = "com.jetbrains.youtrackdb.internal.core.storage.cache.";
  private static final String ATOMIC =
      "com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.";
  private final Map<String, Probe> probes = new LinkedHashMap<>();

  private record Probe(Object target, Method method, Object[] arguments) {
    OptionalLong read() {
      return method == null ? OptionalLong.empty()
          : OptionalLong.of(((Number) invoke(method, target, arguments)).longValue());
    }
  }

  public CounterProbes(AbstractStorage storage) {
    enums("read.", CACHE + "OptimisticReadFailureCounters", "Cause", null,
        "STAMP EPOCH COORDINATES LOOKUP_MISS LOCAL_CHANGES FRAME_IDENTITY LOAD");
    Object protocols = optionalGetter(storage.getAtomicOperationsManager(),
        "getCommitProtocolCounters");
    enums("commit.", ATOMIC + "CommitProtocolCounters", "Outcome", protocols,
        "PAGE_SET FILE_LIFECYCLE OVER_LIMIT NO_PIN_BUDGET RESERVATION_DENIED FORCED ZERO_CHANGE");
    Object eviction = optionalGetter(storage.getReadCache(), "counters");
    scalar("cache.evictionStops", eviction, "evictionStops");
    scalar("cache.largestExcess", eviction, "largestExcess");
    scalar("cache.flushSkips", optionalGetter(storage.getWriteCache(), "pageCacheCounters"),
        "flushSkips");
  }

  void enums(String prefix, String type, String enumName, Object target, String names) {
    Class<?> owner = null;
    Class<?> enumType = null;
    Method count = null;
    try {
      owner = Class.forName(type);
      enumType = Class.forName(type + "$" + enumName);
      count = owner.getMethod("count", enumType);
    } catch (ClassNotFoundException | NoSuchMethodException absent) {
      // Only a missing API means not available. Invocation failures are fatal.
    }
    for (String name : names.split(" ")) {
      Object constant = null;
      if (count != null) {
        for (Object candidate : enumType.getEnumConstants()) {
          if (((Enum<?>) candidate).name().equals(name)) {
            constant = candidate;
          }
        }
        if (constant == null) {
          throw new IllegalStateException("Missing counter enum: " + type + "." + name);
        }
      }
      probes.put(prefix + name, new Probe(target, count, new Object[] {constant}));
    }
  }

  void scalar(String name, Object target, String method) {
    Method resolved = null;
    if (target != null) {
      try {
        resolved = target.getClass().getMethod(method);
      } catch (NoSuchMethodException absent) {
        // This build does not expose this measurement.
      }
    }
    probes.put(name, new Probe(target, resolved, new Object[0]));
  }

  private static Object optionalGetter(Object target, String name) {
    try {
      return invoke(target.getClass().getMethod(name), target, new Object[0]);
    } catch (NoSuchMethodException absent) {
      return null;
    }
  }

  static Object invoke(Method method, Object target, Object[] arguments) {
    try {
      return method.invoke(target, arguments);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException("Counter probe failed: " + method, e);
    }
  }

  public Map<String, OptionalLong> snapshot() {
    Map<String, OptionalLong> values = new LinkedHashMap<>();
    probes.forEach((name, probe) -> values.put(name, probe.read()));
    return values;
  }

  /**
   * Counters are deltas. largestExcess is a lifetime high-water mark in pages, not a delta.
   * Reopen storage between runs to reset storage counters.
   */
  public static Map<String, String> iteration(Map<String, OptionalLong> before,
      Map<String, OptionalLong> after) {
    Map<String, String> result = new LinkedHashMap<>();
    after.forEach((name, value) -> result.put(name,
        value.isEmpty() ? "not available" : Long.toString(
            name.equals("cache.largestExcess") ? value.getAsLong()
                : value.getAsLong() - before.get(name).orElseThrow())));
    return result;
  }
}
