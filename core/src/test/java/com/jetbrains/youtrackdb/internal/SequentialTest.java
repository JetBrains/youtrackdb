package com.jetbrains.youtrackdb.internal;

/**
 * Marker category for tests that must run sequentially (not in parallel).
 *
 * <p>Tests are tagged with this category when they:
 *
 * <ul>
 *   <li>Mutate {@code GlobalConfiguration} without per-method save/restore
 *   <li>Use static shared {@code YouTrackDB} or database instances
 *   <li>Manipulate engine-level singletons
 *   <li>Run heavyweight Cucumber feature suites with shared datasets
 * </ul>
 *
 * <p>The core module's surefire configuration runs two executions: a parallel execution that
 * excludes this category, and a sequential execution that includes only this category.
 */
public interface SequentialTest {
}
