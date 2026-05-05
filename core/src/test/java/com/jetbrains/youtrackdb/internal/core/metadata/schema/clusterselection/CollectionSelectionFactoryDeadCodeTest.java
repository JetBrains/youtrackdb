/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.CollectionSelectionStrategy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Shape pin for {@link CollectionSelectionFactory}'s dead surface: the {@code getStrategy(String)}
 * dispatcher, the {@code registerStrategy()} SPI loop, and the underlying
 * {@code newInstance(String)} factory dispatch. PSI all-scope {@code ReferencesSearch} confirms
 * zero callers of {@code getStrategy(String)} across all five Maven modules — no public API on
 * {@link com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass} switches the
 * cluster-selection strategy by name, and {@code SchemaClassImpl} hardcodes
 * {@code new RoundRobinCollectionSelectionStrategy()} for every class created.
 *
 * <p>The {@link CollectionSelectionFactory} CLASS itself is live — it is instantiated as a field
 * of {@code SchemaShared} and exposed via {@code Schema.getCollectionSelectionFactory()}. What
 * is dead is the lookup-by-name dispatcher: {@code getStrategy(String)} +
 * {@code newInstance(String)} for the {@code "balanced"} and {@code "default"} keys, plus the
 * SPI-registry plumbing that wires those keys up. The {@code "round-robin"} key would also be
 * registered if any caller asked for it, but no caller asks. The default-class fallback (used
 * via {@code newInstance(null)}) returns a {@link RoundRobinCollectionSelectionStrategy} via
 * {@code setDefaultClass(...)} in the constructor — that fallback IS reachable through
 * {@link com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaProxy#getCollectionSelectionFactory()}
 * but no production code today consumes the returned factory.
 *
 * <p>This test pins:
 *
 * <ul>
 *   <li>The factory constructor wires up the round-robin default class and runs the SPI loop
 *       (so each registered key — "balanced", "default", "round-robin" — resolves to the
 *       correct concrete class).</li>
 *   <li>{@code getStrategy(String)} dispatches via {@code newInstance(String)} and returns a
 *       fresh strategy instance per call (the factory is "stateful per instance", not a
 *       singleton).</li>
 *   <li>The SPI service file exists, is non-empty, and lists exactly the three fully-qualified
 *       strategy class names (order-independent set assertion — the plan-file's "first line
 *       matches Balanced" prescription disagrees with the on-disk order, so the pin asserts
 *       the membership invariant rather than line ordering).</li>
 * </ul>
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link CollectionSelectionFactory#getStrategy(String)} and
 * the {@code registerStrategy()} SPI loop together with the {@code "balanced"} and
 * {@code "default"} entries in the SPI service file. The factory class itself stays only if a
 * future caller of {@code getCollectionSelectionFactory()} needs it; otherwise, the whole class
 * + the {@code SchemaShared.collectionSelectionFactory} field + the
 * {@code Schema.getCollectionSelectionFactory()} method may be removed lockstep.
 *
 * <p>Standalone: no database session is needed; the factory is a plain Java POJO over a
 * {@link com.jetbrains.youtrackdb.internal.common.factory.ConfigurableStatefulFactory} registry.
 */
public class CollectionSelectionFactoryDeadCodeTest {

  /** Resource path of the SPI service file; pinned so a Track 22 rename is surfaced. */
  private static final String SPI_RESOURCE_PATH =
      "META-INF/services/com.jetbrains.youtrackdb.internal.core.metadata.schema."
          + "CollectionSelectionStrategy";

  @Test
  public void factoryConstructorWiresRoundRobinAsDefaultClass() {
    // SchemaClassImpl hardcodes `new RoundRobinCollectionSelectionStrategy()` for every class,
    // but the factory's default-class registration is still a meaningful pin: any future caller
    // that uses `newInstance(null)` MUST get RoundRobin. Pin the default-class identity so a
    // Track 22 deletion that drops the round-robin wiring is caught.
    var f = new CollectionSelectionFactory();
    assertSame("default class must be RoundRobinCollectionSelectionStrategy",
        RoundRobinCollectionSelectionStrategy.class, f.getDefaultClass());
  }

  @Test
  public void registerStrategyAddsAllThreeKnownStrategiesViaSpiLoop() {
    // The constructor calls registerStrategy() which iterates the SPI loop. Each strategy's
    // getName() return value becomes the registry key. The three keys are pinned in the
    // sibling *DeadCodeTest classes (Balanced/Default/RoundRobin .NAME constants); here we
    // pin that they all land in the registry after the SPI loop runs.
    var f = new CollectionSelectionFactory();
    var keys = f.getRegisteredNames();

    assertTrue("registry must contain the 'balanced' key from the SPI loop",
        keys.contains(BalancedCollectionSelectionStrategy.NAME));
    assertTrue("registry must contain the 'default' key from the SPI loop",
        keys.contains(DefaultCollectionSelectionStrategy.NAME));
    assertTrue("registry must contain the 'round-robin' key from the SPI loop",
        keys.contains(RoundRobinCollectionSelectionStrategy.NAME));
  }

  @Test
  public void registryMapsEachKeyToTheCorrectConcreteClass() {
    // Pin the key->Class mapping so that a Track 22 deletion that fans out to "rename one
    // SPI key but leave another" is caught. assertSame on the Class objects is byte-for-byte
    // identity — strictly more falsifiable than name-string equality.
    var f = new CollectionSelectionFactory();
    assertSame("'balanced' must map to BalancedCollectionSelectionStrategy",
        BalancedCollectionSelectionStrategy.class, f.get(BalancedCollectionSelectionStrategy.NAME));
    assertSame("'default' must map to DefaultCollectionSelectionStrategy",
        DefaultCollectionSelectionStrategy.class, f.get(DefaultCollectionSelectionStrategy.NAME));
    assertSame("'round-robin' must map to RoundRobinCollectionSelectionStrategy",
        RoundRobinCollectionSelectionStrategy.class,
        f.get(RoundRobinCollectionSelectionStrategy.NAME));
  }

  @Test
  public void getStrategyDispatchesByKeyAndReturnsFreshInstance() {
    // getStrategy(String) is the dead public dispatcher. Pin the contract: returns a NEW
    // instance per call (the factory's javadoc notes "Instances are stateful, so can't be
    // reused on multiple classes"). assertNotSame against a second call is the load-bearing
    // observable for "fresh per call" — a future singleton cache would silently break that.
    var f = new CollectionSelectionFactory();

    CollectionSelectionStrategy s1 = f.getStrategy(BalancedCollectionSelectionStrategy.NAME);
    CollectionSelectionStrategy s2 = f.getStrategy(BalancedCollectionSelectionStrategy.NAME);

    assertNotNull("getStrategy('balanced') must return a non-null instance", s1);
    assertEquals("returned instance must be the BalancedCollectionSelectionStrategy class",
        BalancedCollectionSelectionStrategy.class, s1.getClass());
    assertNotNull("second getStrategy call must also be non-null", s2);
    org.junit.Assert.assertNotSame(
        "factory javadoc says strategies are stateful and not reused — must be fresh per call",
        s1, s2);
  }

  @Test
  public void getStrategyForUnknownKeyFallsBackToRoundRobinDefault() {
    // ConfigurableStatefulFactory.newInstance(unknown) returns newInstanceOfDefaultClass().
    // Pin that the unknown-key path returns a non-null RoundRobin (not null, not a throw).
    // This is the only public way a caller could observe the default-class fallback in
    // production, so the pin is load-bearing for any future deletion that drops
    // setDefaultClass(...).
    var f = new CollectionSelectionFactory();
    CollectionSelectionStrategy unknown = f.getStrategy("never-registered-strategy-key");
    assertNotNull("unknown key must fall back to the default class, not return null", unknown);
    assertEquals("unknown-key fallback must be RoundRobinCollectionSelectionStrategy",
        RoundRobinCollectionSelectionStrategy.class, unknown.getClass());
  }

  @Test
  public void getStrategyForNullKeyFallsBackToRoundRobinDefault() {
    // The base class ConfigurableStatefulFactory.newInstance(null) dispatches to the default
    // class because the registry's get(null) returns null and a defaultClass IS set. Pin this
    // so a future "tighten null handling" change is caught.
    var f = new CollectionSelectionFactory();
    CollectionSelectionStrategy nullKey = f.getStrategy(null);
    assertNotNull("null key must fall back to the default class, not return null", nullKey);
    assertEquals("null-key fallback must be RoundRobinCollectionSelectionStrategy",
        RoundRobinCollectionSelectionStrategy.class, nullKey.getClass());
  }

  @Test
  public void unregisteredKeyAfterUnregisterAllFallsThroughToDefault() {
    // After unregisterAll(), no name resolves through the registry — every getStrategy call
    // must fall through to the default class. Pin this to catch a future change that caches
    // results past unregister.
    var f = new CollectionSelectionFactory();
    f.unregisterAll();
    assertTrue("after unregisterAll(), the registry must be empty",
        f.getRegisteredNames().isEmpty());
    CollectionSelectionStrategy s = f.getStrategy(BalancedCollectionSelectionStrategy.NAME);
    assertNotNull("must fall through to default after unregisterAll", s);
    assertEquals("fall-through must be RoundRobin",
        RoundRobinCollectionSelectionStrategy.class, s.getClass());
  }

  @Test
  public void spiServiceFileListsAllThreeStrategiesByFullyQualifiedName() throws IOException {
    // The SPI service file is the input to ServiceLoader and therefore the input to
    // registerStrategy(). Pin that:
    //   - the file exists on the test classpath (any rename of the resource path breaks SPI);
    //   - it contains exactly the three known FQCN entries (membership pin);
    // Order is NOT pinned because the on-disk file lists RoundRobin first, Default second,
    // Balanced third — this contradicts the plan-file's prescription ("Balanced first, Default
    // second, RoundRobin third"). The on-disk order is the source of truth; pinning a specific
    // order would either lock the wrong order in or require changing the file. Membership
    // pinning catches every deletion the lockstep group cares about (a Track 22 fix that
    // removes "balanced"/"default" while leaving "round-robin" must update this membership
    // assertion explicitly).
    var lines = readSpiResource();
    assertNotNull("SPI resource '" + SPI_RESOURCE_PATH + "' must exist on the test classpath",
        lines);

    Set<String> entries =
        lines.stream()
            .map(String::trim)
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .collect(Collectors.toSet());

    assertEquals("SPI file must list exactly three strategy entries", 3, entries.size());
    assertTrue("SPI file must list BalancedCollectionSelectionStrategy",
        entries.contains(BalancedCollectionSelectionStrategy.class.getName()));
    assertTrue("SPI file must list DefaultCollectionSelectionStrategy",
        entries.contains(DefaultCollectionSelectionStrategy.class.getName()));
    assertTrue("SPI file must list RoundRobinCollectionSelectionStrategy",
        entries.contains(RoundRobinCollectionSelectionStrategy.class.getName()));
  }

  @Test
  public void spiResourceLoadsViaTheStrategyInterfaceClassLoader() {
    // Pin that the SPI resource is reachable via the same classloader the production code uses
    // (CollectionSelectionFactory.class.getClassLoader()). A Track 22 deletion that moves the
    // resource to a different module would still leave the file on a separate classloader and
    // this assertion would catch it.
    var url = CollectionSelectionStrategy.class.getClassLoader().getResource(SPI_RESOURCE_PATH);
    assertNotNull(
        "SPI resource must be reachable via the CollectionSelectionStrategy classloader; "
            + "production code's registerStrategy() uses this loader",
        url);
  }

  /**
   * Reads the SPI service file from the test classpath. Returns null if the resource is
   * absent (the absence assertion is performed at the call site so the test message is
   * meaningful).
   */
  private static List<String> readSpiResource() throws IOException {
    var loader = CollectionSelectionStrategy.class.getClassLoader();
    var stream = loader.getResourceAsStream(SPI_RESOURCE_PATH);
    if (stream == null) {
      return null;
    }
    try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      return reader.lines().collect(Collectors.toList());
    }
  }

  @Test
  public void getStrategyForKeyOfWrongTypeIsRoutedThroughTheStringDispatcher() {
    // Defensive pin: the dispatcher is typed as String -> CollectionSelectionStrategy. The
    // base-class registry IS Map<String, Class<? extends CollectionSelectionStrategy>>. Pin
    // that a fresh-after-unregister-all factory returning the round-robin default for an
    // unregistered key proves the dispatcher has not been retyped to accept arbitrary keys.
    var f = new CollectionSelectionFactory();
    f.unregisterAll();
    f.register("not-a-real-name", RoundRobinCollectionSelectionStrategy.class);
    CollectionSelectionStrategy strategy = f.getStrategy("not-a-real-name");
    assertNotNull("manual register + getStrategy must return the registered class instance",
        strategy);
    assertEquals(RoundRobinCollectionSelectionStrategy.class, strategy.getClass());
    assertNull("a never-registered key returns the default class only via newInstance, but "
        + "the registry's direct .get() must return null for unknown keys",
        f.get("genuinely-unknown-key"));
  }
}
