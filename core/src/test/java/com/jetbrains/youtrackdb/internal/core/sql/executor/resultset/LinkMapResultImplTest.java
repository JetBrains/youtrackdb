package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.LinkTestFixtures.rid;
import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Direct unit tests for {@link LinkMapResultImpl} — a pure delegating {@link
 * com.jetbrains.youtrackdb.internal.core.query.collection.links.LinkMap} wrapper around a {@code
 * HashMap<String, Identifiable>}. All tests are standalone.
 */
public class LinkMapResultImplTest {

  // ------------------------------------------------------------------------- constructors

  @Test
  public void defaultConstructorCreatesEmptyMap() {
    var map = new LinkMapResultImpl();
    assertThat(map).isEmpty();
    assertThat(map.size()).isZero();
  }

  @Test
  public void initialCapacityConstructorIsEmpty() {
    var map = new LinkMapResultImpl(8);
    assertThat(map).isEmpty();
  }

  /**
   * WHEN-FIXED: Track 22 — the int-capacity constructor backs the map with a {@link
   * LinkedHashMap} while the other two constructors use {@link HashMap}. Observable difference:
   * iteration is insertion-ordered only for the int-capacity instance. If the inconsistency is
   * unified (both to HashMap), this test will fail and should be rewritten to assert
   * unordered iteration.
   */
  @Test
  public void initialCapacityConstructorPreservesInsertionOrder() {
    var map = new LinkMapResultImpl(16);
    map.put("z", rid(1, 0));
    map.put("a", rid(1, 1));
    map.put("m", rid(1, 2));
    assertThat(map.keySet()).containsExactly("z", "a", "m");
  }

  @Test
  public void copyConstructorPopulatesFromSource() {
    var source = new HashMap<String, Identifiable>();
    source.put("a", rid(1, 0));
    source.put("b", rid(1, 1));
    var copy = new LinkMapResultImpl(source);
    assertThat(copy).containsEntry("a", rid(1, 0)).containsEntry("b", rid(1, 1));
    source.put("c", rid(1, 2));
    assertThat(copy).doesNotContainKey("c");
  }

  // ------------------------------------------------------------------------- put/get/remove

  @Test
  public void putReturnsPreviousValue() {
    var map = new LinkMapResultImpl();
    assertThat(map.put("a", rid(1, 0))).isNull();
    assertThat(map.put("a", rid(2, 0))).isEqualTo(rid(1, 0));
  }

  @Test
  public void getWithNonStringKeyReturnsNull() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.get(42)).isNull();
    assertThat(map.get("a")).isEqualTo(rid(1, 0));
    assertThat(map.get("missing")).isNull();
  }

  @Test
  public void containsKeyIsStringTyped() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.containsKey("a")).isTrue();
    assertThat(map.containsKey(42)).isFalse();
    assertThat(map.containsKey("missing")).isFalse();
  }

  @Test
  public void containsValueIsIdentifiableTyped() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.containsValue(rid(1, 0))).isTrue();
    assertThat(map.containsValue("not identifiable")).isFalse();
    assertThat(map.containsValue(rid(9, 9))).isFalse();
  }

  @Test
  public void removeByNonStringKeyReturnsNull() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.remove(42)).isNull();
    // Untouched
    assertThat(map.get("a")).isEqualTo(rid(1, 0));
  }

  @Test
  public void removeByStringKeyReturnsRemovedValue() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.remove("a")).isEqualTo(rid(1, 0));
    assertThat(map).isEmpty();
  }

  @Test
  public void getOrDefaultReturnsDefaultForNonStringKey() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.getOrDefault(42, rid(9, 9))).isEqualTo(rid(9, 9));
    assertThat(map.getOrDefault("a", rid(9, 9))).isEqualTo(rid(1, 0));
    assertThat(map.getOrDefault("missing", rid(9, 9))).isEqualTo(rid(9, 9));
  }

  /**
   * Pin the null-key short-circuit on all type-guarded accessors. {@code null instanceof String}
   * is false, so these paths return defaults without reaching the backing map — a distinct branch
   * from the {@code Integer} case covered above.
   */
  @Test
  public void nullKeyReturnsDefaultsOnAllAccessors() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.containsKey(null)).isFalse();
    assertThat(map.get(null)).isNull();
    assertThat(map.remove(null)).isNull();
    assertThat(map.getOrDefault(null, rid(9, 9))).isEqualTo(rid(9, 9));
  }

  /**
   * Documents the divergence from {@link HashMap#containsValue(Object)}: the type-guarded
   * {@code containsValue(null)} always returns false, even when a {@code null} value is mapped.
   * A plain HashMap would return true under the same condition.
   */
  @Test
  public void containsValueForNullReturnsFalseEvenWhenNullMapped() {
    var map = new LinkMapResultImpl();
    map.put("a", null);
    // Wrapper — null is never Identifiable, so always false.
    assertThat(map.containsValue(null)).isFalse();
    // Reference HashMap behavior — a mapped null value IS contained.
    var reference = new HashMap<String, Identifiable>();
    reference.put("a", null);
    assertThat(reference.containsValue(null)).isTrue();
  }

  // ------------------------------------------------------------------------- bulk ops

  @Test
  public void putAllMergesEntries() {
    var map = new LinkMapResultImpl();
    map.put("x", rid(0, 0));
    map.putAll(Map.of("a", rid(1, 0), "b", rid(1, 1)));
    assertThat(map).containsKeys("x", "a", "b");
  }

  @Test
  public void clearEmptiesMap() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    map.clear();
    assertThat(map).isEmpty();
  }

  // ------------------------------------------------------------------------- compute / merge

  @Test
  public void putIfAbsentReturnsExistingValueWithoutOverwriting() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.putIfAbsent("a", rid(2, 0))).isEqualTo(rid(1, 0));
    assertThat(map.get("a")).isEqualTo(rid(1, 0));
    assertThat(map.putIfAbsent("b", rid(1, 1))).isNull();
    assertThat(map.get("b")).isEqualTo(rid(1, 1));
  }

  @Test
  public void removeByKeyValuePairOnlyRemovesIfValueMatches() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.remove("a", rid(9, 9))).isFalse();
    assertThat(map.remove("a", rid(1, 0))).isTrue();
    assertThat(map).isEmpty();
  }

  @Test
  public void replaceWithOldValueOnlyReplacesIfMatching() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.replace("a", rid(9, 9), rid(2, 0))).isFalse();
    assertThat(map.replace("a", rid(1, 0), rid(2, 0))).isTrue();
    assertThat(map.get("a")).isEqualTo(rid(2, 0));
  }

  @Test
  public void replaceReturnsPreviousValueOrNull() {
    var map = new LinkMapResultImpl();
    assertThat(map.replace("a", rid(1, 0))).isNull();
    map.put("a", rid(1, 0));
    assertThat(map.replace("a", rid(2, 0))).isEqualTo(rid(1, 0));
    assertThat(map.get("a")).isEqualTo(rid(2, 0));
  }

  @Test
  public void computeIfAbsentInsertsOnlyWhenMissing() {
    var map = new LinkMapResultImpl();
    assertThat(map.computeIfAbsent("a", k -> rid(1, 0))).isEqualTo(rid(1, 0));
    // Already present: mapping function must not be invoked.
    assertThat(map.computeIfAbsent("a", k -> rid(9, 9))).isEqualTo(rid(1, 0));
  }

  @Test
  public void computeIfPresentReplacesOrRemovesByReturnValue() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.computeIfPresent("a", (k, v) -> rid(2, 0))).isEqualTo(rid(2, 0));
    // Return null -> remove
    assertThat(map.computeIfPresent("a", (k, v) -> null)).isNull();
    assertThat(map).doesNotContainKey("a");
    // Missing key: no-op
    assertThat(map.computeIfPresent("missing", (k, v) -> rid(9, 9))).isNull();
  }

  @Test
  public void computeInsertsWhenAbsentAndUpdatesWhenPresent() {
    var map = new LinkMapResultImpl();
    assertThat(map.compute("a", (k, v) -> rid(1, 0))).isEqualTo(rid(1, 0));
    assertThat(map.compute("a", (k, v) -> rid(2, 0))).isEqualTo(rid(2, 0));
  }

  @Test
  public void mergeCombinesValues() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    var merged = map.merge("a", rid(1, 1), (oldV, newV) -> rid(9, 9));
    assertThat(merged).isEqualTo(rid(9, 9));
    assertThat(map.get("a")).isEqualTo(rid(9, 9));
    // Merge on absent -> inserts the new value
    var inserted = map.merge("b", rid(1, 0), (oldV, newV) -> rid(9, 9));
    assertThat(inserted).isEqualTo(rid(1, 0));
  }

  @Test
  public void replaceAllAppliesFunction() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    map.put("b", rid(1, 1));
    map.replaceAll((k, v) -> rid(9, 9));
    assertThat(map.get("a")).isEqualTo(rid(9, 9));
    assertThat(map.get("b")).isEqualTo(rid(9, 9));
  }

  @Test
  public void forEachVisitsEveryEntry() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    map.put("b", rid(1, 1));
    var seen = new HashMap<String, Identifiable>();
    map.forEach(seen::put);
    assertThat(seen).containsEntry("a", rid(1, 0)).containsEntry("b", rid(1, 1));
  }

  // ------------------------------------------------------------------------- views

  @Test
  public void keySetExposesAllKeys() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    map.put("b", rid(1, 1));
    assertThat(map.keySet()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  public void valuesExposesAllValues() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    map.put("b", rid(1, 1));
    assertThat(map.values()).containsExactlyInAnyOrder(rid(1, 0), rid(1, 1));
  }

  @Test
  public void entrySetExposesAllEntries() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.entrySet()).hasSize(1);
    var e = map.entrySet().iterator().next();
    assertThat(e.getKey()).isEqualTo("a");
    assertThat(e.getValue()).isEqualTo(rid(1, 0));
  }

  /** keySet removal propagates to the backing map (pins live-backing contract). */
  @Test
  public void keySetRemovalPropagatesToMap() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    map.put("b", rid(1, 1));
    assertThat(map.keySet().remove("a")).isTrue();
    assertThat(map).doesNotContainKey("a").containsKey("b");
  }

  @Test
  public void valuesIteratorRemovePropagatesToMap() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    var it = map.values().iterator();
    it.next();
    it.remove();
    assertThat(map).isEmpty();
  }

  @Test
  public void entrySetClearPropagatesToMap() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    map.entrySet().clear();
    assertThat(map).isEmpty();
  }

  // ------------------------------------------------------------------------- equals/hashCode/toString

  @Test
  public void equalsReturnsTrueForSameInstance() {
    var map = new LinkMapResultImpl();
    map.put("a", rid(1, 0));
    assertThat(map.equals(map)).isTrue();
  }

  @Test
  public void equalsReturnsFalseForNonMapObject() {
    var map = new LinkMapResultImpl();
    assertThat(map.equals("not a map")).isFalse();
    assertThat(map.equals(null)).isFalse();
    assertThat(map.equals(List.of())).isFalse();
  }

  /**
   * WHEN-FIXED: Track 22 — {@code LinkMapResultImpl.equals(Object)} delegates to {@code
   * super.equals(obj)} ({@code Object.equals}) instead of {@code map.equals(obj)}. Two distinct
   * instances with identical entries are reported as unequal even though both pass the
   * {@code instanceof Map} check. Flip the final-line delegation to {@code map.equals(obj)}. This
   * test pins the buggy contract and the fix falsifies it.
   */
  @Test
  public void equalsReturnsFalseBetweenDistinctInstancesBecauseSuperEqualsIsBuggy() {
    var a = new LinkMapResultImpl();
    a.put("a", rid(1, 0));
    var b = new LinkMapResultImpl();
    b.put("a", rid(1, 0));
    // Current bug: super.equals(b) -> false. WHEN-FIXED: assertEquals(a, b).
    assertThat(a).isNotEqualTo(b);
  }

  /**
   * WHEN-FIXED: Track 22 — same defect; equality vs a plain {@link LinkedHashMap} with identical
   * entries also fails.
   */
  @Test
  public void equalsAgainstForeignMapReturnsFalseBecauseSuperEqualsIsBuggy() {
    var link = new LinkMapResultImpl();
    link.put("a", rid(1, 0));
    var other = new LinkedHashMap<String, Identifiable>();
    other.put("a", rid(1, 0));
    assertThat(link.equals(other)).isFalse();
    var plainHash = new HashMap<String, Identifiable>();
    plainHash.put("a", rid(1, 0));
    assertThat(link.equals(plainHash)).isFalse();
    // Asymmetric equals! Because HashMap.equals iterates its entrySet and calls link.get(key),
    // plainHash.equals(link) returns true (entries match via delegation), while
    // link.equals(plainHash) returns false (super.equals bug). This violates the equals
    // contract. WHEN-FIXED: both directions become true.
    assertThat(plainHash.equals(link)).isTrue();
    assertThat(link.equals(plainHash)).isFalse();
  }

  @Test
  public void hashCodeMatchesBackingMap() {
    var link = new LinkMapResultImpl();
    link.put("a", rid(1, 0));
    link.put("b", rid(1, 1));
    var other = new HashMap<String, Identifiable>();
    other.put("a", rid(1, 0));
    other.put("b", rid(1, 1));
    assertThat(link.hashCode()).isEqualTo(other.hashCode());
  }

  @Test
  public void toStringMatchesMapFormat() {
    var link = new LinkMapResultImpl();
    link.put("a", rid(1, 0));
    var hashMap = new HashMap<String, Identifiable>();
    hashMap.put("a", rid(1, 0));
    assertThat(link.toString()).isEqualTo(hashMap.toString());
  }
}
