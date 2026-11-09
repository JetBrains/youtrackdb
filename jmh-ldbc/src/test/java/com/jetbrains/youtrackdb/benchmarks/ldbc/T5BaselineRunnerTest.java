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
  static final String REVISION = currentRevision();

  private static String currentRevision() {
    try (var jar = new java.util.jar.JarFile(currentCore().toFile())) {
      return java.util.Objects.requireNonNull(
          jar.getManifest().getMainAttributes().getValue("Implementation-Build"));
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

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

  public static class WholeNativeFixtures extends Fixtures {
    public WholeNativeFixtures() throws Exception {
    }

    @Override
    public boolean wholeNative(T5BaselineRunner.Case query) {
      return true;
    }
  }

  /** Current-core answers and whole-native refusal evidence do not require the baseline jar. */
  @Test
  public void currentCoreCapturesAnswersAndWholeNativeRefusalsWithoutBaseline() throws Exception {
    for (String provider : List.of(Fixtures.class.getName(), WholeNativeFixtures.class.getName())) {
      var request = T5BaselineRunner.resolve(provider, Map.of("fixture", "a".repeat(64)), REVISION);
      Path input = temporary.newFile().toPath();
      Path output = temporary.newFile().toPath();
      T5BaselineRunner.JSON.writeValue(input.toFile(), request);
      var command = T5BaselineRunner.javaCommand(classpath(currentCore()));
      command.addAll(List.of(T5BaselineRunner.class.getName(), "child", input.toString(),
          "native", REVISION, output.toString()));
      T5BaselineRunner.execute(command, temporary.newFile().toPath(), Duration.ofMinutes(1));
      var capture = T5FreezeCapture.read(output);
      assertEquals(6, capture.items().size());
      for (var item : capture.items()) {
        assertEquals(item.key().identity().startsWith("A/") ? List.of(80L)
            : List.of(new T5ResultComparison.ElementKey("vertex", "Category", "u997")),
            item.expected().rows());
        assertEquals("native", item.outcome());
        assertEquals(T5FreezeCapture.Observation.BYPASS, item.cache().observed());
        if (provider.equals(WholeNativeFixtures.class.getName())) {
          assertEquals(Map.of("all", "Record-identifier-bearing member"),
              item.declaration().refusedCuts());
          assertTrue(item.declaration().cuts().isEmpty());
        } else {
          assertEquals("list", item.declaration().preparedIdentity().type());
        }
      }
    }
  }

  private static String baselineCore() {
    String baseline = System.getProperty("t5.baselineCore");
    if (Boolean.getBoolean("t5.requireBaselineCore")) {
      org.junit.Assert.assertNotNull("Required t5.baselineCore is absent", baseline);
    }
    org.junit.Assume.assumeNotNull(baseline);
    return baseline;
  }

  @Test
  public void recordIdentifierMembersBypassTranslationAndRefuseEveryCutInBothRealArms()
      throws Exception {
    String baseline = baselineCore();
    var request = T5BaselineRunner.resolve(WholeNativeFixtures.class.getName(),
        Map.of("fixture", "a".repeat(64)), REVISION);
    var capture = T5BaselineRunner.run(request, List.of(
        new T5BaselineRunner.Arm("native", REVISION, classpath(currentCore())),
        new T5BaselineRunner.Arm("baseline", T5BaselineRunner.BASELINE,
            classpath(Path.of(baseline)))),
        temporary.newFolder().toPath(), temporary.getRoot().toPath().resolve("whole-native.json"),
        Duration.ofMinutes(2));
    assertEquals(12, capture.items().size());
    for (var item : capture.items()) {
      assertEquals("native", item.outcome());
      assertEquals(T5FreezeCapture.Observation.BYPASS, item.cache().observed());
      assertTrue(item.reason().contains("Record-identifier-bearing member"));
      assertTrue(item.declaration().cuts().isEmpty());
      assertEquals(Map.of("all", "Record-identifier-bearing member"),
          item.declaration().refusedCuts());
    }
  }

  /** The standalone CLI must reject missing items, feeds and changed provenance without a baseline. */
  @Test
  public void standaloneCheckRejectsIncompleteOrDifferentRequestsWithoutBaseline()
      throws Exception {
    var request = request();
    var items = new java.util.ArrayList<T5FreezeCapture.Item>();
    for (var c : request.cases()) {
      for (String arm : T5BaselineRunner.ARMS) {
        for (var regime : T5FreezeCapture.Regime.values()) {
          items.add(new T5FreezeCapture.Item(new T5FreezeCapture.Key(c.identity(), c.binding(),
              arm, regime), c.feeds(), c.order(), List.of(T5FreezeCapture.Value.of(80L)),
              "native", "transport fixture",
              new T5FreezeCapture.Cache(arm.equals("native") ? REVISION : T5BaselineRunner.BASELINE,
                  "no lookup", T5FreezeCapture.Observation.BYPASS, "transport fixture"),
              new T5FreezeCapture.Declaration(T5FreezeCapture.Value.of("fixture"), Map.of(),
                  Map.of()),
              false, "", ""));
        }
      }
    }
    var capture = new T5FreezeCapture.Capture(1, true,
        Map.of("native", REVISION, "baseline", T5BaselineRunner.BASELINE),
        request.inputHashes(), items);
    Path output = temporary.newFile().toPath();
    T5FreezeCapture.write(output, capture);
    Path input = temporary.newFile().toPath();
    T5BaselineRunner.JSON.writeValue(input.toFile(), request);
    T5BaselineRunner.main(new String[] {"check", output.toString(), input.toString()});
    var removed = new java.util.ArrayList<>(items);
    removed.removeLast();
    var first = items.getFirst();
    var noFeed = new T5FreezeCapture.Item(first.key(), Map.of(), first.order(), first.answers(),
        first.outcome(), first.reason(), first.cache(), first.declaration(), false, "", "");
    var feedMissing = new java.util.ArrayList<>(items);
    feedMissing.set(0, noFeed);
    for (var incomplete : List.of(removed, feedMissing)) {
      T5FreezeCapture.write(output, new T5FreezeCapture.Capture(1, true,
          capture.revisions(), capture.inputHashes(), incomplete));
      refuseCheck(output, input);
    }
    T5FreezeCapture.write(output, capture);
    var c = request.cases().getFirst();
    var changed = new java.util.ArrayList<>(request.cases());
    changed.set(0, new T5BaselineRunner.Case(c.identity(), c.binding(),
        Map.of("min", T5FreezeCapture.Value.of(99L)), c.order(), false));
    for (var different : List.of(
        new T5BaselineRunner.Request(request.provider(), request.cases(), request.inputHashes(),
            "different-revision"),
        new T5BaselineRunner.Request(request.provider(), changed, request.inputHashes(), REVISION),
        new T5BaselineRunner.Request(request.provider(), request.cases(),
            Map.of("fixture", "b".repeat(64)), REVISION))) {
      T5BaselineRunner.JSON.writeValue(input.toFile(), different);
      refuseCheck(output, input);
    }
  }

  private void refuseCheck(Path output, Path input) throws Exception {
    var command = T5BaselineRunner.javaCommand(classpath(currentCore()));
    command.addAll(List.of(T5BaselineRunner.class.getName(), "check", output.toString(),
        input.toString()));
    assertThrows(IOException.class, () -> T5BaselineRunner.execute(command,
        temporary.newFile().toPath(), Duration.ofMinutes(1)));
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
    String baseline = baselineCore();
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

  /** Edge roots decline before cache extraction, even in COLD and WARM with translation enabled. */
  @Test
  public void edgeRootActuallyBypassesCacheRegardlessOfRegimeLabel() throws Exception {
    var setting =
        com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED;
    boolean previous = setting.getValueAsBoolean();
    setting.setValue(true);
    try (var workload = new Fixtures() {
      @Override
      public Traversal<?, ?> traversal(T5BaselineRunner.Case query) {
        return g.E().count();
      }
    }) {
      for (var regime : List.of(T5FreezeCapture.Regime.COLD, T5FreezeCapture.Regime.WARM)) {
        var item = T5BaselineRunner.observe(workload, workload.resolve().getFirst(),
            "baseline", REVISION, regime);
        assertEquals(T5FreezeCapture.Observation.BYPASS, item.cache().observed());
        assertTrue(item.cache().invalidationEvent().endsWith("hits=0; misses=0"));
        ((com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal) workload.g.getGraph())
            .tx().rollback();
      }
    } finally {
      setting.setValue(previous);
    }
  }

  /** A one-shot predicate read failure hits the production translator safety net during priming.
   * Native predicate evaluation still works, so only the pre-prime error baseline catches it. */
  @Test
  public void warmPrimingTranslationFallbackIsAVisibleFailure() throws Exception {
    var setting =
        com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED;
    boolean previous = setting.getValueAsBoolean();
    setting.setValue(true);
    try (var workload = new Fixtures() {
      boolean first = true;

      @Override
      public Traversal<?, ?> traversal(T5BaselineRunner.Case query) {
        if (first) {
          first = false;
          var predicate = new org.apache.tinkerpop.gremlin.process.traversal.P<Long>(
              org.apache.tinkerpop.gremlin.process.traversal.P.<Long>gte(0L).getBiPredicate(), 0L) {
            boolean fail = true;

            @Override
            public Long getValue() {
              if (fail) {
                fail = false;
                throw new IllegalStateException("Injected priming translation failure");
              }
              return super.getValue();
            }
          };
          return g.V().hasLabel("Person").has("id", predicate).count();
        }
        return super.traversal(query);
      }
    }) {
      var graph =
          (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal) workload.g.getGraph();
      graph.tx().readWrite();
      var metrics =
          com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinTranslationMetrics
              .of(graph.tx().getDatabaseSession());
      long before = metrics.getErrors();
      var error = assertThrows(IllegalStateException.class, () -> T5BaselineRunner.observe(workload,
          workload.resolve().getFirst(), "baseline", REVISION, T5FreezeCapture.Regime.WARM));
      assertTrue(error.getMessage().contains("Translation error"));
      assertEquals(before + 1, metrics.getErrors());
      graph.tx().rollback();
    } finally {
      setting.setValue(previous);
    }
  }

  /** Both real catalogue LONG traversals decline in production and refuse every declaration cut. */
  @Test
  public void catalogueLambdaMembersDeclareWholeNativeWithoutProbe() throws Exception {
    var setting =
        com.jetbrains.youtrackdb.api.config.GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED;
    boolean previous = setting.getValueAsBoolean();
    setting.setValue(true);
    try (var workload = new T5CatalogueWorkload()) {
      for (String variant : List.of("indexed", "no-index")) {
        var query = new T5BaselineRunner.Case("B/" + variant + "/LONG-late-seeded-fold",
            "archive", Map.of(), T5ResultComparison.Order.MULTISET, false);
        workload.prepare(query);
        var graph = (com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal) workload
            .source().getGraph();
        graph.tx().readWrite();
        var metrics =
            com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinTranslationMetrics
                .of(graph.tx().getDatabaseSession());
        long declines = metrics.getDeclines();
        // Keep the production strategy installed for this confirmation, unlike runner execution.
        try (var traversal = workload.traversal(query)) {
          traversal.asAdmin().applyStrategies();
          assertEquals(0, GremlinTraversalShapes
              .countBoundarySteps(traversal.asAdmin()));
          assertEquals(List.of(1), traversal.toList());
        }
        assertTrue(metrics.getDeclines() > declines);
        var declaration = T5BaselineRunner.declare(workload, query);
        assertEquals(Map.of("all", "Lambda-bearing member"), declaration.refusedCuts());
        assertTrue(declaration.cuts().isEmpty());
        graph.tx().rollback();
      }
    } finally {
      setting.setValue(previous);
    }
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
    verifyForcedArm("baseline", Path.of(baselineCore()));
  }

  @Test
  public void providerCannotEnableForcedMatchInCurrentArmWithoutBaseline() throws Exception {
    verifyForcedArm("native", currentCore());
  }

  private void verifyForcedArm(String arm, Path core) throws Exception {
    var original = request();
    var request = new T5BaselineRunner.Request(ForcedFixtures.class.getName(), original.cases(),
        original.inputHashes(), REVISION);
    Path input = temporary.newFile().toPath();
    T5BaselineRunner.JSON.writeValue(input.toFile(), request);
    {
      Path output = temporary.getRoot().toPath().resolve(arm + ".json");
      var command = T5BaselineRunner.javaCommand(classpath(core));
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
