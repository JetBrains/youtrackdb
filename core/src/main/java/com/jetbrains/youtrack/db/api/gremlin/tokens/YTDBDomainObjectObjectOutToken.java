package com.jetbrains.youtrack.db.api.gremlin.tokens;

import com.jetbrains.youtrack.db.api.gremlin.embedded.YTDBDomainObject;

public interface YTDBDomainObjectObjectOutToken<T extends YTDBDomainObject> extends
    YTDBDomainObjectVertexToken<T> {

}
