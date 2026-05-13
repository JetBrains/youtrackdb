package com.jetbrains.youtrackdb.internal.core.gremlin;

import static com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphUtils.decodeClassName;
import static com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphUtils.encodeClassName;
import static com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphUtils.mapDirection;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.apache.tinkerpop.gremlin.structure.Direction;
import org.hamcrest.Matchers;
import org.junit.Test;

public class GraphUtilsTest {
  @Test
  public void encode() {
    assertThat(encodeClassName(null), Matchers.nullValue());
    assertThat(encodeClassName("01"), Matchers.equalTo("-01"));
    assertThat(encodeClassName("my spaced url"), Matchers.equalTo("my+spaced+url"));
    assertThat(encodeClassName("UnChAnGeD"), Matchers.equalTo("UnChAnGeD"));
  }

  @Test
  public void decode() {
    assertThat(decodeClassName(null), Matchers.nullValue());
    assertThat(decodeClassName("-01"), Matchers.equalTo("01"));
    assertThat(decodeClassName("my+spaced+url"), Matchers.equalTo("my spaced url"));
    assertThat(decodeClassName("UnChAnGeD"), Matchers.equalTo("UnChAnGeD"));
  }

  /**
   * Verify the TinkerPop-direction → YouTrackDB-direction map for every enum value. Pinning all
   * three branches catches any future enum-add that forgets to extend the {@code switch}
   * (the absence of a {@code default} arm makes the compile-time check load-bearing — but the
   * test pins the runtime behaviour so a coercion to a wider enum type would surface as a
   * test failure rather than a hidden mapping mistake).
   */
  @Test
  public void mapDirectionTinkerPopToInternalCoversAllEnumValues() {
    assertEquals(
        com.jetbrains.youtrackdb.internal.core.db.record.record.Direction.OUT,
        mapDirection(Direction.OUT));
    assertEquals(
        com.jetbrains.youtrackdb.internal.core.db.record.record.Direction.IN,
        mapDirection(Direction.IN));
    assertEquals(
        com.jetbrains.youtrackdb.internal.core.db.record.record.Direction.BOTH,
        mapDirection(Direction.BOTH));
  }
}
