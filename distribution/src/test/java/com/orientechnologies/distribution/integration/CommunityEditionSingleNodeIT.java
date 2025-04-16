package com.orientechnologies.distribution.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 *
 */
public class CommunityEditionSingleNodeIT extends IntegrationTestTemplate {

  @Test
  public void testSearchOnField() {
    session.executeInTx(tx -> {

      final var result =
          session.query(
              "SELECT from ArchaeologicalSites where search_fields(['Name'],'foro') = true");

      assertThat(result).hasSize(2);
    });
  }

  @Test
  public void testSearchOnClass() {
    session.executeInTx(tx -> {

      final var result = session.query(
          "select * from `Hotels` where search_class('western')=true");

      assertThat(result).hasSize(6);
    });
  }
}
