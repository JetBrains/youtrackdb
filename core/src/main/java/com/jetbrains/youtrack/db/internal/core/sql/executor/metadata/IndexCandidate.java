package com.jetbrains.youtrack.db.internal.core.sql.executor.metadata;

import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.metadata.IndexFinder.Operation;
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
