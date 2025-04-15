/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.command.traverse;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public class TraverseContext extends BasicCommandContext {

  private Memory memory = new StackMemory();
  private final Set<RID> history = new HashSet<RID>();

  private TraverseAbstractProcess<?> currentProcess;

  public void push(final TraverseAbstractProcess<?> iProcess) {
    memory.add(iProcess);
  }

  public Map<String, Object> getVariables() {
    final var map = new HashMap<String, Object>();
    map.put("depth", getDepth());
    map.put("path", getPath());
    map.put("stack", memory.getUnderlying());
    // DELEGATE
    map.putAll(super.getVariables());
    return map;
  }

  public Object getVariable(final String iName) {
    final var name = iName.trim().toUpperCase(Locale.ENGLISH);

    if ("DEPTH".startsWith(name)) {
      return getDepth();
    } else if (name.startsWith("PATH")) {
      return EntityHelper.getFieldValue(getDatabaseSession(), getPath(),
          iName.substring("PATH".length()));
    } else if (name.startsWith("STACK")) {

      var result =
          EntityHelper.getFieldValue(getDatabaseSession(), memory.getUnderlying(),
              iName.substring("STACK".length()));
      if (result instanceof ArrayDeque) {
        result = ((ArrayDeque) result).clone();
      }
      return result;
    } else if (name.startsWith("HISTORY")) {
      return EntityHelper.getFieldValue(getDatabaseSession(), history,
          iName.substring("HISTORY".length()));
    } else
    // DELEGATE
    {
      return super.getVariable(iName);
    }
  }

  public void pop(final Identifiable currentRecord) {
    if (currentRecord != null) {
      final var rid = currentRecord.getIdentity();
      if (!history.remove(rid)) {
        LogManager.instance().warn(this, "Element '" + rid + "' not found in traverse history");
      }
    }

    try {
      memory.dropFrame();
    } catch (NoSuchElementException e) {
      throw new IllegalStateException("Traverse stack is empty", e);
    }
  }

  public TraverseAbstractProcess<?> next() {
    currentProcess = memory.next();
    return currentProcess;
  }

  public boolean isEmpty() {
    return memory.isEmpty();
  }

  public void reset() {
    memory.clear();
  }

  public boolean isAlreadyTraversed(final Identifiable identity, final int iLevel) {
    return history.contains(identity.getIdentity());

    // final int[] l = history.get(identity.getIdentity());
    // if (l == null)
    // return false;
    //
    // for (int i = 0; i < l.length && l[i] > -1; ++i)
    // if (l[i] == iLevel)
    // return true;
  }

  public void addTraversed(final Identifiable identity, final int iLevel) {
    history.add(identity.getIdentity());

    // final int[] l = history.get(identity.getIdentity());
    // if (l == null) {
    // final int[] array = new int[BUCKET_SIZE];
    // array[0] = iLevel;
    // Arrays.fill(array, 1, BUCKET_SIZE, -1);
    // history.put(identity.getIdentity(), array);
    // } else {
    // if (l[l.length - 1] > -1) {
    // // ARRAY FULL, ENLARGE IT
    // final int[] array = Arrays.copyOf(l, l.length + BUCKET_SIZE);
    // array[l.length] = iLevel;
    // Arrays.fill(array, l.length + 1, array.length, -1);
    // history.put(identity.getIdentity(), array);
    // } else {
    // for (int i = l.length - 2; i >= 0; --i) {
    // if (l[i] > -1) {
    // l[i + 1] = iLevel;
    // break;
    // }
    // }
    // }
    // }
  }

  public String getPath() {
    return currentProcess == null ? "" : currentProcess.getPath().toString();
  }

  public int getDepth() {
    return currentProcess == null ? 0 : currentProcess.getPath().getDepth();
  }

  public void setStrategy(final Traverse.STRATEGY strategy) {
    if (strategy == Traverse.STRATEGY.BREADTH_FIRST) {
      memory = new QueueMemory(memory);
    } else {
      memory = new StackMemory(memory);
    }
  }

  private interface Memory {

    void add(TraverseAbstractProcess<?> iProcess);

    TraverseAbstractProcess<?> next();

    void dropFrame();

    void clear();

    Collection<TraverseAbstractProcess<?>> getUnderlying();

    boolean isEmpty();
  }

  private abstract static class AbstractMemory implements Memory {

    protected final Deque<TraverseAbstractProcess<?>> deque;

    public AbstractMemory() {
      deque = new ArrayDeque<TraverseAbstractProcess<?>>();
    }

    public AbstractMemory(final Memory memory) {
      deque = new ArrayDeque<TraverseAbstractProcess<?>>(memory.getUnderlying());
    }

    @Override
    public TraverseAbstractProcess<?> next() {
      return deque.peek();
    }

    @Override
    public void dropFrame() {
      deque.removeFirst();
    }

    @Override
    public void clear() {
      deque.clear();
    }

    @Override
    public boolean isEmpty() {
      return deque.isEmpty();
    }

    @Override
    public Collection<TraverseAbstractProcess<?>> getUnderlying() {
      return deque;
    }
  }

  private static class StackMemory extends AbstractMemory {

    public StackMemory() {
      super();
    }

    public StackMemory(final Memory memory) {
      super(memory);
    }

    @Override
    public void add(final TraverseAbstractProcess<?> iProcess) {
      deque.push(iProcess);
    }
  }

  private static class QueueMemory extends AbstractMemory {

    public QueueMemory(final Memory memory) {
      super(memory);
    }

    @Override
    public void add(final TraverseAbstractProcess<?> iProcess) {
      deque.addLast(iProcess);
    }
  }
}
