package com.jetbrains.youtrackdb.internal.core.sql.executor.metadata;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
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

  List<SchemaProperty> properties();
}
