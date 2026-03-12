// Migrated from: com.jetbrains.youtrackdb.auto.WrongQueryTest
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import org.junit.jupiter.api.Test;

public class WrongQueryTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.WrongQueryTest#queryFieldOperatorNotSupported
  @Test
  void queryFieldOperatorNotSupported() {
    assertThrows(CommandSQLParsingException.class, () -> {
      try (var result = session.execute(
          "select * from Account where name.not() like 'G%'")) {
        // Should not reach here
      }
    });
  }
}
