package com.jetbrains.youtrack.db.internal.core.gremlin;

import static com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphUtils.decodeClassName;
import static com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphUtils.encodeClassName;
import static org.junit.Assert.assertThat;

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
}
