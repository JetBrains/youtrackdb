package com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl;


import javax.lang.model.element.Element;

/**
 * Exception thrown by the {@link GremlinDslProcessor}.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public class ProcessorException extends Exception {

  private final Element element;

  public ProcessorException(final Element element, final String msg, final Object... args) {
    super(String.format(msg, args));
    this.element = element;
  }

  public Element getElement() {
    return element;
  }
}

