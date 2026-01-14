// Generated from C:/Users/Sandra.Adamiec/IdeaProjects/youtrackdb/core/src/main/grammar/antlr/GQL.g4 by ANTLR 4.13.2
package com.jetbrains.youtrackdb.internal.core.gql.parser.gen;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link GQLParser}.
 */
public interface GQLListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link GQLParser#graph_query}.
	 * @param ctx the parse tree
	 */
	void enterGraph_query(GQLParser.Graph_queryContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#graph_query}.
	 * @param ctx the parse tree
	 */
	void exitGraph_query(GQLParser.Graph_queryContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#multi_linear_query_statement}.
	 * @param ctx the parse tree
	 */
	void enterMulti_linear_query_statement(GQLParser.Multi_linear_query_statementContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#multi_linear_query_statement}.
	 * @param ctx the parse tree
	 */
	void exitMulti_linear_query_statement(GQLParser.Multi_linear_query_statementContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#composite_linear_query_statement}.
	 * @param ctx the parse tree
	 */
	void enterComposite_linear_query_statement(GQLParser.Composite_linear_query_statementContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#composite_linear_query_statement}.
	 * @param ctx the parse tree
	 */
	void exitComposite_linear_query_statement(GQLParser.Composite_linear_query_statementContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#simple_linear_query_statement}.
	 * @param ctx the parse tree
	 */
	void enterSimple_linear_query_statement(GQLParser.Simple_linear_query_statementContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#simple_linear_query_statement}.
	 * @param ctx the parse tree
	 */
	void exitSimple_linear_query_statement(GQLParser.Simple_linear_query_statementContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#primitive_query_statement}.
	 * @param ctx the parse tree
	 */
	void enterPrimitive_query_statement(GQLParser.Primitive_query_statementContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#primitive_query_statement}.
	 * @param ctx the parse tree
	 */
	void exitPrimitive_query_statement(GQLParser.Primitive_query_statementContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#call_statment}.
	 * @param ctx the parse tree
	 */
	void enterCall_statment(GQLParser.Call_statmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#call_statment}.
	 * @param ctx the parse tree
	 */
	void exitCall_statment(GQLParser.Call_statmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#call_parameters}.
	 * @param ctx the parse tree
	 */
	void enterCall_parameters(GQLParser.Call_parametersContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#call_parameters}.
	 * @param ctx the parse tree
	 */
	void exitCall_parameters(GQLParser.Call_parametersContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#filter_statment}.
	 * @param ctx the parse tree
	 */
	void enterFilter_statment(GQLParser.Filter_statmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#filter_statment}.
	 * @param ctx the parse tree
	 */
	void exitFilter_statment(GQLParser.Filter_statmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#for_statment}.
	 * @param ctx the parse tree
	 */
	void enterFor_statment(GQLParser.For_statmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#for_statment}.
	 * @param ctx the parse tree
	 */
	void exitFor_statment(GQLParser.For_statmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#list}.
	 * @param ctx the parse tree
	 */
	void enterList(GQLParser.ListContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#list}.
	 * @param ctx the parse tree
	 */
	void exitList(GQLParser.ListContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#as_statment}.
	 * @param ctx the parse tree
	 */
	void enterAs_statment(GQLParser.As_statmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#as_statment}.
	 * @param ctx the parse tree
	 */
	void exitAs_statment(GQLParser.As_statmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#let_statmnet}.
	 * @param ctx the parse tree
	 */
	void enterLet_statmnet(GQLParser.Let_statmnetContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#let_statmnet}.
	 * @param ctx the parse tree
	 */
	void exitLet_statmnet(GQLParser.Let_statmnetContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#linear_graph_variable}.
	 * @param ctx the parse tree
	 */
	void enterLinear_graph_variable(GQLParser.Linear_graph_variableContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#linear_graph_variable}.
	 * @param ctx the parse tree
	 */
	void exitLinear_graph_variable(GQLParser.Linear_graph_variableContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#limit_statment}.
	 * @param ctx the parse tree
	 */
	void enterLimit_statment(GQLParser.Limit_statmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#limit_statment}.
	 * @param ctx the parse tree
	 */
	void exitLimit_statment(GQLParser.Limit_statmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#match_statment}.
	 * @param ctx the parse tree
	 */
	void enterMatch_statment(GQLParser.Match_statmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#match_statment}.
	 * @param ctx the parse tree
	 */
	void exitMatch_statment(GQLParser.Match_statmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#match_hint}.
	 * @param ctx the parse tree
	 */
	void enterMatch_hint(GQLParser.Match_hintContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#match_hint}.
	 * @param ctx the parse tree
	 */
	void exitMatch_hint(GQLParser.Match_hintContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#hint_key}.
	 * @param ctx the parse tree
	 */
	void enterHint_key(GQLParser.Hint_keyContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#hint_key}.
	 * @param ctx the parse tree
	 */
	void exitHint_key(GQLParser.Hint_keyContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#hint_value}.
	 * @param ctx the parse tree
	 */
	void enterHint_value(GQLParser.Hint_valueContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#hint_value}.
	 * @param ctx the parse tree
	 */
	void exitHint_value(GQLParser.Hint_valueContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#offset_statment}.
	 * @param ctx the parse tree
	 */
	void enterOffset_statment(GQLParser.Offset_statmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#offset_statment}.
	 * @param ctx the parse tree
	 */
	void exitOffset_statment(GQLParser.Offset_statmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#skip_statment}.
	 * @param ctx the parse tree
	 */
	void enterSkip_statment(GQLParser.Skip_statmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#skip_statment}.
	 * @param ctx the parse tree
	 */
	void exitSkip_statment(GQLParser.Skip_statmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#order_by_statment}.
	 * @param ctx the parse tree
	 */
	void enterOrder_by_statment(GQLParser.Order_by_statmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#order_by_statment}.
	 * @param ctx the parse tree
	 */
	void exitOrder_by_statment(GQLParser.Order_by_statmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#order_by_specification}.
	 * @param ctx the parse tree
	 */
	void enterOrder_by_specification(GQLParser.Order_by_specificationContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#order_by_specification}.
	 * @param ctx the parse tree
	 */
	void exitOrder_by_specification(GQLParser.Order_by_specificationContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#collation_specification}.
	 * @param ctx the parse tree
	 */
	void enterCollation_specification(GQLParser.Collation_specificationContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#collation_specification}.
	 * @param ctx the parse tree
	 */
	void exitCollation_specification(GQLParser.Collation_specificationContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#return_statment}.
	 * @param ctx the parse tree
	 */
	void enterReturn_statment(GQLParser.Return_statmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#return_statment}.
	 * @param ctx the parse tree
	 */
	void exitReturn_statment(GQLParser.Return_statmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#return_items}.
	 * @param ctx the parse tree
	 */
	void enterReturn_items(GQLParser.Return_itemsContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#return_items}.
	 * @param ctx the parse tree
	 */
	void exitReturn_items(GQLParser.Return_itemsContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#return_item}.
	 * @param ctx the parse tree
	 */
	void enterReturn_item(GQLParser.Return_itemContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#return_item}.
	 * @param ctx the parse tree
	 */
	void exitReturn_item(GQLParser.Return_itemContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#group_by_clause}.
	 * @param ctx the parse tree
	 */
	void enterGroup_by_clause(GQLParser.Group_by_clauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#group_by_clause}.
	 * @param ctx the parse tree
	 */
	void exitGroup_by_clause(GQLParser.Group_by_clauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#groupable_item}.
	 * @param ctx the parse tree
	 */
	void enterGroupable_item(GQLParser.Groupable_itemContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#groupable_item}.
	 * @param ctx the parse tree
	 */
	void exitGroupable_item(GQLParser.Groupable_itemContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#with_statmnet}.
	 * @param ctx the parse tree
	 */
	void enterWith_statmnet(GQLParser.With_statmnetContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#with_statmnet}.
	 * @param ctx the parse tree
	 */
	void exitWith_statmnet(GQLParser.With_statmnetContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#graph_pattern}.
	 * @param ctx the parse tree
	 */
	void enterGraph_pattern(GQLParser.Graph_patternContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#graph_pattern}.
	 * @param ctx the parse tree
	 */
	void exitGraph_pattern(GQLParser.Graph_patternContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#path_pattern_list}.
	 * @param ctx the parse tree
	 */
	void enterPath_pattern_list(GQLParser.Path_pattern_listContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#path_pattern_list}.
	 * @param ctx the parse tree
	 */
	void exitPath_pattern_list(GQLParser.Path_pattern_listContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#top_level_path_pattern}.
	 * @param ctx the parse tree
	 */
	void enterTop_level_path_pattern(GQLParser.Top_level_path_patternContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#top_level_path_pattern}.
	 * @param ctx the parse tree
	 */
	void exitTop_level_path_pattern(GQLParser.Top_level_path_patternContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#path_pattern}.
	 * @param ctx the parse tree
	 */
	void enterPath_pattern(GQLParser.Path_patternContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#path_pattern}.
	 * @param ctx the parse tree
	 */
	void exitPath_pattern(GQLParser.Path_patternContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#quantifier}.
	 * @param ctx the parse tree
	 */
	void enterQuantifier(GQLParser.QuantifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#quantifier}.
	 * @param ctx the parse tree
	 */
	void exitQuantifier(GQLParser.QuantifierContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#path_search_prefix}.
	 * @param ctx the parse tree
	 */
	void enterPath_search_prefix(GQLParser.Path_search_prefixContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#path_search_prefix}.
	 * @param ctx the parse tree
	 */
	void exitPath_search_prefix(GQLParser.Path_search_prefixContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#path_mode}.
	 * @param ctx the parse tree
	 */
	void enterPath_mode(GQLParser.Path_modeContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#path_mode}.
	 * @param ctx the parse tree
	 */
	void exitPath_mode(GQLParser.Path_modeContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#node_pattern}.
	 * @param ctx the parse tree
	 */
	void enterNode_pattern(GQLParser.Node_patternContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#node_pattern}.
	 * @param ctx the parse tree
	 */
	void exitNode_pattern(GQLParser.Node_patternContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#edge_pattern}.
	 * @param ctx the parse tree
	 */
	void enterEdge_pattern(GQLParser.Edge_patternContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#edge_pattern}.
	 * @param ctx the parse tree
	 */
	void exitEdge_pattern(GQLParser.Edge_patternContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#full_edge_any}.
	 * @param ctx the parse tree
	 */
	void enterFull_edge_any(GQLParser.Full_edge_anyContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#full_edge_any}.
	 * @param ctx the parse tree
	 */
	void exitFull_edge_any(GQLParser.Full_edge_anyContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#full_edge_left}.
	 * @param ctx the parse tree
	 */
	void enterFull_edge_left(GQLParser.Full_edge_leftContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#full_edge_left}.
	 * @param ctx the parse tree
	 */
	void exitFull_edge_left(GQLParser.Full_edge_leftContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#full_edge_right}.
	 * @param ctx the parse tree
	 */
	void enterFull_edge_right(GQLParser.Full_edge_rightContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#full_edge_right}.
	 * @param ctx the parse tree
	 */
	void exitFull_edge_right(GQLParser.Full_edge_rightContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#abbreviated_edge_any}.
	 * @param ctx the parse tree
	 */
	void enterAbbreviated_edge_any(GQLParser.Abbreviated_edge_anyContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#abbreviated_edge_any}.
	 * @param ctx the parse tree
	 */
	void exitAbbreviated_edge_any(GQLParser.Abbreviated_edge_anyContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#abbreviated_edge_left}.
	 * @param ctx the parse tree
	 */
	void enterAbbreviated_edge_left(GQLParser.Abbreviated_edge_leftContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#abbreviated_edge_left}.
	 * @param ctx the parse tree
	 */
	void exitAbbreviated_edge_left(GQLParser.Abbreviated_edge_leftContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#abbreviated_edge_right}.
	 * @param ctx the parse tree
	 */
	void enterAbbreviated_edge_right(GQLParser.Abbreviated_edge_rightContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#abbreviated_edge_right}.
	 * @param ctx the parse tree
	 */
	void exitAbbreviated_edge_right(GQLParser.Abbreviated_edge_rightContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#pattern_filler}.
	 * @param ctx the parse tree
	 */
	void enterPattern_filler(GQLParser.Pattern_fillerContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#pattern_filler}.
	 * @param ctx the parse tree
	 */
	void exitPattern_filler(GQLParser.Pattern_fillerContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#is_label_condition}.
	 * @param ctx the parse tree
	 */
	void enterIs_label_condition(GQLParser.Is_label_conditionContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#is_label_condition}.
	 * @param ctx the parse tree
	 */
	void exitIs_label_condition(GQLParser.Is_label_conditionContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#label_expression}.
	 * @param ctx the parse tree
	 */
	void enterLabel_expression(GQLParser.Label_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#label_expression}.
	 * @param ctx the parse tree
	 */
	void exitLabel_expression(GQLParser.Label_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#label_term}.
	 * @param ctx the parse tree
	 */
	void enterLabel_term(GQLParser.Label_termContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#label_term}.
	 * @param ctx the parse tree
	 */
	void exitLabel_term(GQLParser.Label_termContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#label_factor}.
	 * @param ctx the parse tree
	 */
	void enterLabel_factor(GQLParser.Label_factorContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#label_factor}.
	 * @param ctx the parse tree
	 */
	void exitLabel_factor(GQLParser.Label_factorContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#label_primary}.
	 * @param ctx the parse tree
	 */
	void enterLabel_primary(GQLParser.Label_primaryContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#label_primary}.
	 * @param ctx the parse tree
	 */
	void exitLabel_primary(GQLParser.Label_primaryContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#graph_pattern_variable}.
	 * @param ctx the parse tree
	 */
	void enterGraph_pattern_variable(GQLParser.Graph_pattern_variableContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#graph_pattern_variable}.
	 * @param ctx the parse tree
	 */
	void exitGraph_pattern_variable(GQLParser.Graph_pattern_variableContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#cost_expression}.
	 * @param ctx the parse tree
	 */
	void enterCost_expression(GQLParser.Cost_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#cost_expression}.
	 * @param ctx the parse tree
	 */
	void exitCost_expression(GQLParser.Cost_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#where_clause}.
	 * @param ctx the parse tree
	 */
	void enterWhere_clause(GQLParser.Where_clauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#where_clause}.
	 * @param ctx the parse tree
	 */
	void exitWhere_clause(GQLParser.Where_clauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#property_filters}.
	 * @param ctx the parse tree
	 */
	void enterProperty_filters(GQLParser.Property_filtersContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#property_filters}.
	 * @param ctx the parse tree
	 */
	void exitProperty_filters(GQLParser.Property_filtersContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#property_list}.
	 * @param ctx the parse tree
	 */
	void enterProperty_list(GQLParser.Property_listContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#property_list}.
	 * @param ctx the parse tree
	 */
	void exitProperty_list(GQLParser.Property_listContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#property_assignment}.
	 * @param ctx the parse tree
	 */
	void enterProperty_assignment(GQLParser.Property_assignmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#property_assignment}.
	 * @param ctx the parse tree
	 */
	void exitProperty_assignment(GQLParser.Property_assignmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#path_variable}.
	 * @param ctx the parse tree
	 */
	void enterPath_variable(GQLParser.Path_variableContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#path_variable}.
	 * @param ctx the parse tree
	 */
	void exitPath_variable(GQLParser.Path_variableContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#boolean_expression}.
	 * @param ctx the parse tree
	 */
	void enterBoolean_expression(GQLParser.Boolean_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#boolean_expression}.
	 * @param ctx the parse tree
	 */
	void exitBoolean_expression(GQLParser.Boolean_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#boolean_expression_and}.
	 * @param ctx the parse tree
	 */
	void enterBoolean_expression_and(GQLParser.Boolean_expression_andContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#boolean_expression_and}.
	 * @param ctx the parse tree
	 */
	void exitBoolean_expression_and(GQLParser.Boolean_expression_andContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#boolean_expression_inner}.
	 * @param ctx the parse tree
	 */
	void enterBoolean_expression_inner(GQLParser.Boolean_expression_innerContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#boolean_expression_inner}.
	 * @param ctx the parse tree
	 */
	void exitBoolean_expression_inner(GQLParser.Boolean_expression_innerContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#comparison_expression}.
	 * @param ctx the parse tree
	 */
	void enterComparison_expression(GQLParser.Comparison_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#comparison_expression}.
	 * @param ctx the parse tree
	 */
	void exitComparison_expression(GQLParser.Comparison_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#value_expression}.
	 * @param ctx the parse tree
	 */
	void enterValue_expression(GQLParser.Value_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#value_expression}.
	 * @param ctx the parse tree
	 */
	void exitValue_expression(GQLParser.Value_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#list_literal}.
	 * @param ctx the parse tree
	 */
	void enterList_literal(GQLParser.List_literalContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#list_literal}.
	 * @param ctx the parse tree
	 */
	void exitList_literal(GQLParser.List_literalContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#map_literal}.
	 * @param ctx the parse tree
	 */
	void enterMap_literal(GQLParser.Map_literalContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#map_literal}.
	 * @param ctx the parse tree
	 */
	void exitMap_literal(GQLParser.Map_literalContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#map_entry}.
	 * @param ctx the parse tree
	 */
	void enterMap_entry(GQLParser.Map_entryContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#map_entry}.
	 * @param ctx the parse tree
	 */
	void exitMap_entry(GQLParser.Map_entryContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#temporal_literal}.
	 * @param ctx the parse tree
	 */
	void enterTemporal_literal(GQLParser.Temporal_literalContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#temporal_literal}.
	 * @param ctx the parse tree
	 */
	void exitTemporal_literal(GQLParser.Temporal_literalContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#path_function}.
	 * @param ctx the parse tree
	 */
	void enterPath_function(GQLParser.Path_functionContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#path_function}.
	 * @param ctx the parse tree
	 */
	void exitPath_function(GQLParser.Path_functionContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#case_expression}.
	 * @param ctx the parse tree
	 */
	void enterCase_expression(GQLParser.Case_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#case_expression}.
	 * @param ctx the parse tree
	 */
	void exitCase_expression(GQLParser.Case_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#aggregate_function}.
	 * @param ctx the parse tree
	 */
	void enterAggregate_function(GQLParser.Aggregate_functionContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#aggregate_function}.
	 * @param ctx the parse tree
	 */
	void exitAggregate_function(GQLParser.Aggregate_functionContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#exists_predicate}.
	 * @param ctx the parse tree
	 */
	void enterExists_predicate(GQLParser.Exists_predicateContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#exists_predicate}.
	 * @param ctx the parse tree
	 */
	void exitExists_predicate(GQLParser.Exists_predicateContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#math_expression}.
	 * @param ctx the parse tree
	 */
	void enterMath_expression(GQLParser.Math_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#math_expression}.
	 * @param ctx the parse tree
	 */
	void exitMath_expression(GQLParser.Math_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#math_expression_mul}.
	 * @param ctx the parse tree
	 */
	void enterMath_expression_mul(GQLParser.Math_expression_mulContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#math_expression_mul}.
	 * @param ctx the parse tree
	 */
	void exitMath_expression_mul(GQLParser.Math_expression_mulContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#math_expression_inner}.
	 * @param ctx the parse tree
	 */
	void enterMath_expression_inner(GQLParser.Math_expression_innerContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#math_expression_inner}.
	 * @param ctx the parse tree
	 */
	void exitMath_expression_inner(GQLParser.Math_expression_innerContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#comparison_operator}.
	 * @param ctx the parse tree
	 */
	void enterComparison_operator(GQLParser.Comparison_operatorContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#comparison_operator}.
	 * @param ctx the parse tree
	 */
	void exitComparison_operator(GQLParser.Comparison_operatorContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#sub}.
	 * @param ctx the parse tree
	 */
	void enterSub(GQLParser.SubContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#sub}.
	 * @param ctx the parse tree
	 */
	void exitSub(GQLParser.SubContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#numeric_literal}.
	 * @param ctx the parse tree
	 */
	void enterNumeric_literal(GQLParser.Numeric_literalContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#numeric_literal}.
	 * @param ctx the parse tree
	 */
	void exitNumeric_literal(GQLParser.Numeric_literalContext ctx);
	/**
	 * Enter a parse tree produced by {@link GQLParser#property_reference}.
	 * @param ctx the parse tree
	 */
	void enterProperty_reference(GQLParser.Property_referenceContext ctx);
	/**
	 * Exit a parse tree produced by {@link GQLParser#property_reference}.
	 * @param ctx the parse tree
	 */
	void exitProperty_reference(GQLParser.Property_referenceContext ctx);
}