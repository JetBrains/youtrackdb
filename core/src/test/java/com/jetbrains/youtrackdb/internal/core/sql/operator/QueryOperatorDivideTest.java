/*
 *
 *
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
package com.jetbrains.youtrackdb.internal.core.sql.operator;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorDivide;
import java.math.BigDecimal;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the SQL division operator across numeric types. */
public class QueryOperatorDivideTest {

  @Test
  public void test() {
    QueryOperator operator = new QueryOperatorDivide();
    Assert.assertEquals(
        operator.evaluateRecord(
            null, null, null, 10, 3, null, RecordSerializerBinary.INSTANCE.getCurrentSerializer()),
        10 / 3);
    Assert.assertEquals(
        operator.evaluateRecord(
            null,
            null,
            null,
            10L,
            3L,
            null,
            RecordSerializerBinary.INSTANCE.getCurrentSerializer()),
        10L / 3L);
    Assert.assertEquals(
        operator.evaluateRecord(
            null,
            null,
            null,
            10.1,
            3,
            null,
            RecordSerializerBinary.INSTANCE.getCurrentSerializer()),
        10.1 / 3);
    Assert.assertEquals(
        operator.evaluateRecord(
            null,
            null,
            null,
            10,
            3.1,
            null,
            RecordSerializerBinary.INSTANCE.getCurrentSerializer()),
        10 / 3.1);
    Assert.assertEquals(
        operator.evaluateRecord(
            null,
            null,
            null,
            10.1d,
            3,
            null,
            RecordSerializerBinary.INSTANCE.getCurrentSerializer()),
        10.1d / 3);
    Assert.assertEquals(
        operator.evaluateRecord(
            null,
            null,
            null,
            10,
            3.1d,
            null,
            RecordSerializerBinary.INSTANCE.getCurrentSerializer()),
        10 / 3.1d);
    Assert.assertEquals(
        operator.evaluateRecord(
            null,
            null,
            null,
            new BigDecimal(10),
            4,
            null,
            RecordSerializerBinary.INSTANCE.getCurrentSerializer()),
        new BigDecimal(10).divide(new BigDecimal(4)));
    Assert.assertEquals(
        operator.evaluateRecord(
            null,
            null,
            null,
            10,
            new BigDecimal(4),
            null,
            RecordSerializerBinary.INSTANCE.getCurrentSerializer()),
        new BigDecimal(10).divide(new BigDecimal(4)));
  }
}
