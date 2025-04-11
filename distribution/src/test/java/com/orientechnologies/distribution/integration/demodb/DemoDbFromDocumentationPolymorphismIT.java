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
public class DemoDbFromDocumentationPolymorphismIT extends IntegrationTestTemplate {

  @Test
  public void test_Polymorphism_Example_1() throws Exception {
    session.executeInTx(tx -> {

      final var resultSet =
          session.query(
              "MATCH {class: Customers, as: customer, where: (OrderedId=1)}--{Class: Locations, as:"
                  + " location}  RETURN $pathelements");

      assertThat(resultSet).hasSize(16);
    });
  }

  // example 2 is handled already in other files

}
