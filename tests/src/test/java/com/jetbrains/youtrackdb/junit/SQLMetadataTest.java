// Migrated from: com.jetbrains.youtrackdb.auto.SQLMetadataTest
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class SQLMetadataTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLMetadataTest#querySchemaClasses
  @Test
  @Order(1)
  void querySchemaClasses() {
    var result =
        session
            .query("select expand(classes) from metadata:schema").toList();

    assertFalse(result.isEmpty());
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLMetadataTest#querySchemaProperties
  @Test
  @Order(2)
  void querySchemaProperties() {
    var result =
        session
            .query(
                "select expand(properties) from (select expand(classes) from metadata:schema)"
                    + " where name = 'OUser'")
            .toList();

    assertFalse(result.isEmpty());
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLMetadataTest#queryIndexes
  @Test
  @Order(3)
  void queryIndexes() {
    var result =
        session
            .query("select from metadata:indexes").toList();

    assertFalse(result.isEmpty());
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLMetadataTest#queryMetadataNotSupported
  @Test
  @Order(4)
  void queryMetadataNotSupported() {
    assertThrows(UnsupportedOperationException.class, () -> {
      session.query("select from metadata:blaaa").toList();
    });
  }
}
