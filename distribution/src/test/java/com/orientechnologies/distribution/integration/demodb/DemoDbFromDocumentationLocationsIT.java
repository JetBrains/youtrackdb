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
public class DemoDbFromDocumentationLocationsIT extends IntegrationTestTemplate {

  @Test
  public void test_Locations_Example_1() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {Class: Profiles, as: profile, where: (Name='Santo' AND"
                  + " Surname='YouTrackDB')}<-HasProfile-{Class: Customers, as:"
                  + " customer}-HasVisited->{class: Locations, as: location} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(12);

    });
  }

  // examples 2 and 3 are handled already in other files

  @Test
  public void test_Locations_Example_4() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {Class: Locations, as: location}<-HasVisited-{class: Customers, as: customer,"
                  + " where: (OrderedId=2)}\n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(46);

    });
  }

  @Test
  public void test_Locations_Example_5() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {Class: Profiles, as: profile, where: (Name='Santo' and"
                  + " Surname='YouTrackDB')}-HasFriend->{Class: Profiles, as:"
                  + " friend}<-HasProfile-{Class: Customers, as: customer}-HasVisited->{Class:"
                  + " Locations, as: location} \n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(124);

    });
  }
}
