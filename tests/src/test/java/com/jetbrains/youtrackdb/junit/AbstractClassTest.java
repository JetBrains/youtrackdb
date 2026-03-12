/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class AbstractClassTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();
    createSchema();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.AbstractClassTest#createSchema
  // (Originally @BeforeClass)
  private void createSchema() {
    var abstractPerson =
        session.getMetadata().getSchema().createAbstractClass("AbstractPerson");
    abstractPerson.createProperty("name", PropertyType.STRING);

    assertTrue(abstractPerson.isAbstract());
    assertEquals(1, abstractPerson.getCollectionIds().length);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.AbstractClassTest#testCannotCreateInstances
  @Test
  @Order(1)
  void testCannotCreateInstances() {
    try {
      session.begin();
      session.newEntity("AbstractPerson");
      session.begin();
    } catch (BaseException e) {
      Throwable cause = e;

      while (cause.getCause() != null) {
        cause = cause.getCause();
      }

      assertThat(cause).isInstanceOf(SchemaException.class);
    }
  }
}
