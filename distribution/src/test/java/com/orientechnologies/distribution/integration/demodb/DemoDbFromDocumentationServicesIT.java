package com.orientechnologies.distribution.integration.demodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.orientechnologies.distribution.integration.IntegrationTestTemplate;
import java.util.stream.Collectors;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DemoDbFromDocumentationServicesIT extends IntegrationTestTemplate {

  @Test
  public void test_Services_Example_1() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {class: Customers, as: customer, where: (OrderedId=1)}--{Class: Services, as:"
                  + " service}\n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(8);

    });
  }

  @Test
  public void test_Services_Example_2() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  Name, Type, in(\"HasStayed\").size() AS NumberOfBookings \n"
                  + "FROM Hotels \n"
                  + "ORDER BY NumberOfBookings DESC \n"
                  + "LIMIT 3");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);

      final var result = results.iterator().next();

      assertThat(result.<String>getProperty("Name")).isEqualTo("Hotel Cavallino d'Oro");
      assertThat(result.<String>getProperty("Type")).isEqualTo("hotel");
      assertThat(result.<Integer>getProperty("NumberOfBookings")).isEqualTo(7);

    });
  }

  @Test
  public void test_Services_Example_3() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  Name, Type, out(\"HasReview\").size() AS ReviewNumbers \n"
                  + "FROM `Hotels` \n"
                  + "ORDER BY ReviewNumbers DESC \n"
                  + "LIMIT 3");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);

      final var result = results.iterator().next();

      assertThat(result.<String>getProperty("Name")).isEqualTo("Hotel Felicyta");
      assertThat(result.<String>getProperty("Type")).isEqualTo("hotel");
      assertThat(result.<Integer>getProperty("ReviewNumbers")).isEqualTo(5);

    });
  }

  @Test
  public void test_Services_Example_4() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  Name, \n"
                  + "  count(*) as CountryCount \n"
                  + "FROM (\n"
                  + "  SELECT \n"
                  + "    expand(out('IsFromCountry')) AS countries \n"
                  + "  FROM (\n"
                  + "    SELECT \n"
                  + "      expand(in(\"HasEaten\")) AS customers \n"
                  + "    FROM Restaurants \n"
                  + "    WHERE Id='26' \n"
                  + "    UNWIND customers) \n"
                  + "  UNWIND countries) \n"
                  + "GROUP BY Name \n"
                  + "ORDER BY CountryCount DESC \n"
                  + "LIMIT 3");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);

      final var result = results.iterator().next();

      assertThat(result.<String>getProperty("Name")).isEqualTo("Croatia");
      assertThat(result.<Long>getProperty("CountryCount")).isEqualTo(1);

    });
  }
}
