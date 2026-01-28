package com.jetbrains.youtrackdb.internal.core.gql.step;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;

/// Step that executes a GQL MATCH clause.
///
/// For `MATCH (a:Label)`:
/// - Reads all vertices of class "Label"
/// - Binds each vertex to variable "a"
/// - Emits Map<String, Object> with {"a": vertex}
public class GqlMatchStep extends AbstractStep<Map<String, Object>, Map<String, Object>> {

  private final String alias;
  private final String label;

  private Iterator<Map<String, Object>> results = Collections.emptyIterator();
  private boolean initialized = false;

  public GqlMatchStep(Traversal.Admin<?, ?> traversal, String alias, String label) {
    super(traversal);
    this.alias = alias;
    this.label = label;
  }

  private Iterator<Map<String, Object>> executeMatch() {
    // TODO: Implement actual query execution
    // For now, return one empty map
    return Collections.singletonList(Map.<String, Object>of()).iterator();
  }

  public String getAlias() {
    return alias;
  }

  public String getLabel() {
    return label;
  }

  @Override
  protected Admin<Map<String, Object>> processNextStart() throws NoSuchElementException {
    if (!initialized) {
      initialized = true;
      results = executeMatch();
    }

    if (results.hasNext()) {
      var result = results.next();
      return this.getTraversal().getTraverserGenerator().generate(result, this, 1L);
    }

    throw new NoSuchElementException();
  }
}
