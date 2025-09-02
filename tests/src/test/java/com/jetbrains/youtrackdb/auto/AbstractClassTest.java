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
package com.jetbrains.youtrackdb.auto;

import static org.assertj.core.api.Java6Assertions.assertThat;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import java.io.IOException;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class AbstractClassTest extends BaseDBTest {
  @BeforeClass
  public void createSchema() throws IOException {
    var abstractPerson =
        session.getMetadata().getSchema().createAbstractClass("AbstractPerson");
    abstractPerson.createProperty("name", PropertyType.STRING);

    Assert.assertTrue(abstractPerson.isAbstract());
    Assert.assertEquals(abstractPerson.getCollectionIds().length, 1);
  }

  @Test
  public void testCannotCreateInstances() {
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
