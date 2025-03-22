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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class EntityTreeTest extends BaseDBTest {

  @Parameters(value = "remote")
  public EntityTreeTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void init() {
    createComplexTestClass();
    createSimpleTestClass();
    createCascadeDeleteClass();
    createPlanetClasses();
    createRefClasses();
  }

  @Test
  public void testPersonSaving() {
    addGaribaldiAndBonaparte();

    session.begin();
    Assert.assertTrue(
        session.query("select from Profile where nick = 'NBonaparte'").stream()
            .findAny()
            .isPresent());
    session.commit();
  }

  @Test(dependsOnMethods = "testPersonSaving")
  public void testCityEquality() {
    session.begin();
    var resultSet =
        executeQuery("select from profile where location.city.name = 'Rome'");
    Assert.assertEquals(resultSet.size(), 2);

    var p1 = resultSet.get(0);
    var p2 = resultSet.get(1);

    Assert.assertNotSame(p1, p2);
    Assert.assertSame(
        p1.getEntity("location").getEntity("city"),
        p2.getEntity("location").getEntity("city"));
    session.commit();
  }

  @Test(dependsOnMethods = "testCityEquality")
  public void testSaveCircularLink() {
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

  @Test(dependsOnMethods = "testSaveCircularLink")
  public void testSaveMultiCircular() {
    addBarackObamaAndFollowers();
  }

  @SuppressWarnings("unchecked")
  @Test(dependsOnMethods = "testSaveMultiCircular")
  public void testQueryMultiCircular() {
    session.begin();
    var resultSet =
        executeQuery("select * from Profile where name = 'Barack' and surname = 'Obama'");

    Assert.assertEquals(resultSet.size(), 1);
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

  @Test
  public void testSetFieldSize() {
    session.begin();
    var test = session.newEntity("JavaComplexTestClass");
    test.setProperty("set", new HashSet<>());

    for (var i = 0; i < 100; i++) {
      var child = session.newEntity("Child");
      child.setProperty("name", String.valueOf(i));
      test.<Set<Identifiable>>getProperty("set").add(child);
    }
    Assert.assertNotNull(test.<Set<Identifiable>>getProperty("set"));
    Assert.assertEquals(test.<Set<Identifiable>>getProperty("set").size(), 100);

    session.commit();

    // Assert.assertEquals(test.<Set<Identifiable>>getProperty("set").size(), 100);
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
    Assert.assertEquals(test.<Set<Identifiable>>getProperty("set").size(), 100);
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(test));
    session.commit();
  }

  @Test(dependsOnMethods = "testQueryMultiCircular")
  public void testCollectionsRemove() {
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

    var set = new HashSet<Identifiable>();
    set.add(first);
    set.add(second);
    set.add(third);
    set.add(fourth);
    set.add(fifth);

    a.setProperty("set", set);

    var list = new ArrayList<Identifiable>();
    list.add(first);
    list.add(second);
    list.add(third);
    list.add(fourth);
    list.add(fifth);

    a.setProperty("list", list);

    a.<Set<Identifiable>>getProperty("set").remove(third);
    a.<List<Identifiable>>getProperty("list").remove(fourth);

    Assert.assertEquals(a.<Set<Identifiable>>getProperty("set").size(), 4);
    Assert.assertEquals(a.<List<Identifiable>>getProperty("list").size(), 4);

    session.begin();
    a = a;
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    a = activeTx.load(a);
    var rid = a.getIdentity();
    Assert.assertEquals(a.<Set<Identifiable>>getProperty("set").size(), 4);
    Assert.assertEquals(a.<List<Identifiable>>getProperty("list").size(), 4);
    session.commit();

    session.close();

    session = createSessionInstance();

    session.begin();
    var loadedObj = session.loadEntity(rid);

    Assert.assertEquals(loadedObj.<Set<Object>>getProperty("set").size(), 4);
    Assert.assertEquals(loadedObj.<Set<Identifiable>>getProperty("set").size(), 4);

    session.delete(session.load(rid));
    session.commit();
  }

  @Test
  public void childNLevelUpdateTest() {
    session.begin();
    var p = session.newEntity("Planet");
    var near = session.newEntity("Planet");
    var sat = session.newEntity("Satellite");
    var satNear = session.newEntity("Satellite");
    sat.setProperty("diameter", 50);
    sat.setProperty("near", near);
    satNear.setProperty("diameter", 10);

    near.setProperty("satellites", Collections.singletonList(satNear));
    p.setProperty("satellites", Collections.singletonList(sat));

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
    Assert.assertEquals(satNear.<Long>getProperty("diameter"), 10);

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
    Assert.assertEquals(satNear.<Long>getProperty("diameter"), 100);
    session.commit();
  }

  @Test(dependsOnMethods = "childNLevelUpdateTest")
  public void childMapUpdateTest() {
    session.begin();
    var p = session.newEntity("Planet");
    p.setProperty("name", "Earth");
    p.setProperty("distanceSun", 1000);

    var sat = session.newEntity("Satellite");
    sat.setProperty("diameter", 50);
    sat.setProperty("name", "Moon");

    p.setProperty("satellitesMap", Collections.singletonMap(sat.<String>getProperty("name"), sat));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    p = activeTx.load(p);
    Assert.assertEquals(p.<Integer>getProperty("distanceSun"), 1000);
    Assert.assertEquals(p.getProperty("name"), "Earth");
    var rid = p.getIdentity();

    p = session.load(rid);
    Identifiable identifiable1 = p.<Map<String, Identifiable>>getProperty("satellitesMap")
        .get("Moon");
    var transaction1 = session.getActiveTransaction();
    sat = transaction1.loadEntity(identifiable1);
    Assert.assertEquals(p.<Integer>getProperty("distanceSun"), 1000);
    Assert.assertEquals(p.getProperty("name"), "Earth");
    Assert.assertEquals(sat.<Long>getProperty("diameter"), 50);
    sat.setProperty("diameter", 500);

    session.commit();

    session.begin();
    p = session.load(rid);
    Identifiable identifiable = p.<Map<String, Identifiable>>getProperty("satellitesMap")
        .get("Moon");
    var transaction = session.getActiveTransaction();
    sat = transaction.loadEntity(identifiable);
    Assert.assertEquals(sat.<Long>getProperty("diameter"), 500);
    Assert.assertEquals(p.<Integer>getProperty("distanceSun"), 1000);
    Assert.assertEquals(p.getProperty("name"), "Earth");
    session.commit();
  }

  @Test(dependsOnMethods = "childMapUpdateTest")
  public void childMapNLevelUpdateTest() {
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
        Collections.singletonMap(mercuryMoon.<String>getProperty("name"), mercuryMoon));
    jupiter.setProperty(
        "satellitesMap",
        Collections.singletonMap(jupiterMoon.<String>getProperty("name"), jupiterMoon));

    session.begin();
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
    Assert.assertEquals(mercuryMoon.<Long>getProperty("diameter"), 10);
    Assert.assertEquals(mercuryMoon.getProperty("name"), "MercuryMoon");
    Assert.assertEquals(jupiterMoon.<Long>getProperty("diameter"), 50);
    Assert.assertEquals(jupiterMoon.getProperty("name"), "JupiterMoon");
    Assert.assertEquals(jupiter.getProperty("name"), "Jupiter");
    Assert.assertEquals(jupiter.<Integer>getProperty("distanceSun"), 3000);
    Assert.assertEquals(mercury.getProperty("name"), "Mercury");
    Assert.assertEquals(mercury.<Integer>getProperty("distanceSun"), 5000);
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
    Assert.assertEquals(mercuryMoon.<Long>getProperty("diameter"), 100);
    Assert.assertEquals(mercuryMoon.getProperty("name"), "MercuryMoon");
    Assert.assertEquals(jupiterMoon.<Long>getProperty("diameter"), 50);
    Assert.assertEquals(jupiterMoon.getProperty("name"), "JupiterMoon");
    Assert.assertEquals(jupiter.getProperty("name"), "Jupiter");
    Assert.assertEquals(jupiter.<Integer>getProperty("distanceSun"), 3000);
    Assert.assertEquals(mercury.getProperty("name"), "Mercury");
    Assert.assertEquals(mercury.<Integer>getProperty("distanceSun"), 5000);
    session.commit();
    session.close();
  }

  @Test
  public void iteratorShouldTerminate() {
    session.begin();

    var person = session.newEntity("Profile");
    person.setProperty("nick", "Guy1");
    person.setProperty("name", "Guy");
    person.setProperty("surname", "Ritchie");

    person = person;
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

  @Test
  public void testSave() {
    session.begin();
    var parent1 = session.newEntity("RefParent");
    parent1 = parent1;
    var parent2 = session.newEntity("RefParent");
    parent2 = parent2;

    var child1 = session.newEntity("RefChild");
    parent1.setProperty("children", Collections.singleton(child1));
    parent1 = parent1;

    var child2 = session.newEntity("RefChild");
    parent2.setProperty("children", Collections.singleton(child2));
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
