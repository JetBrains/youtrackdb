package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * This {@link SQLFunction} is able to invoke a static method using reflection. If contains more
 * than one {@link Method} it tries to pick the one that better fits the input parameters.
 */
public class SQLStaticReflectiveFunction extends SQLFunctionAbstract {

  private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = new HashMap<>();

  static {
    PRIMITIVE_TO_WRAPPER.put(Boolean.TYPE, Boolean.class);
    PRIMITIVE_TO_WRAPPER.put(Byte.TYPE, Byte.class);
    PRIMITIVE_TO_WRAPPER.put(Character.TYPE, Character.class);
    PRIMITIVE_TO_WRAPPER.put(Short.TYPE, Short.class);
    PRIMITIVE_TO_WRAPPER.put(Integer.TYPE, Integer.class);
    PRIMITIVE_TO_WRAPPER.put(Long.TYPE, Long.class);
    PRIMITIVE_TO_WRAPPER.put(Double.TYPE, Double.class);
    PRIMITIVE_TO_WRAPPER.put(Float.TYPE, Float.class);
    PRIMITIVE_TO_WRAPPER.put(Void.TYPE, Void.TYPE);
  }

  private static final Map<Class<?>, Class<?>> WRAPPER_TO_PRIMITIVE = new HashMap<>();

  static {
    for (var primitive : PRIMITIVE_TO_WRAPPER.keySet()) {
      var wrapper = PRIMITIVE_TO_WRAPPER.get(primitive);
      if (!primitive.equals(wrapper)) {
        WRAPPER_TO_PRIMITIVE.put(wrapper, primitive);
      }
    }
  }

  private static final Object2IntOpenHashMap<Class<?>> PRIMITIVE_WEIGHT =
      new Object2IntOpenHashMap<>();

  static {
    PRIMITIVE_WEIGHT.defaultReturnValue(-1);
    PRIMITIVE_WEIGHT.put(boolean.class, 1);
    PRIMITIVE_WEIGHT.put(char.class, 2);
    PRIMITIVE_WEIGHT.put(byte.class, 3);
    PRIMITIVE_WEIGHT.put(short.class, 4);
    PRIMITIVE_WEIGHT.put(int.class, 5);
    PRIMITIVE_WEIGHT.put(long.class, 6);
    PRIMITIVE_WEIGHT.put(float.class, 7);
    PRIMITIVE_WEIGHT.put(double.class, 8);
    PRIMITIVE_WEIGHT.put(void.class, 9);
  }

  private final Method[] methods;

  public SQLStaticReflectiveFunction(
      String name, int minParams, int maxParams, Method... methods) {
    super(name, minParams, maxParams);
    this.methods = methods;
    // we need to sort the methods by parameters type to return the closest overloaded method
    Arrays.sort(
        methods,
        (m1, m2) -> {
          var m1Params = m1.getParameterTypes();
          var m2Params = m2.getParameterTypes();

          var c = m1Params.length - m2Params.length;
          if (c == 0) {
            for (var i = 0; i < m1Params.length; i++) {
              if (m1Params[i].isPrimitive()
                  && m2Params[i].isPrimitive()
                  && !m1Params[i].equals(m2Params[i])) {
                c += PRIMITIVE_WEIGHT.getInt(m1Params[i]) - PRIMITIVE_WEIGHT.getInt(m2Params[i]);
              }
            }
          }

          return c;
        });
  }

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      Object[] iParams,
      CommandContext iContext) {

    final Supplier<String> paramsPrettyPrint =
        () ->
            Arrays.stream(iParams)
                .map(p -> p + " [ " + p.getClass().getName() + " ]")
                .collect(Collectors.joining(", ", "(", ")"));

    var method = pickMethod(iParams);

    if (method == null) {
      throw new QueryParsingException(iContext.getDatabaseSession().getDatabaseName(),
          "Unable to find a function for " + name + paramsPrettyPrint.get());
    }

    try {
      return method.invoke(null, iParams);
    } catch (ReflectiveOperationException e) {
      throw BaseException.wrapException(
          new QueryParsingException(iContext.getDatabaseSession().getDatabaseName(),
              "Error executing function " + name + paramsPrettyPrint.get()),
          e, iContext.getDatabaseSession());
    } catch (IllegalArgumentException x) {
      LogManager.instance().error(this, "Error executing function %s", x, name);

      return null; // if a function fails for given input, just return null to avoid breaking the
      // query execution
    }
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return this.getName(session);
  }

  @Nullable
  private Method pickMethod(Object[] iParams) {
    for (var m : methods) {
      var parameterTypes = m.getParameterTypes();
      if (iParams.length == parameterTypes.length) {
        var match = true;
        for (var i = 0; i < parameterTypes.length; i++) {
          if (iParams[i] != null && !isAssignable(iParams[i].getClass(), parameterTypes[i])) {
            match = false;
            break;
          }
        }

        if (match) {
          return m;
        }
      }
    }

    return null;
  }

  private static boolean isAssignable(final Class<?> iFromClass, final Class<?> iToClass) {
    // handle autoboxing
    final BiFunction<Class<?>, Class<?>, Class<?>> autoboxer =
        (from, to) -> {
          if (from.isPrimitive() && !to.isPrimitive()) {
            return PRIMITIVE_TO_WRAPPER.get(from);
          } else if (to.isPrimitive() && !from.isPrimitive()) {
            return WRAPPER_TO_PRIMITIVE.get(from);
          } else {
            return from;
          }
        };

    final var fromClass = autoboxer.apply(iFromClass, iToClass);

    if (fromClass == null) {
      return false;
    } else if (fromClass.equals(iToClass)) {
      return true;
    } else if (fromClass.isPrimitive()) {
      if (!iToClass.isPrimitive()) {
        return false;
      } else if (Integer.TYPE.equals(fromClass)) {
        return Long.TYPE.equals(iToClass)
            || Float.TYPE.equals(iToClass)
            || Double.TYPE.equals(iToClass);
      } else if (Long.TYPE.equals(fromClass)) {
        return Float.TYPE.equals(iToClass) || Double.TYPE.equals(iToClass);
      } else if (Boolean.TYPE.equals(fromClass)) {
        return false;
      } else if (Double.TYPE.equals(fromClass)) {
        return false;
      } else if (Float.TYPE.equals(fromClass)) {
        return Double.TYPE.equals(iToClass);
      } else if (Character.TYPE.equals(fromClass)) {
        return Integer.TYPE.equals(iToClass)
            || Long.TYPE.equals(iToClass)
            || Float.TYPE.equals(iToClass)
            || Double.TYPE.equals(iToClass);
      } else if (Short.TYPE.equals(fromClass)) {
        return Integer.TYPE.equals(iToClass)
            || Long.TYPE.equals(iToClass)
            || Float.TYPE.equals(iToClass)
            || Double.TYPE.equals(iToClass);
      } else if (Byte.TYPE.equals(fromClass)) {
        return Short.TYPE.equals(iToClass)
            || Integer.TYPE.equals(iToClass)
            || Long.TYPE.equals(iToClass)
            || Float.TYPE.equals(iToClass)
            || Double.TYPE.equals(iToClass);
      }
      // this should never happen
      return false;
    }
    return iToClass.isAssignableFrom(fromClass);
  }
}
