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
package com.jetbrains.youtrack.db.internal.core.sql.parser.operators;

import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsKeyOperator;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ContainsKeyOperatorTest {

  @Test
  public void test() {
    var op = new SQLContainsKeyOperator(-1);

    Assert.assertFalse(op.execute(null, null));
    Assert.assertFalse(op.execute(null, "foo"));

    Map<Object, Object> originMap = new HashMap<Object, Object>();
    Assert.assertFalse(op.execute(originMap, "foo"));
    Assert.assertFalse(op.execute(originMap, null));

    originMap.put("foo", "bar");
    originMap.put(1, "baz");

    Assert.assertTrue(op.execute(originMap, "foo"));
    Assert.assertTrue(op.execute(originMap, 1));
    Assert.assertFalse(op.execute(originMap, "fooz"));
  }
}
