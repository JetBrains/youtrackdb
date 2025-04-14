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
public class DemoDbFromDocumentationReviewsIT extends IntegrationTestTemplate {

  @Test
  public void test_Reviews_Example_1() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  Stars, count(*) as Count \n"
                  + "FROM HasReview \n"
                  + "GROUP BY Stars \n"
                  + "ORDER BY Count DESC");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(5);

      final var result = results.iterator().next();

      assertThat(result.<Integer>getProperty("Stars")).isEqualTo(2);
      assertThat(result.<Long>getProperty("Count")).isEqualTo(272);

    });
  }

  @Test
  public void test_Reviews_Example_2() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {class: Services, as: s}-HasReview->{class: Reviews, as: r} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(2546);

    });
  }

  @Test
  public void test_Reviews_Example_3() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {class: Services, as: s}-HasReview->{class: Reviews, as: r}<-MadeReview-{class:"
                  + " Customers, as: c} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3819);

    });
  }

  @Test
  public void test_Reviews_Example_4() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  @rid as Service_RID,\n"
                  + "  Name as Service_Name,\n"
                  + "  Type as Service_Type,\n"
                  + "  out(\"HasReview\").size() AS ReviewNumbers \n"
                  + "FROM `Services` \n"
                  + "ORDER BY ReviewNumbers DESC");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3105);

      final var result = results.iterator().next();

      assertThat(result.<String>getProperty("Service_Name")).isEqualTo("Hotel Felicyta");
      assertThat(result.<String>getProperty("Service_Type")).isEqualTo("hotel");
      assertThat(result.<Integer>getProperty("ReviewNumbers")).isEqualTo(5);

    });
  }

  @Test
  public void test_Reviews_Example_5() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT\n"
                  + "  @rid as Restaurant_RID,\n"
                  + "  Name as Restaurants_Name,\n"
                  + "  Type as Restaurants_Type,\n"
                  + "  out(\"HasReview\").size() AS ReviewNumbers \n"
                  + "FROM `Restaurants` \n"
                  + "ORDER BY ReviewNumbers DESC \n"
                  + "LIMIT 3");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);

      final var result = results.iterator().next();

      assertThat(result.<String>getProperty("Restaurants_Name")).isEqualTo("Pizzeria Il Pirata");
      assertThat(result.<String>getProperty("Restaurants_Type")).isEqualTo("restaurant");
      assertThat(result.<Integer>getProperty("ReviewNumbers")).isEqualTo(4);

    });
  }

  @Test
  public void test_Reviews_Example_5_bis() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  @rid as Service_RID,\n"
                  + "  Name as Service_Name,\n"
                  + "  Type as Service_Type,\n"
                  + "  out(\"HasReview\").size() AS ReviewNumbers \n"
                  + "FROM `Services` \n"
                  + "ORDER BY ReviewNumbers DESC \n"
                  + "LIMIT 3");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);

      final var result = results.iterator().next();

      assertThat(result.<String>getProperty("Service_Name")).isEqualTo("Hotel Felicyta");
      assertThat(result.<String>getProperty("Service_Type")).isEqualTo("hotel");
      assertThat(result.<Integer>getProperty("ReviewNumbers")).isEqualTo(5);

    });
  }

  // example 6 is handled already in other files

}
