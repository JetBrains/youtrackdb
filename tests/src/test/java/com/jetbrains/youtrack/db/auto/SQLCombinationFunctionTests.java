package com.jetbrains.youtrack.db.auto;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.Sets;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * Tests for "unionAll", "intersect" and "difference" functions.
 */
@Test
public class SQLCombinationFunctionTests extends BaseDBTest {

  @Parameters(value = "remote")
  public SQLCombinationFunctionTests(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    generateGraphRandomData();
    generateGeoData();
  }


  @Test
  public void unionAllAsAggregationNotRemoveDuplicates() {

    final var continents = session.query("SELECT continent FROM CountryExt").toList()
        .stream()
        .map(r -> r.<String>getProperty("continent"))
        .toList();

    final var continentsCombined =
        session.query("SELECT unionAll(continent) AS continents FROM CountryExt").toList()
            .getFirst()
            .<List<String>>getProperty("continents");

    // comparing sorted lists to ignore any differences in order
    assertListsEqualsIgnoreOrder(continentsCombined, continents);
  }

  @Test
  public void differenceAsAggregationThrowsError() {
    try {
      session.query("SELECT difference(continent) AS continents FROM CountryExt").toList();
      Assert.fail("Expected exception");
    } catch (CommandExecutionException e) {
      Assert.assertTrue(e.getMessage().contains("cannot be used in aggregation mode"));
    }
  }

  @Test
  public void unionAllLanguagesByCountry() {
    findLanguagesForCountry(FunctionDefinition.UNION_ALL);
  }

  @Test
  public void intersectLanguagesByCountry() {
    findLanguagesForCountry(FunctionDefinition.INTERSECT);
  }

  @Test
  public void differenceLanguagesByCountry() {
    findLanguagesForCountry(FunctionDefinition.DIFFERENCE);
  }

  // This test loads languages for specific countries and combines them using the specified function.
  // The loading of the languages is done in both aggregation and inline/scalar modes:
  // Aggregation:
  //    SELECT unionAll(languages) FROM CountryExt WHERE name = 'France' OR name = 'Germany'
  // Inline:
  //    SELECT $r AS langCombined LET
  //       $l0 = (SELECT expand(languages) FROM CountryExt WHERE name = 'France'),
  //       $l1 = (SELECT expand(languages) FROM CountryExt WHERE name = 'Germany'),
  //       $r = unionAll($l0, $l1)
  private void findLanguagesForCountry(FunctionDefinition fDef) {

    final var langsByCountry = session.query("select name, languages from CountryExt").toList()
        .stream()
        .collect(Collectors.toMap(
            r -> r.<String>getProperty("name"),
            r -> r.<List<String>>getProperty("languages")
        ));

    final var countrySets =
        combinations(fDef.minFunctionArgs, langsByCountry.size(), langsByCountry.keySet());

    for (var countrySet : countrySets) {

      // This is the expected result, that must be produced by both queries below
      final var expectedLangs =
          fDef.impl(countrySet.stream().map(langsByCountry::get).toList());

      if (fDef.aggregationMode) {
        // 1. Aggregation mode
        // Example:
        // SELECT unionAll(languages) FROM CountryExt WHERE name = 'France'
        final var query1 = new StringBuilder("SELECT ")
            .append(fDef.name).append("(languages) AS langCombined FROM CountryExt WHERE ");

        // can't use IN operator because of https://youtrack.jetbrains.com/issue/YTDB-227
        final var countryConditions =
            countrySet.stream()
                .map(s -> String.format("(name = '%s')", s))
                .toList();

        query1.append(String.join(" OR ", countryConditions));
        final var l1 = session.query(query1.toString()).toList()
            .getFirst()
            .<Collection<String>>getProperty("langCombined");

        final var langsCombined1 = l1.stream().toList();

        assertListsEqualsIgnoreOrder(langsCombined1, expectedLangs);
      }

      // 2. Inline mode
      // Example:
      // SELECT $r AS langCombined LET
      //   $l0 = (SELECT expand(languages) FROM CountryExt WHERE name = 'France'),
      //   $l1 = (SELECT expand(languages) FROM CountryExt WHERE name = 'Germany'),
      //   $r = unionAll($l0, $l1)
      final var query2 = new StringBuilder("SELECT $r AS langCombined LET ");

      final var lVars = new ArrayList<String>();
      final var countryList = countrySet.stream().toList();
      for (var i = 0; i < countrySet.size(); i++) {
        query2.append(String.format(
            "$l%d = (SELECT expand(languages) FROM CountryExt WHERE name = '%s'),",
            i, countryList.get(i)
        ));
        lVars.add(String.format("$l%d", i));
      }
      query2
          .append("$r = ").append(fDef.name).append("(")
          .append(String.join(",", lVars))
          .append(")");

      final var langsCombined2 = session.query(query2.toString()).toList().getFirst()
          .<Collection<Result>>getProperty("langCombined")
          .stream()
          .map(e -> e.<String>getProperty("value"))
          .collect(Collectors.toList());

      assertListsEqualsIgnoreOrder(langsCombined2, expectedLangs);
    }
  }

  @Test
  public void unionAllCountriesByLanguage() {
    findCountriesForLanguage(FunctionDefinition.UNION_ALL);
  }

  @Test
  public void intersectCountriesByLanguage() {
    findCountriesForLanguage(FunctionDefinition.INTERSECT);
  }

  @Test
  public void differenceCountriesByLanguage() {
    findCountriesForLanguage(FunctionDefinition.DIFFERENCE);
  }

  // This test loads country records for specific languages (inline/scalar mode only):
  // SELECT expand($r) AS countries LET
  //      $l1 = (SELECT FROM CountryExt WHERE 'French' IN languages),
  //      $l2 = (SELECT FROM CountryExt WHERE 'German' IN languages),
  //      $r = unionAll($l1, $l2)

  private void findCountriesForLanguage(FunctionDefinition fDef) {

    final var countryByLang =
        session.query("select name, languages from CountryExt").toList()
            .stream()
            .collect(Collectors.toMap(
                r1 -> r1.<String>getProperty("name"),
                r1 -> r1.<List<String>>getProperty("languages")
            ))
            .entrySet().stream()
            .flatMap(e -> e.getValue().stream().map(l -> new RawPair<>(l, e.getKey())))
            .collect(Collectors.groupingBy(
                RawPair::getFirst,
                Collectors.mapping(RawPair::getSecond, Collectors.toList())
            ));

    final var langCombinations = combinations(fDef.minFunctionArgs, countryByLang.size(),
        countryByLang.keySet());

    for (var langCombination : langCombinations) {
      final var query = new StringBuilder("SELECT expand($r2) LET ");

      final var langsList = langCombination.stream().toList();
      final var varNames = new ArrayList<String>();
      for (var i = 0; i < langsList.size(); i++) {
        query.append(
            String.format("$l%d = (SELECT FROM CountryExt WHERE '%s' IN languages),",
                i, langsList.get(i))
        );
        varNames.add(String.format("$l%d", i));
      }

      query.append(String.format("$r = %s(%s),", fDef.name, String.join(",", varNames)));
      query.append("$r2 = (SELECT from $r)");

      final var selectedCountryNames = session.query(query.toString())
          .stream()
          .map(r -> r.<String>getProperty("name"))
          .toList();

      final var expectedCountryNames = fDef.impl(
          langsList.stream().map(countryByLang::get).toList()
      );

      assertListsEqualsIgnoreOrder(selectedCountryNames, expectedCountryNames);

    }
  }

  @Test
  public void unionAllInlineEdges() {
    runEdgeInlineTest(FunctionDefinition.UNION_ALL);
  }

  @Test
  public void intersectInlineEdges() {
    runEdgeInlineTest(FunctionDefinition.INTERSECT);
  }

  @Test
  public void differenceInlineEdges() {
    runEdgeInlineTest(FunctionDefinition.DIFFERENCE);
  }

  private void runEdgeInlineTest(FunctionDefinition fDef) {

    session.begin();
    final var vertexes = session.query("SELECT FROM GraphVehicle_CF").entityStream().toList();

    final var insAndOuts = vertexes.stream().collect(Collectors.toMap(
        r -> r.<RecordId>getProperty("@rid"),
        r -> {

          final var ins = ((EntityImpl) r).<LinkBag>getPropertyInternal("in_");
          final var outs = ((EntityImpl) r).<LinkBag>getPropertyInternal("out_");

          return new RawPair<>(ins, outs);
        }
    ));

    final var query = "SELECT @rid, " + fDef.name + "(inE(), outE()) AS edges FROM GraphVehicle_CF";
    var edgesAggregated = session.query(query).stream().toList();

    for (var d : edgesAggregated) {
      Assert.assertTrue(d.hasProperty("edges"));
    }

    final var result = edgesAggregated.stream().collect(Collectors.toMap(
        r -> r.<RecordId>getProperty("@rid"),
        r -> r.<Collection<Identifiable>>getProperty("edges")
    ));
    assertEquals(insAndOuts.keySet(), result.keySet());

    for (var e : insAndOuts.entrySet()) {
      final var rid = e.getKey();

      final List<RID> ins =
          e.getValue().getFirst() == null ? List.of() :
              StreamSupport.stream(e.getValue().getFirst().spliterator(), false).toList();

      final List<RID> outs =
          e.getValue().getSecond() == null ? List.of() :
              StreamSupport.stream(e.getValue().getSecond().spliterator(), false).toList();

      final var expectedEdges = fDef.impl(List.of(ins, outs));

      final var edges = new ArrayList<>(result.get(rid));

      assertListsEqualsIgnoreOrder(edges, expectedEdges);
    }
    session.commit();
  }

  private void generateGraphRandomData() {

    var vehicleClass = session.createVertexClass("GraphVehicle_CF");
    session.createClass("GraphCar_CF", vehicleClass.getName());
    session.createClass("GraphMotocycle_CF", "GraphVehicle_CF");
    final var r = new Random();

    final var carsNo = r.nextInt(2, 32);

    session.begin();
    final var cars = new ArrayList<Vertex>();
    for (var i = 0; i < carsNo; i++) {
      var carNode = session.newVertex("GraphCar_CF");
      carNode.setProperty("brand", "Brand" + (i + 1));
      carNode.setProperty("model", "Car" + (i + 1));
      carNode.setProperty("year", r.nextInt(1990, 2024));
      cars.add(carNode);
    }

    final var motorcyclesNo = r.nextInt(2, 32);
    final var motorcycles = new ArrayList<Vertex>();
    for (var i = 0; i < motorcyclesNo; i++) {
      var motorcycleNode = session.newVertex("GraphMotocycle_CF");
      motorcycleNode.setProperty("brand", "Brand" + (i + 1));
      motorcycleNode.setProperty("model", "Motorcycle" + (i + 1));
      motorcycleNode.setProperty("year", r.nextInt(1990, 2024));
      motorcycles.add(motorcycleNode);
    }
    session.commit();

    // creating random edges between cars and motocycles
    class EdgeDef {

      final int carIdx;
      final int monoIdx;
      final boolean reverse;

      public EdgeDef(int carIdx, int monoIdx, boolean reverse) {
        this.carIdx = carIdx;
        this.monoIdx = monoIdx;
        this.reverse = reverse;
      }
    }
    final var edges = new ArrayList<EdgeDef>();
    for (var i = 0; i < carsNo; i++) {
      for (var i1 = 0; i1 < motorcyclesNo; i1++) {
        for (var reverese : new boolean[]{false, true}) {
          edges.add(new EdgeDef(i, i1, reverese));
        }
      }
    }
    Collections.shuffle(edges, r);
    final var edgesToCreate = edges.stream().limit(r.nextInt(2, 32)).toList();

    session.begin();
    for (var re : edgesToCreate) {
      final var car = cars.get(re.carIdx);
      final var motorcycle = motorcycles.get(re.monoIdx);

      final Vertex from;
      final Vertex to;
      if (re.reverse) {
        var activeTx1 = session.getActiveTransaction();
        from = activeTx1.load(motorcycle);
        var activeTx = session.getActiveTransaction();
        to = activeTx.load(car);
      } else {
        var activeTx1 = session.getActiveTransaction();
        from = activeTx1.load(car);
        var activeTx = session.getActiveTransaction();
        to = activeTx.load(motorcycle);
      }
      session.newStatefulEdge(from, to);
    }
    session.commit();
  }

  private void generateGeoData() {
    var countryClass = session.createClass("CountryExt");
    countryClass.createProperty("name", PropertyType.STRING);
    countryClass.createProperty("continent", PropertyType.STRING);
    countryClass.createProperty("languages", PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);

    var cls = session.createClass("CityExt");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("country", PropertyType.LINK, countryClass);

    session.begin();

    final var germany = createCountry("Germany", "Europe", List.of("German"));
    final var czech = createCountry("Czech Republic", "Europe", List.of("Czech"));
    final var switzerland = createCountry("Switzerland", "Europe",
        List.of("German", "French", "Italian"));
    final var france = createCountry("France", "Europe", List.of("French"));
    final var portugal = createCountry("Portugal", "Europe", List.of("Portuguese"));
    final var china = createCountry("China", "Asia", List.of("Mandarin", "Cantonese"));
    final var brazil = createCountry("Brazil", "South America", List.of("Portuguese"));
    final var usa = createCountry("USA", "North America", List.of("English"));
    final var uk = createCountry("United Kingdom", "Europe", List.of("English", "Welsh"));

    createCity("Berlin", null, germany);
    createCity("Munich", "München", germany);
    createCity("Frankfurt", "Frankfurt am Main", germany);
    createCity("Prague", "Praha", czech);
    createCity("Paris", null, france);
    createCity("Lyon", null, france);
    createCity("Bern", null, switzerland);
    createCity("Geneva", null, switzerland);
    createCity("Lisbon", "Lisboa", portugal);
    createCity("Beijing", null, china);
    createCity("Shanghai", null, china);
    createCity("Rio de Janeiro", null, brazil);
    createCity("San Francisco", null, usa);
    createCity("Tampa", null, usa);
    createCity("London", null, uk);
    createCity("Glasgow", null, uk);

    session.commit();
  }

  private Entity createCountry(String name, String continent, List<String> languages) {

    var country = session.newInstance("CountryExt");
    country.setProperty("name", name);
    country.setProperty("continent", continent);
    country.setProperty("languages", session.newEmbeddedList(languages));

    return country;
  }

  private Entity createCity(String name, String localName, Entity country) {
    var city = session.newInstance("CityExt");
    city.setProperty("name", name);
    city.setProperty("localName", localName == null ? name : localName);
    city.setProperty("country", country);

    return city;
  }

  private static void assertListsEqualsIgnoreOrder(List<?> list1, List<?> list2) {
    assertListsEqualsIgnoreOrder(list1, list2, null);
  }

  private static void assertListsEqualsIgnoreOrder(List<?> list1, List<?> list2, String message) {
    final var l1Sorted = list1.stream().sorted().toList();
    final var l2Sorted = list2.stream().sorted().toList();
    Assert.assertEquals(l1Sorted, l2Sorted, message);
  }

  private static Set<Set<String>> combinations(int minLength, int maxLength, Set<String> values) {

    final var combinations = new HashSet<Set<String>>();
    for (var i = minLength; i <= Math.min(maxLength, values.size()); i++) {
      combinations.addAll(Sets.combinations(values, i));
    }

    return combinations;
  }

  private enum FunctionDefinition {

    UNION_ALL("unionAll", true, 1) {
      @Override
      public <T> List<T> impl(List<List<T>> collections) {
        return collections.stream().flatMap(Collection::stream).toList();
      }
    },
    INTERSECT("intersect", true, 1) {
      @Override
      public <T> List<T> impl(List<List<T>> collections) {
        if (collections.isEmpty()) {
          return List.of();
        }

        final var first = collections.getFirst();
        final var rest = collections.stream().skip(1).toList();

        return first.stream().filter(l -> rest.stream().allMatch(r -> r.contains(l))).toList();
      }
    },
    DIFFERENCE("difference", false, 1) {
      @Override
      public <T> List<T> impl(List<List<T>> collections) {
        if (collections.isEmpty()) {
          return List.of();
        }

        final var first = collections.getFirst();
        final var rest = collections.stream().skip(1).toList();

        return first.stream().filter(l -> rest.stream().noneMatch(r -> r.contains(l))).toList();
      }
    };

    final String name;
    final boolean aggregationMode;
    final int minFunctionArgs;

    FunctionDefinition(String name, boolean aggregationMode, int minFunctionArgs) {
      this.name = name;
      this.aggregationMode = aggregationMode;
      this.minFunctionArgs = minFunctionArgs;
    }

    public abstract <T> List<T> impl(List<List<T>> collections);
  }
}
