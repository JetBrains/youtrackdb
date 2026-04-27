/*
 *
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
package com.jetbrains.youtrackdb.internal.core.schedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Standalone unit tests for the fluent setter chain of {@link ScheduledEventBuilder}.
 *
 * <p>The builder is a fixture-only DTO over an internal {@code Map<String, Object>} —
 * setters store their argument under a known key from {@link ScheduledEvent} and return
 * {@code this} so callers can chain. The terminal {@link
 * ScheduledEventBuilder#build(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)}
 * call requires a database session to materialize the entity; the round-trip through
 * {@code build} is therefore covered by {@link ScheduledEventTest}, while this class
 * pins everything observable without a session.
 *
 * <p>Tests are pure JVM unit tests with no DB dependency, so they do not need
 * {@code @Category(SequentialTest.class)} and do not poison the surefire JVM with
 * thread-locals or scheduled tasks.
 */
public class ScheduledEventBuilderTest {

  @Test
  public void defaultConstructorProducesEmptyMap() {
    var b = new ScheduledEventBuilder();
    assertNotNull("properties map must be initialized", b.properties);
    assertTrue("freshly-built builder has no properties yet", b.properties.isEmpty());
  }

  @Test
  public void setNameStoresValueUnderNameKeyAndReturnsSameInstanceForFluentChain() {
    var b = new ScheduledEventBuilder();
    var ret = b.setName("daily-report");
    assertSame("setName must return the same builder for fluent chaining", b, ret);
    assertEquals("daily-report", b.properties.get(ScheduledEvent.PROP_NAME));
  }

  @Test
  public void setRuleStoresValueUnderRuleKeyAndReturnsSameInstanceForFluentChain() {
    var b = new ScheduledEventBuilder();
    var ret = b.setRule("0 0 12 * * ?");
    assertSame(b, ret);
    assertEquals("0 0 12 * * ?", b.properties.get(ScheduledEvent.PROP_RULE));
  }

  @Test
  public void setArgumentsStoresMapByReferenceUnderArgumentsKey() {
    var b = new ScheduledEventBuilder();
    Map<Object, Object> args = new HashMap<>();
    args.put("note", "hello");
    var ret = b.setArguments(args);
    assertSame(b, ret);
    // Pin reference identity — the builder does not defensively copy. A consumer mutating
    // the same map after build() will see the mutation reflected on the entity.
    assertSame("builder stores argument map by reference", args,
        b.properties.get(ScheduledEvent.PROP_ARGUMENTS));
  }

  @Test
  public void setStartTimeStoresDateByReferenceUnderStartTimeKey() {
    var b = new ScheduledEventBuilder();
    var d = new Date(1_700_000_000_000L);
    var ret = b.setStartTime(d);
    assertSame(b, ret);
    // Pin reference identity for the same reason as setArguments — no defensive copy.
    assertSame(d, b.properties.get(ScheduledEvent.PROP_STARTTIME));
  }

  @Test
  public void setFunctionStoresNullValueUnderFuncKeyAndPreservesKeyPresence() {
    // Pass null — the setter does not validate; valid Function instances require a session and
    // are exercised in ScheduledEventTest. We pin that the storage location of whatever value is
    // passed is the PROP_FUNC key, and that the key is materialized in the map even for null.
    var b = new ScheduledEventBuilder();
    var ret = b.setFunction(null);
    assertSame(b, ret);
    assertNull(b.properties.get(ScheduledEvent.PROP_FUNC));
    assertTrue("setFunction(null) must still record the key so build() sees it",
        b.properties.containsKey(ScheduledEvent.PROP_FUNC));
  }

  @Test
  public void setterChainOfDistinctKeysAccumulatesIntoSinglePropertiesMap() {
    var b = new ScheduledEventBuilder()
        .setName("daily")
        .setRule("0 0 12 * * ?")
        .setArguments(new HashMap<>());
    assertEquals(3, b.properties.size());
    assertEquals("daily", b.properties.get(ScheduledEvent.PROP_NAME));
    assertEquals("0 0 12 * * ?", b.properties.get(ScheduledEvent.PROP_RULE));
    assertNotNull(b.properties.get(ScheduledEvent.PROP_ARGUMENTS));
  }

  @Test
  public void setterCalledTwiceForSameKeyOverwritesPreviousValue() {
    var b = new ScheduledEventBuilder()
        .setName("first")
        .setName("second");
    assertEquals("second", b.properties.get(ScheduledEvent.PROP_NAME));
    assertEquals("repeated setter must not duplicate keys", 1, b.properties.size());
  }

  @Test
  public void toStringContainsClassNameAndPropertiesContent() {
    var s = new ScheduledEventBuilder().setName("daily").toString();
    assertTrue("toString format begins with class name: <" + s + ">",
        s.startsWith("ScheduledEventBuilder{"));
    assertTrue("toString format ends with closing brace: <" + s + ">", s.endsWith("}"));
    // The map's toString embeds key=value pairs — pin that the inner content is reachable
    // (assertion content-only, not exact: HashMap iteration order is unspecified).
    assertTrue("toString must include property content: <" + s + ">", s.contains("name=daily"));
    assertTrue("toString must label the inner map as 'properties=': <" + s + ">",
        s.contains("properties="));
  }

  @Test
  public void publicPropertiesFieldIsMutableExposedSurfaceObservableByBuild() {
    // Pin the public-field surface (properties is accessible and writable from outside).
    // build() reads from this map via updateFromMap, so external manipulation between setter
    // calls and build() is observable on the resulting entity. The test therefore captures
    // an intentional contract rather than incidental visibility.
    var b = new ScheduledEventBuilder();
    b.properties.put("custom-key", "custom-value");
    assertEquals("custom-value", b.properties.get("custom-key"));
  }
}
