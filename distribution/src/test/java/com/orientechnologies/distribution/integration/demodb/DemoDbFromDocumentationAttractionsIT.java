package com.orientechnologies.distribution.integration.demodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.orientechnologies.distribution.integration.IntegrationTestTemplate;
import org.junit.Test;

/**
 *
 */
public class DemoDbFromDocumentationAttractionsIT extends IntegrationTestTemplate {

  @Test
  public void test_Attractions_Example_1() throws Exception {
    session.executeInTx(tx -> {
      final var resultSet =
          session.query(
              "MATCH {class: Customers, as: customer, where: (OrderedId=1)}--{Class: Attractions, as:"
                  + " attraction}\n"
                  + "RETURN $pathelements");

      assertThat(resultSet).hasSize(8);
    });
  }
}
