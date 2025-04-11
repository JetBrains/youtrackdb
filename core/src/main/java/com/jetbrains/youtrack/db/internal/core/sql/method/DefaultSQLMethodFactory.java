/*
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.core.sql.method;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.core.sql.functions.coll.SQLMethodMultiValue;
import com.jetbrains.youtrack.db.internal.core.sql.functions.conversion.SQLMethodAsDate;
import com.jetbrains.youtrack.db.internal.core.sql.functions.conversion.SQLMethodAsDateTime;
import com.jetbrains.youtrack.db.internal.core.sql.functions.conversion.SQLMethodAsDecimal;
import com.jetbrains.youtrack.db.internal.core.sql.functions.conversion.SQLMethodBeforeUpdate;
import com.jetbrains.youtrack.db.internal.core.sql.functions.conversion.SQLMethodConvert;
import com.jetbrains.youtrack.db.internal.core.sql.functions.misc.SQLMethodExclude;
import com.jetbrains.youtrack.db.internal.core.sql.functions.misc.SQLMethodInclude;
import com.jetbrains.youtrack.db.internal.core.sql.functions.text.SQLMethodAppend;
import com.jetbrains.youtrack.db.internal.core.sql.functions.text.SQLMethodFromJSON;
import com.jetbrains.youtrack.db.internal.core.sql.functions.text.SQLMethodHash;
import com.jetbrains.youtrack.db.internal.core.sql.functions.text.SQLMethodLength;
import com.jetbrains.youtrack.db.internal.core.sql.functions.text.SQLMethodReplace;
import com.jetbrains.youtrack.db.internal.core.sql.functions.text.SQLMethodRight;
import com.jetbrains.youtrack.db.internal.core.sql.functions.text.SQLMethodSubString;
import com.jetbrains.youtrack.db.internal.core.sql.functions.text.SQLMethodToJSON;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodAsBoolean;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodAsFloat;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodAsInteger;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodAsList;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodAsLong;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodAsMap;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodAsSet;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodAsString;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodField;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodFormat;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodFunctionDelegate;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodIndexOf;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodJavaType;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodKeys;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodLastIndexOf;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodNormalize;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodPrefix;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodRemove;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodRemoveAll;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodSize;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodSplit;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodToLowerCase;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodToUpperCase;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodTrim;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodType;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.SQLMethodValues;
import com.jetbrains.youtrack.db.internal.core.sql.method.sequence.SQLMethodCurrent;
import com.jetbrains.youtrack.db.internal.core.sql.method.sequence.SQLMethodNext;
import com.jetbrains.youtrack.db.internal.core.sql.method.sequence.SQLMethodReset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Default method factory.
 */
public class DefaultSQLMethodFactory implements SQLMethodFactory {

  private final Map<String, Object> methods = new HashMap<String, Object>();

  public DefaultSQLMethodFactory() {
    register(SQLMethodAppend.NAME, new SQLMethodAppend());
    register(SQLMethodAsBoolean.NAME, new SQLMethodAsBoolean());
    register(SQLMethodAsDate.NAME, new SQLMethodAsDate());
    register(SQLMethodAsDateTime.NAME, new SQLMethodAsDateTime());
    register(SQLMethodAsDecimal.NAME, new SQLMethodAsDecimal());
    register(SQLMethodAsFloat.NAME, new SQLMethodAsFloat());
    register(SQLMethodAsInteger.NAME, new SQLMethodAsInteger());
    register(SQLMethodAsList.NAME, new SQLMethodAsList());
    register(SQLMethodAsLong.NAME, new SQLMethodAsLong());
    register(SQLMethodAsMap.NAME, new SQLMethodAsMap());
    register(SQLMethodAsSet.NAME, new SQLMethodAsSet());
    register(SQLMethodAsString.NAME, new SQLMethodAsString());
    register(SQLMethodCharAt.NAME, new SQLMethodCharAt());
    register(SQLMethodConvert.NAME, new SQLMethodConvert());
    register(SQLMethodExclude.NAME, new SQLMethodExclude());
    register(SQLMethodField.NAME, new SQLMethodField());
    register(SQLMethodFormat.NAME, new SQLMethodFormat());
    register(SQLMethodFromJSON.NAME, new SQLMethodFromJSON());
    register(SQLMethodFunctionDelegate.NAME, SQLMethodFunctionDelegate.class);
    register(SQLMethodHash.NAME, new SQLMethodHash());
    register(SQLMethodInclude.NAME, new SQLMethodInclude());
    register(SQLMethodIndexOf.NAME, new SQLMethodIndexOf());
    register(SQLMethodJavaType.NAME, new SQLMethodJavaType());
    register(SQLMethodKeys.NAME, new SQLMethodKeys());
    register(SQLMethodLastIndexOf.NAME, new SQLMethodLastIndexOf());
    register(SQLMethodLeft.NAME, new SQLMethodLeft());
    register(SQLMethodLength.NAME, new SQLMethodLength());
    register(SQLMethodMultiValue.NAME, new SQLMethodMultiValue());
    register(SQLMethodNormalize.NAME, new SQLMethodNormalize());
    register(SQLMethodPrefix.NAME, new SQLMethodPrefix());
    register(SQLMethodRemove.NAME, new SQLMethodRemove());
    register(SQLMethodRemoveAll.NAME, new SQLMethodRemoveAll());
    register(SQLMethodReplace.NAME, new SQLMethodReplace());
    register(SQLMethodRight.NAME, new SQLMethodRight());
    register(SQLMethodSize.NAME, new SQLMethodSize());
    register(SQLMethodSplit.NAME, new SQLMethodSplit());
    register(SQLMethodToLowerCase.NAME, new SQLMethodToLowerCase());
    register(SQLMethodToUpperCase.NAME, new SQLMethodToUpperCase());
    register(SQLMethodTrim.NAME, new SQLMethodTrim());
    register(SQLMethodType.NAME, new SQLMethodType());
    register(SQLMethodSubString.NAME, new SQLMethodSubString());
    register(SQLMethodToJSON.NAME, new SQLMethodToJSON());
    register(SQLMethodValues.NAME, new SQLMethodValues());
    register(SQLMethodBeforeUpdate.NAME, new SQLMethodBeforeUpdate());

    // SEQUENCE
    register(SQLMethodCurrent.NAME, new SQLMethodCurrent());
    register(SQLMethodNext.NAME, new SQLMethodNext());
    register(SQLMethodReset.NAME, new SQLMethodReset());
  }

  public void register(final String iName, final Object iImplementation) {
    methods.put(iName.toLowerCase(Locale.ENGLISH), iImplementation);
  }

  @Override
  public boolean hasMethod(final String iName) {
    return methods.containsKey(iName.toLowerCase(Locale.ENGLISH));
  }

  @Override
  public Set<String> getMethodNames() {
    return methods.keySet();
  }

  @Override
  public SQLMethod createMethod(final String name) throws CommandExecutionException {
    final var m = methods.get(name);
    final SQLMethod method;

    if (m instanceof Class<?>) {
      try {
        method = (SQLMethod) ((Class<?>) m).newInstance();
      } catch (Exception e) {
        throw BaseException.wrapException(
            new CommandExecutionException("Cannot create SQL method: " + m), e, (String) null);
      }
    } else {
      method = (SQLMethod) m;
    }

    if (method == null) {
      throw new CommandExecutionException("Unknown method name: " + name);
    }

    return method;
  }
}
