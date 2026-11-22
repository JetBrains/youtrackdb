package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.function.FailableFunction;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;
import org.junit.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;

/** Verifies workload selection for the IC2 and pre-valueMap bulking benchmark identities. */
public class LdbcGremlinBenchmarkWiringTest {

  /** Maven combines workflow exclusions with the default and supports an empty manual override. */
  @Test
  public void launcherDefaultExcludesBulkingButKeepsRidBenchmarks() throws Exception {
    var pom = java.nio.file.Files.readString(java.nio.file.Path.of("pom.xml"));
    var defaults = launcherDefaults(pom);
    assertSelection(defaults, 0, 5);
    assertSelection("", 7, 5);
    // Exercise both wiring mutations against the same assertion used for the real POM.
    assertThrows(AssertionError.class, () -> launcherDefaults(
        pom.replace("-e gremlin_bulking", "")));
    assertThrows(AssertionError.class, () -> launcherDefaults(
        pom.replace(" ${jmh.defaultExcludes}</argument>", "</argument>")));
  }

  private static String launcherDefaults(String pom) throws Exception {
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    var document = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(
        new java.io.StringReader(pom)));
    var defaults = document.getElementsByTagName("jmh.defaultExcludes").item(0)
        .getTextContent().trim();
    var plugins = document.getElementsByTagName("plugin");
    String launcher = null;
    for (int i = 0; i < plugins.getLength(); i++) {
      var plugin = (org.w3c.dom.Element) plugins.item(i);
      if (plugin.getElementsByTagName("artifactId").item(0).getTextContent()
          .equals("exec-maven-plugin")) {
        var arguments = plugin.getElementsByTagName("argument");
        for (int j = 0; j < arguments.getLength(); j++) {
          var argument = arguments.item(j).getTextContent();
          if (argument.contains("org.openjdk.jmh.Main")) {
            launcher = argument;
          }
        }
      }
    }
    assertNotNull(launcher);
    assertTrue(launcher.contains("${jmh.args} ${jmh.defaultExcludes}"));
    // Check semantics as well as the launcher reference. Clearing the default must fail.
    assertSelection(defaults, 0, 5);
    return defaults;
  }

  private static void assertSelection(String defaults, long expectedBulking, long expectedRid)
      throws Exception {
    var arguments = new java.util.ArrayList<>(List.of("-e", "BothE|pagelock"));
    if (!defaults.isBlank()) {
      arguments.addAll(List.of(defaults.split("\\s+")));
    }
    // Use JMH's parser so repeated -e handling is the launcher's actual behavior.
    var options = new org.openjdk.jmh.runner.options.CommandLineOptions(
        arguments.toArray(String[]::new));
    var excludes = options.getExcludes().stream()
        .map(java.util.regex.Pattern::compile).toList();
    var selected = Arrays.stream(LdbcGremlinTranslatorBenchmark.class.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(Benchmark.class))
        .map(java.lang.reflect.Method::getName)
        .filter(name -> excludes.stream().noneMatch(pattern -> pattern.matcher(name).find()))
        .toList();
    assertEquals(expectedBulking,
        selected.stream().filter(name -> name.startsWith("gremlin_bulking")).count());
    assertEquals(expectedRid,
        selected.stream().filter(name -> name.startsWith("gremlin_rid")).count());
  }

  private static final Date CURATED_DATE = new Date(1000);
  private static final Date LIVE_DATE = new Date(2000);

  /** The actual legacy benchmark completes and reads only curated state. */
  @Test
  public void legacyBenchmarkInvocationUsesCuratedState() throws NoSuchMethodException {
    assertBenchmarkAnnotation("gremlin_ic2_friendsMessagesOrdered");
    var state = new StubState();
    var arm = new StubArm();

    var rows = new LdbcGremlinTranslatorBenchmark()
        .gremlin_ic2_friendsMessagesOrdered(state, arm);

    assertTrue(rows.isEmpty());
    assertTrue(state.personIdRead);
    assertTrue(state.maxDateRead);
    assertFalse(arm.personIdRead);
    assertFalse(arm.maxDateRead);
  }

  /** The actual ordered-limit benchmark completes and reads only live arm state. */
  @Test
  public void orderedLimitBenchmarkInvocationUsesLiveArm() throws NoSuchMethodException {
    assertBenchmarkAnnotation("gremlin_ic2_friendsMessagesOrderedLimit");
    var state = new StubState();
    var arm = new StubArm();

    var rows = new LdbcGremlinTranslatorBenchmark()
        .gremlin_ic2_friendsMessagesOrderedLimit(state, arm);

    assertTrue(rows.isEmpty());
    assertFalse(state.personIdRead);
    assertFalse(state.maxDateRead);
    assertTrue(arm.personIdRead);
    assertTrue(arm.maxDateRead);
  }

  /** The legacy workload reads the curated parameter source from shared benchmark state. */
  @Test
  public void legacyWorkloadUsesCuratedParameters() {
    var state = new StubState();

    assertEquals(11, LdbcGremlinTranslatorBenchmark.LegacyIc2Workload.personId(state, 7));
    assertSame(
        CURATED_DATE, LdbcGremlinTranslatorBenchmark.LegacyIc2Workload.maxDate(state, 7));
  }

  /** The ordered-limit workload reads the live parameter source from translator-arm state. */
  @Test
  public void orderedLimitWorkloadUsesLiveParameters() {
    var arm = new StubArm();

    assertEquals(22, LdbcGremlinTranslatorBenchmark.OrderedLimitIc2Workload.personId(arm, 7));
    assertSame(
        LIVE_DATE, LdbcGremlinTranslatorBenchmark.OrderedLimitIc2Workload.maxDate(arm, 7));
  }

  /** Four method identities expose fixed forms with translatorEnabled as their only JMH param. */
  @Test
  public void bulkingBenchmarksDeclareFixedFormsWithoutAdditionalParams() throws Exception {
    assertEquals(List.of("true", "false"), Arrays.asList(
        LdbcGremlinTranslatorBenchmark.TranslatorArm.class.getField("translatorEnabled")
            .getAnnotation(Param.class).value()));
    for (var form : GremlinTraversalShapes.BulkingForm.values()) {
      var parameters = bulkingParameters(form);
      assertEquals(form, parameters.form);
      var method = LdbcGremlinTranslatorBenchmark.class.getMethod(bulkingMethodName(form),
          LdbcBenchmarkState.class, LdbcGremlinTranslatorBenchmark.TranslatorArm.class,
          parameters.getClass());
      assertNotNull(method.getAnnotation(Benchmark.class));
      for (Class<?> type = parameters.getClass(); type != null; type = type.getSuperclass()) {
        for (var field : type.getDeclaredFields()) {
          assertFalse(field.isAnnotationPresent(Param.class));
        }
      }
    }
  }

  static LdbcGremlinTranslatorBenchmark.BulkingParameters bulkingParameters(
      GremlinTraversalShapes.BulkingForm form) {
    return switch (form) {
      case DUPLICATE -> new LdbcGremlinTranslatorBenchmark.BulkingDuplicateParameters();
      case ONE_ROW -> new LdbcGremlinTranslatorBenchmark.BulkingOneRowParameters();
      case UNIQUE -> new LdbcGremlinTranslatorBenchmark.BulkingUniqueParameters();
      case MOSTLY_UNIQUE -> new LdbcGremlinTranslatorBenchmark.BulkingMostlyUniqueParameters();
    };
  }

  static LdbcGremlinTranslatorBenchmark.BulkingParameters widerBulkingParameters(
      GremlinTraversalShapes.BulkingForm form) {
    return switch (form) {
      case DUPLICATE -> new LdbcGremlinTranslatorBenchmark.BulkingWhereDuplicateParameters();
      case ONE_ROW -> new LdbcGremlinTranslatorBenchmark.BulkingWhereOneRowParameters();
      case UNIQUE -> new LdbcGremlinTranslatorBenchmark.BulkingWhereUniqueParameters();
      case MOSTLY_UNIQUE ->
          throw new IllegalArgumentException("No cross-label benchmark for " + form);
    };
  }

  static List<GremlinTraversalShapes.BulkingForm> widerBulkingForms() {
    return List.of(GremlinTraversalShapes.BulkingForm.DUPLICATE,
        GremlinTraversalShapes.BulkingForm.ONE_ROW, GremlinTraversalShapes.BulkingForm.UNIQUE);
  }

  /** The three wider identities keep fixed forms and add no JMH parameter keys. */
  @Test
  public void widerBulkingBenchmarksKeepValidatorCompatibleIdentities() throws Exception {
    for (var form : widerBulkingForms()) {
      var parameters = widerBulkingParameters(form);
      assertEquals(form, parameters.form);
      assertTrue(parameters.crossLabel);
      var method = LdbcGremlinTranslatorBenchmark.class.getMethod(
          bulkingMethodName(form).replace("bulking", "bulkingWhere"),
          LdbcBenchmarkState.class, LdbcGremlinTranslatorBenchmark.TranslatorArm.class,
          parameters.getClass());
      assertNotNull(method.getAnnotation(Benchmark.class));
      for (Class<?> type = parameters.getClass(); type != null; type = type.getSuperclass()) {
        for (var field : type.getDeclaredFields()) {
          assertFalse(field.isAnnotationPresent(Param.class));
        }
      }
    }
  }

  /** Mostly-unique remains a legacy form without a density band or cross-label builder. */
  @Test
  public void mostlyUniqueIsLegacyOnlyWithoutDensityRestrictions() {
    var form = GremlinTraversalShapes.BulkingForm.MOSTLY_UNIQUE;
    form.requireCounts(42, 4, 3);
    form.requireCounts(42, 4, 4);
    form.requireCounts(42, 97, 70);
    assertThrows(IllegalArgumentException.class, () -> form.traversal(null, 42, true));
    assertFalse(Arrays.stream(LdbcGremlinTranslatorBenchmark.class.getMethods())
        .anyMatch(method -> method.getName().equals("gremlin_bulkingWhereMostlyUnique")));
    assertFalse(Arrays.stream(LdbcGremlinTranslatorBenchmark.class.getDeclaredClasses())
        .anyMatch(type -> type.getSimpleName().equals("BulkingWhereMostlyUniqueParameters")));
  }

  /** Capacity evidence is optional on Track 6 and mandatory when its accessor exists. */
  @Test
  public void widerCapacityProbeSupportsBothCoresAndPropagatesBrokenAccessors() {
    GremlinTraversalShapes.requireBulkingCapacityIfAvailable(new Object());
    GremlinTraversalShapes.requireBulkingCapacityIfAvailable(new CapacityProbe(2500));
    assertThrows(IllegalStateException.class,
        () -> GremlinTraversalShapes.requireBulkingCapacityIfAvailable(new CapacityProbe(0)));
    assertThrows(IllegalStateException.class,
        () -> GremlinTraversalShapes.requireBulkingCapacityIfAvailable(new CapacityProbe(-1)));
    assertThrows(IllegalStateException.class,
        () -> GremlinTraversalShapes.requireBulkingCapacityIfAvailable(new CapacityProbe(null)));
  }

  public static class CapacityProbe {

    private final Integer capacity;

    CapacityProbe(Integer capacity) {
      this.capacity = capacity;
    }

    public int getPropertyMapBulkingCapacity() {
      if (capacity == null) {
        throw new IllegalStateException("broken capacity accessor");
      }
      return capacity;
    }
  }

  private static String bulkingMethodName(GremlinTraversalShapes.BulkingForm form) {
    return switch (form) {
      case DUPLICATE -> "gremlin_bulkingDuplicate";
      case ONE_ROW -> "gremlin_bulkingOneRow";
      case UNIQUE -> "gremlin_bulkingUnique";
      case MOSTLY_UNIQUE -> "gremlin_bulkingMostlyUnique";
    };
  }

  @SuppressWarnings("unchecked")
  static List<Map<Object, Object>> invokeBulkingBenchmark(GremlinTraversalShapes.BulkingForm form,
      LdbcBenchmarkState state, LdbcGremlinTranslatorBenchmark.TranslatorArm arm,
      LdbcGremlinTranslatorBenchmark.BulkingParameters parameters) throws Exception {
    var methodName = parameters.crossLabel
        ? bulkingMethodName(form).replace("bulking", "bulkingWhere") : bulkingMethodName(form);
    var method = LdbcGremlinTranslatorBenchmark.class.getMethod(methodName,
        LdbcBenchmarkState.class, LdbcGremlinTranslatorBenchmark.TranslatorArm.class,
        parameters.getClass());
    return (List<Map<Object, Object>>) method.invoke(
        new LdbcGremlinTranslatorBenchmark(), state, arm, parameters);
  }

  /** Every form rejects an empty feed. Duplicate, singleton, and unique cases enforce their names. */
  @Test
  public void bulkingCountsRejectInvalidParametersWithCountsInTheMessage() {
    for (var form : GremlinTraversalShapes.BulkingForm.values()) {
      assertTrue(assertThrows(IllegalStateException.class,
          () -> form.requireCounts(42, 0, 0)).getMessage().contains("parameter 42"));
    }
    assertThrows(IllegalStateException.class,
        () -> GremlinTraversalShapes.BulkingForm.DUPLICATE.requireCounts(42, 2, 2));
    assertThrows(IllegalStateException.class,
        () -> GremlinTraversalShapes.BulkingForm.ONE_ROW.requireCounts(42, 2, 1));
    assertThrows(IllegalStateException.class,
        () -> GremlinTraversalShapes.BulkingForm.UNIQUE.requireCounts(42, 2, 1));
  }

  /** Null and empty parameter pools fail before a transaction or timed invocation can start. */
  @Test
  public void bulkingTrialRejectsUninitializedAndEmptyPools() {
    for (long[] ids : new long[][] {null, new long[0]}) {
      var arm = new LdbcGremlinTranslatorBenchmark.TranslatorArm() {
        @Override
        long[] bulkingIds(GremlinTraversalShapes.BulkingForm form) {
          return ids;
        }
      };
      var parameters = new LdbcGremlinTranslatorBenchmark.BulkingDuplicateParameters();
      assertTrue(assertThrows(IllegalStateException.class,
          () -> parameters.setUp(new StubState(), arm)).getMessage()
          .contains("empty parameter feed"));
    }
  }

  /** All five RID methods need preparation, while the 43 other methods must skip it. */
  @Test
  public void trialPreparationRuleSelectsOnlyRidBenchmarks() {
    var ridNames = new HashSet<String>();
    int existing = 0;
    for (var method : LdbcGremlinTranslatorBenchmark.class.getDeclaredMethods()) {
      if (method.isAnnotationPresent(Benchmark.class)) {
        boolean isRid = method.getName().startsWith("gremlin_rid");
        assertEquals(method.getName(), isRid,
            LdbcGremlinTranslatorBenchmark.requiresRidPreparation(
                LdbcGremlinTranslatorBenchmark.class.getName() + "." + method.getName()));
        if (isRid) {
          ridNames.add(method.getName());
        } else {
          existing++;
        }
      }
    }
    assertEquals(Set.of("gremlin_ridCount", "gremlin_ridValues", "gremlin_ridOrder",
        "gremlin_ridFirstNameCount", "gremlin_ridWorkFromCount"), ridNames);
    assertEquals(5, ridNames.size());
    assertEquals(43, existing);
    assertEquals(48, ridNames.size() + existing);
  }

  private static void assertBenchmarkAnnotation(String methodName) throws NoSuchMethodException {
    var method = LdbcGremlinTranslatorBenchmark.class.getMethod(
        methodName, LdbcBenchmarkState.class, LdbcGremlinTranslatorBenchmark.TranslatorArm.class);
    assertNotNull(method.getAnnotation(Benchmark.class));
  }

  private static final class CallbackTraversalSource extends YTDBGraphTraversalSource {

    private CallbackTraversalSource() {
      super(EmptyGraph.instance());
    }

    @Override
    public <X extends Exception, R> R computeInTx(
        FailableFunction<YTDBGraphTraversalSource, R, X> code) throws X {
      return code.apply(this);
    }
  }

  private static final class StubState extends LdbcBenchmarkState {

    private boolean personIdRead;
    private boolean maxDateRead;

    private StubState() {
      traversal = new CallbackTraversalSource();
    }

    @Override
    public long ic2PersonId(long index) {
      personIdRead = true;
      return 11;
    }

    @Override
    public Date ic2MaxDate(long index) {
      maxDateRead = true;
      return CURATED_DATE;
    }
  }

  private static final class StubArm extends LdbcGremlinTranslatorBenchmark.TranslatorArm {

    private boolean personIdRead;
    private boolean maxDateRead;

    @Override
    long ic2PersonId(long index) {
      personIdRead = true;
      return 22;
    }

    @Override
    Date ic2MaxDate(long index) {
      maxDateRead = true;
      return LIVE_DATE;
    }
  }
}
