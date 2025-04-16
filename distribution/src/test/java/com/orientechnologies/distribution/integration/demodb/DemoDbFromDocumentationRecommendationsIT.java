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
public class DemoDbFromDocumentationRecommendationsIT extends IntegrationTestTemplate {

  @Test
  public void test_Recommendations_Example_1() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH \n"
                  + "  {class: Profiles, as: profile, where: (Name = 'Isabella' AND"
                  + " Surname='Gomez')}-HasFriend-{as: friend},\n"
                  + "  {as: friend}-HasFriend-{as: friendOfFriend, where: ($matched.profile not in"
                  + " $currentMatch.both('HasFriend') and $matched.profile != $currentMatch)} \n"
                  + "RETURN DISTINCT friendOfFriend.Name");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(29);

    });
  }

  @Test
  public void test_Recommendations_Example_2() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH \n"
                  + "  {Class: Customers, as: customer, where: (OrderedId=1)}-HasProfile->{class:"
                  + " Profiles, as: profile},\n"
                  + "  {as: profile}-HasFriend->{class: Profiles, as: friend},\n"
                  + "  {as: friend}<-HasProfile-{Class: Customers, as: customerFriend},\n"
                  + "  {as: customerFriend}-HasStayed->{Class: Hotels, as: hotel},\n"
                  + "  {as: customerFriend}-MadeReview->{Class: Reviews, as: review},\n"
                  + "  {as: hotel}-HasReview->{as: review}\n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(12);

    });
  }

  @Test
  public void test_Recommendations_Example_2_bis() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH\n"
                  + "  {Class: Customers, as: customer, where: (OrderedId=1)}-HasProfile->{class:"
                  + " Profiles, as: profile},\n"
                  + "  {as: profile}-HasFriend->{class: Profiles, as: friend},\n"
                  + "  {as: friend}<-HasProfile-{Class: Customers, as: customerFriend},\n"
                  + "  {as: customerFriend}-HasStayed->{Class: Hotels, as: hotel},\n"
                  + "  {as: customerFriend}-MadeReview->{Class: Reviews, as: review},\n"
                  + "  {as: hotel}.outE('HasReview'){as: ReviewStars, where: (Stars>3)}.inV(){as:"
                  + " review}\n"
                  + "RETURN $pathelements");

      final var results = resultSet.stream().collect(Collectors.toList());
      assertThat(results).hasSize(7);

    });
  }
}
