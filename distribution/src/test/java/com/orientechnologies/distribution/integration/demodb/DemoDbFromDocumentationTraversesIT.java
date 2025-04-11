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
public class DemoDbFromDocumentationTraversesIT extends IntegrationTestTemplate {

  @Test
  public void test_Traverses_Example_1() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "TRAVERSE * FROM (\n"
                  + "  SELECT FROM Profiles WHERE Name='Santo' and Surname='YouTrackDB'\n"
                  + ") MAXDEPTH 3");

      assertThat(resultSet).hasSize(85);

    });
  }

  @Test
  public void test_Traverses_Example_2() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "TRAVERSE * FROM (\n"
                  + "  SELECT FROM Countries WHERE Name='Italy'\n"
                  + ") MAXDEPTH 3\n");

      assertThat(resultSet).hasSize(135);

    });
  }
}
