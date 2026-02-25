// Generated from GQL.g4 by ANTLR 4.13.2
package com.jetbrains.youtrackdb.internal.core.gql.parser.gen;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link GQLParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface GQLVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link GQLParser#graph_query}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGraph_query(GQLParser.Graph_queryContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#multi_linear_query_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMulti_linear_query_statement(GQLParser.Multi_linear_query_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#composite_linear_query_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitComposite_linear_query_statement(GQLParser.Composite_linear_query_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#simple_linear_query_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSimple_linear_query_statement(GQLParser.Simple_linear_query_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#primitive_query_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimitive_query_statement(GQLParser.Primitive_query_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#call_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCall_statement(GQLParser.Call_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#call_parameters}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCall_parameters(GQLParser.Call_parametersContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#filter_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFilter_statement(GQLParser.Filter_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#for_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFor_statement(GQLParser.For_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList(GQLParser.ListContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#as_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAs_statement(GQLParser.As_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#let_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLet_statement(GQLParser.Let_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#linear_graph_variable}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLinear_graph_variable(GQLParser.Linear_graph_variableContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#limit_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLimit_statement(GQLParser.Limit_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#match_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMatch_statement(GQLParser.Match_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#match_hint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMatch_hint(GQLParser.Match_hintContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#hint_key}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHint_key(GQLParser.Hint_keyContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#hint_value}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHint_value(GQLParser.Hint_valueContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#offset_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOffset_statement(GQLParser.Offset_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#skip_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSkip_statement(GQLParser.Skip_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#order_by_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrder_by_statement(GQLParser.Order_by_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#order_by_specification}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrder_by_specification(GQLParser.Order_by_specificationContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#collation_specification}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCollation_specification(GQLParser.Collation_specificationContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#return_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReturn_statement(GQLParser.Return_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#return_items}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReturn_items(GQLParser.Return_itemsContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#return_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReturn_item(GQLParser.Return_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#group_by_clause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGroup_by_clause(GQLParser.Group_by_clauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#groupable_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGroupable_item(GQLParser.Groupable_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#with_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWith_statement(GQLParser.With_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#graph_pattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGraph_pattern(GQLParser.Graph_patternContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#path_pattern_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPath_pattern_list(GQLParser.Path_pattern_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#top_level_path_pattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTop_level_path_pattern(GQLParser.Top_level_path_patternContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#path_pattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPath_pattern(GQLParser.Path_patternContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#quantifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitQuantifier(GQLParser.QuantifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#path_search_prefix}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPath_search_prefix(GQLParser.Path_search_prefixContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#path_mode}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPath_mode(GQLParser.Path_modeContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#node_pattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNode_pattern(GQLParser.Node_patternContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#edge_pattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEdge_pattern(GQLParser.Edge_patternContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#full_edge_any}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFull_edge_any(GQLParser.Full_edge_anyContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#full_edge_left}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFull_edge_left(GQLParser.Full_edge_leftContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#full_edge_right}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFull_edge_right(GQLParser.Full_edge_rightContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#abbreviated_edge_any}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAbbreviated_edge_any(GQLParser.Abbreviated_edge_anyContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#abbreviated_edge_left}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAbbreviated_edge_left(GQLParser.Abbreviated_edge_leftContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#abbreviated_edge_right}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAbbreviated_edge_right(GQLParser.Abbreviated_edge_rightContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#pattern_filler}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPattern_filler(GQLParser.Pattern_fillerContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#is_label_condition}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIs_label_condition(GQLParser.Is_label_conditionContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#label_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLabel_expression(GQLParser.Label_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#label_term}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLabel_term(GQLParser.Label_termContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#label_factor}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLabel_factor(GQLParser.Label_factorContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#label_primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLabel_primary(GQLParser.Label_primaryContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#graph_pattern_variable}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGraph_pattern_variable(GQLParser.Graph_pattern_variableContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#cost_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCost_expression(GQLParser.Cost_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#where_clause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWhere_clause(GQLParser.Where_clauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#property_filters}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_filters(GQLParser.Property_filtersContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#property_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_list(GQLParser.Property_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#property_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_assignment(GQLParser.Property_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#path_variable}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPath_variable(GQLParser.Path_variableContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#boolean_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBoolean_expression(GQLParser.Boolean_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#boolean_expression_and}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBoolean_expression_and(GQLParser.Boolean_expression_andContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#boolean_expression_inner}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBoolean_expression_inner(GQLParser.Boolean_expression_innerContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#comparison_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitComparison_expression(GQLParser.Comparison_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#value_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValue_expression(GQLParser.Value_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#list_literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_literal(GQLParser.List_literalContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#map_literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMap_literal(GQLParser.Map_literalContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#map_entry}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMap_entry(GQLParser.Map_entryContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#temporal_literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTemporal_literal(GQLParser.Temporal_literalContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#path_function}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPath_function(GQLParser.Path_functionContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#case_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCase_expression(GQLParser.Case_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#aggregate_function}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAggregate_function(GQLParser.Aggregate_functionContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#exists_predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExists_predicate(GQLParser.Exists_predicateContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#math_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMath_expression(GQLParser.Math_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#math_expression_mul}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMath_expression_mul(GQLParser.Math_expression_mulContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#math_expression_inner}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMath_expression_inner(GQLParser.Math_expression_innerContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#comparison_operator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitComparison_operator(GQLParser.Comparison_operatorContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#sub}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSub(GQLParser.SubContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#numeric_literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNumeric_literal(GQLParser.Numeric_literalContext ctx);
	/**
	 * Visit a parse tree produced by {@link GQLParser#property_reference}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_reference(GQLParser.Property_referenceContext ctx);
}