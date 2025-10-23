package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class YTDBPropertiesStructureTest extends YTDBAbstractGremlinTest {

  @Test
  public void testHasNoProperty() {
    final var person = graph().addVertex("person");
    assertFalse(person.hasProperty("name"));

    final var loaded = (YTDBVertex) graph().vertices(person.id()).next();
    assertFalse(loaded.hasProperty("name"));
  }

  @Test
  public void testHasProperty() {
    final var person = graph().addVertex("person");
    person.property("name", "John");
    assertTrue(person.hasProperty("name"));

    final var loaded = (YTDBVertex) graph().vertices(person.id()).next();
    assertTrue(loaded.hasProperty("name"));
  }

  @Test
  public void testRemoveExistingProperty() {
    final var person = graph().addVertex("person");
    person.property("name", "John");
    assertTrue(person.hasProperty("name"));

    person.removeProperty("name");
    assertFalse(person.hasProperty("name"));
    assertFalse(person.property("name").isPresent());

    final var loaded = (YTDBVertex) graph().vertices(person.id()).next();
    assertFalse(loaded.hasProperty("name"));
    assertFalse(loaded.property("name").isPresent());
  }

  @Test
  public void testRemoveNonExistingProperty() {
    final var person = graph().addVertex("person");
    person.property("name", "John");
    assertFalse(person.hasProperty("age"));

    person.removeProperty("age");
    assertFalse(person.hasProperty("age"));
    assertTrue(person.hasProperty("name"));

    final var loaded = (YTDBVertex) graph().vertices(person.id()).next();
    assertFalse(loaded.hasProperty("age"));
    assertTrue(loaded.hasProperty("name"));
  }

  @Test
  public void testRemoveFromEdge() {
    final var person = graph().addVertex("person");
    final var friend = graph().addVertex("person");
    final var isFriend = person.addEdge("friend", friend);

    assertFalse(isFriend.hasProperty("since"));

    isFriend.property("since", 2022);
    assertTrue(isFriend.hasProperty("since"));

    isFriend.removeProperty("since");
    assertFalse(isFriend.hasProperty("since"));
    assertFalse(isFriend.property("since").isPresent());
  }

  @Test
  public void testRemoveInternalFromEdge() {

    final var person = graph().addVertex("person");
    final var friend = graph().addVertex("person");
    final var isFriend = person.addEdge("friend", friend);
    isFriend.property("since", 2022);

    isFriend.removeProperty("in");
    isFriend.removeProperty("out");

    assertEquals(person, isFriend.outVertex());
    assertEquals(friend, isFriend.inVertex());
  }

  @Test
  public void testPropertyTypes() {
    final var person = graph().addVertex("person");
    person.property("name", "John");
    person.property("age", 25);
    person.property("active", true);
    person.property("weight", 70.5);

    person.property("tagsSet", Set.of("tag1", "tag2", "tag3"));
    person.property("tagsList", List.of("tag1", "tag2", "tag3"));
    person.property("aMap", Map.of("key1", "value1", "key2", "value2"));

    assertEquals(PropertyType.STRING, person.property("name").type());
    assertEquals(PropertyType.INTEGER, person.property("age").type());
    assertEquals(PropertyType.BOOLEAN, person.property("active").type());
    assertEquals(PropertyType.DOUBLE, person.property("weight").type());
    assertEquals(PropertyType.EMBEDDEDSET, person.property("tagsSet").type());
    assertEquals(PropertyType.EMBEDDEDLIST, person.property("tagsList").type());
    assertEquals(PropertyType.EMBEDDEDMAP, person.property("aMap").type());
  }
}
