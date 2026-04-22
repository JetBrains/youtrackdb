package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Direct unit tests for {@link EmbeddedMapResultImpl} — a pure delegating {@link
 * com.jetbrains.youtrackdb.internal.core.query.collection.embedded.EmbeddedMap} wrapper around a
 * {@code HashMap<String, T>}. Unlike its Link counterpart, this implementation correctly
 * delegates {@code equals} to {@code map.equals(obj)} — no WHEN-FIXED marker needed.
 */
public class EmbeddedMapResultImplTest {

  @Test
  public void defaultConstructorCreatesEmptyMap() {
    var map = new EmbeddedMapResultImpl<String>();
    assertThat(map).isEmpty();
  }

  @Test
  public void initialCapacityConstructorIsEmpty() {
    var map = new EmbeddedMapResultImpl<String>(8);
    assertThat(map).isEmpty();
  }

  @Test
  public void copyConstructorPopulatesFromSource() {
    var source = new HashMap<String, String>();
    source.put("a", "1");
    source.put("b", "2");
    var copy = new EmbeddedMapResultImpl<>(source);
    assertThat(copy).containsEntry("a", "1").containsEntry("b", "2");
    source.put("c", "3");
    assertThat(copy).doesNotContainKey("c");
  }

  @Test
  public void putReturnsPreviousValue() {
    var map = new EmbeddedMapResultImpl<String>();
    assertThat(map.put("a", "1")).isNull();
    assertThat(map.put("a", "2")).isEqualTo("1");
  }

  @Test
  public void getAndContainsKeyDelegateToHashMap() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    assertThat(map.get("a")).isEqualTo("1");
    assertThat(map.get("missing")).isNull();
    assertThat(map.containsKey("a")).isTrue();
    assertThat(map.containsKey("z")).isFalse();
  }

  @Test
  public void containsValueAnswersByValue() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    assertThat(map.containsValue("1")).isTrue();
    assertThat(map.containsValue("z")).isFalse();
  }

  @Test
  public void removeReturnsValue() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    assertThat(map.remove("a")).isEqualTo("1");
    assertThat(map).isEmpty();
  }

  @Test
  public void getOrDefaultFallsBackWhenMissing() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    assertThat(map.getOrDefault("a", "fallback")).isEqualTo("1");
    assertThat(map.getOrDefault("missing", "fallback")).isEqualTo("fallback");
  }

  @Test
  public void putAllAndClearWork() {
    var map = new EmbeddedMapResultImpl<String>();
    map.putAll(Map.of("a", "1", "b", "2"));
    assertThat(map).containsKeys("a", "b");
    map.clear();
    assertThat(map).isEmpty();
  }

  @Test
  public void putIfAbsentOnlyInsertsIfMissing() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    assertThat(map.putIfAbsent("a", "2")).isEqualTo("1");
    assertThat(map.putIfAbsent("b", "3")).isNull();
    assertThat(map.get("b")).isEqualTo("3");
  }

  @Test
  public void removeKeyValuePairGatesByValue() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    assertThat(map.remove("a", "2")).isFalse();
    assertThat(map.remove("a", "1")).isTrue();
  }

  @Test
  public void replaceBranches() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    assertThat(map.replace("a", "2")).isEqualTo("1");
    assertThat(map.replace("a", "wrong", "3")).isFalse();
    assertThat(map.replace("a", "2", "3")).isTrue();
    assertThat(map.get("a")).isEqualTo("3");
  }

  @Test
  public void computeIfAbsentInsertsOnlyWhenMissing() {
    var map = new EmbeddedMapResultImpl<String>();
    assertThat(map.computeIfAbsent("a", k -> "1")).isEqualTo("1");
    assertThat(map.computeIfAbsent("a", k -> "2")).isEqualTo("1");
  }

  @Test
  public void computeIfPresentReplacesOrRemoves() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    assertThat(map.computeIfPresent("a", (k, v) -> "2")).isEqualTo("2");
    assertThat(map.computeIfPresent("a", (k, v) -> null)).isNull();
    assertThat(map).doesNotContainKey("a");
    assertThat(map.computeIfPresent("missing", (k, v) -> "ignored")).isNull();
  }

  @Test
  public void computeReplacesOrInserts() {
    var map = new EmbeddedMapResultImpl<String>();
    assertThat(map.compute("a", (k, v) -> "1")).isEqualTo("1");
    assertThat(map.compute("a", (k, v) -> v + "-upd")).isEqualTo("1-upd");
    assertThat(map.compute("a", (k, v) -> null)).isNull();
    assertThat(map).doesNotContainKey("a");
  }

  @Test
  public void mergeCombinesValuesOrInserts() {
    var map = new EmbeddedMapResultImpl<String>();
    assertThat(map.merge("a", "1", (l, r) -> l + r)).isEqualTo("1");
    assertThat(map.merge("a", "2", (l, r) -> l + r)).isEqualTo("12");
    assertThat(map.get("a")).isEqualTo("12");
  }

  @Test
  public void replaceAllTransformsValues() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    map.put("b", "2");
    map.replaceAll((k, v) -> v + v);
    assertThat(map.get("a")).isEqualTo("11");
    assertThat(map.get("b")).isEqualTo("22");
  }

  @Test
  public void forEachVisitsEntries() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    map.put("b", "2");
    var seen = new HashMap<String, String>();
    map.forEach(seen::put);
    assertThat(seen).containsEntry("a", "1").containsEntry("b", "2");
  }

  @Test
  public void viewsAreLiveAndComplete() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    map.put("b", "2");
    assertThat(map.keySet()).containsExactlyInAnyOrder("a", "b");
    assertThat(map.values()).containsExactlyInAnyOrder("1", "2");
    assertThat(map.entrySet()).hasSize(2);
  }

  // ------------------------------------------------------------------------- equals/hashCode

  @Test
  public void equalsReturnsTrueForSameInstance() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    assertThat(map.equals(map)).isTrue();
  }

  /**
   * Unlike the Link variants, EmbeddedMap delegates equals properly, so cross-instance equality
   * works.
   */
  @Test
  public void equalsDelegatesToBackingMapAcrossInstances() {
    var a = new EmbeddedMapResultImpl<String>();
    a.put("k", "v");
    var b = new EmbeddedMapResultImpl<String>();
    b.put("k", "v");
    assertThat(a).isEqualTo(b);
  }

  @Test
  public void equalsAgainstLinkedHashMapWithSameEntries() {
    var a = new EmbeddedMapResultImpl<String>();
    a.put("k", "v");
    var other = new LinkedHashMap<String, String>();
    other.put("k", "v");
    assertThat(a).isEqualTo(other);
  }

  @Test
  public void equalsReturnsFalseForNonMap() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    assertThat(map.equals("not a map")).isFalse();
    assertThat(map.equals(null)).isFalse();
    assertThat(map.equals(java.util.List.of())).isFalse();
  }

  @Test
  public void hashCodeMatchesBackingMap() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    map.put("b", "2");
    var other = new HashMap<String, String>();
    other.put("a", "1");
    other.put("b", "2");
    assertThat(map.hashCode()).isEqualTo(other.hashCode());
  }

  @Test
  public void toStringMatchesMapFormat() {
    var map = new EmbeddedMapResultImpl<String>();
    map.put("a", "1");
    var hash = new HashMap<String, String>();
    hash.put("a", "1");
    assertThat(map.toString()).isEqualTo(hash.toString());
  }

  @Test
  public void sizeAndIsEmptyReflectState() {
    var map = new EmbeddedMapResultImpl<String>();
    assertThat(map.isEmpty()).isTrue();
    map.put("a", "1");
    assertThat(map.size()).isEqualTo(1);
    assertThat(map.isEmpty()).isFalse();
  }
}
