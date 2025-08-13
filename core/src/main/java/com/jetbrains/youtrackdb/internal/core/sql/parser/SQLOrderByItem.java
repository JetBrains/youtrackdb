package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.text.Collator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 *
 */
public class SQLOrderByItem {

  public static final String ASC = "ASC";
  public static final String DESC = "DESC";
  protected String alias;
  protected SQLModifier modifier;
  protected String recordAttr;
  protected SQLRid rid;
  protected String type = ASC;
  protected SQLExpression collate;

  // calculated at run time
  private Collate collateStrategy;
  private Collator stringCollator;
  private boolean isEdge;

  public String getAlias() {
    return alias;
  }

  public void setAlias(String alias) {
    this.alias = alias;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getRecordAttr() {
    return recordAttr;
  }

  public void setRecordAttr(String recordAttr) {
    this.recordAttr = recordAttr;
  }

  public SQLRid getRid() {
    return rid;
  }

  public void setRid(SQLRid rid) {
    this.rid = rid;
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {

    if (alias != null) {
      builder.append(alias);
      if (modifier != null) {
        modifier.toString(params, builder);
      }
    } else if (recordAttr != null) {
      builder.append(recordAttr);
    } else if (rid != null) {
      rid.toString(params, builder);
    }
    if (type != null) {
      builder.append(" ").append(type);
    }
    if (collate != null) {
      builder.append(" COLLATE ");
      collate.toString(params, builder);
    }
  }

  public int compare(Result a, Result b, CommandContext ctx) {
    Object aVal = null;
    Object bVal = null;
    if (rid != null) {
      throw new UnsupportedOperationException("ORDER BY " + rid + " is not supported yet");
    }

    var result = 0;
    if (recordAttr != null) {
      aVal = a.getProperty(recordAttr);
      bVal = b.getProperty(recordAttr);
    } else if (alias != null) {
      if (isEdge) {
        var aElement = (Vertex) a.asEntityOrNull();
        var aIter =
            aElement != null ? aElement.getVertices(Direction.OUT, alias).iterator() : null;
        aVal = (aIter != null && aIter.hasNext()) ? aIter.next() : null;

        var bElement = (Vertex) b.asEntityOrNull();
        var bIter =
            bElement != null ? bElement.getVertices(Direction.OUT, alias).iterator() : null;
        bVal = (bIter != null && bIter.hasNext()) ? bIter.next() : null;
      } else {
        if (a.hasProperty(alias)) {
          aVal = a.getProperty(alias);
        }
        if (b.hasProperty(alias)) {
          bVal = b.getProperty(alias);
        }
      }
    }
    if (aVal == null && bVal == null) {
      aVal = ((ResultInternal) a).getMetadata(alias);
      bVal = ((ResultInternal) b).getMetadata(alias);
    }
    if (modifier != null) {
      aVal = modifier.execute(a, aVal, ctx);
      bVal = modifier.execute(b, bVal, ctx);
    }
    if (collate != null && collateStrategy == null) {
      var collateVal = collate.execute(new ResultInternal(ctx.getDatabaseSession()), ctx);
      if (collateVal == null) {
        collateVal = collate.toString();
        if (collateVal.equals("null")) {
          collateVal = null;
        }
      }
      if (collateVal != null) {
        collateStrategy = SQLEngine.getCollate(String.valueOf(collateVal));
        if (collateStrategy == null) {
          collateStrategy =
              SQLEngine.getCollate(String.valueOf(collateVal).toUpperCase(Locale.ENGLISH));
        }
        if (collateStrategy == null) {
          collateStrategy =
              SQLEngine.getCollate(String.valueOf(collateVal).toLowerCase(Locale.ENGLISH));
        }
        if (collateStrategy == null) {
          throw new CommandExecutionException(ctx.getDatabaseSession(),
              "Invalid collate for ORDER BY: " + collateVal);
        }
      }
    }

    if (collateStrategy != null) {
      result = collateStrategy.compareForOrderBy(aVal, bVal);
    } else {
      if (aVal == null) {
        if (bVal == null) {
          result = 0;
        } else {
          result = -1;
        }
      } else if (bVal == null) {
        result = 1;
      } else if (aVal instanceof String && bVal instanceof String) {

        var internal = ctx.getDatabaseSession();
        if (stringCollator == null) {
          var language = (String) internal.get(DatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE);
          var country = (String) internal.get(DatabaseSession.ATTRIBUTES.LOCALE_COUNTRY);
          Locale locale;
          if (language != null) {
            if (country != null) {
              locale = new Locale(language, country);
            } else {
              locale = new Locale(language);
            }
          } else {
            locale = Locale.getDefault();
          }
          stringCollator = Collator.getInstance(locale);
        }
        result = stringCollator.compare(aVal, bVal);
      } else if (aVal instanceof Comparable && bVal instanceof Comparable) {

        try {
          result = ((Comparable) aVal).compareTo(bVal);
        } catch (Exception e) {
          LogManager.instance().error(this, "Error during comparision", e);
          result = 0;
        }
      }
    }
    if (type == DESC) {
      result = -1 * result;
    }
    return result;
  }

  public SQLOrderByItem copy() {
    var result = new SQLOrderByItem();
    result.alias = alias;
    result.modifier = modifier == null ? null : modifier.copy();
    result.recordAttr = recordAttr;
    result.rid = rid == null ? null : rid.copy();
    result.type = type;
    result.collate = this.collate == null ? null : collate.copy();
    result.isEdge = this.isEdge;
    return result;
  }

  public void extractSubQueries(SubQueryCollector collector) {
    if (modifier != null) {
      modifier.extractSubQueries(collector);
    }
  }

  public boolean refersToParent() {
    if (alias != null && alias.equalsIgnoreCase("$parent")) {
      return true;
    }
    if (modifier != null && modifier.refersToParent()) {
      return true;
    }
    return collate != null && collate.refersToParent();
  }

  public SQLModifier getModifier() {
    return modifier;
  }

  public void setModifier(SQLModifier modifier) {
    this.modifier = modifier;
  }

  public Result serialize(DatabaseSessionEmbedded session) {
    var result = new ResultInternal(session);
    result.setProperty("alias", alias);
    if (modifier != null) {
      result.setProperty("modifier", modifier.serialize(session));
    }
    result.setProperty("recordAttr", recordAttr);
    if (rid != null) {
      result.setProperty("rid", rid.serialize(session));
    }
    result.setProperty("type", type);
    if (collate != null) {
      result.setProperty("collate", collate.serialize(session));
    }
    return result;
  }

  public void deserialize(Result fromResult) {
    alias = fromResult.getProperty("alias");
    if (fromResult.getProperty("modifier") != null) {
      modifier = new SQLModifier(-1);
      modifier.deserialize(fromResult.getProperty("modifier"));
    }
    recordAttr = fromResult.getProperty("recordAttr");
    if (fromResult.getProperty("rid") != null) {
      rid = new SQLRid(-1);
      rid.deserialize(fromResult.getProperty("rid"));
    }
    type = DESC.equals(fromResult.getProperty("type")) ? DESC : ASC;
    if (fromResult.getProperty("collate") != null) {
      collate = new SQLExpression(-1);
      collate.deserialize(fromResult.getProperty("collate"));
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var that = (SQLOrderByItem) o;

    if (!Objects.equals(alias, that.alias)) {
      return false;
    }
    if (!Objects.equals(modifier, that.modifier)) {
      return false;
    }
    if (!Objects.equals(recordAttr, that.recordAttr)) {
      return false;
    }
    if (!Objects.equals(rid, that.rid)) {
      return false;
    }
    if (!Objects.equals(type, that.type)) {
      return false;
    }
    return Objects.equals(collate, that.collate);
  }

  @Override
  public int hashCode() {
    var result = alias != null ? alias.hashCode() : 0;
    result = 31 * result + (modifier != null ? modifier.hashCode() : 0);
    result = 31 * result + (recordAttr != null ? recordAttr.hashCode() : 0);
    result = 31 * result + (rid != null ? rid.hashCode() : 0);
    result = 31 * result + (type != null ? type.hashCode() : 0);
    result = 31 * result + (collate != null ? collate.hashCode() : 0);
    return result;
  }

  public SQLExpression getCollate() {
    return collate;
  }

  public void toGenericStatement(StringBuilder builder) {

    if (alias != null) {
      builder.append(alias);
      if (modifier != null) {
        modifier.toGenericStatement(builder);
      }
    } else if (recordAttr != null) {
      builder.append(recordAttr);
    } else if (rid != null) {
      rid.toGenericStatement(builder);
    }
    if (type != null) {
      builder.append(" ").append(type);
    }
    if (collate != null) {
      builder.append(" COLLATE ");
      collate.toGenericStatement(builder);
    }
  }

  public void setEdge(boolean isEdge) {
    this.isEdge = isEdge;
  }
}
