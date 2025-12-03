/*
 *
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
package com.jetbrains.youtrackdb.internal.core.sql.method.misc;

import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.util.PatternConst;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.text.Normalizer;

/**
 *
 */
public class SQLMethodNormalize extends AbstractSQLMethod {

  public static final String NAME = "normalize";

  public SQLMethodNormalize() {
    super(NAME, 0, 2);
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {

    if (ioResult != null) {
      final Normalizer.Form form;
      if (iParams != null && iParams.length > 0) {
        form = Normalizer.Form.valueOf(IOUtils.getStringContent(iParams[0].toString()));
      } else {
        form = Normalizer.Form.NFD;
      }

      var normalized = Normalizer.normalize(ioResult.toString(), form);
      if (iParams != null && iParams.length > 1) {
        normalized = normalized.replaceAll(IOUtils.getStringContent(iParams[0].toString()), "");
      } else {
        normalized = PatternConst.PATTERN_DIACRITICAL_MARKS.matcher(normalized).replaceAll("");
      }
      ioResult = normalized;
    }
    return ioResult;
  }
}
