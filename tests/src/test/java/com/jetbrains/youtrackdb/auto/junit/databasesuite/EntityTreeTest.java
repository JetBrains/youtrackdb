/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of EntityTreeTest. Original test class:
 * com.jetbrains.youtrackdb.auto.EntityTreeTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class EntityTreeTest extends BaseDBTest {

  /**
   * Original method: init (BeforeClass) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:32
   */
  @BeforeClass
  public static void setUpClass() throws Exception {
    EntityTreeTest instance = new EntityTreeTest();
    instance.beforeClass();
    instance.init();
  }

  public void init() {
    createComplexTestClass();
    createSimpleTestClass();
    createCascadeDeleteClass();
    createPlanetClasses();
    createRefClasses();
  }

  /**
   * Original test method: testPersonSaving Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:41
   */
  @Test
  public void test01_PersonSaving() {
    addGaribaldiAndBonaparte();

    session.begin();
    Assert.assertTrue(
        session.query("select from Profile where nick = 'NBonaparte'").stream()
            .findAny()
            .isPresent());
    session.commit();
  }

  /**
   * Original test method: testCityEquality Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:53 Depends on:
   * testPersonSaving
   */
  @Test
  public void test02_CityEquality() {
    session.begin();
    var resultSet =
        executeQuery("select from profile where location.city.name = 'Rome'");
    Assert.assertEquals(2, resultSet.size());

    var p1 = resultSet.get(0);
    var p2 = resultSet.get(1);

    Assert.assertNotSame(p1, p2);
    Assert.assertSame(
        p1.getEntity("location").getEntity("city"),
        p2.getEntity("location").getEntity("city"));
    session.commit();
  }

  /**
   * Original test method: testSaveCircularLink Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:70 Depends on:
   * testCityEquality
   */
  @Test
  public void test03_SaveCircularLink() {
    session.begin();
    var winston = session.newEntity("Profile");

    winston.setProperty("nick", "WChurcill");
    winston.setProperty("name", "Winston");
    winston.setProperty("surname", "Churcill");

    var country = session.newEntity("Country");
    country.setProperty("name", "England");

    var city = session.newEntity("City");
    city.setProperty("name", "London");
    city.setProperty("country", country);

    var address = session.newEntity("Address");
    address.setProperty("type", "Residence");
    address.setProperty("city", city);
    address.setProperty("street", "unknown");

    winston.setProperty("location", address);

    var nicholas = session.newEntity("Profile");
    nicholas.setProperty("nick", "NChurcill");
    nicholas.setProperty("name", "Nicholas");
    nicholas.setProperty("surname", "Churcill");

    nicholas.setProperty("location", winston.getEntity("location"));

    nicholas.setProperty("invitedBy", winston);
    winston.setProperty("invitedBy", nicholas);

    session.commit();
  }

  /**
   * Original test method: testSaveMultiCircular Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:106 Depends on:
   * testSaveCircularLink
   */
  @Test
  public void test04_SaveMultiCircular() {
    addBarackObamaAndFollowers();
  }

  /**
   * Original test method: testQueryMultiCircular Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:111 Depends on:
   * testSaveMultiCircular
   */
  @SuppressWarnings("unchecked")
  @Test
  public void test05_QueryMultiCircular() {
    session.begin();
    var resultSet =
        executeQuery("select * from Profile where name = 'Barack' and surname = 'Obama'");

    Assert.assertEquals(1, resultSet.size());
    for (var result : resultSet) {
      var profile = result.asEntityOrNull();
      final Collection<Identifiable> followers = profile.getProperty("followers");
      if (followers != null) {
        for (var follower : followers) {
          var transaction = session.getActiveTransaction();
          Assert.assertTrue(
              ((Collection<Identifiable>)
                  Objects.requireNonNull(
                      transaction.loadEntity(follower).getProperty("followings")))
                  .contains(profile));
        }
      }
    }
    session.commit();
  }

  /**
   * Original test method: testSetFieldSize Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:136
   */
  @Test
  public void test06_SetFieldSize() {
    session.begin();
    var test = session.newEntity("JavaComplexTestClass");
    test.getOrCreateLinkSet("set");

    for (var i = 0; i < 100; i++) {
      var child = session.newEntity("Child");
      child.setProperty("name", String.valueOf(i));
      test.<Set<Identifiable>>getProperty("set").add(child);
    }
    Assert.assertNotNull(test.<Set<Identifiable>>getProperty("set"));
    Assert.assertEquals(100, test.<Set<Identifiable>>getProperty("set").size());

    session.commit();

    var rid = test.getIdentity();
    session.close();
    session = createSessionInstance();

    session.begin();
    test = session.load(rid);
    Assert.assertNotNull(test.<Set<Identifiable>>getProperty("set"));
    for (var identifiable : test.<Set<Identifiable>>getProperty("set")) {
      var transaction = session.getActiveTransaction();
      var child = transaction.loadEntity(identifiable);
      Assert.assertNotNull(child.<String>getProperty("name"));
      Assert.assertTrue(Integer.parseInt(child.getProperty("name")) < 100);
      Assert.assertTrue(Integer.parseInt(child.getProperty("name")) >= 0);
    }
    Assert.assertEquals(100, test.<Set<Identifiable>>getProperty("set").size());
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(test));
    session.commit();
  }

  /**
   * Original test method: testCollectionsRemove Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:173 Depends on:
   * testQueryMultiCircular
   */
  @Test
  public void test07_CollectionsRemove() {
    session.begin();
    var a = session.newEntity("JavaComplexTestClass");

    // LIST TEST
    var first = session.newEntity("Child");
    first.setProperty("name", "1");
    var second = session.newEntity("Child");
    second.setProperty("name", "2");
    var third = session.newEntity("Child");
    third.setProperty("name", "3");
    var fourth = session.newEntity("Child");
    fourth.setProperty("name", "4");
    var fifth = session.newEntity("Child");
    fifth.setProperty("name", "5");

    var set = session.newLinkSet();
    set.add(first);
    set.add(second);
    set.add(third);
    set.add(fourth);
    set.add(fifth);

    a.setProperty("set", set);

    var list = session.newLinkList();
    list.add(first);
    list.add(second);
    list.add(third);
    list.add(fourth);
    list.add(fifth);

    a.setProperty("list", list);

    a.<Set<Identifiable>>getProperty("set").remove(third);
    a.<List<Identifiable>>getProperty("list").remove(fourth);

    Assert.assertEquals(4, a.<Set<Identifiable>>getProperty("set").size());
    Assert.assertEquals(4, a.<List<Identifiable>>getProperty("list").size());

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    a = activeTx.load(a);
    var rid = a.getIdentity();
    Assert.assertEquals(4, a.<Set<Identifiable>>getProperty("set").size());
    Assert.assertEquals(4, a.<List<Identifiable>>getProperty("list").size());
    session.commit();

    session.close();

    session = createSessionInstance();

    session.begin();
    var loadedObj = session.loadEntity(rid);

    Assert.assertEquals(4, loadedObj.<Set<Object>>getProperty("set").size());
    Assert.assertEquals(4, loadedObj.<Set<Identifiable>>getProperty("set").size());

    session.delete(session.load(rid));
    session.commit();
  }

  /**
   * Original test method: childNLevelUpdateTest Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:238
   */
  @Test
  public void test08_ChildNLevelUpdateTest() {
    session.begin();
    var p = session.newEntity("Planet");
    var near = session.newEntity("Planet");
    var sat = session.newEntity("Satellite");
    var satNear = session.newEntity("Satellite");
    sat.setProperty("diameter", 50);
    sat.setProperty("near", near);
    satNear.setProperty("diameter", 10);

    near.setProperty("satellites", session.newLinkList(List.of(satNear)));
    p.setProperty("satellites", session.newLinkSet(List.of(sat)));

    session.commit();

    session.begin();
    var rid = p.getIdentity();
    p = session.load(rid);
    Identifiable identifiable3 = p.<List<Identifiable>>getProperty("satellites").getFirst();
    var transaction3 = session.getActiveTransaction();
    sat = transaction3.loadEntity(identifiable3);
    near = sat.getEntity("near");
    Identifiable identifiable2 = near.<List<Identifiable>>getProperty("satellites").getFirst();
    var transaction2 = session.getActiveTransaction();
    satNear = transaction2.loadEntity(identifiable2);
    Assert.assertEquals(10, satNear.<Long>getProperty("diameter").longValue());

    satNear.setProperty("diameter", 100);

    session.commit();

    session.begin();
    p = session.load(rid);
    Identifiable identifiable1 = p.<List<Identifiable>>getProperty("satellites").getFirst();
    var transaction1 = session.getActiveTransaction();
    sat = transaction1.loadEntity(identifiable1);
    near = sat.getEntity("near");
    Identifiable identifiable = near.<List<Identifiable>>getProperty("satellites").getFirst();
    var transaction = session.getActiveTransaction();
    satNear = transaction.loadEntity(identifiable);
    Assert.assertEquals(100, satNear.<Long>getProperty("diameter").longValue());
    session.commit();
  }

  /**
   * Original test method: childMapUpdateTest Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:283 Depends on:
   * childNLevelUpdateTest
   */
  @Test
  public void test09_ChildMapUpdateTest() {
    session.begin();
    var p = session.newEntity("Planet");
    p.setProperty("name", "Earth");
    p.setProperty("distanceSun", 1000);

    var sat = session.newEntity("Satellite");
    sat.setProperty("diameter", 50);
    sat.setProperty("name", "Moon");

    p.setProperty("satellitesMap", session.newLinkMap(Map.of(sat.getString("name"), sat)));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    p = activeTx.load(p);
    Assert.assertEquals(1000, p.<Integer>getProperty("distanceSun").intValue());
    Assert.assertEquals("Earth", p.getProperty("name"));
    var rid = p.getIdentity();

    p = session.load(rid);
    Identifiable identifiable1 = p.<Map<String, Identifiable>>getProperty("satellitesMap")
        .get("Moon");
    var transaction1 = session.getActiveTransaction();
    sat = transaction1.loadEntity(identifiable1);
    Assert.assertEquals(1000, p.<Integer>getProperty("distanceSun").intValue());
    Assert.assertEquals("Earth", p.getProperty("name"));
    Assert.assertEquals(50, sat.<Long>getProperty("diameter").longValue());
    sat.setProperty("diameter", 500);

    session.commit();

    session.begin();
    p = session.load(rid);
    Identifiable identifiable = p.<Map<String, Identifiable>>getProperty("satellitesMap")
        .get("Moon");
    var transaction = session.getActiveTransaction();
    sat = transaction.loadEntity(identifiable);
    Assert.assertEquals(500, sat.<Long>getProperty("diameter").longValue());
    Assert.assertEquals(1000, p.<Integer>getProperty("distanceSun").intValue());
    Assert.assertEquals("Earth", p.getProperty("name"));
    session.commit();
  }

  /**
   * Original test method: childMapNLevelUpdateTest Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:328 Depends on:
   * childMapUpdateTest
   */
  @Test
  public void test10_ChildMapNLevelUpdateTest() {
    session.begin();
    var jupiter = session.newEntity("Planet");
    jupiter.setProperty("name", "Jupiter");
    jupiter.setProperty("distanceSun", 3000);
    var mercury = session.newEntity("Planet");
    mercury.setProperty("name", "Mercury");
    mercury.setProperty("distanceSun", 5000);
    var jupiterMoon = session.newEntity("Satellite");
    var mercuryMoon = session.newEntity("Satellite");
    jupiterMoon.setProperty("diameter", 50);
    jupiterMoon.setProperty("near", mercury);
    jupiterMoon.setProperty("name", "JupiterMoon");
    mercuryMoon.setProperty("diameter", 10);
    mercuryMoon.setProperty("name", "MercuryMoon");

    mercury.setProperty(
        "satellitesMap",
        session.newLinkMap(Map.of(mercuryMoon.getProperty("name"), mercuryMoon)));
    jupiter.setProperty(
        "satellitesMap",
        session.newLinkMap(Map.of(jupiterMoon.getProperty("name"), jupiterMoon)));

    session.commit();

    session.begin();
    var rid = jupiter.getIdentity();
    jupiter = session.load(rid);
    Identifiable identifiable3 = jupiter
        .<Map<String, Identifiable>>getProperty("satellitesMap")
        .get("JupiterMoon");
    var transaction3 = session.getActiveTransaction();
    jupiterMoon =
        transaction3.loadEntity(identifiable3);
    mercury = jupiterMoon.getEntity("near");
    Identifiable identifiable2 = mercury
        .<Map<String, Identifiable>>getProperty("satellitesMap")
        .get("MercuryMoon");
    var transaction2 = session.getActiveTransaction();
    mercuryMoon =
        transaction2.loadEntity(identifiable2);
    Assert.assertEquals(10, mercuryMoon.<Long>getProperty("diameter").longValue());
    Assert.assertEquals("MercuryMoon", mercuryMoon.getProperty("name"));
    Assert.assertEquals(50, jupiterMoon.<Long>getProperty("diameter").longValue());
    Assert.assertEquals("JupiterMoon", jupiterMoon.getProperty("name"));
    Assert.assertEquals("Jupiter", jupiter.getProperty("name"));
    Assert.assertEquals(3000, jupiter.<Integer>getProperty("distanceSun").intValue());
    Assert.assertEquals("Mercury", mercury.getProperty("name"));
    Assert.assertEquals(5000, mercury.<Integer>getProperty("distanceSun").intValue());
    mercuryMoon.setProperty("diameter", 100);
    session.commit();

    session.close();
    session = createSessionInstance();

    session.begin();
    jupiter = session.load(rid);
    Identifiable identifiable1 = jupiter
        .<Map<String, Identifiable>>getProperty("satellitesMap")
        .get("JupiterMoon");
    var transaction1 = session.getActiveTransaction();
    jupiterMoon =
        transaction1.loadEntity(identifiable1);
    mercury = jupiterMoon.getEntity("near");
    Identifiable identifiable = mercury
        .<Map<String, Identifiable>>getProperty("satellitesMap")
        .get("MercuryMoon");
    var transaction = session.getActiveTransaction();
    mercuryMoon =
        transaction.loadEntity(identifiable);
    Assert.assertEquals(100, mercuryMoon.<Long>getProperty("diameter").longValue());
    Assert.assertEquals("MercuryMoon", mercuryMoon.getProperty("name"));
    Assert.assertEquals(50, jupiterMoon.<Long>getProperty("diameter").longValue());
    Assert.assertEquals("JupiterMoon", jupiterMoon.getProperty("name"));
    Assert.assertEquals("Jupiter", jupiter.getProperty("name"));
    Assert.assertEquals(3000, jupiter.<Integer>getProperty("distanceSun").intValue());
    Assert.assertEquals("Mercury", mercury.getProperty("name"));
    Assert.assertEquals(5000, mercury.<Integer>getProperty("distanceSun").intValue());
    session.commit();
    session.close();
  }

  /**
   * Original test method: iteratorShouldTerminate Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:411
   */
  @Test
  public void test11_IteratorShouldTerminate() {
    session.begin();

    var person = session.newEntity("Profile");
    person.setProperty("nick", "Guy1");
    person.setProperty("name", "Guy");
    person.setProperty("surname", "Ritchie");

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(person));
    session.commit();

    session.begin();
    var person2 = session.newEntity("Profile");
    person2.setProperty("nick", "Guy2");
    person2.setProperty("name", "Guy");
    person2.setProperty("surname", "Brush");

    var it = session.browseClass("Profile");
    while (it.hasNext()) {
      it.next();
    }

    session.commit();
  }

  /**
   * Original test method: testSave Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:442
   */
  @Test
  public void test12_Save() {
    session.begin();
    var parent1 = session.newEntity("RefParent");
    var parent2 = session.newEntity("RefParent");

    var child1 = session.newEntity("RefChild");
    parent1.setProperty("children", session.newLinkSet(Set.of(child1)));

    var child2 = session.newEntity("RefChild");
    parent2.setProperty("children", session.newLinkSet(Set.of(child2)));
    session.commit();

    session.begin();
    parent1 = session.load(parent1.getIdentity());
    parent2 = session.load(parent2.getIdentity());

    var child3 = session.newEntity("RefChild");

    var otherThing = session.newEntity("OtherThing");
    child3.setProperty("otherThing", otherThing);

    otherThing.setProperty("relationToParent1", parent1);
    otherThing.setProperty("relationToParent2", parent2);

    parent1.<Set<Identifiable>>getProperty("children").add(child3);
    parent2.<Set<Identifiable>>getProperty("children").add(child3);

    session.commit();
  }

  /**
   * Original helper method: createCascadeDeleteClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:475
   */
  private void createCascadeDeleteClass() {
    var schema = session.getSchema();
    if (schema.existsClass("JavaCascadeDeleteTestClass")) {
      schema.dropClass("JavaCascadeDeleteTestClass");
    }

    var child = schema.getClass("Child");
    var clazz = schema.createClass("JavaCascadeDeleteTestClass");
    clazz.createProperty("simpleClass", PropertyType.LINK,
        schema.getClass("JavaSimpleTestClass"));
    clazz.createProperty("binary", PropertyType.LINK);
    clazz.createProperty("name", PropertyType.STRING);
    clazz.createProperty("set", PropertyType.LINKSET, child);
    clazz.createProperty("children", PropertyType.LINKMAP, child);
    clazz.createProperty("list", PropertyType.LINKLIST, child);
  }

  /**
   * Original helper method: createPlanetClasses Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:492
   */
  private void createPlanetClasses() {
    var schema = session.getSchema();
    var satellite = schema.createClass("Satellite");
    var planet = schema.createClass("Planet");

    planet.createProperty("name", PropertyType.STRING);
    planet.createProperty("distanceSun", PropertyType.INTEGER);
    planet.createProperty("satellites", PropertyType.LINKLIST, satellite);
    planet.createProperty("satellitesMap", PropertyType.LINKMAP, satellite);

    satellite.createProperty("name", PropertyType.STRING);
    satellite.createProperty("diameter", PropertyType.LONG);
    satellite.createProperty("near", PropertyType.LINK, planet);
  }

  /**
   * Original helper method: createRefClasses Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/EntityTreeTest.java:507
   */
  private void createRefClasses() {
    var schema = session.getSchema();
    var refParent = schema.createClass("RefParent");
    var refChild = schema.createClass("RefChild");
    var otherThing = schema.createClass("OtherThing");

    refParent.createProperty("children", PropertyType.LINKSET, refChild);
    refChild.createProperty("otherThing", PropertyType.LINK, otherThing);
    otherThing.createProperty("relationToParent1", PropertyType.LINK, refParent);
    otherThing.createProperty("relationToParent2", PropertyType.LINK, refParent);
  }
}
