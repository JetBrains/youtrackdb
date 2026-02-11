/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrackdb.internal.spatial.functions;

import com.jetbrains.youtrackdb.api.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import com.jetbrains.youtrackdb.internal.spatial.shape.ShapeFactory;

/**
 *
 */
public class STGeomFromTextFunction extends SQLFunctionAbstract {

  public static final String NAME = "ST_GeomFromText";

  private final ShapeFactory factory = ShapeFactory.INSTANCE;

  public STGeomFromTextFunction() {
    super(NAME, 1, 1);
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      Object[] iParams,
      CommandContext context) {
    var geom = (String) iParams[0];
    try {
      return factory.toEmbeddedEntity(geom, context.getDatabaseSession());
    } catch (Exception e) {
      throw BaseException.wrapException(
          new CommandExecutionException(context.getDatabaseSession(),
              String.format("Cannot parse geometry {%s}", geom)), e, context.getDatabaseSession());
    }
  }

  @Override
  public String getSyntax(DatabaseSessionEmbedded session) {
    return "";
  }
}
