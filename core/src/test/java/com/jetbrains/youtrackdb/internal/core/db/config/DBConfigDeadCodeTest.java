/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.db.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.config.UDPUnicastConfiguration.Address;
import java.util.List;
import org.junit.Test;

/**
 * Dead-code pin tests for the {@code core/db/config} package — the six public classes
 * {@link MulticastConfguration}, {@link MulticastConfigurationBuilder},
 * {@link NodeConfiguration}, {@link NodeConfigurationBuilder},
 * {@link UDPUnicastConfiguration}, {@link UDPUnicastConfigurationBuilder}.
 *
 * <p>Phase A reviewers performed an all-scope PSI {@code ReferencesSearch} for every class in
 * this package and confirmed <strong>zero references outside {@code core/db/config/}
 * itself</strong> across the full module graph (core / server / driver / embedded /
 * gremlin-annotations / tests / docker-tests). The classes are mutually self-referential —
 * the builders construct the configs and the configs hold references to each other — but
 * nothing in production or test code constructs any of them, calls any of their public
 * methods, or imports the package. The shape was originally drafted as cluster discovery
 * configuration for a feature that never landed.
 *
 * <p>Each test below pins a falsifiable behavioural observable rather than a structural
 * shape, so a future change that mutates a default value, drops a setter, alters a builder
 * return type, or accidentally aliases internal state will fail loudly. Combined, the tests
 * exercise every public ctor, every public/protected setter and getter, the static
 * {@code builder()} factories, and every {@code build()} arm — guaranteeing that a deletion
 * commit either removes this file in lockstep or fails at compile time.
 *
 * <p>WHEN-FIXED: delete the entire {@code core/db/config} package
 * (six classes plus the inner {@link UDPUnicastConfiguration.Address} record) once the
 * deferred-cleanup track absorbs the deletion item. No production callers exist anywhere
 * in the codebase, so the deletion needs only to update or remove this test file.
 *
 * <p>This class is standalone — no database session is needed. The classes under test are
 * pure value objects with no I/O, no static state, and no thread-safety affordances; the
 * tests therefore run cleanly under {@code <parallel>classes</parallel>}.
 */
public class DBConfigDeadCodeTest {

  // ---------------------------------------------------------------------------
  // MulticastConfguration — defaults + protected setters via builder + four-arg ctor
  // ---------------------------------------------------------------------------

  @Test
  public void multicastConfigurationDefaultConstructorPinsHistoricalDefaults() {
    // The package was drafted for cluster discovery; the historical defaults below are
    // load-bearing in the sense that any future caller (or revival) would inherit them.
    // Pinning them ensures a silent change to "enabled=false" or "ip=192.168.x.x" cannot
    // sneak in unnoticed before the deletion lands.
    var cfg = new MulticastConfguration();
    assertTrue("default enabled flag must be true", cfg.isEnabled());
    assertEquals("default multicast IP must be 230.0.0.0", "230.0.0.0", cfg.getIp());
    assertEquals("default multicast port must be 4321", 4321, cfg.getPort());
    assertArrayEquals("default discovery ports must contain only the multicast port",
        new int[] {4321}, cfg.getDiscoveryPorts());
  }

  @Test
  public void multicastConfigurationFourArgConstructorRoundTripsAllFields() {
    // The package-private four-arg ctor is reachable through the builder's build() method.
    // Round-trip via the builder pins both the ctor field assignments and the build()
    // delegation in a single observable.
    var built = MulticastConfguration.builder()
        .setEnabled(false)
        .setIp("239.255.0.1")
        .setPort(7000)
        .setDiscoveryPorts(new int[] {7001, 7002, 7003})
        .build();

    assertFalse("enabled flag must round-trip through build()", built.isEnabled());
    assertEquals("ip must round-trip through build()", "239.255.0.1", built.getIp());
    assertEquals("port must round-trip through build()", 7000, built.getPort());
    assertArrayEquals("discoveryPorts must round-trip through build()",
        new int[] {7001, 7002, 7003}, built.getDiscoveryPorts());
  }

  @Test
  public void multicastConfigurationBuilderProducesFreshInstance() {
    // build() constructs a NEW MulticastConfguration via the four-arg ctor rather than
    // returning the builder's internal accumulator. Pin via assertNotSame so any future
    // refactor that returns the cached field directly fails loudly — that change would
    // alias the builder's mutable state into the produced config.
    var builder = MulticastConfguration.builder();
    var first = builder.build();
    var second = builder.build();
    assertNotSame("each build() call must return a fresh instance", first, second);
  }

  @Test
  public void multicastConfigurationBuilderFactoryReturnsNewBuilderEachCall() {
    // The static builder() factory must not be a singleton — sharing a builder across calls
    // would cross-contaminate fields between unrelated configs. Pin via assertNotSame.
    assertNotSame("builder() must return a fresh builder each call",
        MulticastConfguration.builder(), MulticastConfguration.builder());
  }

  @Test
  public void multicastConfigurationBuilderSettersReturnFluentSelf() {
    // Each setter returns the builder for chaining. Pin the fluent-chaining contract by
    // checking identity, not just non-null — a refactor that drops the return statement
    // would compile but lose chainability.
    var builder = MulticastConfguration.builder();
    assertSame("setEnabled must return the builder itself for chaining",
        builder, builder.setEnabled(true));
    assertSame("setIp must return the builder itself for chaining",
        builder, builder.setIp("224.0.0.1"));
    assertSame("setPort must return the builder itself for chaining",
        builder, builder.setPort(8123));
    assertSame("setDiscoveryPorts must return the builder itself for chaining",
        builder, builder.setDiscoveryPorts(new int[] {8123}));
  }

  // ---------------------------------------------------------------------------
  // NodeConfiguration — both protected ctors via the builder, plus public setters
  // ---------------------------------------------------------------------------

  @Test
  public void nodeConfigurationBuilderDefaultsAreLoadBearingForHistoricalShape() {
    // Defaults from NodeConfigurationBuilder: quorum=2, nodeName=random UUID,
    // groupName="YouTrackDB", groupPassword="YouTrackDB", tcpPort=null. With neither
    // multicast nor unicast set, build() falls into the third arm and constructs a
    // NodeConfiguration with a freshly-defaulted MulticastConfguration.
    var cfg = NodeConfiguration.builder().build();
    assertEquals("default quorum must be 2", 2, cfg.getQuorum());
    assertEquals("default groupName must be 'YouTrackDB'", "YouTrackDB", cfg.getGroupName());
    assertEquals("default groupPassword must be 'YouTrackDB'",
        "YouTrackDB", cfg.getGroupPassword());
    assertNull("default tcpPort must be null", cfg.getTcpPort());
    assertNotNull("default nodeName must be a non-null UUID", cfg.getNodeName());
    // Random UUID is 36 characters with 4 hyphens.
    assertEquals("default nodeName must be a 36-character UUID",
        36, cfg.getNodeName().length());
    assertNotNull("fallback build() arm must populate a MulticastConfguration",
        cfg.getMulticast());
    assertNull("fallback build() arm must leave UDPUnicast null", cfg.getUdpUnicast());
  }

  @Test
  public void nodeConfigurationMulticastBuildArmStoresPassedConfig() {
    // Setting multicast (and not unicast) routes through the multicast-arm ctor, which
    // assigns the multicast field directly. Pin same-identity to catch a regression that
    // accidentally builds a fresh MulticastConfguration instead of forwarding the input.
    var multicast = MulticastConfguration.builder().setIp("239.0.0.10").build();
    var cfg = NodeConfiguration.builder().setMulticast(multicast).build();
    assertSame("multicast-arm ctor must store the supplied MulticastConfguration",
        multicast, cfg.getMulticast());
    assertNull("multicast-arm ctor must leave UDPUnicast null", cfg.getUdpUnicast());
  }

  @Test
  public void nodeConfigurationUnicastBuildArmStoresPassedConfig() {
    // Setting unicast (and not multicast) routes through the unicast-arm ctor.
    var unicast = UDPUnicastConfiguration.builder().setPort(9000).build();
    var cfg = NodeConfiguration.builder().setUnicast(unicast).build();
    assertSame("unicast-arm ctor must store the supplied UDPUnicastConfiguration",
        unicast, cfg.getUdpUnicast());
    assertNull("unicast-arm ctor must leave Multicast null", cfg.getMulticast());
  }

  @Test
  public void nodeConfigurationMulticastTakesPrecedenceWhenBothAreSet() {
    // The build() if/else-if/else cascade favours multicast over unicast — pin the
    // precedence so a future refactor that swaps the order silently changes node
    // bring-up semantics for any caller who set both.
    var multicast = MulticastConfguration.builder().setIp("224.0.0.5").build();
    var unicast = UDPUnicastConfiguration.builder().setPort(11111).build();
    var cfg = NodeConfiguration.builder()
        .setMulticast(multicast)
        .setUnicast(unicast)
        .build();
    assertSame("multicast must take precedence in build()", multicast, cfg.getMulticast());
    assertNull("unicast must be ignored when multicast is present", cfg.getUdpUnicast());
  }

  @Test
  public void nodeConfigurationBuilderRoundTripsAllScalarFields() {
    var cfg = NodeConfiguration.builder()
        .setNodeName("node-1")
        .setGroupName("group-A")
        .setGroupPassword("secret")
        .setQuorum(5)
        .setTcpPort(2424)
        .build();
    assertEquals("node-1", cfg.getNodeName());
    assertEquals("group-A", cfg.getGroupName());
    assertEquals("secret", cfg.getGroupPassword());
    assertEquals(5, cfg.getQuorum());
    assertEquals(Integer.valueOf(2424), cfg.getTcpPort());
  }

  @Test
  public void nodeConfigurationPublicSettersMutateInPlace() {
    // NodeConfiguration exposes public setters for groupName / tcpPort / groupPassword and
    // a protected setter for quorum / multicast. Pin the public-setter mutation paths via
    // a build-then-mutate sequence — these branches would otherwise be unreachable because
    // the rest of the class is dead.
    var cfg = NodeConfiguration.builder().build();

    cfg.setGroupName("renamed");
    cfg.setTcpPort(5005);
    cfg.setGroupPassword("rotated");

    assertEquals("setGroupName must update the field", "renamed", cfg.getGroupName());
    assertEquals("setTcpPort must update the field", Integer.valueOf(5005), cfg.getTcpPort());
    assertEquals("setGroupPassword must update the field", "rotated", cfg.getGroupPassword());
  }

  @Test
  public void nodeConfigurationBuilderFactoryReturnsFreshBuilderEachCall() {
    assertNotSame("builder() must return a fresh builder each call",
        NodeConfiguration.builder(), NodeConfiguration.builder());
  }

  @Test
  public void nodeConfigurationBuilderSettersReturnFluentSelf() {
    var builder = NodeConfiguration.builder();
    assertSame(builder, builder.setQuorum(3));
    assertSame(builder, builder.setNodeName("n"));
    assertSame(builder, builder.setGroupName("g"));
    assertSame(builder, builder.setTcpPort(2424));
    assertSame(builder, builder.setGroupPassword("p"));
    assertSame(builder, builder.setMulticast(new MulticastConfguration()));
    assertSame(builder, builder.setUnicast(new UDPUnicastConfiguration()));
  }

  // ---------------------------------------------------------------------------
  // UDPUnicastConfiguration — defaults, public setters, builder build() arm
  // ---------------------------------------------------------------------------

  @Test
  public void udpUnicastConfigurationDefaultConstructorPinsHistoricalDefaults() {
    var cfg = new UDPUnicastConfiguration();
    assertTrue("default enabled flag must be true", cfg.isEnabled());
    assertEquals("default port must be 4321", 4321, cfg.getPort());
    assertNotNull("default discoveryAddresses must be a non-null empty list",
        cfg.getDiscoveryAddresses());
    assertTrue("default discoveryAddresses must start empty",
        cfg.getDiscoveryAddresses().isEmpty());
  }

  @Test
  public void udpUnicastConfigurationPublicSettersMutateInPlace() {
    var cfg = new UDPUnicastConfiguration();
    cfg.setEnabled(false);
    cfg.setPort(7700);
    cfg.setDiscoveryAddresses(List.of(new Address("10.0.0.1", 7700)));
    assertFalse(cfg.isEnabled());
    assertEquals(7700, cfg.getPort());
    assertEquals(1, cfg.getDiscoveryAddresses().size());
    assertEquals("10.0.0.1", cfg.getDiscoveryAddresses().get(0).address());
    assertEquals(7700, cfg.getDiscoveryAddresses().get(0).port());
  }

  @Test
  public void udpUnicastConfigurationBuilderRoundTripsScalarsAndCopiesAddresses() {
    // build() defensively copies the discoveryAddresses list (new ArrayList<>(...)) so that
    // mutations to the produced config don't propagate back into the builder. Pin the copy
    // by mutating the produced list and re-building from the same builder; the second build
    // must reflect only what was originally set on the builder, not the post-build mutation.
    var builder = UDPUnicastConfiguration.builder()
        .setEnabled(false)
        .setPort(8500)
        .addAddress("10.0.0.1", 8500)
        .addAddress("10.0.0.2", 8500);

    var first = builder.build();
    assertFalse(first.isEnabled());
    assertEquals(8500, first.getPort());
    assertEquals(2, first.getDiscoveryAddresses().size());

    // Mutate the produced list. If build() shared the list with the builder, the second
    // build would carry the mutation forward — that's exactly what the defensive copy
    // prevents.
    first.getDiscoveryAddresses().add(new Address("10.0.0.99", 9999));
    var second = builder.build();
    assertEquals("post-build mutation of first must not affect builder state",
        2, second.getDiscoveryAddresses().size());
  }

  @Test
  public void udpUnicastConfigurationBuilderAddAddressAppendsInOrder() {
    // addAddress mutates the builder's confguration field directly; pin both ordering and
    // multiplicity so an accidental switch to a Set or a reverse-order append fails.
    var cfg = UDPUnicastConfiguration.builder()
        .addAddress("a.example", 1)
        .addAddress("b.example", 2)
        .addAddress("c.example", 3)
        .build();
    var addresses = cfg.getDiscoveryAddresses();
    assertEquals(3, addresses.size());
    assertEquals("a.example", addresses.get(0).address());
    assertEquals(1, addresses.get(0).port());
    assertEquals("b.example", addresses.get(1).address());
    assertEquals(2, addresses.get(1).port());
    assertEquals("c.example", addresses.get(2).address());
    assertEquals(3, addresses.get(2).port());
  }

  @Test
  public void udpUnicastConfigurationBuilderFactoryReturnsFreshBuilderEachCall() {
    assertNotSame("builder() must return a fresh builder each call",
        UDPUnicastConfiguration.builder(), UDPUnicastConfiguration.builder());
  }

  @Test
  public void udpUnicastConfigurationBuilderSettersReturnFluentSelf() {
    var builder = UDPUnicastConfiguration.builder();
    assertSame(builder, builder.setEnabled(false));
    assertSame(builder, builder.setPort(1234));
    assertSame(builder, builder.addAddress("host", 1234));
  }

  // ---------------------------------------------------------------------------
  // UDPUnicastConfiguration.Address — record component accessors
  // ---------------------------------------------------------------------------

  @Test
  public void addressRecordExposesAddressAndPortComponents() {
    var addr = new Address("192.0.2.1", 12345);
    assertEquals("192.0.2.1", addr.address());
    assertEquals(12345, addr.port());
  }

  @Test
  public void addressRecordEqualsAndHashCodeFollowComponentEquality() {
    // Records auto-generate equals/hashCode from components. Pin both so a future
    // change to a non-record class without an explicit equals override would fail.
    var a = new Address("h", 1);
    var b = new Address("h", 1);
    var c = new Address("h", 2);
    var d = new Address("i", 1);
    assertEquals("records with equal components must be equal", a, b);
    assertEquals("records with equal components must hash equally",
        a.hashCode(), b.hashCode());
    assertNotSame("two new() calls must return distinct instances", a, b);
    assertFalse("differing port must break equality", a.equals(c));
    assertFalse("differing address must break equality", a.equals(d));
  }
}
