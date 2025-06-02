package com.jetbrains.youtrack.db.api.gremlin;

import com.jetbrains.youtrack.db.api.record.RID;

public record YTDBVertexPropertyId(RID rid, String key) {
}
