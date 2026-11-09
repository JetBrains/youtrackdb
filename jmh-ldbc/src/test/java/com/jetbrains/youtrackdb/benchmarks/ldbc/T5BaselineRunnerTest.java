package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class T5BaselineRunnerTest {
  static final String REVISION = "9dd20f18edba48319cf95da8394103f9713d3e59";
  @Rule
  public TemporaryFolder temporary = TemporaryFolder.builder().assureDeletion().build();

  /** A real provider populates both independently tested fixtures, not a fake baseline graph. */
  public static class Fixtures implements T5BaselineRunner.Workload {
    final Path root;
    final YouTrackDB db;
    final YTDBGraphTraversalSource g;

    public Fixtures() throws Exception {
      root = Files.createTempDirectory("t5-fixtures-");
      db = YourTracks.instance(root.toString());
      db.create("fixture", DatabaseType.MEMORY, "admin", "admin", "admin");
      g = db.openTraversal("fixture", "admin", "admin");
      new T5GeneratedFixture(200, 20261020L, "uniform").populate(g, 100);
      new T5WhereFixture().populate(g, new int[] {0, 1, 9});
    }

    @Override
    public List<T5BaselineRunner.Case> resolve() {
      return List.of(new T5BaselineRunner.Case("A/persons", "0",
          Map.of("min", T5FreezeCapture.Value.of(0L)), T5ResultComparison.Order.MULTISET, false),
          new T5BaselineRunner.Case("B/category", "0",
              Map.of("uid", T5FreezeCapture.Value.of("u997"),
                  "date", T5FreezeCapture.Value.of(T5BenchmarkCatalogue.derivedDate(10, 100, 63))),
              T5ResultComparison.Order.SEQUENCE, false));
    }

    @Override
    public YTDBGraphTraversalSource source() {
      return g;
    }

    @Override
    public Traversal<?, ?> traversal(T5BaselineRunner.Case query) {
      return query.identity().startsWith("A/")
          ? g.V().hasLabel("Person").has("id", org.apache.tinkerpop.gremlin.process.traversal.P
              .gte(query.feeds().get("min").decode())).count()
          : g.V().hasLabel("Category").has("uid", query.feeds().get("uid").decode());
    }

    @Override
    public T5ResultComparison.ElementKey logicalKey(Element element) {
      return new T5ResultComparison.ElementKey("vertex", element.label(), element.value("uid"));
    }

    @Override
    public void close() throws Exception {
      g.close();
      db.close();
      try (var paths = Files.walk(root)) {
        for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.delete(path);
        }
      }
    }
  }

  static T5BaselineRunner.Request request() throws Exception {
    return T5BaselineRunner.resolve(Fixtures.class.getName(), Map.of("fixture", "a".repeat(64)),
        REVISION);
  }

  static String classpath(Path core) {
    String current =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    return core + java.io.File.pathSeparator
        + Arrays.stream(current.split(java.io.File.pathSeparator))
            .filter(p -> !p.contains("/core/target/") && !p.contains("youtrackdb-core-"))
            .collect(Collectors.joining(java.io.File.pathSeparator));
  }

  static Path currentCore() {
    return Path.of("../core/target/youtrackdb-core-0.5.0-SNAPSHOT.jar").toAbsolutePath();
  }

  @Test
  public void isolatedRealArmsCaptureBothFixturesAndObserveAllRegimes() throws Exception {
    // Supply the immutable baseline jar explicitly. No network or baseline build occurs in tests.
    String baseline = System.getProperty("t5.baselineCore");
    org.junit.Assume.assumeNotNull(baseline);
    var request = request();
    var output = temporary.getRoot().toPath().resolve("capture.json");
    var capture = T5BaselineRunner.run(request, List.of(
        new T5BaselineRunner.Arm("native", REVISION, classpath(currentCore())),
        new T5BaselineRunner.Arm("baseline", T5BaselineRunner.BASELINE,
            classpath(Path.of(baseline)))),
        temporary.newFolder().toPath(), output, Duration.ofMinutes(2));
    assertTrue(T5FreezeCapture.check(capture, T5BaselineRunner.inventory(request)).complete());
    assertEquals(12, capture.items().size());
    for (var item : capture.items()) {
      assertEquals(item.key().identity().startsWith("A/") ? List.of(80L)
          : List.of(new T5ResultComparison.ElementKey("vertex", "Category", "u997")),
          item.expected().rows());
      boolean nativeArm = item.key().arm().equals("native");
      assertEquals(nativeArm || item.key().regime() == T5FreezeCapture.Regime.UNCACHEABLE
          ? T5FreezeCapture.Observation.BYPASS
          : item.key().regime() == T5FreezeCapture.Regime.COLD ? T5FreezeCapture.Observation.MISS
              : T5FreezeCapture.Observation.HIT,
          item.cache().observed());
      assertEquals(nativeArm, item.declaration().preparedIdentity().type().equals("list"));
      assertTrue(item.declaration().cuts().values().stream()
          .allMatch("declared-not-validated"::equals));
      assertFalse(item.knownBaselineDifference());
      if (item.key().identity().startsWith("B/")) {
        assertEquals(new java.util.Date(101), item.feeds().get("date").decode());
      }
    }
    assertEquals(capture, T5FreezeCapture.read(output));
    T5BaselineRunner.JSON.writeValue(temporary.getRoot().toPath().resolve("request.json").toFile(),
        request);
    T5BaselineRunner.main(new String[] {"check", output.toString(),
        temporary.getRoot().toPath().resolve("request.json").toString()});
  }

  @Test
  public void wrongLoadedRevisionAndFailedChildCannotPublishCapture() throws Exception {
    var request = request();
    var input = temporary.newFile().toPath();
    T5BaselineRunner.JSON.writeValue(input.toFile(), request);
    var output = temporary.getRoot().toPath().resolve("refused.json");
    var command = T5BaselineRunner.javaCommand(classpath(currentCore()));
    command.addAll(List.of(T5BaselineRunner.class.getName(), "child", input.toString(), "baseline",
        T5BaselineRunner.BASELINE, output.toString()));
    var failure = assertThrows(IOException.class, () -> T5BaselineRunner.execute(command,
        temporary.newFile().toPath(), Duration.ofMinutes(1)));
    assertTrue(failure.getMessage().contains("Core revision mismatch"));
    assertFalse(Files.exists(output));
  }

  /** A deliberately inert child tests process transport independently of database initialization. */
  public static class Child {
    public static void main(String[] args) throws Exception {
      if (args[0].equals("wait")) {
        Thread.sleep(60_000);
      } else if (args[0].equals("fail")) {
        throw new IllegalStateException("child failed visibly");
      } else {
        Files.writeString(Path.of(args[0]), "{\"version\":1");
      }
    }
  }

  @Test
  public void failedTimedOutAndPartialChildrenAreVisibleFailures() throws Exception {
    for (String mode : List.of("fail", "wait")) {
      var command = T5BaselineRunner.javaCommand(classpath(currentCore()));
      command.addAll(List.of(Child.class.getName(), mode));
      var error = assertThrows(IOException.class, () -> T5BaselineRunner.execute(command,
          temporary.newFile().toPath(), mode.equals("wait") ? Duration.ofMillis(200)
              : Duration.ofSeconds(10)));
      assertTrue(error.getMessage().contains(mode.equals("wait") ? "Timed out" : "Failed child"));
    }
    Path partial = temporary.newFile().toPath();
    var command = T5BaselineRunner.javaCommand(classpath(currentCore()));
    command.addAll(List.of(Child.class.getName(), partial.toString()));
    T5BaselineRunner.execute(command, temporary.newFile().toPath(), Duration.ofSeconds(10));
    assertThrows(IOException.class, () -> T5FreezeCapture.read(partial));
  }

  @Test
  public void missingItemsAndLabelOnlyCacheClaimsCannotPass() throws Exception {
    var request = request();
    var empty = new T5FreezeCapture.Capture(1, true, Map.of("native", REVISION),
        request.inputHashes(), List.of());
    assertThrows(IOException.class, () -> T5BaselineRunner.requireComplete(empty,
        T5BaselineRunner.inventory(request)));
    // The same declared regime cannot turn zero observed lookups into a hit or miss.
    assertEquals(T5FreezeCapture.Observation.BYPASS, T5BaselineRunner.observed(0, 0));
    assertEquals(T5FreezeCapture.Observation.HIT, T5BaselineRunner.observed(1, 0));
    assertEquals(T5FreezeCapture.Observation.MISS, T5BaselineRunner.observed(0, 1));
    assertThrows(IllegalStateException.class, () -> T5BaselineRunner.observed(1, 1));
    assertThrows(IllegalStateException.class, () -> T5BaselineRunner.observed(-1, 0));
    T5BaselineRunner.requireUnforced();
    assertFalse(
        T5BaselineRunner.javaCommand("classpath").stream().anyMatch(s -> s.contains("FORCE")));
  }

  /** The provider violates arm isolation on purpose. The child must reject it before execution. */
  public static class ForcedFixtures extends Fixtures {
    public ForcedFixtures() throws Exception {
      var field =
          com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy.class
              .getDeclaredField("FORCE_MATCH_FOR_TESTS");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var forced = (ThreadLocal<Boolean>) field.get(null);
      forced.set(true);
    }
  }

  @Test
  public void providerCannotEnableForcedMatchInEitherArm() throws Exception {
    String baseline = System.getProperty("t5.baselineCore");
    org.junit.Assume.assumeNotNull(baseline);
    var original = request();
    var request = new T5BaselineRunner.Request(ForcedFixtures.class.getName(), original.cases(),
        original.inputHashes(), REVISION);
    Path input = temporary.newFile().toPath();
    T5BaselineRunner.JSON.writeValue(input.toFile(), request);
    for (String arm : T5BaselineRunner.ARMS) {
      Path output = temporary.getRoot().toPath().resolve(arm + ".json");
      var command = T5BaselineRunner.javaCommand(classpath(arm.equals("native")
          ? currentCore() : Path.of(baseline)));
      command.addAll(List.of(T5BaselineRunner.class.getName(), "child", input.toString(), arm,
          arm.equals("native") ? REVISION : T5BaselineRunner.BASELINE, output.toString()));
      var error = assertThrows(IOException.class, () -> T5BaselineRunner.execute(command,
          temporary.newFile().toPath(), Duration.ofMinutes(1)));
      assertTrue(error.getMessage().contains("Forced MATCH is forbidden"));
      assertFalse(Files.exists(output));
    }
  }

  @Test
  public void secondArmFailurePreservesExistingPublishedBytesAndCleansTransportFiles()
      throws Exception {
    var request = request();
    Path output = temporary.newFile().toPath();
    Files.writeString(output, "retained capture bytes");
    Path scratch = temporary.newFolder().toPath();
    // The second arm deliberately loads current core while claiming the pinned baseline revision.
    var arms = List.of(new T5BaselineRunner.Arm("native", REVISION, classpath(currentCore())),
        new T5BaselineRunner.Arm("baseline", T5BaselineRunner.BASELINE, classpath(currentCore())));
    assertThrows(IOException.class, () -> T5BaselineRunner.run(request, arms, scratch, output,
        Duration.ofMinutes(1)));
    assertEquals("retained capture bytes", Files.readString(output));
    try (var files = Files.list(scratch)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  public void onlyExplicitStepLabelFixCasesMayRecordKnownBaselineDifferences() throws Exception {
    var request = request();
    var c = request.cases().getFirst();
    var base = new T5FreezeCapture.Item(new T5FreezeCapture.Key(c.identity(), c.binding(),
        "baseline", T5FreezeCapture.Regime.COLD), c.feeds(), c.order(),
        List.of(T5FreezeCapture.Value.of(79L)), "native", "test transport",
        new T5FreezeCapture.Cache(T5BaselineRunner.BASELINE, "invalidate",
            T5FreezeCapture.Observation.MISS, ""),
        new T5FreezeCapture.Declaration(T5FreezeCapture.Value.of("test"), Map.of(), Map.of()),
        false, "", "");
    var nativeItem = new T5FreezeCapture.Item(new T5FreezeCapture.Key(c.identity(), c.binding(),
        "native", T5FreezeCapture.Regime.COLD), c.feeds(), c.order(),
        List.of(T5FreezeCapture.Value.of(80L)), base.outcome(), base.reason(), base.cache(),
        base.declaration(), false, "", "");
    assertThrows(AssertionError.class,
        () -> T5BaselineRunner.markDifferences(request, List.of(nativeItem, base)));
    var allowed = new T5BaselineRunner.Request(request.provider(), List.of(
        new T5BaselineRunner.Case(c.identity(), c.binding(), c.feeds(), c.order(), true)),
        request.inputHashes(), REVISION);
    var marked = T5BaselineRunner.markDifferences(allowed, List.of(nativeItem, base));
    assertFalse(marked.getFirst().knownBaselineDifference());
    assertTrue(marked.getLast().knownBaselineDifference());
    assertTrue(marked.getLast().differenceReason().contains("T5.6"));
  }
}
