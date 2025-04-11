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

import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLContainsValueOperator;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ContainsValueOperatorTest {

  @Test
  public void test() {
    var op = new SQLContainsValueOperator(-1);

    Assert.assertFalse(op.execute(null, null));
    Assert.assertFalse(op.execute(null, "foo"));

    Map<Object, Object> originMap = new HashMap<Object, Object>();
    Assert.assertFalse(op.execute(originMap, "bar"));
    Assert.assertFalse(op.execute(originMap, null));

    originMap.put("foo", "bar");
    originMap.put(1, "baz");
    originMap.put(2, 12);

    Assert.assertTrue(op.execute(originMap, "bar"));
    Assert.assertTrue(op.execute(originMap, "baz"));
    Assert.assertTrue(op.execute(originMap, 12));
    Assert.assertFalse(op.execute(originMap, "asdfafsd"));
  }
}
