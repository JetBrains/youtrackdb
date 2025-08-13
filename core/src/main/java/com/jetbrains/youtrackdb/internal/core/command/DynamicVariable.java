package com.jetbrains.youtrackdb.internal.core.command;

public interface DynamicVariable {

  Object resolve(CommandContext contex);
}
