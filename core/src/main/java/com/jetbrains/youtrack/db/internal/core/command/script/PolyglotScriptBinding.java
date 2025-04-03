package com.jetbrains.youtrack.db.internal.core.command.script;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.script.Bindings;
import org.graalvm.polyglot.Value;

/**
 * Wraps a GraalVM value as a JSR223 Bindings for compatibility.
 */
public class PolyglotScriptBinding implements Bindings {

  private final Value context;

  public PolyglotScriptBinding(final Value value) {
    this.context = value;
  }

  @Override
  public Object put(String name, Object value) {
    final var old = context.getMember(name);
    context.putMember(name, value);
    return old;
  }

  @Override
  public void putAll(Map<? extends String, ?> toMerge) {
    for (var entry : toMerge.entrySet()) {
      context.putMember(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public void clear() {
    for (var name : context.getMemberKeys()) {
      context.removeMember(name);
    }
  }

  @Override
  public Set<String> keySet() {
    return context.getMemberKeys();
  }

  @Override
  public Collection<Object> values() {
    List<Object> result = new ArrayList<>();
    for (var name : context.getMemberKeys()) {
      result.add(context.getMember(name));
    }
    return result;
  }

  @Nullable
  @Override
  public Set<Entry<String, Object>> entrySet() {
    return null;
  }

  @Override
  public int size() {
    return context.getMemberKeys().size();
  }

  @Override
  public boolean isEmpty() {
    return !context.hasMembers();
  }

  @Override
  public boolean containsKey(final Object key) {
    return context.hasMember(key.toString());
  }

  @Override
  public boolean containsValue(Object value) {
    return false;
  }

  @Override
  public Object get(Object key) {
    return context.getMember(key.toString());
  }

  @Override
  public Object remove(Object key) {
    final var old = context.getMember(key.toString());
    context.removeMember(key.toString());
    return old;
  }
}
