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
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.FetchFromIndexStep;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

  protected List<EntityImpl> executeQuery(String sql, DatabaseSessionInternal db,
      Object... args) {
    return db.query(sql, args).stream()
        .map(Result::toEntity)
        .map(element -> (EntityImpl) element)
        .toList();
  }

  protected List<EntityImpl> executeQuery(String sql, DatabaseSessionInternal db, Map args) {
    return db.query(sql, args).stream()
        .map(Result::toEntity)
        .map(element -> (EntityImpl) element)
        .toList();
  }

  protected List<EntityImpl> executeQuery(String sql, DatabaseSessionInternal db) {
    return db.query(sql).stream()
        .map(Result::toEntity)
        .map(element -> (EntityImpl) element)
        .toList();
  }

  protected List<EntityImpl> executeQuery(String sql, Object... args) {
    return database.query(sql, args).stream()
        .map(Result::toEntity)
        .map(element -> (EntityImpl) element)
        .toList();
  }

  protected List<EntityImpl> executeQuery(String sql, Map<?, ?> args) {
    return database.query(sql, args).stream()
        .map(Result::toEntity)
        .map(element -> (EntityImpl) element)
        .toList();
  }

  protected List<EntityImpl> executeQuery(String sql) {
    return database.query(sql).stream()
        .map(Result::toEntity)
        .map(element -> (EntityImpl) element)
        .toList();
  }

  protected void addBarackObamaAndFollowers() {
    createProfileClass();

    database.begin();
    if (database.query("select from Profile where name = 'Barack' and surname = 'Obama'").stream()
        .findAny()
        .isEmpty()) {

      var bObama = database.newEntity("Profile");
      bObama.setProperty("nick", "ThePresident");
      bObama.setProperty("name", "Barack");
      bObama.setProperty("surname", "Obama");
      bObama.setProperty("followings", Collections.emptySet());

      var follower1 = database.newEntity("Profile");
      follower1.setProperty("nick", "PresidentSon1");
      follower1.setProperty("name", "Malia Ann");
      follower1.setProperty("surname", "Obama");
      follower1.setProperty("followings", Collections.singleton(bObama));
      follower1.setProperty("followers", Collections.emptySet());

      var follower2 = database.newEntity("Profile");
      follower2.setProperty("nick", "PresidentSon2");
      follower2.setProperty("name", "Natasha");
      follower2.setProperty("surname", "Obama");
      follower2.setProperty("followings", Collections.singleton(bObama));
      follower2.setProperty("followers", Collections.emptySet());

      var followers = new HashSet<Entity>();
      followers.add(follower1);
      followers.add(follower2);

      bObama.setProperty("followers", followers);
      bObama.save();
    }

    database.commit();
  }

  protected void fillInAccountData() {
    Set<Integer> ids = new HashSet<>();
    for (int i = 0; i < TOT_RECORDS_ACCOUNT; i++) {
      ids.add(i);
    }

    byte[] binary;
    createAccountClass();

    final Set<Integer> accountClusterIds =
        Arrays.stream(database.getMetadata().getSchema().getClass("Account").getClusterIds())
            .asLongStream()
            .mapToObj(i -> (int) i)
            .collect(HashSet::new, HashSet::add, HashSet::addAll);

    if (database.query("select count(*) as count from Account").stream()
        .findFirst()
        .orElseThrow()
        .<Long>getProperty("count")
        == 0) {
      for (var id : ids) {
        database.begin();
        var element = database.newEntity("Account");
        element.setProperty("id", id);
        element.setProperty("name", "Gipsy");
        element.setProperty("location", "Italy");
        element.setProperty("testLong", 10000000000L);
        element.setProperty("salary", id + 300);
        element.setProperty("extra", "This is an extra field not included in the schema");
        element.setProperty("value", (byte) 10);

        binary = new byte[100];
        for (int b = 0; b < binary.length; ++b) {
          binary[b] = (byte) b;
        }
        element.setProperty("binary", binary);
        element.save();
        if (!remoteDB) {
          Assert.assertTrue(accountClusterIds.contains(element.getIdentity().getClusterId()));
        }
        database.commit();
      }
    }
  }

  protected void generateProfiles() {
    createProfileClass();
    createCountryClass();
    createCityClass();

    database.executeInTx(
        () -> {
          addGaribaldiAndBonaparte();
          addBarackObamaAndFollowers();

          var count =
              database.query("select count(*) as count from Profile").stream()
                  .findFirst()
                  .orElseThrow()
                  .<Long>getProperty("count");

          if (count < 1_000) {
            for (int i = 0; i < 1_000 - count; i++) {
              var profile = database.newEntity("Profile");
              profile.setProperty("nick", "generatedNick" + i);
              profile.setProperty("name", "generatedName" + i);
              profile.setProperty("surname", "generatedSurname" + i);
              profile.save();
            }
          }
        });
  }

  protected void addGaribaldiAndBonaparte() {
    database.executeInTx(
        () -> {
          if (database.query("select from Profile where nick = 'NBonaparte'").stream()
              .findAny()
              .isPresent()) {
            return;
          }

          var rome = addRome();
          var garibaldi = database.newInstance("Profile");
          garibaldi.setProperty("nick", "GGaribaldi");
          garibaldi.setProperty("name", "Giuseppe");
          garibaldi.setProperty("surname", "Garibaldi");

          var gAddress = database.newInstance("Address");
          gAddress.setProperty("type", "Residence");
          gAddress.setProperty("street", "Piazza Navona, 1");
          gAddress.setProperty("city", rome);
          garibaldi.setProperty("location", gAddress);

          var bonaparte = database.newInstance("Profile");
          bonaparte.setProperty("nick", "NBonaparte");
          bonaparte.setProperty("name", "Napoleone");
          bonaparte.setProperty("surname", "Bonaparte");
          bonaparte.setProperty("invitedBy", garibaldi);

          var bnAddress = database.newInstance("Address");
          bnAddress.setProperty("type", "Residence");
          bnAddress.setProperty("street", "Piazza di Spagna, 111");
          bnAddress.setProperty("city", rome);
          bonaparte.setProperty("location", bnAddress);

          bonaparte.save();
        });
  }

  private Entity addRome() {
    return database.computeInTx(
        () -> {
          var italy = addItaly();
          var city = database.newInstance("City");
          city.setProperty("name", "Rome");
          city.setProperty("country", italy);
          city.save();

          return city;
        });
  }

  private Entity addItaly() {
    return database.computeInTx(
        () -> {
          var italy = database.newEntity("Country");
          italy.setProperty("name", "Italy");
          italy.save();

          return italy;
        });
  }

  protected void generateCompanyData() {
    fillInAccountData();
    createCompanyClass();

    if (database.query("select count(*) as count from Company").stream()
        .findFirst()
        .orElseThrow()
        .<Long>getProperty("count")
        > 0) {
      return;
    }

    var address = createRedmondAddress();

    for (int i = 0; i < TOT_COMPANY_RECORDS; ++i) {
      database.begin();
      var company = database.newInstance("Company");
      company.setProperty("id", i);
      company.setProperty("name", "Microsoft" + i);
      company.setProperty("employees", 100000 + i);
      company.setProperty("salary", 1000000000.0f + i);

      var addresses = new ArrayList<Entity>();
      addresses.add(database.bindToSession(address));
      company.setProperty("addresses", addresses);
      database.save(company);
      database.commit();
    }
  }

  protected Entity createRedmondAddress() {
    database.begin();
    var washington = database.newInstance("Country");
    washington.setProperty("name", "Washington");
    washington.save();

    var redmond = database.newInstance("City");
    redmond.setProperty("name", "Redmond");
    redmond.setProperty("country", washington);
    redmond.save();

    var address = database.newInstance("Address");
    address.setProperty("type", "Headquarter");
    address.setProperty("city", redmond);
    address.setProperty("street", "WA 98073-9717");
    address.save();

    database.commit();
    return address;
  }

  protected SchemaClass createCountryClass() {
    if (database.getClass("Country") != null) {
      return database.getClass("Country");
    }

    var cls = database.createClass("Country");
    cls.createProperty(database, "name", PropertyType.STRING);
    return cls;
  }

  protected SchemaClass createCityClass() {
    var countryCls = createCountryClass();

    if (database.getClass("City") != null) {
      return database.getClass("City");
    }

    var cls = database.createClass("City");
    cls.createProperty(database, "name", PropertyType.STRING);
    cls.createProperty(database, "country", PropertyType.LINK, countryCls);

    return cls;
  }

  protected SchemaClass createAddressClass() {
    if (database.getClass("Address") != null) {
      return database.getClass("Address");
    }

    var cityCls = createCityClass();
    var cls = database.createClass("Address");
    cls.createProperty(database, "type", PropertyType.STRING);
    cls.createProperty(database, "street", PropertyType.STRING);
    cls.createProperty(database, "city", PropertyType.LINK, cityCls);

    return cls;
  }

  protected SchemaClass createAccountClass() {
    if (database.getClass("Account") != null) {
      return database.getClass("Account");
    }

    var addressCls = createAddressClass();
    var cls = database.createClass("Account");
    cls.createProperty(database, "id", PropertyType.INTEGER);
    cls.createProperty(database, "name", PropertyType.STRING);
    cls.createProperty(database, "surname", PropertyType.STRING);
    cls.createProperty(database, "birthDate", PropertyType.DATE);
    cls.createProperty(database, "salary", PropertyType.FLOAT);
    cls.createProperty(database, "addresses", PropertyType.LINKLIST, addressCls);
    cls.createProperty(database, "thumbnail", PropertyType.BINARY);
    cls.createProperty(database, "photo", PropertyType.BINARY);

    return cls;
  }

  protected void createCompanyClass() {
    if (database.getClass("Company") != null) {
      return;
    }

    createAccountClass();
    var cls = database.createClassIfNotExist("Company", "Account");
    cls.createProperty(database, "employees", PropertyType.INTEGER);
  }

  protected void createProfileClass() {
    if (database.getClass("Profile") != null) {
      return;
    }

    var addressCls = createAddressClass();
    var cls = database.createClass("Profile");
    cls.createProperty(database, "nick", PropertyType.STRING)
        .setMin(database, "3")
        .setMax(database, "30")
        .createIndex(database, SchemaClass.INDEX_TYPE.UNIQUE,
            Map.of("ignoreNullValues", true));
    cls.createProperty(database, "followings", PropertyType.LINKSET, cls);
    cls.createProperty(database, "followers", PropertyType.LINKSET, cls);
    cls.createProperty(database, "name", PropertyType.STRING)
        .setMin(database, "3")
        .setMax(database, "30")
        .createIndex(database, SchemaClass.INDEX_TYPE.NOTUNIQUE);

    cls.createProperty(database, "surname", PropertyType.STRING).setMin(database, "3")
        .setMax(database, "30");
    cls.createProperty(database, "location", PropertyType.LINK, addressCls);
    cls.createProperty(database, "hash", PropertyType.LONG);
    cls.createProperty(database, "invitedBy", PropertyType.LINK, cls);
    cls.createProperty(database, "value", PropertyType.INTEGER);

    cls.createProperty(database, "registeredOn", PropertyType.DATETIME)
        .setMin(database, "2010-01-01 00:00:00");
    cls.createProperty(database, "lastAccessOn", PropertyType.DATETIME)
        .setMin(database, "2010-01-01 00:00:00");
    cls.createProperty(database, "photo", PropertyType.TRANSIENT);
  }

  protected SchemaClass createInheritanceTestAbstractClass() {
    if (database.getClass("InheritanceTestAbstractClass") != null) {
      return database.getClass("InheritanceTestAbstractClass");
    }

    var cls = database.createClass("InheritanceTestAbstractClass");
    cls.createProperty(database, "cField", PropertyType.INTEGER);
    return cls;
  }

  protected SchemaClass createInheritanceTestBaseClass() {
    if (database.getClass("InheritanceTestBaseClass") != null) {
      return database.getClass("InheritanceTestBaseClass");
    }

    var abstractCls = createInheritanceTestAbstractClass();
    var cls = database.createClass("InheritanceTestBaseClass", abstractCls.getName());
    cls.createProperty(database, "aField", PropertyType.STRING);

    return cls;
  }

  protected void createInheritanceTestClass() {
    if (database.getClass("InheritanceTestClass") != null) {
      return;
    }

    var baseCls = createInheritanceTestBaseClass();
    var cls = database.createClass("InheritanceTestClass", baseCls.getName());
    cls.createProperty(database, "bField", PropertyType.STRING);
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

    if (database.getClusterIdByName("csv") == -1) {
      database.addCluster("csv");
    }

    if (database.getClusterIdByName("flat") == -1) {
      database.addCluster("flat");
    }

    if (database.getClusterIdByName("binary") == -1) {
      database.addCluster("binary");
    }
  }

  private void createWhizClass() {
    SchemaClass account = createAccountClass();
    if (database.getMetadata().getSchema().existsClass("Whiz")) {
      return;
    }

    SchemaClass whiz = database.getMetadata().getSchema()
        .createClass("Whiz", 1, (SchemaClass[]) null);
    whiz.createProperty(database, "id", PropertyType.INTEGER);
    whiz.createProperty(database, "account", PropertyType.LINK, account);
    whiz.createProperty(database, "date", PropertyType.DATE).setMin(database, "2010-01-01");
    whiz.createProperty(database, "text", PropertyType.STRING).setMandatory(database, true)
        .setMin(database, "1")
        .setMax(database, "140");
    whiz.createProperty(database, "replyTo", PropertyType.LINK, account);
  }

  private void createAnimalRaceClass() {
    if (database.getMetadata().getSchema().existsClass("AnimalRace")) {
      return;
    }

    SchemaClass animalRace =
        database.getMetadata().getSchema().createClass("AnimalRace", 1, (SchemaClass[]) null);
    animalRace.createProperty(database, "name", PropertyType.STRING);
    SchemaClass animal = database.getMetadata().getSchema()
        .createClass("Animal", 1, (SchemaClass[]) null);
    animal.createProperty(database, "races", PropertyType.LINKSET, animalRace);
    animal.createProperty(database, "name", PropertyType.STRING);
  }

  private void createStrictTestClass() {
    if (database.getMetadata().getSchema().existsClass("StrictTest")) {
      return;
    }

    SchemaClass strictTest =
        database.getMetadata().getSchema().createClass("StrictTest", 1, (SchemaClass[]) null);
    strictTest.setStrictMode(database, true);
    strictTest.createProperty(database, "id", PropertyType.INTEGER).isMandatory();
    strictTest.createProperty(database, "name", PropertyType.STRING);
  }

  protected void createComplexTestClass() {
    if (database.getSchema().existsClass("JavaComplexTestClass")) {
      database.getSchema().dropClass("JavaComplexTestClass");
    }
    if (database.getSchema().existsClass("Child")) {
      database.getSchema().dropClass("Child");
    }

    var childCls = database.createClass("Child");
    childCls.createProperty(database, "name", PropertyType.STRING);

    var cls = database.createClass("JavaComplexTestClass");

    cls.createProperty(database, "embeddedDocument", PropertyType.EMBEDDED);
    cls.createProperty(database, "document", PropertyType.LINK);
    cls.createProperty(database, "byteArray", PropertyType.LINK);
    cls.createProperty(database, "name", PropertyType.STRING);
    cls.createProperty(database, "child", PropertyType.LINK, childCls);
    cls.createProperty(database, "stringMap", PropertyType.EMBEDDEDMAP);
    cls.createProperty(database, "stringListMap", PropertyType.EMBEDDEDMAP);
    cls.createProperty(database, "list", PropertyType.LINKLIST, childCls);
    cls.createProperty(database, "set", PropertyType.LINKSET, childCls);
    cls.createProperty(database, "duplicationTestSet", PropertyType.LINKSET, childCls);
    cls.createProperty(database, "children", PropertyType.LINKMAP, childCls);
    cls.createProperty(database, "stringSet", PropertyType.EMBEDDEDSET);
    cls.createProperty(database, "embeddedList", PropertyType.EMBEDDEDLIST);
    cls.createProperty(database, "embeddedSet", PropertyType.EMBEDDEDSET);
    cls.createProperty(database, "embeddedChildren", PropertyType.EMBEDDEDMAP);
    cls.createProperty(database, "mapObject", PropertyType.EMBEDDEDMAP);
  }

  protected void createSimpleTestClass() {
    if (database.getSchema().existsClass("JavaSimpleTestClass")) {
      database.getSchema().dropClass("JavaSimpleTestClass");
    }

    var cls = database.createClass("JavaSimpleTestClass");
    cls.createProperty(database, "text", PropertyType.STRING).setDefaultValue(database, "initTest");
    cls.createProperty(database, "numberSimple", PropertyType.INTEGER)
        .setDefaultValue(database, "0");
    cls.createProperty(database, "longSimple", PropertyType.LONG).setDefaultValue(database, "0");
    cls.createProperty(database, "doubleSimple", PropertyType.DOUBLE)
        .setDefaultValue(database, "0");
    cls.createProperty(database, "floatSimple", PropertyType.FLOAT).setDefaultValue(database, "0");
    cls.createProperty(database, "byteSimple", PropertyType.BYTE).setDefaultValue(database, "0");
    cls.createProperty(database, "shortSimple", PropertyType.SHORT).setDefaultValue(database, "0");
    cls.createProperty(database, "dateField", PropertyType.DATETIME);
  }

  protected void generateGraphData() {
    if (database.getSchema().existsClass("GraphVehicle")) {
      return;
    }

    SchemaClass vehicleClass = database.createVertexClass("GraphVehicle");
    database.createClass("GraphCar", vehicleClass.getName());
    database.createClass("GraphMotocycle", "GraphVehicle");

    database.begin();
    var carNode = database.newVertex("GraphCar");
    carNode.setProperty("brand", "Hyundai");
    carNode.setProperty("model", "Coupe");
    carNode.setProperty("year", 2003);
    carNode.save();

    var motoNode = database.newVertex("GraphMotocycle");
    motoNode.setProperty("brand", "Yamaha");
    motoNode.setProperty("model", "X-City 250");
    motoNode.setProperty("year", 2009);
    motoNode.save();

    database.commit();

    database.begin();
    carNode = database.bindToSession(carNode);
    motoNode = database.bindToSession(motoNode);
    database.newRegularEdge(carNode, motoNode).save();

    List<Result> result =
        database.query("select from GraphVehicle").stream().collect(Collectors.toList());
    Assert.assertEquals(result.size(), 2);
    for (Result v : result) {
      Assert.assertTrue(
          v.getEntity().get().getSchemaType().get().isSubClassOf(database, vehicleClass));
    }

    database.commit();
    result = database.query("select from GraphVehicle").stream().toList();
    Assert.assertEquals(result.size(), 2);

    Edge edge1 = null;
    Edge edge2 = null;

    for (Result v : result) {
      Assert.assertTrue(
          v.getEntity().get().getSchemaType().get().isSubClassOf(database, "GraphVehicle"));

      if (v.getEntity().get().getSchemaType().isPresent()
          && v.getEntity().get().getSchemaType().get().getName().equals("GraphCar")) {
        Assert.assertEquals(
            CollectionUtils.size(
                database.<Vertex>load(v.getIdentity().get()).getEdges(Direction.OUT)),
            1);
        edge1 =
            database
                .<Vertex>load(v.getIdentity().get())
                .getEdges(Direction.OUT)
                .iterator()
                .next();
      } else {
        Assert.assertEquals(
            CollectionUtils.size(
                database.<Vertex>load(v.getIdentity().get()).getEdges(Direction.IN)),
            1);
        edge2 =
            database.<Vertex>load(v.getIdentity().get()).getEdges(Direction.IN).iterator()
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
