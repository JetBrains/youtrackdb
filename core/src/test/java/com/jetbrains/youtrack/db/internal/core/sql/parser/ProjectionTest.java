/*
 *
 *  *  Copyright YouTrackDB
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ProjectionTest {

  @Test
  public void testIsExpand() throws ParseException {
    var parser = getParserFor("select expand(foo)  from V");
    var stm = (SQLSelectStatement) parser.parse();
    Assert.assertTrue(stm.getProjection().isExpand());

    var parser2 = getParserFor("select foo  from V");
    var stm2 = (SQLSelectStatement) parser2.parse();
    Assert.assertFalse(stm2.getProjection().isExpand());

    var parser3 = getParserFor("select expand  from V");
    var stm3 = (SQLSelectStatement) parser3.parse();
    Assert.assertFalse(stm3.getProjection().isExpand());
  }

  @Test
  public void testValidate() throws ParseException {
    var parser = getParserFor("select expand(foo)  from V");
    var stm = (SQLSelectStatement) parser.parse();
    stm.getProjection().validate();

    try {
      getParserFor("select expand(foo), bar  from V").parse();
      Assert.fail();
    } catch (CommandSQLParsingException ex) {

    } catch (Exception x) {
      Assert.fail();
    }
  }

  protected YouTrackDBSql getParserFor(String string) {
    InputStream is = new ByteArrayInputStream(string.getBytes());
    var osql = new YouTrackDBSql(is);
    return osql;
  }
}
