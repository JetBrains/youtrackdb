package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.query.ExecutionPlan;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity.IndexBy;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * @since 7/3/14
 */
@Test
public abstract class BaseDBTest extends BaseTest {

  protected static final int TOT_COMPANY_RECORDS = 10;
  protected static final int TOT_RECORDS_ACCOUNT = 100;

  protected BaseDBTest() {
    super();
  }

  public BaseDBTest(String prefix) {
    super(prefix);
  }

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    createBasicTestSchema();
  }

  @Override
  protected DatabaseSessionEmbedded createSessionInstance(
      YouTrackDBImpl youTrackDB, String dbName, String user, String password) {
    return (DatabaseSessionEmbedded) youTrackDB.open(dbName, user, password);
  }

  protected List<Result> executeQuery(String sql, DatabaseSessionInternal db,
      Object... args) {
    return db.query(sql, args).stream()
        .toList();
  }

  protected static List<Result> executeQuery(String sql, DatabaseSessionInternal db, Map args) {
    return db.query(sql, args).stream()
        .toList();
  }

  protected static List<Result> executeQuery(String sql, DatabaseSessionInternal db) {
    try (var rs = db.query(sql)) {
      return rs.stream()
          .toList();
    }
  }

  protected List<Result> executeQuery(String sql, Object... args) {
    return session.query(sql, args).stream()
        .toList();
  }

  protected List<Result> executeQuery(String sql, Map<?, ?> args) {
    return session.query(sql, args).stream()
        .toList();
  }

  protected List<Result> executeQuery(String sql) {
    try (var rs = session.query(sql)) {
      return rs.stream().toList();
    }
  }

  protected void addBarackObamaAndFollowers() {
    createProfileClass();

    session.begin();
    if (session.query("select from Profile where name = 'Barack' and surname = 'Obama'").stream()
        .findAny()
        .isEmpty()) {

      var bObama = session.newEntity("Profile");
      bObama.setProperty("nick", "ThePresident");
      bObama.setProperty("name", "Barack");
      bObama.setProperty("surname", "Obama");
      bObama.getOrCreateLinkSet("followings");

      var follower1 = session.newEntity("Profile");
      follower1.setProperty("nick", "PresidentSon1");
      follower1.setProperty("name", "Malia Ann");
      follower1.setProperty("surname", "Obama");

      follower1.getOrCreateLinkSet("followings").add(bObama);
      follower1.getOrCreateLinkSet("followers");

      var follower2 = session.newEntity("Profile");
      follower2.setProperty("nick", "PresidentSon2");
      follower2.setProperty("name", "Natasha");
      follower2.setProperty("surname", "Obama");
      follower2.getOrCreateLinkSet("followings").add(bObama);
      follower2.getOrCreateLinkSet("followers");

      var followers = new HashSet<Entity>();
      followers.add(follower1);
      followers.add(follower2);

      bObama.getOrCreateLinkSet("followers").addAll(followers);
    }

    session.commit();
  }

  protected void fillInAccountData() {
    Set<Integer> ids = new HashSet<>();
    for (var i = 0; i < TOT_RECORDS_ACCOUNT; i++) {
      ids.add(i);
    }

    byte[] binary;
    createAccountClass();

    final Set<Integer> accountCollectionIds =
        Arrays.stream(
                session.getMetadata().getSlowMutableSchema().getClass("Account").getCollectionIds())
            .asLongStream()
            .mapToObj(i -> (int) i)
            .collect(HashSet::new, HashSet::add, HashSet::addAll);

    if (session.countClass("Account") == 0) {
      for (var id : ids) {
        session.begin();
        var element = session.newEntity("Account");
        element.setProperty("id", id);
        element.setProperty("name", "Gipsy");
        element.setProperty("location", "Italy");
        element.setProperty("testLong", 10000000000L);
        element.setProperty("salary", id + 300);
        element.setProperty("extra", "This is an extra field not included in the schema");
        element.setProperty("value", (byte) 10);

        binary = new byte[100];
        for (var b = 0; b < binary.length; ++b) {
          binary[b] = (byte) b;
        }
        element.setProperty("binary", binary);
        Assert.assertTrue(accountCollectionIds.contains(element.getIdentity().getCollectionId()));

        session.commit();
      }
    }
  }

  protected void generateProfiles() {
    createProfileClass();
    createCountryClass();
    createCityClass();

    session.executeInTx(
        transaction -> {
          addGaribaldiAndBonaparte();
          addBarackObamaAndFollowers();

          var count =
              session.query("select count(*) as count from Profile").stream()
                  .findFirst()
                  .orElseThrow()
                  .<Long>getProperty("count");

          if (count < 1_000) {
            for (var i = 0; i < 1_000 - count; i++) {
              var profile = session.newEntity("Profile");
              profile.setProperty("nick", "generatedNick" + i);
              profile.setProperty("name", "generatedName" + i);
              profile.setProperty("surname", "generatedSurname" + i);
            }
          }
        });
  }

  protected void addGaribaldiAndBonaparte() {
    session.executeInTx(
        transaction -> {
          if (session.query("select from Profile where nick = 'NBonaparte'").stream()
              .findAny()
              .isPresent()) {
            return;
          }

          var rome = addRome();
          var garibaldi = session.newInstance("Profile");
          garibaldi.setProperty("nick", "GGaribaldi");
          garibaldi.setProperty("name", "Giuseppe");
          garibaldi.setProperty("surname", "Garibaldi");

          var gAddress = session.newInstance("Address");
          gAddress.setProperty("type", "Residence");
          gAddress.setProperty("street", "Piazza Navona, 1");
          gAddress.setProperty("city", rome);
          garibaldi.setProperty("location", gAddress);

          var bonaparte = session.newInstance("Profile");
          bonaparte.setProperty("nick", "NBonaparte");
          bonaparte.setProperty("name", "Napoleone");
          bonaparte.setProperty("surname", "Bonaparte");
          bonaparte.setProperty("invitedBy", garibaldi);

          var bnAddress = session.newInstance("Address");
          bnAddress.setProperty("type", "Residence");
          bnAddress.setProperty("street", "Piazza di Spagna, 111");
          bnAddress.setProperty("city", rome);
          bonaparte.setProperty("location", bnAddress);

        });
  }

  private Entity addRome() {
    return session.computeInTx(
        transaction -> {
          var italy = addItaly();
          var city = session.newInstance("City");
          city.setProperty("name", "Rome");
          city.setProperty("country", italy);

          return city;
        });
  }

  private Entity addItaly() {
    return session.computeInTx(
        transaction -> {
          var italy = session.newEntity("Country");
          italy.setProperty("name", "Italy");
          return italy;
        });
  }

  protected void generateCompanyData() {
    fillInAccountData();
    createCompanyClass();

    if (session.countClass("Company") > 0) {
      return;
    }

    var address = createRedmondAddress();

    for (var i = 0; i < TOT_COMPANY_RECORDS; ++i) {
      session.begin();
      var company = session.newInstance("Company");
      company.setProperty("id", i);
      company.setProperty("name", "Microsoft" + i);
      company.setProperty("employees", 100000 + i);
      company.setProperty("salary", 1000000000.0f + i);

      var addresses = session.newLinkList();
      var activeTx = session.getActiveTransaction();
      addresses.add(activeTx.<Entity>load(address));
      company.setProperty("addresses", addresses);
      session.commit();
    }
  }

  protected Entity createRedmondAddress() {
    session.begin();
    var washington = session.newInstance("Country");
    washington.setProperty("name", "Washington");

    var redmond = session.newInstance("City");
    redmond.setProperty("name", "Redmond");
    redmond.setProperty("country", washington);

    var address = session.newInstance("Address");
    address.setProperty("type", "Headquarter");
    address.setProperty("city", redmond);
    address.setProperty("street", "WA 98073-9717");

    session.commit();
    return address;
  }

  protected void createCountryClass() {
    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaClass("Country").fold().coalesce(
            __.unfold(),
            __.addSchemaClass("Country").
                addSchemaProperty("name", PropertyType.STRING)
        )
    );
  }

  protected void createCityClass() {
    createCountryClass();

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaClass("City").fold().coalesce(
            __.unfold(),
            __.addSchemaClass("City",
                __.addSchemaProperty("name", PropertyType.STRING),
                __.addSchemaProperty("country", PropertyType.LINK, "Country")
            )
        )
    );
  }

  protected void createAddressClass() {
    createCityClass();

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaClass("Address").fold().coalesce(
            __.unfold(),
            __.addSchemaClass("Address",
                __.addSchemaProperty("type", PropertyType.STRING),
                __.addSchemaProperty("street", PropertyType.STRING),
                __.addSchemaProperty("city", PropertyType.LINK, "City")
            )
        )
    );
  }

  protected void createAccountClass() {
    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaClass("Account").fold().coalesce(
            __.unfold(),
            __.addSchemaClass("Account",
                __.addSchemaProperty("id", PropertyType.INTEGER),
                __.addSchemaProperty("name", PropertyType.STRING),
                __.addSchemaProperty("surname", PropertyType.STRING),
                __.addSchemaProperty("birthDate", PropertyType.DATE),
                __.addSchemaProperty("salary", PropertyType.FLOAT),
                __.addSchemaProperty("thumbnail", PropertyType.BINARY),
                __.addSchemaProperty("photo", PropertyType.BINARY),
                __.addSchemaProperty("addresses", PropertyType.LINKLIST, "Address")
            )
        )
    );

  }

  protected void createCompanyClass() {
    createAccountClass();

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaClass("Company").fold().coalesce(
            __.unfold(),
            __.addSchemaClass("Company").addParentClass("Account")
                .addSchemaProperty("employees", PropertyType.INTEGER))
    );
  }

  protected void createProfileClass() {
    createAddressClass();

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaClass("Profile").fold().coalesce(
            __.unfold(),
            __.addSchemaClass("Profile",
                __.addSchemaProperty("nick", PropertyType.STRING).
                    minAttr("3").maxAttr("30")
                    .addPropertyIndex(IndexType.UNIQUE, IndexBy.BY_VALUE, true),
                __.addSchemaProperty("name", PropertyType.STRING).minAttr("3").maxAttr("30")
                    .addPropertyIndex(IndexType.NOT_UNIQUE),
                __.addSchemaProperty("location", PropertyType.LINK, "Address"),
                __.addSchemaProperty("surname", PropertyType.STRING).minAttr("3").maxAttr("30"),
                __.addSchemaProperty("hash", PropertyType.LONG),
                __.addSchemaProperty("value", PropertyType.INTEGER),
                __.addSchemaProperty("registeredOn", PropertyType.DATETIME)
                    .minAttr("2010-01-01 00:00:00"),
                __.addSchemaProperty("lastAccessOn", PropertyType.DATETIME)
                    .minAttr("2010-01-01 00:00:00")
            )
        )
    );

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaClass("Profile").sideEffects(
            __.addSchemaProperty("invitedBy", PropertyType.LINK, "Profile"),
            __.addSchemaProperty("followings", PropertyType.LINKSET, "Profile"),
            __.addSchemaProperty("followers", PropertyType.LINKSET, "Profile")
        )
    );
  }

  protected void createInheritanceTestAbstractClass() {
    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaClass("InheritanceTestAbstractClass").fold().coalesce(
            __.unfold(),
            __.addAbstractSchemaClass("InheritanceTestAbstractClass")
                .addSchemaProperty("cField", PropertyType.INTEGER)
        ));
  }

  protected void createInheritanceTestBaseClass() {
    createInheritanceTestAbstractClass();

    //noinspection unchecked
    graph.autoExecuteInTx(g -> g.schemaClass("InheritanceTestBaseClass").
        fold().coalesce(
            __.unfold(),
            __.addSchemaClass("InheritanceTestBaseClass").
                addParentClass("InheritanceTestAbstractClass")
                .addSchemaProperty("aField", PropertyType.STRING)
        ));
  }

  protected void createInheritanceTestClass() {
    createInheritanceTestBaseClass();

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaClass("InheritanceTestClass").fold().coalesce(
            __.unfold(),
            __.addSchemaClass("InheritanceTestClass")
                .addParentClass("InheritanceTestBaseClass")
                .addSchemaProperty("bField", PropertyType.STRING)
        )
    );
  }

  protected void createBasicTestSchema() {
    createCountryClass();
    createAddressClass();
    createCityClass();
    createAccountClass();
    createCompanyClass();
    createProfileClass();
    createStrictTestClass();
    createAnimalRaceClass();
    createWhizClass();
  }

  private void createWhizClass() {
    createAccountClass();

    //noinspection unchecked
    graph.autoExecuteInTx(g -> g.schemaClass("Whiz").fold().coalesce(
        __.unfold(),
        __.addSchemaClass("Whiz")
            .addSchemaProperty("id", PropertyType.INTEGER)
            .addSchemaProperty("account", PropertyType.LINK, "Account")
            .addSchemaProperty("date", PropertyType.DATE).minAttr("2010-01-01")
            .addSchemaProperty("text", PropertyType.STRING).mandatoryAttr(true)
            .minAttr("1")
            .maxAttr("140")
            .addSchemaProperty("replyTo", PropertyType.LINK, "Account")
    ));
  }

  private void createAnimalRaceClass() {
    //noinspection unchecked
    graph.autoExecuteInTx(g -> g.schemaClass("AnimalRace").fold().coalesce(
        __.unfold(),
        __.addSchemaClass("AnimalRace").addSchemaProperty("name", PropertyType.STRING).
            addSchemaClass("Animal",
                __.addSchemaProperty("races", PropertyType.LINKSET, "AnimalRace"),
                __.addSchemaProperty("name", PropertyType.STRING)
            )
    ));
  }

  private void createStrictTestClass() {
    //noinspection unchecked
    graph.autoExecuteInTx(g -> g.schemaClass("StrictTest").fold().coalesce(
        __.unfold(),
        __.addSchemaClass("StrictTest",
            __.addSchemaProperty("id", PropertyType.INTEGER).mandatoryAttr(true),
            __.addSchemaProperty("name", PropertyType.STRING)
        )
    ));
  }

  protected void createComplexTestClass() {
    graph.autoExecuteInTx(g -> g.schemaClass("JavaComplexTestClass").drop());
    graph.autoExecuteInTx(g -> g.schemaClass("Child").drop());

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("Child").addSchemaProperty("name", PropertyType.STRING).
            addSchemaClass("JavaComplexTestClass",
                __.addSchemaProperty("embeddedDocument", PropertyType.EMBEDDED),
                __.addSchemaProperty("document", PropertyType.LINK),
                __.addSchemaProperty("byteArray", PropertyType.LINK),
                __.addSchemaProperty("name", PropertyType.STRING),
                __.addSchemaProperty("stringMap", PropertyType.EMBEDDEDMAP),
                __.addSchemaProperty("stringListMap", PropertyType.EMBEDDEDMAP),
                __.addSchemaProperty("stringSet", PropertyType.EMBEDDEDSET),
                __.addSchemaProperty("embeddedList", PropertyType.EMBEDDEDLIST),
                __.addSchemaProperty("embeddedSet", PropertyType.EMBEDDEDSET),
                __.addSchemaProperty("embeddedChildren", PropertyType.EMBEDDEDMAP),
                __.addSchemaProperty("mapObject", PropertyType.EMBEDDEDMAP),

                __.addSchemaProperty("child", PropertyType.LINK, "Child"),
                __.addSchemaProperty("set", PropertyType.LINKSET, "Child"),
                __.addSchemaProperty("duplicationTestSet", PropertyType.LINKSET, "Child"),
                __.addSchemaProperty("children", PropertyType.LINKMAP, "Child")
            )
    );
  }

  protected void createSimpleTestClass() {
    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaClass("JavaSimpleTestClass").fold().coalesce(
            __.unfold(),
            __.addSchemaClass("JavaSimpleTestClass",
                __.addSchemaProperty("text", PropertyType.STRING).defaultValueAttr("initTest"),
                __.addSchemaProperty("numberSimple", PropertyType.INTEGER).defaultValueAttr("0"),
                __.addSchemaProperty("longSimple", PropertyType.LONG).defaultValueAttr("0"),
                __.addSchemaProperty("doubleSimple", PropertyType.DOUBLE).defaultValueAttr("0"),
                __.addSchemaProperty("floatSimple", PropertyType.FLOAT).defaultValueAttr("0"),
                __.addSchemaProperty("byteSimple", PropertyType.BYTE).defaultValueAttr("0"),
                __.addSchemaProperty("shortSimple", PropertyType.SHORT).defaultValueAttr("0"),
                __.addSchemaProperty("dateField", PropertyType.DATETIME)
            )
        )
    );
  }

  protected void generateGraphData() {
    //noinspection unchecked
    graph.autoExecuteInTx(g -> g.schemaClass("GraphVehicle").
        fold().coalesce(
            __.unfold(),
            __.addSchemaClass("GraphVehicle").addSchemaClass("GraphCar")
                .addParentClass("GraphVehicle").
                addSchemaClass("GraphMotocycle").addParentClass("GraphVehicle")
        )
    );

    session.begin();
    var carNode = session.newVertex("GraphCar");
    carNode.setProperty("brand", "Hyundai");
    carNode.setProperty("model", "Coupe");
    carNode.setProperty("year", 2003);

    var motoNode = session.newVertex("GraphMotocycle");
    motoNode.setProperty("brand", "Yamaha");
    motoNode.setProperty("model", "X-City 250");
    motoNode.setProperty("year", 2009);
    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    carNode = activeTx1.load(carNode);
    var activeTx = session.getActiveTransaction();
    motoNode = activeTx.load(motoNode);
    session.newStatefulEdge(carNode, motoNode);

    var result =
        session.query("select from GraphVehicle").stream().collect(Collectors.toList());
    Assert.assertEquals(result.size(), 2);
    for (var v : result) {
      Assert.assertTrue(
          v.asEntity().getSchemaClass().isChildOf("GraphVehicle"));
    }

    session.commit();
    session.begin();
    result = session.query("select from GraphVehicle").stream().toList();
    Assert.assertEquals(result.size(), 2);

    Edge edge1 = null;
    Edge edge2 = null;

    for (var v : result) {
      Assert.assertTrue(
          v.asEntity().getSchemaClass().isChildOf("GraphVehicle"));

      if (v.asEntity().getSchemaClass() != null
          && v.asEntity().getSchemaClassName().equals("GraphCar")) {
        Assert.assertEquals(
            CollectionUtils.size(
                session.<Vertex>load(v.getIdentity()).getEdges(Direction.OUT)),
            1);
        edge1 =
            session
                .<Vertex>load(v.getIdentity())
                .getEdges(Direction.OUT)
                .iterator()
                .next();
      } else {
        Assert.assertEquals(
            CollectionUtils.size(
                session.<Vertex>load(v.getIdentity()).getEdges(Direction.IN)),
            1);
        edge2 =
            session.<Vertex>load(v.getIdentity()).getEdges(Direction.IN).iterator()
                .next();
      }
    }

    Assert.assertEquals(edge1, edge2);
    session.commit();
  }

  public static int indexesUsed(ExecutionPlan executionPlan) {
    var indexes = new HashSet<String>();
    indexesUsed(indexes, executionPlan);

    return indexes.size();
  }

  private static void indexesUsed(Set<String> indexes, ExecutionPlan executionPlan) {
    var steps = executionPlan.getSteps();
    for (var step : steps) {
      indexesUsed(indexes, step);
    }
  }

  private static void indexesUsed(Set<String> indexes, ExecutionStep step) {
    if (step instanceof FetchFromIndexStep fetchFromIndexStep) {
      indexes.add(fetchFromIndexStep.getIndexName());
    }

    var subSteps = step.getSubSteps();
    for (var subStep : subSteps) {
      indexesUsed(indexes, subStep);
    }

    if (step instanceof ExecutionStepInternal internalStep) {
      var subPlans = internalStep.getSubExecutionPlans();
      for (var subPlan : subPlans) {
        indexesUsed(indexes, subPlan);
      }
    }
  }
}
