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
package com.jetbrains.youtrackdb.auto.junit;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.io.IOException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of AbstractClassTest. Original test class:
 * com.jetbrains.youtrackdb.auto.AbstractClassTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/AbstractClassTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AbstractClassTest extends BaseDBTest {

  /**
   * Original method: createSchema (BeforeClass) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/AbstractClassTest.java:30
   */
  @BeforeClass
  public static void setUpClass() throws Exception {
    AbstractClassTest instance = new AbstractClassTest();
    instance.beforeClass();
    instance.createSchema();
  }

  /**
   * Original method: createSchema Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/AbstractClassTest.java:30
   */
  public void createSchema() throws IOException {
    var abstractPerson =
        session.getMetadata().getSchema().createAbstractClass("AbstractPerson");
    abstractPerson.createProperty("name", PropertyType.STRING);

    Assert.assertTrue(abstractPerson.isAbstract());
    Assert.assertEquals(1, abstractPerson.getCollectionIds().length);
  }

  /**
   * Original test method: testCannotCreateInstances Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/AbstractClassTest.java:40
   */
  @Test
  public void test01_CannotCreateInstances() {
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
