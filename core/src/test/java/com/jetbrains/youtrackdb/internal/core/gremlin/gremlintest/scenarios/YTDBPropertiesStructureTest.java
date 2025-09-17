package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
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
}
