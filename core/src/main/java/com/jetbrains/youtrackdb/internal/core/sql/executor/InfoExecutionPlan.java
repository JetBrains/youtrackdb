package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Read-only representation of an execution plan used for informational display and serialization. */
public class InfoExecutionPlan implements ExecutionPlan {

  private List<ExecutionStep> steps = new ArrayList<>();
  private String prettyPrint;
  private String type;
  private String javaType;
  private Integer cost;
  private String stmText;

  @Override
  public @Nonnull List<ExecutionStep> getSteps() {
    return steps;
  }

  @Override
  public @Nonnull String prettyPrint(int depth, int indent) {
    return prettyPrint;
  }

  @Nonnull
  @Override
  public Result toResult(@Nullable DatabaseSessionEmbedded session) {
    return null;
  }

  public void setSteps(List<ExecutionStep> steps) {
    this.steps = steps;
  }

  public String getPrettyPrint() {
    return prettyPrint;
  }

  public void setPrettyPrint(String prettyPrint) {
    this.prettyPrint = prettyPrint;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getJavaType() {
    return javaType;
  }

  public void setJavaType(String javaType) {
    this.javaType = javaType;
  }

  public Integer getCost() {
    return cost;
  }

  public void setCost(Integer cost) {
    this.cost = cost;
  }

  public String getStmText() {
    return stmText;
  }

  public void setStmText(String stmText) {
    this.stmText = stmText;
  }

  @Override
  public String toString() {
    return prettyPrint;
  }
}
