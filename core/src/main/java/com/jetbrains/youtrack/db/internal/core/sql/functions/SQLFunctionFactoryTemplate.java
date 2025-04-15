package com.jetbrains.youtrack.db.internal.core.sql.functions;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public abstract class SQLFunctionFactoryTemplate implements SQLFunctionFactory {

  private final Map<String, Object> functions;

  public SQLFunctionFactoryTemplate() {
    functions = new HashMap<>();
  }

  protected void register(DatabaseSession session, final SQLFunction function) {
    functions.put(function.getName(session).toLowerCase(Locale.ENGLISH), function);
  }

  protected void register(String name, Object function) {
    functions.put(name.toLowerCase(Locale.ENGLISH), function);
  }

  @Override
  public boolean hasFunction(final String name, DatabaseSessionInternal session) {
    return functions.containsKey(name);
  }

  @Override
  public Set<String> getFunctionNames(DatabaseSessionInternal session) {
    return functions.keySet();
  }

  @Override
  public SQLFunction createFunction(final String name, DatabaseSessionInternal session)
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
