package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class T5FreezeCaptureTest {
  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private static final T5FreezeCapture.Key KEY = new T5FreezeCapture.Key(
      "C/ic2", "slot-0", "native", T5FreezeCapture.Regime.COLD);

  private static T5FreezeCapture.Item item(T5FreezeCapture.Key key,
      Map<String, T5FreezeCapture.Value> feeds,
      List<?> answers, String failure) {
    return new T5FreezeCapture.Item(key, feeds, T5ResultComparison.Order.SEQUENCE,
        answers.stream().map(T5FreezeCapture.Value::of).toList(), "native", "translator disabled",
        new T5FreezeCapture.Cache("abc", "new JVM", T5FreezeCapture.Observation.BYPASS, "disabled"),
        new T5FreezeCapture.Declaration(T5FreezeCapture.Value.of(List.of("has", T.label)),
            Map.of("root:2", "declared-not-validated"), Map.of("root:1", "inside unit")),
        true, "baseline lacks label fix", failure);
  }

  private static T5FreezeCapture.Capture capture(boolean complete,
      List<T5FreezeCapture.Item> items) {
    return new T5FreezeCapture.Capture(1, complete, Map.of("native", "abc", "baseline", "def"),
        Map.of("params", "a".repeat(64)), items);
  }

  private static T5FreezeCapture.Item simple() {
    return item(KEY, Map.of("person", T5FreezeCapture.Value.of(42L)), List.of(7L), "");
  }

  private Path path() {
    return folder.getRoot().toPath().resolve("capture.json");
  }

  @Test
  public void everyValueKindRoundTripsWithLiteralTypesAndLogicalKeys() throws Exception {
    var nullable = new LinkedHashMap<Object, Object>();
    nullable.put(T.id, null);
    nullable.put("nested", Arrays.asList(1, 2L, "3", null));
    List<?> rows = Arrays.asList(null, "text", true, 'x', (byte) 1, (short) 2, 3, 4L,
        1.25f, -0.0d, Float.NaN, Double.POSITIVE_INFINITY, new BigInteger("9007199254740993"),
        new BigDecimal("1.2300"), new Date(1234567890123L), T.label, nullable,
        new AbstractMap.SimpleImmutableEntry<>("k", null),
        new T5ResultComparison.ElementKey("edge", "knows", List.of(11L, 12L, 2)));
    var source =
        capture(true, List.of(item(KEY, Map.of("feed", T5FreezeCapture.Value.of(rows)), rows, "")));
    T5FreezeCapture.write(path(), source);
    var restored = T5FreezeCapture.read(path());
    assertEquals(source, restored);
    var expected =
        new T5ResultComparison.Expected("literal", T5ResultComparison.Order.SEQUENCE, rows);
    T5ResultComparison.requireEqual(expected, restored.items().getFirst().expected().rows(), e -> {
      throw new AssertionError("Physical element must not occur");
    });
    T5ResultComparison.requireEqual(expected,
        (List<?>) restored.items().getFirst().feeds().get("feed").decode(), e -> null);
    assertTrue(Files.readString(path()).contains("\"type\":\"Long\",\"scalar\":\"4\""));
    assertEquals(new T5FreezeCapture.Value("Integer", "3", List.of()),
        restored.items().getFirst().answers().get(6));
  }

  @Test
  public void restoredAnswersRejectTypeDateNullOrderAndMultiplicityDrift() throws Exception {
    List<?> rows = List.of(1L, new Date(1001), Map.of("present", "x"), List.of(1, 2), 1L);
    T5FreezeCapture.write(path(), capture(true, List.of(item(KEY, Map.of(), rows, ""))));
    var expected = T5FreezeCapture.read(path()).items().getFirst().expected();
    for (Object wrong : List.of(1, "1")) {
      assertThrows(AssertionError.class, () -> T5ResultComparison.requireEqual(expected,
          List.of(wrong, new Date(1001), Map.of("present", "x"), List.of(1, 2), 1L), e -> null));
    }
    assertThrows(AssertionError.class, () -> T5ResultComparison.requireEqual(expected,
        List.of(1L, new Date(1000), Map.of("present", "x"), List.of(1, 2), 1L), e -> null));
    var nullMap = new LinkedHashMap<String, Object>();
    nullMap.put("missing", null);
    assertThrows(AssertionError.class, () -> T5ResultComparison.requireEqual(
        new T5ResultComparison.Expected("absent", T5ResultComparison.Order.SEQUENCE,
            List.of(Map.of())),
        List.of(nullMap), e -> null));
    var reversed = List.of(1L, List.of(1, 2), Map.of("present", "x"), new Date(1001), 1L);
    assertThrows(AssertionError.class,
        () -> T5ResultComparison.requireEqual(expected, reversed, e -> null));
    var multisetItem = new T5FreezeCapture.Item(KEY, Map.of(), T5ResultComparison.Order.MULTISET,
        rows.stream().map(T5FreezeCapture.Value::of).toList(), "native", "disabled",
        simple().cache(), simple().declaration(), false, "", "");
    T5FreezeCapture.write(path(), capture(true, List.of(multisetItem)));
    var multiset = T5FreezeCapture.read(path()).items().getFirst().expected();
    T5ResultComparison.requireEqual(multiset, reversed, e -> null);
    assertThrows(AssertionError.class, () -> T5ResultComparison.requireEqual(multiset,
        rows.subList(0, 4), e -> null));
  }

  @Test
  public void unknownFieldsVersionsTruncationDuplicatesAndPartialFilesAreRefused()
      throws Exception {
    T5FreezeCapture.write(path(), capture(true, List.of(simple())));
    String valid = Files.readString(path());
    for (String invalid : List.of(valid.replace("\"version\":1", "\"version\":2"),
        valid.replace("\"version\":1", "\"version\":1,\"extra\":0"),
        valid.replace("\"outcome\":", "\"unknownItemField\":0,\"outcome\":"),
        valid.replace("\"type\":\"Long\"", "\"type\":\"RID\""),
        valid.replace("\"complete\":true", "\"complete\":false"),
        valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
        valid.replace("\"failure\":\"\"", "\"failure\":\"\" , \"unused\":null"),
        valid.replace("\"version\":1", "\"version\":1.5"),
        valid.replace("declared-not-validated", "validated"),
        valid.replace("\"revision\":\"abc\"", "\"revision\":\"wrong\""),
        valid.replace("\"bypassReason\":\"disabled\"", "\"bypassReason\":\"\""),
        valid.replace("\"differenceReason\":\"baseline lacks label fix\"",
            "\"differenceReason\":\"\""),
        valid.replace("a".repeat(64), "bad-hash"),
        valid.replace("\"regime\":\"COLD\"", "\"regime\":\"UNKNOWN\""),
        valid.substring(0, valid.length() - 1), valid + "{}",
        valid.replace("\"complete\":true,", ""))) {
      Files.writeString(path(), invalid);
      assertThrows(IOException.class, () -> T5FreezeCapture.read(path()));
    }
    Files.writeString(path(),
        new ObjectMapper().writeValueAsString(capture(true, List.of(simple(), simple()))));
    assertThrows(IOException.class, () -> T5FreezeCapture.read(path()));
  }

  @Test
  public void unsupportedValuesMalformedTagsAndMapKeyCollisionsFailClosed() {
    assertThrows(IllegalArgumentException.class, () -> T5FreezeCapture.Value.of(new Object()));
    for (var value : List.of(new T5FreezeCapture.Value("Boolean", "yes", List.of()),
        new T5FreezeCapture.Value("null", null, List.of(T5FreezeCapture.Value.of(1))),
        new T5FreezeCapture.Value("Long", "x", List.of()),
        new T5FreezeCapture.Value("entry", null, List.of()),
        new T5FreezeCapture.Value("String", "x", List.of(T5FreezeCapture.Value.of(1))),
        new T5FreezeCapture.Value("unknown", "x", List.of()),
        new T5FreezeCapture.Value("map", null, List.of(T5FreezeCapture.Value.of("x"))))) {
      assertThrows(IllegalArgumentException.class, value::decode);
    }
    var entry = T5FreezeCapture.Value.of(Map.entry("key", 1));
    assertThrows(IllegalArgumentException.class,
        () -> new T5FreezeCapture.Value("map", null, List.of(entry, entry)).decode());
  }

  @Test
  public void atomicPublicationKeepsOldFileOrNoFileAfterSimulatedMidWriteFailure()
      throws Exception {
    for (boolean exists : List.of(false, true)) {
      if (exists) {
        T5FreezeCapture.write(path(), capture(true, List.of(simple())));
      }
      byte[] before = exists ? Files.readAllBytes(path()) : null;
      assertThrows(IOException.class, () -> T5FreezeCapture.publish(path(), temporary -> {
        Files.writeString(temporary, "{\"version\":1");
        throw new IOException("simulated disk failure");
      }));
      if (exists) {
        assertTrue(Arrays.equals(before, Files.readAllBytes(path())));
      } else {
        assertFalse(Files.exists(path()));
      }
      try (var files = Files.list(folder.getRoot().toPath())) {
        assertEquals(exists ? 1L : 0L, files.count());
      }
    }
    assertThrows(IllegalArgumentException.class,
        () -> T5FreezeCapture.write(path(), capture(false, List.of(simple()))));
    assertTrue(T5FreezeCapture.read(path()).complete());
  }

  @Test
  public void completeSmallInventoryNeedsEveryIdentitySlotArmFeedAndSuccessfulItem() {
    var baselineKey =
        new T5FreezeCapture.Key("C/ic2", "slot-0", "baseline", T5FreezeCapture.Regime.WARM);
    var base = simple();
    var baseline = new T5FreezeCapture.Item(baselineKey, base.feeds(), base.order(), base.answers(),
        "native", "policy decline",
        new T5FreezeCapture.Cache("def", "retained", T5FreezeCapture.Observation.MISS, ""),
        base.declaration(), false, "", "");
    var inventory = List.of(new T5FreezeCapture.Required(KEY, Set.of("person")),
        new T5FreezeCapture.Required(baselineKey, Set.of("person")));
    assertEquals(0,
        T5FreezeCapture.check(capture(true, List.of(base, baseline)), inventory).exitCode());
    var missing = T5FreezeCapture.check(capture(true, List.of(base)), inventory);
    assertEquals(1, missing.exitCode());
    assertTrue(missing.problems().stream().anyMatch(s -> s.contains("baseline")));
    for (var key : List.of(new T5FreezeCapture.Key("A/other", "slot-0", "native", KEY.regime()),
        new T5FreezeCapture.Key(KEY.identity(), "slot-1", "native", KEY.regime()),
        new T5FreezeCapture.Key(KEY.identity(), KEY.binding(), "native",
            T5FreezeCapture.Regime.WARM))) {
      assertEquals(1, T5FreezeCapture.check(capture(true, List.of(base)),
          List.of(new T5FreezeCapture.Required(key, Set.of()))).exitCode());
    }
    for (var c : List.of(capture(false, List.of(base)), capture(true, List.of()),
        capture(true, List.of(item(KEY, Map.of(), List.of(), ""))),
        capture(true, List.of(item(KEY, base.feeds(), List.of(), "arm failed"))))) {
      assertEquals(1, T5FreezeCapture.check(c, List.of(inventory.getFirst())).exitCode());
    }
    assertEquals(1, T5FreezeCapture.check(capture(true, List.of(base)), List.of()).exitCode());
    var allMissing = T5FreezeCapture.check(capture(true, List.of(base)),
        List.of(new T5FreezeCapture.Required(baselineKey, Set.of("person", "date"))));
    assertEquals(4, allMissing.problems().size()); // Missing item, both feeds, unexpected native item.
    var empty = T5FreezeCapture.check(capture(false, List.of()), inventory);
    assertTrue(empty.problems().stream().anyMatch(s -> s.contains("baseline")));
    assertTrue(empty.problems().stream().anyMatch(s -> s.contains("native")));
    assertEquals(1, empty.exitCode());
  }
}
