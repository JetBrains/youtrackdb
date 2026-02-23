package com.jetbrains.youtrackdb.internal.core.sql.functions;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Template base class for SQL function factories that register and look up functions by name.
 */
public abstract class SQLFunctionFactoryTemplate implements SQLFunctionFactory {

  private final Map<String, Object> functions;

  public SQLFunctionFactoryTemplate() {
    functions = new HashMap<>();
  }

  protected void register(DatabaseSessionEmbedded session, final SQLFunction function) {
    functions.put(function.getName(session).toLowerCase(Locale.ENGLISH), function);
  }

  protected void register(String name, Object function) {
    functions.put(name.toLowerCase(Locale.ENGLISH), function);
  }

  @Override
  public boolean hasFunction(final String name, DatabaseSessionEmbedded session) {
    return functions.containsKey(name);
  }

  @Override
  public Set<String> getFunctionNames(DatabaseSessionEmbedded session) {
    return functions.keySet();
  }

  @Override
  public SQLFunction createFunction(final String name, DatabaseSessionEmbedded session)
      throws CommandExecutionException {
    final var obj = functions.get(name);

    if (obj == null) {
      throw new CommandExecutionException(session, "Unknown function name :" + name);
    }

    if (obj instanceof SQLFunction) {
      return (SQLFunction) obj;
    } else {
      // it's a class
      final var clazz = (Class<?>) obj;
      try {
        return (SQLFunction) clazz.newInstance();
      } catch (Exception e) {
        throw BaseException.wrapException(
            new CommandExecutionException(session,
                "Error in creation of function "
                    + name
                    + "(). Probably there is not an empty constructor or the constructor generates"
                    + " errors"),
            e, session);
      }
    }
  }

  public Map<String, Object> getFunctions() {
    return functions;
  }
}
