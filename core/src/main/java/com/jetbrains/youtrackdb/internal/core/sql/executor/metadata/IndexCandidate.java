package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaProperty;
import com.jetbrains.youtrackdb.internal.core.sql.executor.metadata.IndexFinder.Operation;
import java.util.List;
import javax.annotation.Nullable;

public interface IndexCandidate {

  String getName();

  @Nullable
  IndexCandidate invert();

  Operation getOperation();

  @Nullable
  IndexCandidate normalize(CommandContext ctx);

  List<ImmutableSchemaProperty> properties();
}
