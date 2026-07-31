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
 *   <li>Reference classes from the {@code gremlin-test} dependency, which the parallel
 *       execution excludes from its classpath entirely
 * </ul>
 *
 * <p>The core module's surefire configuration runs a parallel {@code default-test} execution
 * that excludes this category, and a {@code sequential-tests} execution that includes only this
 * category. Three further executions run the TinkerPop compliance suites (process, structure,
 * Cucumber feature); those wrapper classes also carry this category, but their executions
 * configure no group filter at all (a category filter would silently prune every test they wrap
 * to zero -- see {@code core/pom.xml}), so tagging a class with this category alone does not
 * route it to those three.
 */
public interface SequentialTest {
}
