package com.jetbrains.youtrackdb.internal.core.command;

/** An immutable, preprocessed internal name. It never retains an execution context or value. */
public final class ContextVariableReference {

  final String name;
  final String lookupName;
  final boolean simple;
  final ContextVariableReference childReference;

  public ContextVariableReference(String name) {
    this.name = name;
    this.lookupName = name.startsWith("$") ? name.substring(1) : name;
    this.simple = lookupName.indexOf('.') < 0 && lookupName.indexOf('[') < 0;
    // Each named child lookup strips one leading dollar again. Preserve that scope contract.
    this.childReference = name.startsWith("$") ? new ContextVariableReference(lookupName) : this;
  }

  public String getName() {
    return name;
  }
}
