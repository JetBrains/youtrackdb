package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.FetchFromIndexStep;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 7/3/14
 */
@Test
public abstract class BaseDBTest extends BaseTest<DatabaseSessionInternal> {

  protected static final int TOT_COMPANY_RECORDS = 10;
  protected static final int TOT_RECORDS_ACCOUNT = 100;

  protected BaseDBTest() {
  }

  @Parameters(value = "remote")
  protected BaseDBTest(boolean remote) {
    super(remote);
  }

  public BaseDBTest(boolean remote, String prefix) {
    super(remote, prefix);
  }

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    createBasicTestSchema();
  }

  @Override
  protected DatabaseSessionInternal createSessionInstance(
      YouTrackDB youTrackDB, String dbName, String user, String password) {
    var session = youTrackDB.open(dbName, user, password);
    return (DatabaseSessionInternal) session;
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
    return db.query(sql).stream()
        .toList();
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
    return session.query(sql).stream()
        .toList();
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

    final Set<Integer> accountClusterIds =
        Arrays.stream(session.getMetadata().getSchema().getClass("Account").getClusterIds())
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
        if (!remoteDB) {
          Assert.assertTrue(accountClusterIds.contains(element.getIdentity().getClusterId()));
        }
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
      addresses.add(session.bindToSession(address));
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

  protected SchemaClass createCountryClass() {
    if (session.getClass("Country") != null) {
      return session.getClass("Country");
    }

    var cls = session.createClass("Country");
    cls.createProperty("name", PropertyType.STRING);
    return cls;
  }

  protected SchemaClass createCityClass() {
    var countryCls = createCountryClass();

    if (session.getClass("City") != null) {
      return session.getClass("City");
    }

    var cls = session.createClass("City");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("country", PropertyType.LINK, countryCls);

    return cls;
  }

  protected SchemaClass createAddressClass() {
    if (session.getClass("Address") != null) {
      return session.getClass("Address");
    }

    var cityCls = createCityClass();
    var cls = session.createClass("Address");
    cls.createProperty("type", PropertyType.STRING);
    cls.createProperty("street", PropertyType.STRING);
    cls.createProperty("city", PropertyType.LINK, cityCls);

    return cls;
  }

  protected SchemaClass createAccountClass() {
    if (session.getClass("Account") != null) {
      return session.getClass("Account");
    }

    var addressCls = createAddressClass();
    var cls = session.createClass("Account");
    cls.createProperty("id", PropertyType.INTEGER);
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("surname", PropertyType.STRING);
    cls.createProperty("birthDate", PropertyType.DATE);
    cls.createProperty("salary", PropertyType.FLOAT);
    cls.createProperty("addresses", PropertyType.LINKLIST, addressCls);
    cls.createProperty("thumbnail", PropertyType.BINARY);
    cls.createProperty("photo", PropertyType.BINARY);

    return cls;
  }

  protected void createCompanyClass() {
    if (session.getClass("Company") != null) {
      return;
    }

    createAccountClass();
    var cls = session.createClassIfNotExist("Company", "Account");
    cls.createProperty("employees", PropertyType.INTEGER);
  }

  protected void createProfileClass() {
    if (session.getClass("Profile") != null) {
      return;
    }

    var addressCls = createAddressClass();
    var cls = session.createClass("Profile");
    cls.createProperty("nick", PropertyType.STRING)
        .setMin("3")
        .setMax("30")
        .createIndex(SchemaClass.INDEX_TYPE.UNIQUE,
            Map.of("ignoreNullValues", true));
    cls.createProperty("followings", PropertyType.LINKSET, cls);
    cls.createProperty("followers", PropertyType.LINKSET, cls);
    cls.createProperty("name", PropertyType.STRING)
        .setMin("3")
        .setMax("30")
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    cls.createProperty("surname", PropertyType.STRING).setMin("3")
        .setMax("30");
    cls.createProperty("location", PropertyType.LINK, addressCls);
    cls.createProperty("hash", PropertyType.LONG);
    cls.createProperty("invitedBy", PropertyType.LINK, cls);
    cls.createProperty("value", PropertyType.INTEGER);

    cls.createProperty("registeredOn", PropertyType.DATETIME)
        .setMin("2010-01-01 00:00:00");
    cls.createProperty("lastAccessOn", PropertyType.DATETIME)
        .setMin("2010-01-01 00:00:00");
  }

  protected SchemaClass createInheritanceTestAbstractClass() {
    if (session.getClass("InheritanceTestAbstractClass") != null) {
      return session.getClass("InheritanceTestAbstractClass");
    }

    var cls = session.createClass("InheritanceTestAbstractClass");
    cls.createProperty("cField", PropertyType.INTEGER);
    return cls;
  }

  protected SchemaClass createInheritanceTestBaseClass() {
    if (session.getClass("InheritanceTestBaseClass") != null) {
      return session.getClass("InheritanceTestBaseClass");
    }

    var abstractCls = createInheritanceTestAbstractClass();
    var cls = session.createClass("InheritanceTestBaseClass", abstractCls.getName());
    cls.createProperty("aField", PropertyType.STRING);

    return cls;
  }

  protected void createInheritanceTestClass() {
    if (session.getClass("InheritanceTestClass") != null) {
      return;
    }

    var baseCls = createInheritanceTestBaseClass();
    var cls = session.createClass("InheritanceTestClass", baseCls.getName());
    cls.createProperty("bField", PropertyType.STRING);
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

    if (session.getClusterIdByName("csv") == -1) {
      session.addCluster("csv");
    }

    if (session.getClusterIdByName("flat") == -1) {
      session.addCluster("flat");
    }

    if (session.getClusterIdByName("binary") == -1) {
      session.addCluster("binary");
    }
  }

  private void createWhizClass() {
    var account = createAccountClass();
    if (session.getMetadata().getSchema().existsClass("Whiz")) {
      return;
    }

    var whiz = session.getMetadata().getSchema().createClass("Whiz", 1);
    whiz.createProperty("id", PropertyType.INTEGER);
    whiz.createProperty("account", PropertyType.LINK, account);
    whiz.createProperty("date", PropertyType.DATE).setMin("2010-01-01");
    whiz.createProperty("text", PropertyType.STRING).setMandatory(true)
        .setMin("1")
        .setMax("140");
    whiz.createProperty("replyTo", PropertyType.LINK, account);
  }

  private void createAnimalRaceClass() {
    if (session.getMetadata().getSchema().existsClass("AnimalRace")) {
      return;
    }

    var animalRace =
        session.getMetadata().getSchema().createClass("AnimalRace", 1);
    animalRace.createProperty("name", PropertyType.STRING);
    var animal = session.getMetadata().getSchema().createClass("Animal", 1);
    animal.createProperty("races", PropertyType.LINKSET, animalRace);
    animal.createProperty("name", PropertyType.STRING);
  }

  private void createStrictTestClass() {
    if (session.getMetadata().getSchema().existsClass("StrictTest")) {
      return;
    }

    var strictTest =
        session.getMetadata().getSchema().createClass("StrictTest", 1);
    strictTest.setStrictMode(true);
    strictTest.createProperty("id", PropertyType.INTEGER).isMandatory();
    strictTest.createProperty("name", PropertyType.STRING);
  }

  protected void createComplexTestClass() {
    if (session.getSchema().existsClass("JavaComplexTestClass")) {
      session.getSchema().dropClass("JavaComplexTestClass");
    }
    if (session.getSchema().existsClass("Child")) {
      session.getSchema().dropClass("Child");
    }

    var childCls = session.createClass("Child");
    childCls.createProperty("name", PropertyType.STRING);

    var cls = session.createClass("JavaComplexTestClass");

    cls.createProperty("embeddedDocument", PropertyType.EMBEDDED);
    cls.createProperty("document", PropertyType.LINK);
    cls.createProperty("byteArray", PropertyType.LINK);
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("child", PropertyType.LINK, childCls);
    cls.createProperty("stringMap", PropertyType.EMBEDDEDMAP);
    cls.createProperty("stringListMap", PropertyType.EMBEDDEDMAP);
    cls.createProperty("list", PropertyType.LINKLIST, childCls);
    cls.createProperty("set", PropertyType.LINKSET, childCls);
    cls.createProperty("duplicationTestSet", PropertyType.LINKSET, childCls);
    cls.createProperty("children", PropertyType.LINKMAP, childCls);
    cls.createProperty("stringSet", PropertyType.EMBEDDEDSET);
    cls.createProperty("embeddedList", PropertyType.EMBEDDEDLIST);
    cls.createProperty("embeddedSet", PropertyType.EMBEDDEDSET);
    cls.createProperty("embeddedChildren", PropertyType.EMBEDDEDMAP);
    cls.createProperty("mapObject", PropertyType.EMBEDDEDMAP);
  }

  protected void createSimpleTestClass() {
    if (session.getSchema().existsClass("JavaSimpleTestClass")) {
      session.getSchema().dropClass("JavaSimpleTestClass");
    }

    var cls = session.createClass("JavaSimpleTestClass");
    cls.createProperty("text", PropertyType.STRING).setDefaultValue("initTest");
    cls.createProperty("numberSimple", PropertyType.INTEGER)
        .setDefaultValue("0");
    cls.createProperty("longSimple", PropertyType.LONG).setDefaultValue("0");
    cls.createProperty("doubleSimple", PropertyType.DOUBLE)
        .setDefaultValue("0");
    cls.createProperty("floatSimple", PropertyType.FLOAT).setDefaultValue("0");
    cls.createProperty("byteSimple", PropertyType.BYTE).setDefaultValue("0");
    cls.createProperty("shortSimple", PropertyType.SHORT).setDefaultValue("0");
    cls.createProperty("dateField", PropertyType.DATETIME);
  }

  protected void generateGraphData() {
    if (session.getSchema().existsClass("GraphVehicle")) {
      return;
    }

    var vehicleClass = session.createVertexClass("GraphVehicle");
    session.createClass("GraphCar", vehicleClass.getName());
    session.createClass("GraphMotocycle", "GraphVehicle");

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
    carNode = session.bindToSession(carNode);
    motoNode = session.bindToSession(motoNode);
    session.newStatefulEdge(carNode, motoNode);

    var result =
        session.query("select from GraphVehicle").stream().collect(Collectors.toList());
    Assert.assertEquals(result.size(), 2);
    for (var v : result) {
      Assert.assertTrue(
          v.asEntity().getSchemaClass().isSubClassOf(vehicleClass));
    }

    session.commit();
    result = session.query("select from GraphVehicle").stream().toList();
    Assert.assertEquals(result.size(), 2);

    Edge edge1 = null;
    Edge edge2 = null;

    for (var v : result) {
      Assert.assertTrue(
          v.asEntity().getSchemaClass().isSubClassOf("GraphVehicle"));

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
