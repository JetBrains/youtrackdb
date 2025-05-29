package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrains.youtrack.db.internal.core.index.Index;

import java.util.Iterator;

public record YTDBIndexQuery(Index index, Iterator<Object> values) {
}
