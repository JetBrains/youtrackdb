package com.jetbrains.youtrackdb.internal.core.command.script.transformer;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.command.script.ScriptResultSet;
import com.jetbrains.youtrackdb.internal.core.command.script.ScriptResultSets;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.result.MapTransformer;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.resultset.ResultSetTransformer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.graalvm.polyglot.Value;

public class ScriptTransformerImpl implements ScriptTransformer {

  protected Map<Class, ResultSetTransformer> resultSetTransformers = new HashMap<>();
  protected Map<Class, ResultTransformer> transformers = new LinkedHashMap<>(2);

  public ScriptTransformerImpl() {

    if (!GlobalConfiguration.SCRIPT_POLYGLOT_USE_GRAAL.getValueAsBoolean()) {
      try {
        final var c = Class.forName("jdk.nashorn.api.scripting.JSObject");
        registerResultTransformer(
            c,
            (db, value) -> {
              var internal = new ResultInternal(db);

              var res = new ArrayList();
              internal.setProperty("value", res);

              for (var v : ((Map) value).values()) {
                res.add(new ResultInternal(db, (Identifiable) v));
              }

              return internal;
            });
      } catch (Exception e) {
        // NASHORN NOT INSTALLED, IGNORE IT
      }
    }
    registerResultTransformer(Map.class, new MapTransformer(this));
  }

  @Override
  public ResultSet toResultSet(DatabaseSessionInternal db, Object value) {
    if (value instanceof Value v) {
      if (v.isNull()) {
        return null;
      } else if (v.hasArrayElements()) {
        final List<Object> array = new ArrayList<>((int) v.getArraySize());
        for (var i = 0; i < v.getArraySize(); ++i) {
          var hostObject = v.getArrayElement(i).asHostObject();
          if (hostObject instanceof Identifiable identifiable) {
            array.add(new ResultInternal(db, identifiable));
          } else if (hostObject instanceof EdgeInternal edge) {
            array.add((new ResultInternal(db, edge)));
          } else {
            array.add(toResult(db, hostObject));
          }

        }
        value = array;
      } else if (v.isHostObject()) {
        value = v.asHostObject();
      } else if (v.isString()) {
        value = v.asString();
      } else if (v.isNumber()) {
        value = v.asDouble();
      } else {
        value = v;
      }
    }

    if (value == null) {
      return ScriptResultSets.empty(db);
    }
    if (value instanceof ResultSet) {
      return (ResultSet) value;
    } else if (value instanceof Iterator) {
      return new ScriptResultSet(db, (Iterator) value, this);
    }
    var resultSetTransformer = resultSetTransformers.get(value.getClass());

    if (resultSetTransformer != null) {
      return resultSetTransformer.transform(value);
    }
    return defaultResultSet(db, value);
  }

  private ResultSet defaultResultSet(DatabaseSessionInternal db, Object value) {
    return new ScriptResultSet(db, Collections.singletonList(value).iterator(), this);
  }

  @Override
  public Result toResult(DatabaseSessionInternal db, Object value) {
    var transformer = getTransformer(value.getClass());

    if (transformer == null) {
      return defaultTransformer(db, value);
    }

    return transformer.transform(db, value);
  }

  @Nullable
  public ResultTransformer getTransformer(final Class clazz) {
    if (clazz != null) {
      for (var entry : transformers.entrySet()) {
        if (entry.getKey().isAssignableFrom(clazz)) {
          return entry.getValue();
        }
      }
    }
    return null;
  }

  @Override
  public boolean doesHandleResult(Object value) {
    return getTransformer(value.getClass()) != null;
  }

  private static Result defaultTransformer(DatabaseSessionInternal db, Object value) {
    var internal = new ResultInternal(db);
    internal.setProperty("value", value);
    return internal;
  }

  @Override
  public void registerResultTransformer(Class clazz, ResultTransformer transformer) {
    transformers.put(clazz, transformer);
  }

  @Override
  public void registerResultSetTransformer(Class clazz, ResultSetTransformer transformer) {
    resultSetTransformers.put(clazz, transformer);
  }
}
