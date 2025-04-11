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
public class DemoDbFromDocumentationProfileIT extends IntegrationTestTemplate {

  @Test
  public void test_Profile_Example_1() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT \n"
                  + "  count(*) as NumberOfProfiles, \n"
                  + "  Birthday.format('yyyy') AS YearOfBirth \n"
                  + "FROM Profiles \n"
                  + "GROUP BY YearOfBirth \n"
                  + "ORDER BY NumberOfProfiles DESC");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(51);

      final var result = results.iterator().next();

      assertThat(result.<Long>getProperty("NumberOfProfiles")).isEqualTo(34);
      assertThat(result.<String>getProperty("YearOfBirth")).isEqualTo("1997");

    });
  }

  @Test
  public void test_Profile_Example_2() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "SELECT  @rid as Profile_RID, Name, Surname, (both('HasFriend').size()) AS"
                  + " FriendsNumber FROM `Profiles` ORDER BY FriendsNumber DESC LIMIT 3");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(3);

      final var result = results.iterator().next();

      assertThat(result.<String>getProperty("Name")).isEqualTo("Jeremiah");
      assertThat(result.<String>getProperty("Surname")).isEqualTo("Schneider");
      assertThat(result.<Integer>getProperty("FriendsNumber")).isEqualTo(12);

    });
  }
}
