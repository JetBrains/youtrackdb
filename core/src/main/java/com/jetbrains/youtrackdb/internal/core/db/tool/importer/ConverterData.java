package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Set;

/**
 * Holds the database session and broken RID set shared across import converters.
 */
public class ConverterData {

  protected DatabaseSessionEmbedded session;
  protected Set<RID> brokenRids;

  public ConverterData(DatabaseSessionEmbedded session, Set<RID> brokenRids) {
    this.session = session;
    this.brokenRids = brokenRids;
  }
}
