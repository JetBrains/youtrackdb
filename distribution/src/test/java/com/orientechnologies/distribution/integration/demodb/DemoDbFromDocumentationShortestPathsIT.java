package com.orientechnologies.distribution.integration.demodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.orientechnologies.distribution.integration.IntegrationTestTemplate;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DemoDbFromDocumentationShortestPathsIT extends IntegrationTestTemplate {

  @Test
  public void test_ShortestPaths_Example_1() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT expand(path) FROM (\n"
                  + "  SELECT shortestPath($from, $to) AS path \n"
                  + "  LET \n"
                  + "    $from = (SELECT FROM Profiles WHERE Name='Santo' and Surname='YouTrackDB'), \n"
                  + "    $to = (SELECT FROM Countries WHERE Name='United States') \n"
                  + "  UNWIND path\n"
                  + ")");

      assertThat(resultSet).hasSize(4);

    });
  }

  @Test
  public void test_ShortestPaths_Example_2() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT expand(path) FROM (\n"
                  + "  SELECT shortestPath($from, $to) AS path \n"
                  + "  LET \n"
                  + "    $from = (SELECT FROM Profiles WHERE Name='Santo' and Surname='YouTrackDB'), \n"
                  + "    $to = (SELECT FROM Restaurants WHERE Name='Malga Granezza') \n"
                  + "  UNWIND path\n"
                  + ")");

      assertThat(resultSet).hasSize(4);

    });
  }
}
