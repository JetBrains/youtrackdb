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
package com.jetbrains.youtrackdb.internal.core.command.traverse;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.command.Command;
import com.jetbrains.youtrackdb.internal.core.command.CommandExecutorAbstract;
import com.jetbrains.youtrackdb.internal.core.command.CommandPredicate;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Base class for traversing.
 */
public class Traverse implements Command, Iterable<Identifiable>, Iterator<Identifiable> {

  private CommandPredicate predicate;
  private Iterator<? extends Identifiable> target;
  private final List<Object> fields = new ArrayList<Object>();
  private long resultCount = 0;
  private long limit = 0;
  private Identifiable lastTraversed;
  private STRATEGY strategy = STRATEGY.DEPTH_FIRST;
  private final TraverseContext context = new TraverseContext();
  private int maxDepth = -1;

  public Traverse(DatabaseSessionEmbedded db) {
    context.setDatabaseSession(db);
  }

  public enum STRATEGY {
    DEPTH_FIRST,
    BREADTH_FIRST
  }

  /*
   * Executes a traverse collecting all the result in the returning List<Identifiable>. This could be memory expensive because for
   * large results the list could be huge. it's always better to use it as an Iterable and lazy fetch each result on next() call.
   */
  @Override
  public List<Identifiable> execute(DatabaseSessionEmbedded session) {
    context.setDatabaseSession(session);
    final List<Identifiable> result = new ArrayList<>();

    while (hasNext()) {
      result.add(next());
    }

    return result;
  }

  public TraverseAbstractProcess<?> nextProcess() {
    return context.next();
  }

  @Override
  public boolean hasNext() {
    if (limit > 0 && resultCount >= limit) {
      return false;
    }

    if (lastTraversed == null)
    // GET THE NEXT
    {
      lastTraversed = next();
    }

    if (lastTraversed == null && !context.isEmpty()) {
      throw new IllegalStateException("Traverse ended abnormally");
    }

    if (!CommandExecutorAbstract.checkInterruption(context)) {
      return false;
    }

    // BROWSE ALL THE RECORDS
    return lastTraversed != null;
  }

  @Override
  @Nullable
  public Identifiable next() {
    if (Thread.interrupted()) {
      throw new CommandExecutionException(context.getDatabaseSession().getDatabaseName(),
          "The traverse execution has been interrupted");
    }

    if (lastTraversed != null) {
      // RETURN LATEST AND RESET IT
      final var result = lastTraversed;
      lastTraversed = null;
      return result;
    }

    if (limit > 0 && resultCount >= limit) {
      return null;
    }

    Identifiable result;
    TraverseAbstractProcess<?> toProcess;
    // RESUME THE LAST PROCESS
    while ((toProcess = nextProcess()) != null) {
      result = toProcess.process();
      if (result != null) {
        resultCount++;
        return result;
      }
    }

    return null;
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("remove()");
  }

  @Override
  public Iterator<Identifiable> iterator() {
    return this;
  }

  @Override
  public TraverseContext getContext() {
    return context;
  }

  public Traverse target(final Iterable<? extends Identifiable> iTarget) {
    return target(iTarget.iterator());
  }

  public Traverse target(final Identifiable... iRecords) {
    final List<Identifiable> list = new ArrayList<Identifiable>();
    Collections.addAll(list, iRecords);
    return target(list.iterator());
  }

  @SuppressWarnings("unchecked")
  public Traverse target(final Iterator<? extends Identifiable> iTarget) {
    target = iTarget;
    context.reset();
    new TraverseRecordSetProcess(this, (Iterator<Identifiable>) target, TraversePath.empty(),
        context.getDatabaseSession());
    return this;
  }

  public Iterator<? extends Identifiable> getTarget() {
    return target;
  }

  public Traverse predicate(final CommandPredicate iPredicate) {
    predicate = iPredicate;
    return this;
  }

  public CommandPredicate getPredicate() {
    return predicate;
  }

  public Traverse field(final Object iField) {
    if (!fields.contains(iField)) {
      fields.add(iField);
    }
    return this;
  }

  public Traverse fields(final Collection<Object> iFields) {
    for (var f : iFields) {
      field(f);
    }
    return this;
  }

  public Traverse fields(final String... iFields) {
    for (var f : iFields) {
      field(f);
    }
    return this;
  }

  public List<Object> getFields() {
    return fields;
  }

  public long getLimit() {
    return limit;
  }

  public Traverse limit(final long iLimit) {
    if (iLimit < -1) {
      throw new IllegalArgumentException("Limit cannot be negative. 0 = infinite");
    }
    this.limit = iLimit;
    return this;
  }

  @Override
  public String toString() {
    return String.format(
        "Traverse.target(%s).fields(%s).limit(%d).predicate(%s)",
        target, fields, limit, predicate);
  }

  public long getResultCount() {
    return resultCount;
  }

  public Identifiable getLastTraversed() {
    return lastTraversed;
  }

  public STRATEGY getStrategy() {
    return strategy;
  }

  public void setStrategy(STRATEGY strategy) {
    this.strategy = strategy;
    context.setStrategy(strategy);
  }

  public int getMaxDepth() {
    return maxDepth;
  }

  public void setMaxDepth(final int maxDepth) {
    this.maxDepth = maxDepth;
  }
}
