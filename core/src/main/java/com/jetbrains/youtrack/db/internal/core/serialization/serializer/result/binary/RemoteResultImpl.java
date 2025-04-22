package com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary;

import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.query.BasicResultInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.EmbeddedListResultImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.EmbeddedMapResultImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.EmbeddedSetResultImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.LinkListResultImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.LinkMapResultImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.LinkSetResultImpl;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RemoteResultImpl implements RemoteResult, BasicResultInternal {

  private Map<String, Object> content;
  private Map<String, Object> metadata;
  private RID rid;

  @Nullable
  private byte[] blob;

  @Nullable
  protected RemoteDatabaseSessionInternal session;

  public RemoteResultImpl(@Nullable RemoteDatabaseSessionInternal session) {
    content = new HashMap<>();
    this.session = session;
  }

  public RemoteResultImpl(@Nullable RemoteDatabaseSessionInternal session, @Nonnull byte[] blob) {
    this.session = session;
    this.blob = blob;
  }

  public RemoteResultImpl(@Nullable RemoteDatabaseSessionInternal session, @Nonnull RID rid) {
    content = new HashMap<>();
    this.session = session;
    this.rid = rid;
  }

  @Override
  public void setIdentity(@Nonnull RID identity) {
    this.rid = identity;
  }

  @Override
  public void setProperty(@Nonnull String name, Object value) {
    assert checkSession();

    if (content != null) {
      value = convertPropertyValue(value);
      content.put(name, value);
    } else {
      throw new IllegalStateException(
          "Result is not a projection and it's property can not be set");
    }
  }

  @Nullable
  private Object convertPropertyValue(Object value) {
    if (value == null) {
      return null;
    }

    if (PropertyTypeInternal.isSingleValueType(value)) {
      return value;
    }

    switch (value) {
      case RemoteResult result -> {
        var resultSession = result.getBoundedToSession();
        if (resultSession != null && resultSession != session) {
          throw new DatabaseException(
              "Result is bound to different session, cannot use it as property value");
        }

        return result;
      }
      case Object[] array -> {
        List<Object> listCopy = null;
        var allIdentifiable = false;

        for (var o : array) {
          var res = convertPropertyValue(o);

          if (res instanceof Identifiable) {
            allIdentifiable = true;
            if (listCopy == null) {
              //noinspection unchecked,rawtypes
              listCopy = (List<Object>) (List) new LinkListResultImpl(array.length);
            }
          } else {
            if (allIdentifiable) {
              throw new IllegalArgumentException(
                  "Invalid property value, if list contains identifiables, it should contain only them");
            }
            if (listCopy == null) {
              listCopy = new EmbeddedListResultImpl<>(array.length);
            }
          }

          listCopy.add(res);
        }
        if (listCopy == null) {
          listCopy = new EmbeddedListResultImpl<>();
        }
        return listCopy;
      }
      case List<?> collection -> {
        List<Object> listCopy = null;
        var allIdentifiable = false;

        for (var o : collection) {
          var res = convertPropertyValue(o);

          if (res instanceof Identifiable) {
            allIdentifiable = true;
            if (listCopy == null) {
              //noinspection unchecked,rawtypes
              listCopy = (List<Object>) (List) new LinkListResultImpl(collection.size());
            }
          } else {
            if (allIdentifiable) {
              throw new IllegalArgumentException(
                  "Invalid property value, if list contains identifiables, it should contain only them");
            }
            if (listCopy == null) {
              listCopy = new EmbeddedListResultImpl<>(collection.size());
            }
          }

          listCopy.add(res);
        }

        if (listCopy == null) {
          listCopy = new EmbeddedListResultImpl<>();
        }
        return listCopy;
      }
      case Set<?> set -> {
        Set<Object> setCopy = null;

        var allIdentifiable = false;

        for (var o : set) {
          var res = convertPropertyValue(o);
          if (res instanceof Identifiable) {
            if (setCopy == null) {
              //noinspection unchecked,rawtypes
              setCopy = (Set<Object>) (Set) new LinkSetResultImpl(set.size());
            }
            allIdentifiable = true;
          } else {
            if (allIdentifiable) {
              throw new IllegalArgumentException(
                  "Invalid property value, if set contains identifiables, it should contain only them");
            }
            if (setCopy == null) {
              setCopy = new EmbeddedSetResultImpl<>(set.size());
            }
          }

          setCopy.add(res);
        }

        if (setCopy == null) {
          setCopy = new EmbeddedSetResultImpl<>();
        }

        return setCopy;
      }

      case Map<?, ?> map -> {
        Map<String, Object> mapCopy = null;
        var allIdentifiable = false;

        for (var entry : map.entrySet()) {
          var key = entry.getKey();

          if (!(key instanceof String stringKey)) {
            throw new IllegalArgumentException(
                "Invalid property value, only maps with key types of String are supported : " +
                    key);
          }

          var res = convertPropertyValue(entry.getValue());
          if (res instanceof Identifiable) {
            allIdentifiable = true;
            if (mapCopy == null) {
              //noinspection unchecked,rawtypes
              mapCopy = (Map<String, Object>) (Map) new LinkMapResultImpl(map.size());
            }
          } else {
            if (allIdentifiable) {
              throw new IllegalArgumentException(
                  "Invalid property value, if map contains identifiables, it should contain only them");

            }
            if (mapCopy == null) {
              mapCopy = new EmbeddedMapResultImpl<>(map.size());
            }
          }

          mapCopy.put(stringKey, res);
        }

        if (mapCopy == null) {
          mapCopy = new EmbeddedMapResultImpl<>();
        }

        return mapCopy;
      }
      case RID recordId -> {
        return recordId;
      }
      default -> {
        throw new CommandExecutionException(
            "Invalid property value for Result: " + value + " - " + value.getClass().getName());
      }
    }
  }

  @Override
  public <T> T getProperty(@Nonnull String name) {
    assert checkSession();

    if (content != null) {
      //noinspection unchecked
      return (T) content.get(name);
    }

    throw new IllegalStateException("Result is not a projection and it's property can not be get");
  }

  @Nullable
  @Override
  public BasicResult getResult(@Nonnull String name) {
    assert checkSession();

    if (content == null) {
      throw new IllegalStateException(
          "Result is not a projection and it's property can not be get");
    }

    Object result = null;
    if (content.containsKey(name)) {
      result = content.get(name);
    }

    if (result instanceof BasicResult res) {
      return res;
    }

    if (result == null) {
      return null;
    }

    throw new DatabaseException("Property " + name + " is not a result.");
  }

  @Nullable
  @Override
  public RID getLink(@Nonnull String name) {
    assert checkSession();

    if (content == null) {
      throw new IllegalStateException(
          "Result is not a projection and it's property can not be get");
    }

    Object result = null;
    if (content.containsKey(name)) {
      result = content.get(name);
    }

    return switch (result) {
      case null -> null;
      case Identifiable id -> id.getIdentity();
      default -> throw new IllegalStateException("Property " + name + " is not a link");
    };

  }

  @Override
  public @Nonnull List<String> getPropertyNames() {
    assert checkSession();

    if (content == null) {
      throw new IllegalStateException(
          "Result is not a projection and it's property can not be get");
    }

    return new ArrayList<>(content.keySet());
  }

  @Override
  public boolean isIdentifiable() {
    assert checkSession();

    return rid != null;
  }

  @Nullable
  @Override
  public RID getIdentity() {
    assert checkSession();

    return rid;
  }

  @Override
  public boolean hasProperty(@Nonnull String propName) {
    assert checkSession();

    if (content == null) {
      throw new IllegalStateException(
          "Result is not a projection and it's property can not be get");
    }

    return content.containsKey(propName);
  }

  public boolean isBlob() {
    assert checkSession();

    return blob != null;
  }

  @Nonnull
  public byte[] asBlob() {
    assert checkSession();

    if (blob == null) {
      throw new IllegalStateException("Result is not a blob.");
    }

    return blob;
  }

  @Nullable
  public byte[] asBlobOrNull() {
    assert checkSession();

    return blob;
  }

  @Nullable
  @Override
  public RemoteDatabaseSession getBoundedToSession() {
    return session;
  }

  public void setSession(@Nullable RemoteDatabaseSessionInternal session) {
    this.session = session;
  }

  @Override
  public @Nonnull BasicResult detach() {
    assert checkSession();

    var detached = new RemoteResultImpl(null);

    if (content != null) {
      var detachedMap = new HashMap<String, Object>(content.size());

      for (var entry : content.entrySet()) {
        detachedMap.put(entry.getKey(), ResultInternal.toMapValue(entry.getValue(), false));
      }

      detached.content = detachedMap;
    } else {
      detached.rid = rid;
    }

    return detached;
  }


  private boolean checkSession() {
    assert session == null || session.assertIfNotActive();
    return true;
  }

  @Nullable
  public Object getMetadata(String key) {
    assert checkSession();
    if (key == null) {
      return null;
    }
    return metadata == null ? null : metadata.get(key);
  }

  @Override
  public void setMetadata(String key, Object value) {
    assert checkSession();
    if (key == null) {
      return;
    }
    value = convertPropertyValue(value);
    if (metadata == null) {
      metadata = new HashMap<>();
    }
    metadata.put(key, value);
  }

  public void addMetadata(Map<String, Object> values) {
    assert checkSession();
    if (values == null) {
      return;
    }
    if (this.metadata == null) {
      this.metadata = new HashMap<>();
    }
    this.metadata.putAll(values);
  }

  public Set<String> getMetadataKeys() {
    assert checkSession();
    return metadata == null ? Collections.emptySet() : metadata.keySet();
  }

  @Override
  public boolean isProjection() {
    return content != null;
  }

  @Nonnull
  @Override
  public Map<String, Object> toMap() {
    assert checkSession();

    var map = new HashMap<String, Object>();
    if (isProjection()) {
      for (var prop : getPropertyNames()) {
        var propVal = getProperty(prop);
        map.put(prop, ResultInternal.toMapValue(propVal, false));
      }
      if (isIdentifiable()) {
        map.put("@rid", rid);
      }
    } else if (isIdentifiable()) {
      map.put("@rid", rid);
    } else {
      throw new IllegalStateException("Result is not initialized");
    }

    return map;
  }

  @Override
  public @Nonnull String toJSON() {
    assert checkSession();

    var result = new StringBuilder();

    if (isProjection()) {
      var propNames = new ArrayList<>(getPropertyNames());
      //record metadata properties has to be sorted first
      propNames.sort((v1, v2) -> {
        if (v1 == null) {
          return -1;
        }
        if (v2 == null) {
          return 1;
        }

        if (!v1.isEmpty() && v1.charAt(0) == '@') {
          if (!v2.isEmpty() && v2.charAt(0) == '@') {
            return v1.compareTo(v2);
          }

          return -1;
        }

        return v1.compareTo(v2);
      });

      result.append("{");
      var first = true;

      for (var prop : propNames) {
        if (!first) {
          result.append(", ");
        }
        result.append(toJson(prop));
        result.append(": ");
        result.append(toJson(getProperty(prop)));
        first = false;
      }
      result.append("}");
    } else {
      result.append("{").append("\"@rid\": ").append(toJson(rid)).append("}");
    }

    return result.toString();
  }

  private static String toJson(Object val) {
    String jsonVal;
    if (val == null) {
      jsonVal = "null";
    } else if (val instanceof String) {
      jsonVal = "\"" + encode(val.toString()) + "\"";
    } else if (val instanceof Number || val instanceof Boolean) {
      jsonVal = val.toString();
    } else if (val instanceof BasicResult) {
      jsonVal = ((BasicResult) val).toJSON();
    } else if (val instanceof RID) {
      jsonVal = "\"" + val + "\"";
    } else if (val instanceof Iterable) {
      var builder = new StringBuilder();
      builder.append("[");
      var first = true;
      for (var o : (Iterable<?>) val) {
        if (!first) {
          builder.append(", ");
        }
        builder.append(toJson(o));
        first = false;
      }
      builder.append("]");
      jsonVal = builder.toString();
    } else if (val instanceof Iterator<?> iterator) {
      var builder = new StringBuilder();
      builder.append("[");
      var first = true;
      while (iterator.hasNext()) {
        if (!first) {
          builder.append(", ");
        }
        builder.append(toJson(iterator.next()));
        first = false;
      }
      builder.append("]");
      jsonVal = builder.toString();
    } else if (val instanceof Map) {
      var builder = new StringBuilder();
      builder.append("{");
      var first = true;
      @SuppressWarnings("unchecked")
      var map = (Map<Object, Object>) val;
      for (var entry : map.entrySet()) {
        if (!first) {
          builder.append(", ");
        }
        builder.append(toJson(entry.getKey()));
        builder.append(": ");
        builder.append(toJson(entry.getValue()));
        first = false;
      }
      builder.append("}");
      jsonVal = builder.toString();
    } else if (val instanceof byte[]) {
      jsonVal = "\"" + Base64.getEncoder().encodeToString((byte[]) val) + "\"";
    } else if (val instanceof Date) {
      jsonVal = "\"" + DateHelper.getDateTimeFormatInstance(null).format(val) + "\"";
    } else if (val.getClass().isArray()) {
      var builder = new StringBuilder();
      builder.append("[");
      for (var i = 0; i < Array.getLength(val); i++) {
        if (i > 0) {
          builder.append(", ");
        }
        builder.append(toJson(Array.get(val, i)));
      }
      builder.append("]");
      jsonVal = builder.toString();
    } else {
      throw new UnsupportedOperationException(
          "Cannot convert " + val + " - " + val.getClass() + " to JSON");
    }
    return jsonVal;
  }

  @Override
  public String toString() {
    return "content:{\n"
        + content.entrySet().stream()
        .map(x -> x.getKey() + ": " + x.getValue())
        .reduce("", (a, b) -> a + b + "\n")
        + "}\n";
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof RemoteResultImpl resultObj)) {
      if (obj instanceof BasicResult result) {
        var propNames = result.getPropertyNames();

        if (propNames.size() != content.size()) {
          return false;
        }

        //noinspection ObjectInstantiationInEqualsHashCode
        for (var prop : propNames) {
          var thisValue = content.get(prop);
          var otherValue = result.getProperty(prop);
          if (thisValue == null && otherValue == null) {
            continue;
          }
          if (thisValue == null || otherValue == null) {
            return false;
          }
          if (!thisValue.equals(otherValue)) {
            return false;
          }
        }

        return true;
      }

      return false;
    }

    if (session != resultObj.session) {
      return false;
    }

    if (content != null) {
      return this.content.equals(resultObj.content);
    } else {
      return resultObj.content == null;
    }
  }

  @Override
  public int hashCode() {
    return content.hashCode();
  }

  private static String encode(String s) {
    return IOUtils.encodeJsonString(s);
  }
}
