package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements LDBC-style parameter curation for JMH benchmarks.
 *
 * <p>The core idea: pre-select query parameters that produce similar execution
 * difficulty, so benchmark variance comes from the database engine — not from
 * wildly different parameter neighborhoods. This eliminates the need for
 * extremely long measurement iterations to average out parameter sensitivity.
 *
 * <p>The algorithm:
 * <ol>
 *   <li>Compute factor tables (e.g., friend count per person) from the DB</li>
 *   <li>Sort entities by the factor metric</li>
 *   <li>Use gap-based grouping to find clusters of similar-difficulty entities</li>
 *   <li>Pick the cluster with the lowest standard deviation</li>
 *   <li>Cross-join curated pools to produce per-query parameter arrays</li>
 * </ol>
 *
 * <p>Factor tables are cached to a JSON file alongside the database so they are
 * computed only once and reused across JMH forks.
 *
 * @see <a href="https://github.com/ldbc/ldbc_snb_interactive_v2_driver/tree/main/paramgen">
 *     LDBC paramgen source</a>
 */
final class ParameterCurator {

  private static final Logger log = LoggerFactory.getLogger(ParameterCurator.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FACTOR_CACHE_FILE = "factor-tables.json";
  // Bump version suffix when curation logic changes to invalidate old caches
  private static final String CURATED_PARAMS_CACHE_FILE = "curated-params-v3.json";

  // System property that must be set to allow regeneration of curated parameters.
  // Without this flag, missing canonical params cause a hard failure instead of
  // silently regenerating — preventing accidental parameter desync between runs.
  // Pass -Dldbc.allow.param.generation=true to enable (e.g. when updating canonical params).
  static final String ALLOW_PARAM_GENERATION_PROP = "ldbc.allow.param.generation";

  // Target number of curated parameter tuples per query
  static final int PARAMS_PER_QUERY = 500;

  // Number of (person, date) pairs to sample for IC4 factor computation
  private static final int IC4_SAMPLE_SIZE = 200;

  private ParameterCurator() {
  }

  // ==================== FACTOR TABLE STRUCTURES ====================

  /** A (entityKey, metricValue) pair from a factor table. */
  record FactorEntry<K>(K key, long metric) {
  }

  /** Cached factor tables persisted to JSON. */
  record FactorTables(
      List<long[]> personFriendCounts,
      List<long[]> personFoFCounts,
      List<long[]> personFoFoFCounts,
      List<long[]> firstNameFrequencies,
      List<long[]> creationDayMessageCounts,
      List<String[]> tagPersonCounts,
      List<String[]> tagClassNames,
      List<String[]> countryPairs) {
  }

  // ==================== CURATED PARAMETER SETS ====================

  /** Curated parameter tuple for a specific query. */
  record Ic1Params(long personId, String firstName) {
  }

  record Ic3Params(long personId, String countryX, String countryY,
      Date startDate) {
  }

  record Ic4Params(long personId, Date startDate) {
  }

  record Ic6Params(long personId, String tagName) {
  }

  record Ic9Params(long personId, Date maxDate) {
  }

  record Ic12Params(long personId, String tagClassName) {
  }

  record PersonDateParams(long personId, Date maxDate) {
  }

  /** All curated parameter arrays for all queries. */
  record CuratedParams(
      // IS queries — simple person/message pools
      long[] isPersonIds,
      long[] isMessageIds,
      // IC queries — per-query tuples
      Ic1Params[] ic1,
      PersonDateParams[] ic2,
      Ic3Params[] ic3,
      Ic4Params[] ic4,
      long[] ic5PersonIds,
      Ic6Params[] ic6,
      long[] ic7PersonIds,
      long[] ic8PersonIds,
      Ic9Params[] ic9,
      long[] ic10PersonIds,
      long[] ic11PersonIds,
      String[] ic11CountryNames,
      Ic12Params[] ic12,
      long[] ic13PersonIds1,
      long[] ic13PersonIds2,
      Date[] dates) {
  }

  // ==================== PUBLIC API ====================

  /**
   * Computes or loads curated parameters for all LDBC queries.
   *
   * <p>Curated parameters are cached to a JSON file alongside the database so
   * they are computed only once and reused across JMH forks. This eliminates
   * ~2.3 hours of overhead from re-running expensive SQL queries (especially
   * the Message GROUP BY creation day query at ~43s) on every fork.
   *
   * <p>The cache is invalidated when the factor tables file is newer, which
   * indicates that the underlying data has changed.
   *
   * @param state the benchmark state (for executing SQL queries)
   * @param dbPath path to the database directory (for caching)
   * @return curated parameter arrays for all queries
   */
  static CuratedParams curate(LdbcBenchmarkState state, Path dbPath) {
    Path cacheFile = dbPath.resolve(CURATED_PARAMS_CACHE_FILE);
    Path factorCacheFile = dbPath.resolve(FACTOR_CACHE_FILE);

    if (Files.exists(cacheFile)) {
      try {
        // Invalidate if factor tables are newer than curated params
        if (Files.exists(factorCacheFile)
            && Files.getLastModifiedTime(factorCacheFile)
                .compareTo(Files.getLastModifiedTime(cacheFile)) > 0) {
          log.info("Factor tables newer than curated params cache,"
              + " regeneration required.");
        } else {
          log.info("Loading cached curated params from: {}", cacheFile);
          CuratedParams cached = loadCuratedParamsFromJson(cacheFile);
          log.info("Curated params loaded from cache ({} IC1, {} IC4 params)",
              cached.ic1().length, cached.ic4().length);
          return cached;
        }
      } catch (Exception e) {
        log.warn("Failed to load curated params cache: {}", e.getMessage());
      }
    }

    // Canonical curated params are missing or stale — regeneration is needed.
    // Require an explicit flag to prevent accidental parameter desync.
    boolean allowGeneration =
        Boolean.getBoolean(ALLOW_PARAM_GENERATION_PROP);
    if (!allowGeneration) {
      throw new IllegalStateException(
          "Curated parameters missing or stale at " + cacheFile + " and regeneration"
              + " is not allowed. If you downloaded canonical params from S3,"
              + " ensure curated-params was installed after factor-tables so its"
              + " mtime is not older. Otherwise, download canonical curated params"
              + " (ldbc/curated-params-v3.json, ldbc/factor-tables.json) and"
              + " install them into the DB directory. To regenerate locally,"
              + " pass -D" + ALLOW_PARAM_GENERATION_PROP + "=true");
    }

    log.warn("Regenerating curated parameters ({}=true)",
        ALLOW_PARAM_GENERATION_PROP);
    FactorTables factors = loadOrComputeFactors(state, dbPath);
    CuratedParams params = generateParams(factors, state);

    try {
      saveCuratedParamsToJson(params, cacheFile);
      log.info("Curated params cached to: {}", cacheFile);
    } catch (IOException e) {
      log.warn("Failed to cache curated params: {}", e.getMessage());
    }

    return params;
  }

  // ==================== FACTOR TABLE COMPUTATION ====================

  private static FactorTables loadOrComputeFactors(
      LdbcBenchmarkState state, Path dbPath) {
    Path cacheFile = dbPath.resolve(FACTOR_CACHE_FILE);
    if (Files.exists(cacheFile)) {
      try {
        log.info("Loading cached factor tables from: {}", cacheFile);
        FactorTables cached = loadFactorTablesFromJson(cacheFile);
        log.info("Factor tables loaded from cache");
        return cached;
      } catch (Exception e) {
        log.warn("Failed to load factor cache: {}", e.getMessage());
      }
    }

    // Factor tables are missing — the caller already checked the allow flag,
    // so we can proceed with computation here.
    log.info("Computing factor tables from database...");
    long start = System.currentTimeMillis();
    FactorTables factors = computeFactorTables(state);
    long elapsed = System.currentTimeMillis() - start;
    log.info("Factor tables computed in {}s", elapsed / 1000.0);

    try {
      saveFactorTablesToJson(factors, cacheFile);
      log.info("Factor tables cached to: {}", cacheFile);
    } catch (IOException e) {
      log.warn("Failed to cache factor tables: {}", e.getMessage());
    }

    return factors;
  }

  @SuppressWarnings("unchecked")
  private static FactorTables computeFactorTables(LdbcBenchmarkState state) {
    // 1. Person friend count: SELECT id, out('KNOWS').size() as cnt FROM Person
    log.info("  Computing person friend counts...");
    List<Map<String, Object>> friendRows = state.executeSql(
        "SELECT id, out('KNOWS').size() as cnt FROM Person");
    List<long[]> personFriendCounts = friendRows.stream()
        .map(r -> new long[] {
            ((Number) r.get("id")).longValue(),
            ((Number) r.get("cnt")).longValue()
        })
        .toList();
    log.info("  {} persons with friend counts", personFriendCounts.size());

    // 2. Person FoF count (2-hop KNOWS)
    // For performance, compute via SQL aggregation per person
    log.info("  Computing person FoF counts (2-hop)...");
    List<Map<String, Object>> fofRows = state.executeSql(
        "SELECT id, out('KNOWS').out('KNOWS').size() as cnt FROM Person");
    List<long[]> personFoFCounts = fofRows.stream()
        .map(r -> new long[] {
            ((Number) r.get("id")).longValue(),
            ((Number) r.get("cnt")).longValue()
        })
        .toList();
    log.info("  {} persons with FoF counts", personFoFCounts.size());

    // 3. Person FoFoF count (3-hop KNOWS)
    log.info("  Computing person FoFoF counts (3-hop)...");
    List<Map<String, Object>> fofofRows = state.executeSql(
        "SELECT id, out('KNOWS').out('KNOWS').out('KNOWS').size() as cnt"
            + " FROM Person");
    List<long[]> personFoFoFCounts = fofofRows.stream()
        .map(r -> new long[] {
            ((Number) r.get("id")).longValue(),
            ((Number) r.get("cnt")).longValue()
        })
        .toList();
    log.info("  {} persons with FoFoF counts", personFoFoFCounts.size());

    // 4. First name frequency
    log.info("  Computing first name frequencies...");
    List<Map<String, Object>> nameRows = state.executeSql(
        "SELECT firstName, count(*) as cnt FROM Person GROUP BY firstName");
    List<long[]> firstNameFreqs = new ArrayList<>();
    List<String> nameList = new ArrayList<>();
    for (var r : nameRows) {
      nameList.add(r.get("firstName").toString());
      firstNameFreqs.add(new long[] {
          nameList.size() - 1,
          ((Number) r.get("cnt")).longValue()
      });
    }
    // Store name-index pairs for firstNameFreqs, actual names in separate list
    log.info("  {} distinct first names", nameList.size());

    // 5. Creation day message counts
    log.info("  Computing creation day message counts...");
    List<Map<String, Object>> dayRows = state.executeSql(
        "SELECT creationDate.format('yyyy-MM-dd') as day, count(*) as cnt"
            + " FROM Message GROUP BY creationDate.format('yyyy-MM-dd')");
    List<long[]> creationDayCounts = new ArrayList<>();
    List<String> dayList = new ArrayList<>();
    for (var r : dayRows) {
      dayList.add(r.get("day").toString());
      creationDayCounts.add(new long[] {
          dayList.size() - 1,
          ((Number) r.get("cnt")).longValue()
      });
    }
    log.info("  {} distinct creation days", dayList.size());

    // 6. Tag person count (how many persons have each tag as interest)
    log.info("  Computing tag person counts...");
    List<Map<String, Object>> tagRows = state.executeSql(
        "SELECT name, in('HAS_INTEREST').size() as cnt FROM Tag");
    List<String[]> tagPersonCounts = tagRows.stream()
        .map(r -> new String[] {
            r.get("name").toString(),
            String.valueOf(((Number) r.get("cnt")).longValue())
        })
        .toList();
    log.info("  {} tags with person counts", tagPersonCounts.size());

    // 7. TagClass names
    log.info("  Collecting tag class names...");
    List<Map<String, Object>> tcRows = state.executeSql(
        "SELECT DISTINCT(name) as name FROM TagClass");
    List<String[]> tagClassNames = tcRows.stream()
        .map(r -> new String[] {r.get("name").toString()})
        .toList();
    log.info("  {} tag classes", tagClassNames.size());

    // 8. Country pairs (for IC3) — enumerate distinct country pairs
    //    where cross-border friendships exist
    log.info("  Computing country pairs with cross-border friendships...");
    List<Map<String, Object>> countryPairRows = state.executeSql(
        "SELECT p1City.out('IS_PART_OF').name as c1,"
            + " p2City.out('IS_PART_OF').name as c2, count(*) as cnt"
            + " FROM ("
            + "   SELECT out('IS_LOCATED_IN') as p1City,"
            + "          out('KNOWS').out('IS_LOCATED_IN') as p2City"
            + "   FROM Person UNWIND p2City"
            + " )"
            + " WHERE c1 <> c2"
            + " GROUP BY c1, c2"
            + " ORDER BY cnt DESC"
            + " LIMIT 100");
    List<String[]> countryPairs;
    if (countryPairRows.isEmpty()) {
      // Fallback: just use pairs of countries
      log.info("  Cross-border friendship query returned no results, using"
          + " fallback country pairs");
      List<Map<String, Object>> countries = state.executeSql(
          "SELECT name FROM Place WHERE type = 'Country'");
      countryPairs = new ArrayList<>();
      var cNames = countries.stream()
          .map(r -> r.get("name").toString()).toList();
      for (int i = 0; i < cNames.size() && countryPairs.size() < 100; i++) {
        for (int j = i + 1; j < cNames.size()
            && countryPairs.size() < 100; j++) {
          countryPairs.add(new String[] {cNames.get(i), cNames.get(j)});
        }
      }
    } else {
      countryPairs = countryPairRows.stream()
          .map(r -> new String[] {
              r.get("c1").toString(),
              r.get("c2").toString()
          })
          .toList();
    }
    log.info("  {} country pairs", countryPairs.size());

    // Encode firstNameFreqs with actual names embedded
    // Store as [nameIndex, freq] but we need names accessible later,
    // so we'll store names as String[] arrays in the factor table
    List<long[]> nameFreqs = new ArrayList<>();
    for (int i = 0; i < nameList.size(); i++) {
      nameFreqs.add(new long[] {i, firstNameFreqs.get(i)[1]});
    }

    // Store day-index pairs similarly
    List<long[]> dayCounts = new ArrayList<>();
    for (int i = 0; i < dayList.size(); i++) {
      dayCounts.add(new long[] {i, creationDayCounts.get(i)[1]});
    }

    // We need to persist the string lookup tables alongside the numeric
    // factor arrays. Pack them into the String[] arrays:
    // firstNameFrequencies: index -> [nameIndex, freq] (nameList via separate field)
    // creationDayMessageCounts: index -> [dayIndex, freq] (dayList via separate field)

    // For serialization, store name/day strings as tagPersonCounts-style arrays
    List<String[]> nameFreqStrings = new ArrayList<>();
    for (int i = 0; i < nameList.size(); i++) {
      nameFreqStrings.add(
          new String[] {nameList.get(i), String.valueOf(nameFreqs.get(i)[1])});
    }

    List<String[]> dayCountStrings = new ArrayList<>();
    for (int i = 0; i < dayList.size(); i++) {
      dayCountStrings.add(
          new String[] {dayList.get(i), String.valueOf(dayCounts.get(i)[1])});
    }

    // Re-pack: use nameFreqStrings for firstNameFrequencies encoding
    // and dayCountStrings for creationDayMessageCounts encoding
    // by storing the string data in the long[] arrays as indices
    // into the String[] lists. Actually, let's simplify: store everything
    // as the raw factor data that we can deserialize easily.

    return new FactorTables(
        personFriendCounts,
        personFoFCounts,
        personFoFoFCounts,
        nameFreqs,
        dayCounts,
        tagPersonCounts,
        tagClassNames,
        countryPairs);
  }

  // ==================== GAP-BASED GROUPING ====================

  /**
   * Selects entities from a factor table using the LDBC gap-based grouping
   * algorithm.
   *
   * <p>Algorithm:
   * <ol>
   *   <li>Filter entries below minValue</li>
   *   <li>Sort by metric ascending</li>
   *   <li>Walk sorted values; start a new group when the gap between
   *       consecutive values exceeds the threshold</li>
   *   <li>Among groups with at least minGroupSize members, pick the one
   *       with the lowest population standard deviation</li>
   * </ol>
   *
   * <p>If no group qualifies with the initial thresholds, the algorithm
   * auto-escalates: threshold is multiplied by 2x, 3x, ... up to 100x,
   * and minValue is halved each escalation step. This handles small scale
   * factors where the official LDBC thresholds are too restrictive.
   *
   * @param entries the factor table entries
   * @param threshold maximum gap between consecutive values within a group
   * @param minGroupSize minimum number of entities in a qualifying group
   * @param minValue minimum metric value to include
   * @return list of selected entity keys, or all keys if grouping fails
   */
  static <K> List<K> gapBasedGrouping(
      List<FactorEntry<K>> entries,
      long threshold, int minGroupSize, long minValue) {

    // Try with escalating thresholds if the initial ones are too strict
    for (int escalation = 1; escalation <= 100; escalation++) {
      long adjThreshold = threshold * escalation;
      long adjMinValue = Math.max(0, minValue / escalation);

      List<FactorEntry<K>> filtered = entries.stream()
          .filter(e -> e.metric() >= adjMinValue)
          .sorted(Comparator.comparingLong(FactorEntry::metric))
          .toList();

      if (filtered.size() < minGroupSize) {
        continue;
      }

      // Build groups by splitting on gaps
      List<List<FactorEntry<K>>> groups = new ArrayList<>();
      List<FactorEntry<K>> currentGroup = new ArrayList<>();
      currentGroup.add(filtered.getFirst());

      for (int i = 1; i < filtered.size(); i++) {
        long gap = filtered.get(i).metric() - filtered.get(i - 1).metric();
        if (gap > adjThreshold) {
          groups.add(List.copyOf(currentGroup));
          currentGroup = new ArrayList<>();
        }
        currentGroup.add(filtered.get(i));
      }
      groups.add(List.copyOf(currentGroup));

      // Find qualifying groups (size >= minGroupSize) with lowest stddev
      List<FactorEntry<K>> bestGroup = null;
      double bestStddev = Double.MAX_VALUE;

      for (var group : groups) {
        if (group.size() < minGroupSize) {
          continue;
        }
        double stddev = populationStddev(group);
        if (stddev < bestStddev) {
          bestStddev = stddev;
          bestGroup = group;
        }
      }

      if (bestGroup != null) {
        if (escalation > 1) {
          log.info("    Gap grouping succeeded at {}x escalation"
              + " (threshold={}, minValue={}, groupSize={})",
              escalation, adjThreshold, adjMinValue, bestGroup.size());
        }
        return bestGroup.stream().map(FactorEntry::key).toList();
      }
    }

    // Ultimate fallback: return all entries sorted by metric
    log.warn("    Gap grouping failed after 100x escalation,"
        + " using all {} entries", entries.size());
    return entries.stream()
        .sorted(Comparator.comparingLong(FactorEntry::metric))
        .map(FactorEntry::key)
        .toList();
  }

  private static <K> double populationStddev(List<FactorEntry<K>> group) {
    double mean = group.stream()
        .mapToLong(FactorEntry::metric).average().orElse(0);
    double variance = group.stream()
        .mapToDouble(e -> {
          double diff = e.metric() - mean;
          return diff * diff;
        })
        .average().orElse(0);
    return Math.sqrt(variance);
  }

  // ==================== PARAMETER GENERATION ====================

  @SuppressWarnings("unchecked")
  private static CuratedParams generateParams(
      FactorTables factors, LdbcBenchmarkState state) {

    log.info("Generating curated parameter sets...");

    // --- Curate person pools by neighborhood size ---

    // Friends-selected: persons with similar friend count
    log.info("  Curating friends-selected pool...");
    List<FactorEntry<Long>> friendEntries = factors.personFriendCounts().stream()
        .map(a -> new FactorEntry<>(a[0], a[1]))
        .toList();
    List<Long> friendsSelected = gapBasedGrouping(
        friendEntries, 2, 50, 10);
    log.info("  friends-selected: {} persons", friendsSelected.size());

    // FoF-selected: persons with similar FoF count
    log.info("  Curating FoF-selected pool...");
    List<FactorEntry<Long>> fofEntries = factors.personFoFCounts().stream()
        .map(a -> new FactorEntry<>(a[0], a[1]))
        .toList();
    List<Long> fofSelected = gapBasedGrouping(
        fofEntries, 5, 50, 100);
    log.info("  FoF-selected: {} persons", fofSelected.size());

    // FoFoF-selected: persons with similar 3-hop count
    log.info("  Curating FoFoF-selected pool...");
    List<FactorEntry<Long>> fofofEntries = factors.personFoFoFCounts().stream()
        .map(a -> new FactorEntry<>(a[0], a[1]))
        .toList();
    List<Long> fofofSelected = gapBasedGrouping(
        fofofEntries, 1000, 30, 1000);
    log.info("  FoFoF-selected: {} persons", fofofSelected.size());

    // --- Curate name pool ---
    log.info("  Curating first names pool...");
    // firstNameFrequencies: [nameIndex, freq]
    // We need the actual names — retrieve from DB or reconstruct
    List<Map<String, Object>> nameRows = state.executeSql(
        "SELECT firstName, count(*) as cnt FROM Person GROUP BY firstName");
    List<String> allNames = nameRows.stream()
        .map(r -> r.get("firstName").toString()).toList();
    List<Long> allNameFreqs = nameRows.stream()
        .map(r -> ((Number) r.get("cnt")).longValue()).toList();

    List<FactorEntry<String>> nameEntries = new ArrayList<>();
    for (int i = 0; i < allNames.size(); i++) {
      nameEntries.add(new FactorEntry<>(allNames.get(i), allNameFreqs.get(i)));
    }
    List<String> namesSelected = gapBasedGrouping(
        nameEntries, 5, 10, 0);
    log.info("  names-selected: {} names", namesSelected.size());

    // --- Curate creation day pool ---
    log.info("  Curating creation day pool...");
    List<Map<String, Object>> dayRows = state.executeSql(
        "SELECT creationDate.format('yyyy-MM-dd') as day, count(*) as cnt"
            + " FROM Message GROUP BY creationDate.format('yyyy-MM-dd')");
    List<FactorEntry<String>> dayEntries = dayRows.stream()
        .map(r -> new FactorEntry<>(
            r.get("day").toString(),
            ((Number) r.get("cnt")).longValue()))
        .toList();
    List<String> daysSelected = gapBasedGrouping(
        dayEntries, 500, 10, 100);
    log.info("  days-selected: {} days", daysSelected.size());

    // Convert day strings to Date objects
    List<Date> selectedDates = daysSelected.stream()
        .map(ParameterCurator::parseDayToDate)
        .toList();

    // --- Curate tag pool (for IC6) — tags around p25 frequency ---
    log.info("  Curating tag pool...");
    List<FactorEntry<String>> tagEntries = factors.tagPersonCounts().stream()
        .map(a -> new FactorEntry<>(a[0], Long.parseLong(a[1])))
        .toList();
    // Pick tags with moderate popularity (not too popular, not too rare)
    List<String> tagsSelected = gapBasedGrouping(
        tagEntries, 5, 10, 1);
    log.info("  tags-selected: {} tags", tagsSelected.size());

    // --- Tag class names (for IC12) ---
    List<String> tagClassList = factors.tagClassNames().stream()
        .map(a -> a[0]).toList();

    // --- Country pairs (for IC3) ---
    List<String[]> countryPairs = new ArrayList<>(factors.countryPairs());

    // --- Country names (for IC11) ---
    List<Map<String, Object>> countryRows = state.executeSql(
        "SELECT name FROM Place WHERE type = 'Country'");
    List<String> countryNames = countryRows.stream()
        .map(r -> r.get("name").toString()).toList();

    // --- Message IDs (for IS queries) ---
    List<Map<String, Object>> msgRows = state.executeSql(
        "SELECT id FROM Message LIMIT " + PARAMS_PER_QUERY);
    long[] messageIds = msgRows.stream()
        .mapToLong(r -> ((Number) r.get("id")).longValue()).toArray();

    // ==================== GENERATE PER-QUERY TUPLES ====================

    // IC1: FoFoF-selected × names-selected
    log.info("  Generating IC1 params...");
    List<Ic1Params> ic1List = new ArrayList<>();
    for (int pi = 0; pi < fofofSelected.size()
        && ic1List.size() < PARAMS_PER_QUERY; pi++) {
      for (int ni = 0; ni < namesSelected.size()
          && ic1List.size() < PARAMS_PER_QUERY; ni++) {
        ic1List.add(new Ic1Params(fofofSelected.get(pi),
            namesSelected.get(ni)));
      }
    }

    // IC2: FoF-selected × dates
    log.info("  Generating IC2 params...");
    List<PersonDateParams> ic2List = new ArrayList<>();
    for (int pi = 0; pi < fofSelected.size()
        && ic2List.size() < PARAMS_PER_QUERY; pi++) {
      for (int di = 0; di < selectedDates.size()
          && ic2List.size() < PARAMS_PER_QUERY; di++) {
        ic2List.add(new PersonDateParams(fofSelected.get(pi),
            selectedDates.get(di)));
      }
    }

    // IC3: FoF-selected × country pairs × dates
    log.info("  Generating IC3 params...");
    List<Ic3Params> ic3List = new ArrayList<>();
    outer3 : for (int pi = 0; pi < fofSelected.size(); pi++) {
      for (int ci = 0; ci < countryPairs.size(); ci++) {
        for (int di = 0; di < selectedDates.size(); di++) {
          if (ic3List.size() >= PARAMS_PER_QUERY) {
            break outer3;
          }
          ic3List.add(new Ic3Params(
              fofSelected.get(pi),
              countryPairs.get(ci)[0],
              countryPairs.get(ci)[1],
              selectedDates.get(di)));
        }
      }
    }

    // IC4: friends-selected × dates, with oldPost-count-based difficulty
    // factor. IC4 ("new topics") cost is dominated by the NOT pattern which
    // checks all posts by friends before startDate. Result count alone misses
    // this — two pairs with the same number of "new" tags can have vastly
    // different execution times if one person's friends have many more old
    // posts. We count oldPosts (friends' posts before startDate) per sample
    // and gap-group on that count. This is a data-derived factor that is
    // stable across code changes, hardware, and JIT state.
    log.info("  Generating IC4 params (with oldPost-count factor)...");
    List<Ic4Params> ic4Candidates = new ArrayList<>();
    for (Long pid : friendsSelected) {
      for (Date d : selectedDates) {
        ic4Candidates.add(new Ic4Params(pid, d));
        if (ic4Candidates.size() >= friendsSelected.size()
            * selectedDates.size()) {
          break;
        }
      }
    }

    // Sample up to IC4_SAMPLE_SIZE pairs for factor computation
    int sampleSize = Math.min(IC4_SAMPLE_SIZE, ic4Candidates.size());
    // Use stride-based sampling for even coverage across the cross-product
    int stride = Math.max(1, ic4Candidates.size() / sampleSize);
    List<Ic4Params> ic4Sample = new ArrayList<>();
    for (int si = 0; si < ic4Candidates.size()
        && ic4Sample.size() < sampleSize; si += stride) {
      ic4Sample.add(ic4Candidates.get(si));
    }

    log.info("    Counting oldPosts for {} IC4 pairs...",
        ic4Sample.size());
    long ic4FactorStart = System.currentTimeMillis();
    List<FactorEntry<Ic4Params>> ic4Factors = new ArrayList<>();
    for (int si = 0; si < ic4Sample.size(); si++) {
      Ic4Params p = ic4Sample.get(si);
      List<Map<String, Object>> result = state.executeSql(
          LdbcQuerySql.IC4_OLDPOST_COUNT,
          "personId", p.personId(),
          "startDate", p.startDate());
      long oldPostCount = result.isEmpty() ? 0
          : ((Number) result.getFirst().get("cnt")).longValue();
      ic4Factors.add(new FactorEntry<>(p, oldPostCount));
      if ((si + 1) % 50 == 0) {
        log.info("    IC4 factor progress: {}/{}", si + 1,
            ic4Sample.size());
      }
    }
    long ic4FactorElapsed = System.currentTimeMillis() - ic4FactorStart;
    log.info("    IC4 oldPost-count factor computed in {}s for {} pairs",
        ic4FactorElapsed / 1000.0, ic4Sample.size());

    // Gap-group on oldPost count to find pairs with similar NOT-pattern cost.
    // threshold=50 (consecutive pairs differ by ≤50 old posts),
    // minGroupSize=20, minValue=10 (exclude pairs with very few old posts
    // where the NOT pattern is trivially cheap).
    List<Ic4Params> ic4Selected = gapBasedGrouping(
        ic4Factors, 50, 20, 10);
    log.info("    IC4 gap-grouped: {} pairs selected", ic4Selected.size());

    // Fill to PARAMS_PER_QUERY by cycling through the selected pairs
    List<Ic4Params> ic4List = new ArrayList<>();
    for (int i = 0; i < PARAMS_PER_QUERY; i++) {
      ic4List.add(ic4Selected.get(i % ic4Selected.size()));
    }

    // IC5: FoF-selected (person only)
    log.info("  Generating IC5 params...");
    long[] ic5PersonIds = fillPersonPool(fofSelected, PARAMS_PER_QUERY);

    // IC6: FoF-selected × tags
    log.info("  Generating IC6 params...");
    List<Ic6Params> ic6List = new ArrayList<>();
    for (int pi = 0; pi < fofSelected.size()
        && ic6List.size() < PARAMS_PER_QUERY; pi++) {
      for (int ti = 0; ti < tagsSelected.size()
          && ic6List.size() < PARAMS_PER_QUERY; ti++) {
        ic6List.add(new Ic6Params(fofSelected.get(pi),
            tagsSelected.get(ti)));
      }
    }

    // IC7: friends-selected (person only)
    log.info("  Generating IC7 params...");
    long[] ic7PersonIds = fillPersonPool(friendsSelected, PARAMS_PER_QUERY);

    // IC8: friends-selected (person only)
    long[] ic8PersonIds = fillPersonPool(friendsSelected, PARAMS_PER_QUERY);

    // IC9: FoF-selected × dates
    log.info("  Generating IC9 params...");
    List<Ic9Params> ic9List = new ArrayList<>();
    for (int pi = 0; pi < fofSelected.size()
        && ic9List.size() < PARAMS_PER_QUERY; pi++) {
      for (int di = 0; di < selectedDates.size()
          && ic9List.size() < PARAMS_PER_QUERY; di++) {
        ic9List.add(new Ic9Params(fofSelected.get(pi),
            selectedDates.get(di)));
      }
    }

    // IC10: FoF-selected (person only)
    long[] ic10PersonIds = fillPersonPool(fofSelected, PARAMS_PER_QUERY);

    // IC11: friends-selected × countries
    log.info("  Generating IC11 params...");
    long[] ic11PersonIds = fillPersonPool(friendsSelected, PARAMS_PER_QUERY);
    String[] ic11Countries = fillStringPool(countryNames, PARAMS_PER_QUERY);

    // IC12: FoF-selected × tagClasses
    log.info("  Generating IC12 params...");
    List<Ic12Params> ic12List = new ArrayList<>();
    for (int pi = 0; pi < fofSelected.size()
        && ic12List.size() < PARAMS_PER_QUERY; pi++) {
      for (int tci = 0; tci < tagClassList.size()
          && ic12List.size() < PARAMS_PER_QUERY; tci++) {
        ic12List.add(new Ic12Params(fofSelected.get(pi),
            tagClassList.get(tci)));
      }
    }

    // IC13: two distinct person pools from friends-selected
    log.info("  Generating IC13 params...");
    long[] ic13Ids1 = fillPersonPool(friendsSelected, PARAMS_PER_QUERY);
    // Offset by half for the second person
    long[] ic13Ids2 = new long[PARAMS_PER_QUERY];
    for (int i = 0; i < PARAMS_PER_QUERY; i++) {
      int offset = friendsSelected.size() / 2;
      ic13Ids2[i] = friendsSelected.get(
          (i + offset) % friendsSelected.size());
    }

    // IS person IDs: use friends-selected
    long[] isPersonIds = fillPersonPool(friendsSelected, PARAMS_PER_QUERY);

    // All dates pool (for queries that need a date parameter not
    // tied to a specific person)
    Date[] datesArray = fillDatePool(selectedDates, PARAMS_PER_QUERY);

    log.info("Parameter curation complete");

    return new CuratedParams(
        isPersonIds,
        messageIds,
        ic1List.toArray(Ic1Params[]::new),
        ic2List.toArray(PersonDateParams[]::new),
        ic3List.toArray(Ic3Params[]::new),
        ic4List.toArray(Ic4Params[]::new),
        ic5PersonIds,
        ic6List.toArray(Ic6Params[]::new),
        ic7PersonIds,
        ic8PersonIds,
        ic9List.toArray(Ic9Params[]::new),
        ic10PersonIds,
        ic11PersonIds,
        ic11Countries,
        ic12List.toArray(Ic12Params[]::new),
        ic13Ids1,
        ic13Ids2,
        datesArray);
  }

  // ==================== HELPERS ====================

  /** Fill a long[] pool by cycling through the source list. */
  private static long[] fillPersonPool(List<Long> source, int size) {
    long[] result = new long[size];
    for (int i = 0; i < size; i++) {
      result[i] = source.get(i % source.size());
    }
    return result;
  }

  /** Fill a String[] pool by cycling through the source list. */
  private static String[] fillStringPool(List<String> source, int size) {
    String[] result = new String[size];
    for (int i = 0; i < size; i++) {
      result[i] = source.get(i % source.size());
    }
    return result;
  }

  /** Fill a Date[] pool by cycling through the source list. */
  private static Date[] fillDatePool(List<Date> source, int size) {
    Date[] result = new Date[size];
    for (int i = 0; i < size; i++) {
      result[i] = source.get(i % source.size());
    }
    return result;
  }

  /** Parse "yyyy-MM-dd" to a Date at midnight UTC. */
  private static Date parseDayToDate(String day) {
    java.time.LocalDate localDate = java.time.LocalDate.parse(day);
    return Date.from(
        localDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
  }

  // ==================== JSON PERSISTENCE ====================

  /** Data class for JSON serialization of factor tables. */
  record FactorTablesJson(
      List<long[]> personFriendCounts,
      List<long[]> personFoFCounts,
      List<long[]> personFoFoFCounts,
      List<long[]> firstNameFrequencies,
      List<long[]> creationDayMessageCounts,
      List<String[]> tagPersonCounts,
      List<String[]> tagClassNames,
      List<String[]> countryPairs) {
  }

  private static void saveFactorTablesToJson(
      FactorTables factors, Path path) throws IOException {
    var json = new FactorTablesJson(
        factors.personFriendCounts(),
        factors.personFoFCounts(),
        factors.personFoFoFCounts(),
        factors.firstNameFrequencies(),
        factors.creationDayMessageCounts(),
        factors.tagPersonCounts(),
        factors.tagClassNames(),
        factors.countryPairs());
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), json);
  }

  private static FactorTables loadFactorTablesFromJson(Path path)
      throws IOException {
    var json = MAPPER.readValue(path.toFile(),
        new TypeReference<FactorTablesJson>() {
        });
    return new FactorTables(
        json.personFriendCounts(),
        json.personFoFCounts(),
        json.personFoFoFCounts(),
        json.firstNameFrequencies(),
        json.creationDayMessageCounts(),
        json.tagPersonCounts(),
        json.tagClassNames(),
        json.countryPairs());
  }

  // ==================== CURATED PARAMS JSON PERSISTENCE ====================

  /**
   * JSON-serializable representation of CuratedParams. Dates are stored as
   * epoch millis (long) for portability. Jackson handles records natively
   * in 2.12+, so the inner param records serialize automatically.
   */
  record CuratedParamsJson(
      long[] isPersonIds,
      long[] isMessageIds,
      Ic1Params[] ic1,
      PersonDateParamsJson[] ic2,
      Ic3ParamsJson[] ic3,
      Ic4ParamsJson[] ic4,
      long[] ic5PersonIds,
      Ic6Params[] ic6,
      long[] ic7PersonIds,
      long[] ic8PersonIds,
      Ic9ParamsJson[] ic9,
      long[] ic10PersonIds,
      long[] ic11PersonIds,
      String[] ic11CountryNames,
      Ic12Params[] ic12,
      long[] ic13PersonIds1,
      long[] ic13PersonIds2,
      long[] dates) {
  }

  /** PersonDateParams with Date as epoch millis. */
  record PersonDateParamsJson(long personId, long maxDate) {
  }

  /** Ic3Params with Date as epoch millis. */
  record Ic3ParamsJson(long personId, String countryX, String countryY,
      long startDate) {
  }

  /** Ic4Params with Date as epoch millis. */
  record Ic4ParamsJson(long personId, long startDate) {
  }

  /** Ic9Params with Date as epoch millis. */
  record Ic9ParamsJson(long personId, long maxDate) {
  }

  private static void saveCuratedParamsToJson(
      CuratedParams params, Path path) throws IOException {
    var json = new CuratedParamsJson(
        params.isPersonIds(),
        params.isMessageIds(),
        params.ic1(),
        toPersonDateJson(params.ic2()),
        toIc3Json(params.ic3()),
        toIc4Json(params.ic4()),
        params.ic5PersonIds(),
        params.ic6(),
        params.ic7PersonIds(),
        params.ic8PersonIds(),
        toIc9Json(params.ic9()),
        params.ic10PersonIds(),
        params.ic11PersonIds(),
        params.ic11CountryNames(),
        params.ic12(),
        params.ic13PersonIds1(),
        params.ic13PersonIds2(),
        datesToMillis(params.dates()));
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), json);
  }

  private static CuratedParams loadCuratedParamsFromJson(Path path)
      throws IOException {
    var json = MAPPER.readValue(path.toFile(),
        new TypeReference<CuratedParamsJson>() {
        });
    return new CuratedParams(
        json.isPersonIds(),
        json.isMessageIds(),
        json.ic1(),
        fromPersonDateJson(json.ic2()),
        fromIc3Json(json.ic3()),
        fromIc4Json(json.ic4()),
        json.ic5PersonIds(),
        json.ic6(),
        json.ic7PersonIds(),
        json.ic8PersonIds(),
        fromIc9Json(json.ic9()),
        json.ic10PersonIds(),
        json.ic11PersonIds(),
        json.ic11CountryNames(),
        json.ic12(),
        json.ic13PersonIds1(),
        json.ic13PersonIds2(),
        millisToDates(json.dates()));
  }

  // --- Date conversion helpers ---

  private static long[] datesToMillis(Date[] dates) {
    long[] millis = new long[dates.length];
    for (int i = 0; i < dates.length; i++) {
      millis[i] = dates[i].getTime();
    }
    return millis;
  }

  private static Date[] millisToDates(long[] millis) {
    Date[] dates = new Date[millis.length];
    for (int i = 0; i < millis.length; i++) {
      dates[i] = new Date(millis[i]);
    }
    return dates;
  }

  // --- Record conversion helpers (Date <-> long millis) ---

  private static PersonDateParamsJson[] toPersonDateJson(
      PersonDateParams[] params) {
    var json = new PersonDateParamsJson[params.length];
    for (int i = 0; i < params.length; i++) {
      json[i] = new PersonDateParamsJson(
          params[i].personId(), params[i].maxDate().getTime());
    }
    return json;
  }

  private static PersonDateParams[] fromPersonDateJson(
      PersonDateParamsJson[] json) {
    var params = new PersonDateParams[json.length];
    for (int i = 0; i < json.length; i++) {
      params[i] = new PersonDateParams(
          json[i].personId(), new Date(json[i].maxDate()));
    }
    return params;
  }

  private static Ic3ParamsJson[] toIc3Json(Ic3Params[] params) {
    var json = new Ic3ParamsJson[params.length];
    for (int i = 0; i < params.length; i++) {
      json[i] = new Ic3ParamsJson(
          params[i].personId(), params[i].countryX(),
          params[i].countryY(), params[i].startDate().getTime());
    }
    return json;
  }

  private static Ic3Params[] fromIc3Json(Ic3ParamsJson[] json) {
    var params = new Ic3Params[json.length];
    for (int i = 0; i < json.length; i++) {
      params[i] = new Ic3Params(
          json[i].personId(), json[i].countryX(),
          json[i].countryY(), new Date(json[i].startDate()));
    }
    return params;
  }

  private static Ic4ParamsJson[] toIc4Json(Ic4Params[] params) {
    var json = new Ic4ParamsJson[params.length];
    for (int i = 0; i < params.length; i++) {
      json[i] = new Ic4ParamsJson(
          params[i].personId(), params[i].startDate().getTime());
    }
    return json;
  }

  private static Ic4Params[] fromIc4Json(Ic4ParamsJson[] json) {
    var params = new Ic4Params[json.length];
    for (int i = 0; i < json.length; i++) {
      params[i] = new Ic4Params(
          json[i].personId(), new Date(json[i].startDate()));
    }
    return params;
  }

  private static Ic9ParamsJson[] toIc9Json(Ic9Params[] params) {
    var json = new Ic9ParamsJson[params.length];
    for (int i = 0; i < params.length; i++) {
      json[i] = new Ic9ParamsJson(
          params[i].personId(), params[i].maxDate().getTime());
    }
    return json;
  }

  private static Ic9Params[] fromIc9Json(Ic9ParamsJson[] json) {
    var params = new Ic9Params[json.length];
    for (int i = 0; i < json.length; i++) {
      params[i] = new Ic9Params(
          json[i].personId(), new Date(json[i].maxDate()));
    }
    return params;
  }
}
