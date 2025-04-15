package com.orientechnologies.distribution.integration.demodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.orientechnologies.distribution.integration.IntegrationTestTemplate;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Tests for issue #7661
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DemoDbGroupByTestIT extends IntegrationTestTemplate {

  @Test
  public void testGroupBy1() throws Exception {
    session.executeInTx(tx -> {
      var resultSet =
          session.query("SELECT count(*) FROM Orders GROUP BY OrderDate.format('yyyy')");

      assertThat(resultSet).hasSize(7);
    });
  }

  @Test
  public void testGroupBy2() throws Exception {

    session.executeInTx(tx -> {
      var resultSet =
          session.query(
              "SELECT count(*), OrderDate.format('yyyy') as year FROM Orders GROUP BY year");

      assertThat(resultSet).hasSize(7);
    });
  }

  @Test
  public void testGroupBy3() throws Exception {

    session.executeInTx(tx -> {
      final var resultSet =
          session.query(
              "SELECT count(*), OrderDate.format('yyyy') FROM Orders GROUP BY"
                  + " OrderDate.format('yyyy')");

      assertThat(resultSet).hasSize(7);
    });
  }

  @Test
  public void testGroupBy4() throws Exception {

    session.executeInTx(tx -> {
      final var resultSet =
          session.query(
              "SELECT count(*), OrderDate.format('yyyy') as year FROM Orders GROUP BY"
                  + " OrderDate.format('yyyy')");

      assertThat(resultSet).hasSize(7);
    });
  }
}
