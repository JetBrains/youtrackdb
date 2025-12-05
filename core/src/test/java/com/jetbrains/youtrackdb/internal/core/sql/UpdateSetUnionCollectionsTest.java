package com.jetbrains.youtrackdb.internal.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.common.query.BasicResult;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class UpdateSetUnionCollectionsTest {

  private YouTrackDBImpl youTrackDB;

  @Before
  public void before() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDB.create(UpdateSetUnionCollectionsTest.class.getSimpleName(), DatabaseType.MEMORY,
        "admin", "admpwd", "admin");
  }

  @After
  public void after() {
    youTrackDB.close();
  }

  @Test
  public void updateMapElementWithUnionMap() {
    try (var session =
        youTrackDB.open(UpdateSetUnionCollectionsTest.class.getSimpleName(), "admin", "admpwd")) {
      session.computeScript("sql", "create class example extends V").close();
      session.computeScript("sql", "create property example.metadata EMBEDDEDMAP").close();
      session
          .computeScript(
              "SQL",
              """
                    begin; \
                    let $example = create vertex example;
                    let $a = {"key1":"value1"};
                    let $b = {"key2":"value2", "key3":"value3"};
                    let $u = unionAll($a, $b);\s
                  
                    /* both of the following throw the exception and require to restart the\
                   server*/
                    update $example set metadata["something1"] = $u, metadata.something2 = $u;
                    commit;\
                  """
          )
          .close();

      for (var field : List.of("something1", "something2")) {

        session.executeInTx(transaction -> {
          var values = transaction.query("select metadata." + field + " from example").stream()
              .toList();
          assertThat(values).hasSize(1);
          assertThat(values.getFirst().<Map<?, ?>>getProperty("metadata." + field))
              .isEqualTo(Map.of(
                  "key1", "value1",
                  "key2", "value2",
                  "key3", "value3"
              ));

          var expandedValues = transaction.query(
                  "select expand(metadata." + field + ") from example")
              .stream()
              .map(BasicResult::toMap)
              .collect(Collectors.toSet());

          assertThat(expandedValues).isEqualTo(
              Set.of(
                  Map.of("key1", "value1"),
                  Map.of("key2", "value2"),
                  Map.of("key3", "value3")
              )
          );
        });
      }

    }
  }

  @Test
  public void updateMapWithUnionMap() {
    try (var session =
        youTrackDB.open(UpdateSetUnionCollectionsTest.class.getSimpleName(), "admin", "admpwd")) {

      session.computeScript("sql", "create class example_schema extends V").close();
      session.computeScript("sql", "create property example_schema.metadata EMBEDDEDMAP").close();
      session.computeScript("sql", "create class example_schemaless extends V").close();

      for (var clazz : List.of("example_schema", "example_schemaless")) {

        session
            .computeScript(
                "SQL",
                """
                    begin;
                    let $example = create vertex""" + " " + clazz + ";" + """
                      let $a = {"key1":"value1"};
                      let $b = {"key2":"value2", "key3":"value3"};
                      let $u = unionAll($a, $b);\s
                    
                      /* both of the following throw the exception and require to restart the\
                     server*/
                      update $example set metadata = $u;
                      commit;\
                    """
            )
            .close();

        session.executeInTx(transaction -> {
          var values = transaction.query("select metadata from " + clazz).stream()
              .toList();
          assertThat(values).hasSize(1);
          assertThat(values.getFirst().<Map<?, ?>>getProperty("metadata"))
              .isEqualTo(Map.of(
                  "key1", "value1",
                  "key2", "value2",
                  "key3", "value3"
              ));

          var expandedValues = transaction.query(
                  "select expand(metadata) from " + clazz)
              .stream()
              .map(BasicResult::toMap)
              .collect(Collectors.toSet());

          assertThat(expandedValues).isEqualTo(
              Set.of(
                  Map.of("key1", "value1"),
                  Map.of("key2", "value2"),
                  Map.of("key3", "value3")
              )
          );
        });
      }

    }
  }

  @Test
  public void updateMapElementWithUnionList() {
    try (var session =
        youTrackDB.open(UpdateSetUnionCollectionsTest.class.getSimpleName(), "admin", "admpwd")) {
      session.computeScript("sql", "create class example extends V").close();
      session.computeScript("sql", "create property example.metadata EMBEDDEDMAP").close();
      session
          .computeScript(
              "SQL",
              """
                    begin; \
                    let $example = create vertex example;
                    let $a = ["value1", "value2"];
                    let $b = ["value3"];
                    let $u = unionAll($a, $b);\s
                  
                    /* both of the following throw the exception and require to restart the\
                   server*/
                    update $example set metadata["something1"] = $u, metadata.something2 = $u;
                    commit;\
                  """
          )
          .close();

      for (var field : List.of("something1", "something2")) {

        session.executeInTx(transaction -> {
          var values = transaction.query("select metadata." + field + " from example").stream()
              .toList();
          assertThat(values).hasSize(1);
          assertThat(values.getFirst().<List<?>>getProperty("metadata." + field))
              .isEqualTo(List.of("value1", "value2", "value3"));

          var expandedValues = transaction.query(
                  "select expand(metadata." + field + ") from example")
              .stream()
              .map(BasicResult::toMap)
              .collect(Collectors.toSet());

          assertThat(expandedValues).isEqualTo(
              Set.of(
                  Map.of("value", "value1"),
                  Map.of("value", "value2"),
                  Map.of("value", "value3")
              )
          );
        });
      }

    }
  }

  @Test
  public void updateListWithUnionList() {
    try (var session =
        youTrackDB.open(UpdateSetUnionCollectionsTest.class.getSimpleName(), "admin", "admpwd")) {

      session.computeScript("sql", "create class example_schema extends V").close();
      session.computeScript("sql", "create property example_schema.metadata EMBEDDEDLIST").close();
      session.computeScript("sql", "create class example_schemaless extends V").close();

      for (var clazz : List.of("example_schema", "example_schemaless")) {
        session
            .computeScript(
                "SQL",
                """
                    begin; \
                    let $example = create vertex""" + " " + clazz + ";" + """
                      let $a = ["value1", "value2"];
                      let $b = ["value3"];
                      let $u = unionAll($a, $b);\s
                    
                      /* both of the following throw the exception and require to restart the\
                     server*/
                      update $example set metadata = $u;
                      commit;\
                    """
            )
            .close();

        session.executeInTx(transaction -> {
          var values = transaction.query("select metadata from " + clazz).stream()
              .toList();
          assertThat(values).hasSize(1);
          assertThat(values.getFirst().<List<?>>getProperty("metadata"))
              .isEqualTo(List.of("value1", "value2", "value3"));

          var expandedValues = transaction.query(
                  "select expand(metadata) from " + clazz)
              .stream()
              .map(BasicResult::toMap)
              .collect(Collectors.toSet());

          assertThat(expandedValues).isEqualTo(
              Set.of(
                  Map.of("value", "value1"),
                  Map.of("value", "value2"),
                  Map.of("value", "value3")
              )
          );
        });

      }
    }
  }
}
