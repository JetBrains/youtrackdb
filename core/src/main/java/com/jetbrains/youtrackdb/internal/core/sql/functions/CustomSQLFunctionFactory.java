package com.jetbrains.youtrackdb.internal.core.sql.functions;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLStaticReflectiveFunction;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Factory for custom SQL functions.
 */
public class CustomSQLFunctionFactory implements SQLFunctionFactory {

  private static final Map<String, Object> FUNCTIONS = new HashMap<>();

  static {
    register("math_", Math.class);
  }

  public static void register(final String prefix, final Class<?> clazz) {
    final var methodsMap =
        Arrays.stream(clazz.getMethods())
            .filter(m -> Modifier.isStatic(m.getModifiers()))
            .collect(Collectors.groupingBy(Method::getName));

    for (var entry : methodsMap.entrySet()) {
      final var name = prefix + entry.getKey();
      if (FUNCTIONS.containsKey(name)) {
        LogManager.instance()
            .warn(
                CustomSQLFunctionFactory.class,
                "Unable to register reflective function with name " + name);
      } else {
        var methodsList = methodsMap.get(entry.getKey());
        var methods = new Method[methodsList.size()];
        var i = 0;
        var minParams = 0;
        var maxParams = 0;
        for (var m : methodsList) {
          methods[i++] = m;
          minParams =
              minParams < m.getParameterTypes().length ? minParams : m.getParameterTypes().length;
          maxParams =
              maxParams > m.getParameterTypes().length ? maxParams : m.getParameterTypes().length;
        }
        FUNCTIONS.put(
            name.toLowerCase(Locale.ENGLISH),
            new SQLStaticReflectiveFunction(name, minParams, maxParams, methods));
      }
    }
  }


  @Override
  public void registerDefaultFunctions(DatabaseSessionEmbedded db) {
    // do nothing
  }

  @Override
  public Set<String> getFunctionNames(DatabaseSessionEmbedded session) {
    return FUNCTIONS.keySet();
  }

  @Override
  public boolean hasFunction(final String name, DatabaseSessionEmbedded session) {
    return FUNCTIONS.containsKey(name);
  }

  @Override
  public SQLFunction createFunction(final String name, DatabaseSessionEmbedded session) {
    final var obj = FUNCTIONS.get(name);

    if (obj == null) {
      throw new CommandExecutionException(session, "Unknown function name :" + name);
    }

    if (obj instanceof SQLFunction fn) {
      return fn;
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
}
