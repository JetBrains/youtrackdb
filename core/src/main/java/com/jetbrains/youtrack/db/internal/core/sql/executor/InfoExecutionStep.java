package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class InfoExecutionStep implements ExecutionStep {

  private String name;
  private String type;
  private String javaType;

  private String description;
  private long cost;
  private final List<ExecutionStep> subSteps = new ArrayList<>();

  @Override
  public @Nonnull String getName() {
    return name;
  }

  @Override
  public @Nonnull String getType() {
    return type;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Nonnull
  @Override
  public List<ExecutionStep> getSubSteps() {
    return subSteps;
  }

  @Override
  public long getCost() {
    return cost;
  }

  @Nonnull
  @Override
  public Result toResult(DatabaseSession db) {
    return null;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setType(String type) {
    this.type = type;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setCost(long cost) {
    this.cost = cost;
  }

  public String getJavaType() {
    return javaType;
  }

  public void setJavaType(String javaType) {
    this.javaType = javaType;
  }
}
