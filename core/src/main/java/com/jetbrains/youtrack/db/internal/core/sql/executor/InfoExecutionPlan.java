package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
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
  public Result toResult(@Nullable DatabaseSession session) {
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
