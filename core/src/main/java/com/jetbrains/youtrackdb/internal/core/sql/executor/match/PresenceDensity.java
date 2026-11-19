package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, pre-inference population inputs. This record never authorizes index acquisition. */
record PresenceDensity(String alias, String candidateClass, Source source, double density,
    long candidates, long total, long nulls, boolean applied) {

  enum Source {
    MANDATORY, INDEX, NO_CLASS, NO_DECLARATION, NO_COVERAGE, NO_STATISTICS, EMPTY_STATISTICS, NONPOSITIVE_POPULATION, STATISTICS_FAILURE
  }

  PresenceDensity applied(boolean value) {
    return new PresenceDensity(alias, candidateClass, source, density, candidates, total, nulls,
        value);
  }

  boolean useful() {
    return source == Source.MANDATORY || source == Source.INDEX;
  }

  static PresenceDensity read(String alias, String className, Set<String> keys,
      DatabaseSessionEmbedded session) {
    if (className == null) {
      return unavailable(alias, null, Source.NO_CLASS, 0);
    }
    var clazz = session.getMetadata().getImmutableSchemaSnapshot().getClassInternal(className);
    if (!(clazz instanceof SchemaImmutableClass immutable)) {
      return unavailable(alias, className, Source.NO_CLASS, 0);
    }
    // More than one promoted key needs joint presence statistics, not independent guesses.
    if (keys.size() != 1) {
      return unavailable(alias, className, Source.NO_DECLARATION, 0);
    }
    var key = keys.iterator().next();
    var hierarchy = new ArrayList<SchemaClass>();
    hierarchy.add(clazz);
    hierarchy.addAll(clazz.getAllSubclasses());
    long count = immutable.approximateCountImpl(true, session);
    if (hierarchy.stream().allMatch(c -> c.getProperty(key) != null
        && c.getProperty(key).isMandatory())) {
      return new PresenceDensity(alias, className, Source.MANDATORY, 1, count, 0, 0, false);
    }
    if (hierarchy.stream().anyMatch(c -> c.getProperty(key) == null
        || !c.getProperty(key).isNotNull())) {
      return unavailable(alias, className, Source.NO_DECLARATION, count);
    }
    if (count <= 0) {
      return unavailable(alias, className, Source.NONPOSITIVE_POPULATION, count);
    }
    var collections = clazz.getPolymorphicCollectionIds();
    var readable = SchemaClassImpl.readableCollections(session, collections, className);
    // Counters cover all index collections. A restricted reader must not divide those counters
    // by just the readable subset of the population.
    if (!Arrays.equals(collections, readable)) {
      return unavailable(alias, className, Source.NO_COVERAGE, count);
    }
    var names = new HashSet<String>();
    for (var id : collections) {
      if (id >= 0) {
        names.add(session.getCollectionNameById(id));
      }
    }
    var reason = Source.NO_COVERAGE;
    for (var index : clazz.getIndexesInternal()) {
      var definition = index.getDefinition();
      if (definition == null || definition.getClass() != PropertyIndexDefinition.class
          || !className.equals(definition.getClassName())
          || !definition.getProperties().equals(List.of(key))
          || definition.getTypes().length != 1 || definition.getTypes()[0].isMultiValue()
          || !index.getCollections().equals(names)) {
        continue;
      }
      try {
        var statistics = index.getStatistics(session);
        if (statistics == null) {
          reason = Source.NO_STATISTICS;
          continue;
        }
        long total = statistics.totalCount();
        long nulls = statistics.nullCount();
        if (total <= 0 || nulls < 0 || nulls >= total) {
          reason = Source.EMPTY_STATISTICS;
          continue;
        }
        return new PresenceDensity(alias, className, Source.INDEX,
            Math.min(1, (double) (total - nulls) / count), count, total, nulls, false);
      } catch (RuntimeException failure) {
        // Statistics are optional planning inputs. Execution still evaluates presence itself.
        reason = Source.STATISTICS_FAILURE;
      }
    }
    return unavailable(alias, className, reason, count);
  }

  private static PresenceDensity unavailable(String alias, String className, Source source,
      long count) {
    return new PresenceDensity(alias, className, source, 1, count, 0, 0, false);
  }
}
