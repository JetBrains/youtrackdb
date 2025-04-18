package com.jetbrains.youtrack.db.internal.core.sql.functions.coll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public class SQLFunctionExpandTest extends DbTestBase {

  @Test
  public void expandSingleValue() {

    final var aClass = session.createClass("SingleValueClass");
    aClass.createProperty("stringProp", PropertyType.STRING);
    session.executeInTx(transaction -> {
      session.newEntity(aClass).setProperty("stringProp", "a1");
      session.newEntity(aClass).setProperty("stringProp", "a2");
    });

    session.executeInTx(transaction -> {
      final var result = session.query("SELECT expand(stringProp) FROM SingleValueClass");
      assertThat(result.hasNext()).isFalse();
    });
  }


  @Test
  public void expandList() {

    final var aClass = session.createClass("ClassWithList");
    aClass.createProperty("name", PropertyType.STRING);
    aClass.createProperty("listProp", PropertyType.EMBEDDEDLIST, PropertyType.STRING);

    // init data
    session.executeInTx(transaction -> {
      final var a1 = session.newEntity(aClass);
      a1.setProperty("name", "a1");
      a1.getOrCreateEmbeddedList("listProp").addAll(List.of("a1", "a2"));

      final var a2 = session.newEntity(aClass);
      a2.setProperty("name", "a2");
      a2.getOrCreateEmbeddedList("listProp").addAll(List.of("a3", "a4"));

      final var a3 = session.newEntity(aClass);
      a3.setProperty("name", "a3");
      a3.getOrCreateEmbeddedList("listProp");

      final var a4 = session.newEntity(aClass);
      a4.setProperty("name", "a4");
    });

    final var includeAlias = List.of(false, true);
    final var conditions = List.of(
        new Pair<>("", Set.of("a1", "a2", "a3", "a4")),
        new Pair<>("WHERE name = 'a1'", Set.of("a1", "a2")),
        new Pair<>("WHERE name = 'a3'", Set.of()),
        new Pair<>("WHERE name = 'a4'", Set.of())
    );

    for (var condition : conditions) {
      for (var withAlias : includeAlias) {
        session.executeInTx(transaction -> {

          final var result = session.query(
              "SELECT expand(listProp) " +
                  (withAlias ? "AS myAlias " : "") +
                  "FROM ClassWithList " +
                  condition.key
          );

          final var values = result.stream()
              .map(e -> e.getString(withAlias ? "myAlias" : "value"))
              .collect(Collectors.toSet());

          assertThat(values).isEqualTo(condition.value);
        });
      }
    }
  }

  @Test
  public void expandMap() {

    final var aClass = session.createClass("ClassWithMap");
    aClass.createProperty("name", PropertyType.STRING);
    aClass.createProperty("mapProp", PropertyType.EMBEDDEDMAP, PropertyType.STRING);

    // init data
    session.executeInTx(transaction -> {
      final var a1 = session.newEntity(aClass);
      a1.setProperty("name", "a1");
      a1.getOrCreateEmbeddedMap("mapProp").putAll(Map.of(
          "key1", "value1",
          "key2", "value2"
      ));

      final var a2 = session.newEntity(aClass);
      a2.setProperty("name", "a2");
      a2.getOrCreateEmbeddedMap("mapProp").putAll(Map.of(
          "key3", "value3",
          "key4", "value4"
      ));

      final var a3 = session.newEntity(aClass);
      a3.setProperty("name", "a3");
      a3.getOrCreateEmbeddedMap("mapProp");

      final var a4 = session.newEntity(aClass);
      a4.setProperty("name", "a4");
    });

    final var includeAlias = List.of(false, true);
    final var conditions = List.of(
        new Pair<>("", Map.of(
            "key1", "value1",
            "key2", "value2",
            "key3", "value3",
            "key4", "value4"
        )),
        new Pair<>("WHERE name = 'a1'", Map.of(
            "key1", "value1",
            "key2", "value2"
        )),
        new Pair<>("WHERE name = 'a3'", Map.of()),
        new Pair<>("WHERE name = 'a4'", Map.of())
    );

    for (var condition : conditions) {
      for (var withAlias : includeAlias) {

        final Set<Map<String, ?>> result;
        try {
          result = session.computeInTx(transaction ->
              session
                  .query(
                      "SELECT expand(mapProp) " +
                          (withAlias ? "AS myAlias " : "") +
                          "FROM ClassWithMap " +
                          condition.key
                  )
                  .stream()
                  .map(BasicResult::toMap)
                  .collect(Collectors.toSet())
          );

        } catch (CommandExecutionException exception) {
          if (withAlias && exception.getMessage().contains("myAlias")) {
            continue;
          }
          throw exception;
        }

        if (withAlias && !condition.value.isEmpty()) {
          fail("expand for maps with aliases should not be allowed");
        }

        assertThat(result).isEqualTo(
            condition.value.entrySet().stream()
                .map(Map::ofEntries)
                .collect(Collectors.toSet())
        );
      }
    }
  }

  @Test
  public void expandSingleLink() {

    final var linkedClass = session.createClass("LinkedClass");
    linkedClass.createProperty("name", PropertyType.STRING);

    final var linkingClass = session.createClass("LinkingClass");
    linkingClass.createProperty("name", PropertyType.STRING);
    linkingClass.createProperty("link", PropertyType.LINK, linkedClass);

    // init data
    session.executeInTx(transaction -> {
      final var linked1 = session.newEntity(linkedClass);
      linked1.setProperty("name", "linked1");

      final var linked2 = session.newEntity(linkedClass);
      linked2.setProperty("name", "linked2");

      final var linking1 = session.newEntity(linkingClass);
      linking1.setProperty("name", "linking1");
      linking1.setLink("link", linked1);

      final var linking2 = session.newEntity(linkingClass);
      linking2.setProperty("name", "linking2");
      linking2.setLink("link", linked2);

      final var linking3 = session.newEntity(linkingClass);
      linking3.setProperty("name", "linking3");
    });

    final var includeAlias = List.of(false, true);
    final var conditions = List.of(
        new Pair<>("", Set.of("linked1", "linked2")),
        new Pair<>("WHERE name = 'linking2'", Set.of("linked2")),
        new Pair<>("WHERE name = 'linking3'", Set.of())
    );

    for (var condition : conditions) {
      for (var withAlias : includeAlias) {
        final Set<String> result;
        try {
          result = session.computeInTx(transaction -> session
              .query(
                  "SELECT expand(link) " +
                      (withAlias ? "AS myAlias " : "") +
                      "FROM LinkingClass " +
                      condition.key
              )
              .stream()
              .map(e -> e.getString("name"))
              .collect(Collectors.toSet())
          );
        } catch (CommandExecutionException exception) {
          if (withAlias && exception.getMessage().contains("myAlias")) {
            continue;
          }
          throw exception;
        }

        if (withAlias && !condition.value.isEmpty()) {
          fail("expand for links with aliases should not be allowed");
        }

        assertThat(result).isEqualTo(condition.value);
      }
    }
  }

  @Test
  public void expandLinkList() {

    final var linkedClass = session.createClass("LinkedClass");
    linkedClass.createProperty("name", PropertyType.STRING);

    final var linkingClass = session.createClass("LinkingClass");
    linkingClass.createProperty("name", PropertyType.STRING);
    linkingClass.createProperty("links", PropertyType.LINKLIST, linkedClass);

    // init data
    session.executeInTx(transaction -> {
      final var linked1 = session.newEntity(linkedClass);
      linked1.setProperty("name", "linked1");

      final var linked2 = session.newEntity(linkedClass);
      linked2.setProperty("name", "linked2");

      final var linked3 = session.newEntity(linkedClass);
      linked3.setProperty("name", "linked3");

      final var linking1 = session.newEntity(linkingClass);
      linking1.setProperty("name", "linking1");
      linking1.getOrCreateLinkList("links").addAll(List.of(linked1, linked2));

      final var linking2 = session.newEntity(linkingClass);
      linking2.setProperty("name", "linking2");
      linking2.getOrCreateLinkList("links").add(linked3);

      final var linking3 = session.newEntity(linkingClass);
      linking3.setProperty("name", "linking3");
      linking3.getOrCreateLinkList("links");

      final var linking4 = session.newEntity(linkingClass);
      linking4.setProperty("name", "linking4");
    });

    final var includeAlias = List.of(false, true);
    final var conditions = List.of(
        new Pair<>("", Set.of("linked1", "linked2", "linked3")),
        new Pair<>("WHERE name = 'linking1'", Set.of("linked1", "linked2")),
        new Pair<>("WHERE name = 'linking2'", Set.of("linked3")),
        new Pair<>("WHERE name = 'linking3'", Set.of()),
        new Pair<>("WHERE name = 'linking4'", Set.of())
    );

    for (var condition : conditions) {
      for (var withAlias : includeAlias) {
        final Set<String> result;
        try {
          result = session.computeInTx(transaction ->
              session
                  .query(
                      "SELECT expand(links) " +
                          (withAlias ? "AS myAlias " : "") +
                          "FROM LinkingClass " +
                          condition.key
                  )
                  .stream()
                  .map(e -> e.getString("name"))
                  .collect(Collectors.toSet())
          );
        } catch (CommandExecutionException exception) {
          if (withAlias && exception.getMessage().contains("myAlias")) {
            continue;
          }
          throw exception;
        }

        if (withAlias && !condition.value.isEmpty()) {
          fail("expand for links with aliases should not be allowed");
        }

        assertThat(result).isEqualTo(condition.value);
      }
    }
  }
}
