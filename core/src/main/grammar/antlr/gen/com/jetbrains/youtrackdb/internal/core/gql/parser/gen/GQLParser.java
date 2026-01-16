// Generated from C:/Users/Sandra.Adamiec/IdeaProjects/youtrackdb/core/src/main/grammar/antlr/GQL.g4 by ANTLR 4.13.2
package com.jetbrains.youtrackdb.internal.core.gql.parser.gen;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class GQLParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		T__0=1, T__1=2, T__2=3, T__3=4, T__4=5, T__5=6, T__6=7, T__7=8, T__8=9, 
		T__9=10, T__10=11, T__11=12, MATCH=13, CALL=14, FILTER=15, FOR=16, LET=17, 
		LIMIT=18, NEXT=19, RETURN=20, SKIP_TOKEN=21, WITH=22, OFFSET=23, ORDER=24, 
		BY=25, GROUP=26, OPTIONAL=27, AS=28, WHERE=29, OR=30, AND=31, NOT=32, 
		IN=33, NULL_TOKEN=34, ALL=35, ANY=36, SHORTEST=37, CHEAPEST=38, WALK=39, 
		ACYCLIC=40, TRAIL=41, PATH=42, PATHS=43, COST=44, IS=45, COLLATE=46, ASC=47, 
		ASCENDING=48, DESC=49, DESCENDING=50, DISTINCT=51, CASE=52, ELSE=53, WHEN=54, 
		THEN=55, END=56, COUNT=57, SUM=58, AVG=59, MIN=60, MAX=61, COLLECT=62, 
		EXISTS=63, UNION=64, INTERSECT=65, EXCEPT=66, DATE=67, TIME=68, TIMESTAMP=69, 
		DURATION=70, NODES=71, EDGES=72, LENGTH=73, LABELS=74, ARROW_RIGHT=75, 
		ARROW_LEFT=76, NEQ=77, GTE=78, LTE=79, GT=80, LT=81, EQ=82, ADD=83, MUL=84, 
		DIV=85, MOD=86, BOOL=87, DOT=88, ID=89, NUMBER=90, INT=91, STRING=92, 
		DASH=93, WS=94;
	public static final int
		RULE_graph_query = 0, RULE_multi_linear_query_statement = 1, RULE_composite_linear_query_statement = 2, 
		RULE_simple_linear_query_statement = 3, RULE_primitive_query_statement = 4, 
		RULE_call_statment = 5, RULE_call_parameters = 6, RULE_filter_statment = 7, 
		RULE_for_statment = 8, RULE_list = 9, RULE_as_statment = 10, RULE_let_statmnet = 11, 
		RULE_linear_graph_variable = 12, RULE_limit_statment = 13, RULE_match_statment = 14, 
		RULE_match_hint = 15, RULE_hint_key = 16, RULE_hint_value = 17, RULE_offset_statment = 18, 
		RULE_skip_statment = 19, RULE_order_by_statment = 20, RULE_order_by_specification = 21, 
		RULE_collation_specification = 22, RULE_return_statment = 23, RULE_return_items = 24, 
		RULE_return_item = 25, RULE_group_by_clause = 26, RULE_groupable_item = 27, 
		RULE_with_statmnet = 28, RULE_graph_pattern = 29, RULE_path_pattern_list = 30, 
		RULE_top_level_path_pattern = 31, RULE_path_pattern = 32, RULE_quantifier = 33, 
		RULE_path_search_prefix = 34, RULE_path_mode = 35, RULE_node_pattern = 36, 
		RULE_edge_pattern = 37, RULE_full_edge_any = 38, RULE_full_edge_left = 39, 
		RULE_full_edge_right = 40, RULE_abbreviated_edge_any = 41, RULE_abbreviated_edge_left = 42, 
		RULE_abbreviated_edge_right = 43, RULE_pattern_filler = 44, RULE_is_label_condition = 45, 
		RULE_label_expression = 46, RULE_label_term = 47, RULE_label_factor = 48, 
		RULE_label_primary = 49, RULE_graph_pattern_variable = 50, RULE_cost_expression = 51, 
		RULE_where_clause = 52, RULE_property_filters = 53, RULE_property_list = 54, 
		RULE_property_assignment = 55, RULE_path_variable = 56, RULE_boolean_expression = 57, 
		RULE_boolean_expression_and = 58, RULE_boolean_expression_inner = 59, 
		RULE_comparison_expression = 60, RULE_value_expression = 61, RULE_list_literal = 62, 
		RULE_map_literal = 63, RULE_map_entry = 64, RULE_temporal_literal = 65, 
		RULE_path_function = 66, RULE_case_expression = 67, RULE_aggregate_function = 68, 
		RULE_exists_predicate = 69, RULE_math_expression = 70, RULE_math_expression_mul = 71, 
		RULE_math_expression_inner = 72, RULE_comparison_operator = 73, RULE_sub = 74, 
		RULE_numeric_literal = 75, RULE_property_reference = 76;
	private static String[] makeRuleNames() {
		return new String[] {
			"graph_query", "multi_linear_query_statement", "composite_linear_query_statement", 
			"simple_linear_query_statement", "primitive_query_statement", "call_statment", 
			"call_parameters", "filter_statment", "for_statment", "list", "as_statment", 
			"let_statmnet", "linear_graph_variable", "limit_statment", "match_statment", 
			"match_hint", "hint_key", "hint_value", "offset_statment", "skip_statment", 
			"order_by_statment", "order_by_specification", "collation_specification", 
			"return_statment", "return_items", "return_item", "group_by_clause", 
			"groupable_item", "with_statmnet", "graph_pattern", "path_pattern_list", 
			"top_level_path_pattern", "path_pattern", "quantifier", "path_search_prefix", 
			"path_mode", "node_pattern", "edge_pattern", "full_edge_any", "full_edge_left", 
			"full_edge_right", "abbreviated_edge_any", "abbreviated_edge_left", "abbreviated_edge_right", 
			"pattern_filler", "is_label_condition", "label_expression", "label_term", 
			"label_factor", "label_primary", "graph_pattern_variable", "cost_expression", 
			"where_clause", "property_filters", "property_list", "property_assignment", 
			"path_variable", "boolean_expression", "boolean_expression_and", "boolean_expression_inner", 
			"comparison_expression", "value_expression", "list_literal", "map_literal", 
			"map_entry", "temporal_literal", "path_function", "case_expression", 
			"aggregate_function", "exists_predicate", "math_expression", "math_expression_mul", 
			"math_expression_inner", "comparison_operator", "sub", "numeric_literal", 
			"property_reference"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'('", "')'", "'{'", "'}'", "','", "'@{'", "'['", "']'", "':'", 
			"'|'", "'&'", "'!'", null, null, null, null, null, null, null, null, 
			null, null, null, null, null, null, null, null, null, null, null, null, 
			null, null, null, null, null, null, null, null, null, null, null, null, 
			null, null, null, null, null, null, null, null, null, null, null, null, 
			null, null, null, null, null, null, null, null, null, null, null, null, 
			null, null, null, null, null, null, "'->'", "'<-'", "'!='", "'>='", "'<='", 
			"'>'", "'<'", "'='", "'+'", "'*'", "'/'", "'%'", null, "'.'", null, null, 
			null, null, "'-'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, null, null, null, null, null, null, null, null, null, null, null, 
			null, "MATCH", "CALL", "FILTER", "FOR", "LET", "LIMIT", "NEXT", "RETURN", 
			"SKIP_TOKEN", "WITH", "OFFSET", "ORDER", "BY", "GROUP", "OPTIONAL", "AS", 
			"WHERE", "OR", "AND", "NOT", "IN", "NULL_TOKEN", "ALL", "ANY", "SHORTEST", 
			"CHEAPEST", "WALK", "ACYCLIC", "TRAIL", "PATH", "PATHS", "COST", "IS", 
			"COLLATE", "ASC", "ASCENDING", "DESC", "DESCENDING", "DISTINCT", "CASE", 
			"ELSE", "WHEN", "THEN", "END", "COUNT", "SUM", "AVG", "MIN", "MAX", "COLLECT", 
			"EXISTS", "UNION", "INTERSECT", "EXCEPT", "DATE", "TIME", "TIMESTAMP", 
			"DURATION", "NODES", "EDGES", "LENGTH", "LABELS", "ARROW_RIGHT", "ARROW_LEFT", 
			"NEQ", "GTE", "LTE", "GT", "LT", "EQ", "ADD", "MUL", "DIV", "MOD", "BOOL", 
			"DOT", "ID", "NUMBER", "INT", "STRING", "DASH", "WS"
		};
	}
	private static final String[] _SYMBOLIC_NAMES = makeSymbolicNames();
	public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

	/**
	 * @deprecated Use {@link #VOCABULARY} instead.
	 */
	@Deprecated
	public static final String[] tokenNames;
	static {
		tokenNames = new String[_SYMBOLIC_NAMES.length];
		for (int i = 0; i < tokenNames.length; i++) {
			tokenNames[i] = VOCABULARY.getLiteralName(i);
			if (tokenNames[i] == null) {
				tokenNames[i] = VOCABULARY.getSymbolicName(i);
			}

			if (tokenNames[i] == null) {
				tokenNames[i] = "<INVALID>";
			}
		}
	}

	@Override
	@Deprecated
	public String[] getTokenNames() {
		return tokenNames;
	}

	@Override

	public Vocabulary getVocabulary() {
		return VOCABULARY;
	}

	@Override
	public String getGrammarFileName() { return "GQL.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public GQLParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Graph_queryContext extends ParserRuleContext {
		public Multi_linear_query_statementContext multi_linear_query_statement() {
			return getRuleContext(Multi_linear_query_statementContext.class,0);
		}
		public TerminalNode EOF() { return getToken(GQLParser.EOF, 0); }
		public Graph_queryContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_graph_query; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterGraph_query(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitGraph_query(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitGraph_query(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Graph_queryContext graph_query() throws RecognitionException {
		Graph_queryContext _localctx = new Graph_queryContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_graph_query);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(154);
			multi_linear_query_statement();
			setState(155);
			match(EOF);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Multi_linear_query_statementContext extends ParserRuleContext {
		public List<Composite_linear_query_statementContext> composite_linear_query_statement() {
			return getRuleContexts(Composite_linear_query_statementContext.class);
		}
		public Composite_linear_query_statementContext composite_linear_query_statement(int i) {
			return getRuleContext(Composite_linear_query_statementContext.class,i);
		}
		public List<TerminalNode> NEXT() { return getTokens(GQLParser.NEXT); }
		public TerminalNode NEXT(int i) {
			return getToken(GQLParser.NEXT, i);
		}
		public Multi_linear_query_statementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_multi_linear_query_statement; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterMulti_linear_query_statement(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitMulti_linear_query_statement(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitMulti_linear_query_statement(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Multi_linear_query_statementContext multi_linear_query_statement() throws RecognitionException {
		Multi_linear_query_statementContext _localctx = new Multi_linear_query_statementContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_multi_linear_query_statement);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(157);
			composite_linear_query_statement();
			setState(162);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==NEXT) {
				{
				{
				setState(158);
				match(NEXT);
				setState(159);
				composite_linear_query_statement();
				}
				}
				setState(164);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Composite_linear_query_statementContext extends ParserRuleContext {
		public List<Simple_linear_query_statementContext> simple_linear_query_statement() {
			return getRuleContexts(Simple_linear_query_statementContext.class);
		}
		public Simple_linear_query_statementContext simple_linear_query_statement(int i) {
			return getRuleContext(Simple_linear_query_statementContext.class,i);
		}
		public List<TerminalNode> UNION() { return getTokens(GQLParser.UNION); }
		public TerminalNode UNION(int i) {
			return getToken(GQLParser.UNION, i);
		}
		public List<TerminalNode> INTERSECT() { return getTokens(GQLParser.INTERSECT); }
		public TerminalNode INTERSECT(int i) {
			return getToken(GQLParser.INTERSECT, i);
		}
		public List<TerminalNode> EXCEPT() { return getTokens(GQLParser.EXCEPT); }
		public TerminalNode EXCEPT(int i) {
			return getToken(GQLParser.EXCEPT, i);
		}
		public List<TerminalNode> ALL() { return getTokens(GQLParser.ALL); }
		public TerminalNode ALL(int i) {
			return getToken(GQLParser.ALL, i);
		}
		public List<TerminalNode> DISTINCT() { return getTokens(GQLParser.DISTINCT); }
		public TerminalNode DISTINCT(int i) {
			return getToken(GQLParser.DISTINCT, i);
		}
		public Composite_linear_query_statementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_composite_linear_query_statement; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterComposite_linear_query_statement(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitComposite_linear_query_statement(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitComposite_linear_query_statement(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Composite_linear_query_statementContext composite_linear_query_statement() throws RecognitionException {
		Composite_linear_query_statementContext _localctx = new Composite_linear_query_statementContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_composite_linear_query_statement);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(165);
			simple_linear_query_statement();
			setState(173);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (((((_la - 64)) & ~0x3f) == 0 && ((1L << (_la - 64)) & 7L) != 0)) {
				{
				{
				setState(166);
				_la = _input.LA(1);
				if ( !(((((_la - 64)) & ~0x3f) == 0 && ((1L << (_la - 64)) & 7L) != 0)) ) {
				_errHandler.recoverInline(this);
				}
				else {
					if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
					_errHandler.reportMatch(this);
					consume();
				}
				setState(168);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==ALL || _la==DISTINCT) {
					{
					setState(167);
					_la = _input.LA(1);
					if ( !(_la==ALL || _la==DISTINCT) ) {
					_errHandler.recoverInline(this);
					}
					else {
						if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
						_errHandler.reportMatch(this);
						consume();
					}
					}
				}

				setState(170);
				simple_linear_query_statement();
				}
				}
				setState(175);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Simple_linear_query_statementContext extends ParserRuleContext {
		public List<Primitive_query_statementContext> primitive_query_statement() {
			return getRuleContexts(Primitive_query_statementContext.class);
		}
		public Primitive_query_statementContext primitive_query_statement(int i) {
			return getRuleContext(Primitive_query_statementContext.class,i);
		}
		public Return_statmentContext return_statment() {
			return getRuleContext(Return_statmentContext.class,0);
		}
		public Simple_linear_query_statementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_simple_linear_query_statement; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterSimple_linear_query_statement(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitSimple_linear_query_statement(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitSimple_linear_query_statement(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Simple_linear_query_statementContext simple_linear_query_statement() throws RecognitionException {
		Simple_linear_query_statementContext _localctx = new Simple_linear_query_statementContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_simple_linear_query_statement);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(179);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & 166191104L) != 0)) {
				{
				{
				setState(176);
				primitive_query_statement();
				}
				}
				setState(181);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(183);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==RETURN) {
				{
				setState(182);
				return_statment();
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Primitive_query_statementContext extends ParserRuleContext {
		public Call_statmentContext call_statment() {
			return getRuleContext(Call_statmentContext.class,0);
		}
		public Filter_statmentContext filter_statment() {
			return getRuleContext(Filter_statmentContext.class,0);
		}
		public For_statmentContext for_statment() {
			return getRuleContext(For_statmentContext.class,0);
		}
		public Let_statmnetContext let_statmnet() {
			return getRuleContext(Let_statmnetContext.class,0);
		}
		public Limit_statmentContext limit_statment() {
			return getRuleContext(Limit_statmentContext.class,0);
		}
		public Match_statmentContext match_statment() {
			return getRuleContext(Match_statmentContext.class,0);
		}
		public Offset_statmentContext offset_statment() {
			return getRuleContext(Offset_statmentContext.class,0);
		}
		public Order_by_statmentContext order_by_statment() {
			return getRuleContext(Order_by_statmentContext.class,0);
		}
		public Skip_statmentContext skip_statment() {
			return getRuleContext(Skip_statmentContext.class,0);
		}
		public With_statmnetContext with_statmnet() {
			return getRuleContext(With_statmnetContext.class,0);
		}
		public Primitive_query_statementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_primitive_query_statement; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterPrimitive_query_statement(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitPrimitive_query_statement(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitPrimitive_query_statement(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Primitive_query_statementContext primitive_query_statement() throws RecognitionException {
		Primitive_query_statementContext _localctx = new Primitive_query_statementContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_primitive_query_statement);
		try {
			setState(195);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,5,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(185);
				call_statment();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(186);
				filter_statment();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(187);
				for_statment();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(188);
				let_statmnet();
				}
				break;
			case 5:
				enterOuterAlt(_localctx, 5);
				{
				setState(189);
				limit_statment();
				}
				break;
			case 6:
				enterOuterAlt(_localctx, 6);
				{
				setState(190);
				match_statment();
				}
				break;
			case 7:
				enterOuterAlt(_localctx, 7);
				{
				setState(191);
				offset_statment();
				}
				break;
			case 8:
				enterOuterAlt(_localctx, 8);
				{
				setState(192);
				order_by_statment();
				}
				break;
			case 9:
				enterOuterAlt(_localctx, 9);
				{
				setState(193);
				skip_statment();
				}
				break;
			case 10:
				enterOuterAlt(_localctx, 10);
				{
				setState(194);
				with_statmnet();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Call_statmentContext extends ParserRuleContext {
		public TerminalNode CALL() { return getToken(GQLParser.CALL, 0); }
		public Call_parametersContext call_parameters() {
			return getRuleContext(Call_parametersContext.class,0);
		}
		public Graph_queryContext graph_query() {
			return getRuleContext(Graph_queryContext.class,0);
		}
		public TerminalNode OPTIONAL() { return getToken(GQLParser.OPTIONAL, 0); }
		public Call_statmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_call_statment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterCall_statment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitCall_statment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitCall_statment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Call_statmentContext call_statment() throws RecognitionException {
		Call_statmentContext _localctx = new Call_statmentContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_call_statment);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(198);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==OPTIONAL) {
				{
				setState(197);
				match(OPTIONAL);
				}
			}

			setState(200);
			match(CALL);
			setState(201);
			match(T__0);
			setState(202);
			call_parameters();
			setState(203);
			match(T__1);
			setState(204);
			match(T__2);
			setState(205);
			graph_query();
			setState(206);
			match(T__3);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Call_parametersContext extends ParserRuleContext {
		public List<TerminalNode> ID() { return getTokens(GQLParser.ID); }
		public TerminalNode ID(int i) {
			return getToken(GQLParser.ID, i);
		}
		public Call_parametersContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_call_parameters; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterCall_parameters(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitCall_parameters(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitCall_parameters(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Call_parametersContext call_parameters() throws RecognitionException {
		Call_parametersContext _localctx = new Call_parametersContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_call_parameters);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(216);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ID) {
				{
				setState(208);
				match(ID);
				setState(213);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==T__4) {
					{
					{
					setState(209);
					match(T__4);
					setState(210);
					match(ID);
					}
					}
					setState(215);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Filter_statmentContext extends ParserRuleContext {
		public TerminalNode FILTER() { return getToken(GQLParser.FILTER, 0); }
		public Boolean_expressionContext boolean_expression() {
			return getRuleContext(Boolean_expressionContext.class,0);
		}
		public TerminalNode WHERE() { return getToken(GQLParser.WHERE, 0); }
		public Filter_statmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_filter_statment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterFilter_statment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitFilter_statment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitFilter_statment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Filter_statmentContext filter_statment() throws RecognitionException {
		Filter_statmentContext _localctx = new Filter_statmentContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_filter_statment);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(218);
			match(FILTER);
			setState(220);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==WHERE) {
				{
				setState(219);
				match(WHERE);
				}
			}

			setState(222);
			boolean_expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class For_statmentContext extends ParserRuleContext {
		public TerminalNode FOR() { return getToken(GQLParser.FOR, 0); }
		public TerminalNode STRING() { return getToken(GQLParser.STRING, 0); }
		public TerminalNode IN() { return getToken(GQLParser.IN, 0); }
		public ListContext list() {
			return getRuleContext(ListContext.class,0);
		}
		public TerminalNode WITH() { return getToken(GQLParser.WITH, 0); }
		public TerminalNode OFFSET() { return getToken(GQLParser.OFFSET, 0); }
		public As_statmentContext as_statment() {
			return getRuleContext(As_statmentContext.class,0);
		}
		public For_statmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_for_statment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterFor_statment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitFor_statment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitFor_statment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final For_statmentContext for_statment() throws RecognitionException {
		For_statmentContext _localctx = new For_statmentContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_for_statment);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(224);
			match(FOR);
			setState(225);
			match(STRING);
			setState(226);
			match(IN);
			setState(227);
			list();
			setState(233);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,11,_ctx) ) {
			case 1:
				{
				setState(228);
				match(WITH);
				setState(229);
				match(OFFSET);
				setState(231);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==AS) {
					{
					setState(230);
					as_statment();
					}
				}

				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ListContext extends ParserRuleContext {
		public List_literalContext list_literal() {
			return getRuleContext(List_literalContext.class,0);
		}
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public Property_referenceContext property_reference() {
			return getRuleContext(Property_referenceContext.class,0);
		}
		public TerminalNode NULL_TOKEN() { return getToken(GQLParser.NULL_TOKEN, 0); }
		public ListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_list; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterList(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitList(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitList(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ListContext list() throws RecognitionException {
		ListContext _localctx = new ListContext(_ctx, getState());
		enterRule(_localctx, 18, RULE_list);
		try {
			setState(239);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,12,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(235);
				list_literal();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(236);
				match(ID);
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(237);
				property_reference();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(238);
				match(NULL_TOKEN);
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class As_statmentContext extends ParserRuleContext {
		public TerminalNode AS() { return getToken(GQLParser.AS, 0); }
		public TerminalNode STRING() { return getToken(GQLParser.STRING, 0); }
		public As_statmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_as_statment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterAs_statment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitAs_statment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitAs_statment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final As_statmentContext as_statment() throws RecognitionException {
		As_statmentContext _localctx = new As_statmentContext(_ctx, getState());
		enterRule(_localctx, 20, RULE_as_statment);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(241);
			match(AS);
			setState(242);
			match(STRING);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Let_statmnetContext extends ParserRuleContext {
		public TerminalNode LET() { return getToken(GQLParser.LET, 0); }
		public List<Linear_graph_variableContext> linear_graph_variable() {
			return getRuleContexts(Linear_graph_variableContext.class);
		}
		public Linear_graph_variableContext linear_graph_variable(int i) {
			return getRuleContext(Linear_graph_variableContext.class,i);
		}
		public Let_statmnetContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_let_statmnet; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterLet_statmnet(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitLet_statmnet(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitLet_statmnet(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Let_statmnetContext let_statmnet() throws RecognitionException {
		Let_statmnetContext _localctx = new Let_statmnetContext(_ctx, getState());
		enterRule(_localctx, 22, RULE_let_statmnet);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(244);
			match(LET);
			setState(245);
			linear_graph_variable();
			setState(250);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__4) {
				{
				{
				setState(246);
				match(T__4);
				setState(247);
				linear_graph_variable();
				}
				}
				setState(252);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Linear_graph_variableContext extends ParserRuleContext {
		public TerminalNode STRING() { return getToken(GQLParser.STRING, 0); }
		public TerminalNode EQ() { return getToken(GQLParser.EQ, 0); }
		public Value_expressionContext value_expression() {
			return getRuleContext(Value_expressionContext.class,0);
		}
		public Linear_graph_variableContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_linear_graph_variable; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterLinear_graph_variable(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitLinear_graph_variable(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitLinear_graph_variable(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Linear_graph_variableContext linear_graph_variable() throws RecognitionException {
		Linear_graph_variableContext _localctx = new Linear_graph_variableContext(_ctx, getState());
		enterRule(_localctx, 24, RULE_linear_graph_variable);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(253);
			match(STRING);
			setState(254);
			match(EQ);
			setState(255);
			value_expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Limit_statmentContext extends ParserRuleContext {
		public TerminalNode LIMIT() { return getToken(GQLParser.LIMIT, 0); }
		public TerminalNode INT() { return getToken(GQLParser.INT, 0); }
		public Limit_statmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_limit_statment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterLimit_statment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitLimit_statment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitLimit_statment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Limit_statmentContext limit_statment() throws RecognitionException {
		Limit_statmentContext _localctx = new Limit_statmentContext(_ctx, getState());
		enterRule(_localctx, 26, RULE_limit_statment);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(257);
			match(LIMIT);
			setState(258);
			match(INT);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Match_statmentContext extends ParserRuleContext {
		public TerminalNode MATCH() { return getToken(GQLParser.MATCH, 0); }
		public Graph_patternContext graph_pattern() {
			return getRuleContext(Graph_patternContext.class,0);
		}
		public TerminalNode OPTIONAL() { return getToken(GQLParser.OPTIONAL, 0); }
		public Match_hintContext match_hint() {
			return getRuleContext(Match_hintContext.class,0);
		}
		public Match_statmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_match_statment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterMatch_statment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitMatch_statment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitMatch_statment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Match_statmentContext match_statment() throws RecognitionException {
		Match_statmentContext _localctx = new Match_statmentContext(_ctx, getState());
		enterRule(_localctx, 28, RULE_match_statment);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(261);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==OPTIONAL) {
				{
				setState(260);
				match(OPTIONAL);
				}
			}

			setState(263);
			match(MATCH);
			setState(265);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==T__5) {
				{
				setState(264);
				match_hint();
				}
			}

			setState(267);
			graph_pattern();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Match_hintContext extends ParserRuleContext {
		public Hint_keyContext hint_key() {
			return getRuleContext(Hint_keyContext.class,0);
		}
		public TerminalNode EQ() { return getToken(GQLParser.EQ, 0); }
		public Hint_valueContext hint_value() {
			return getRuleContext(Hint_valueContext.class,0);
		}
		public Match_hintContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_match_hint; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterMatch_hint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitMatch_hint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitMatch_hint(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Match_hintContext match_hint() throws RecognitionException {
		Match_hintContext _localctx = new Match_hintContext(_ctx, getState());
		enterRule(_localctx, 30, RULE_match_hint);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(269);
			match(T__5);
			setState(270);
			hint_key();
			setState(271);
			match(EQ);
			setState(272);
			hint_value();
			setState(273);
			match(T__3);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Hint_keyContext extends ParserRuleContext {
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public Hint_keyContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_hint_key; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterHint_key(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitHint_key(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitHint_key(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Hint_keyContext hint_key() throws RecognitionException {
		Hint_keyContext _localctx = new Hint_keyContext(_ctx, getState());
		enterRule(_localctx, 32, RULE_hint_key);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(275);
			match(ID);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Hint_valueContext extends ParserRuleContext {
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public TerminalNode STRING() { return getToken(GQLParser.STRING, 0); }
		public Math_expressionContext math_expression() {
			return getRuleContext(Math_expressionContext.class,0);
		}
		public TerminalNode BOOL() { return getToken(GQLParser.BOOL, 0); }
		public Hint_valueContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_hint_value; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterHint_value(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitHint_value(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitHint_value(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Hint_valueContext hint_value() throws RecognitionException {
		Hint_valueContext _localctx = new Hint_valueContext(_ctx, getState());
		enterRule(_localctx, 34, RULE_hint_value);
		try {
			setState(281);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,16,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(277);
				match(ID);
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(278);
				match(STRING);
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(279);
				math_expression();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(280);
				match(BOOL);
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Offset_statmentContext extends ParserRuleContext {
		public TerminalNode OFFSET() { return getToken(GQLParser.OFFSET, 0); }
		public TerminalNode INT() { return getToken(GQLParser.INT, 0); }
		public Offset_statmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_offset_statment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterOffset_statment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitOffset_statment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitOffset_statment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Offset_statmentContext offset_statment() throws RecognitionException {
		Offset_statmentContext _localctx = new Offset_statmentContext(_ctx, getState());
		enterRule(_localctx, 36, RULE_offset_statment);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(283);
			match(OFFSET);
			setState(284);
			match(INT);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Skip_statmentContext extends ParserRuleContext {
		public TerminalNode SKIP_TOKEN() { return getToken(GQLParser.SKIP_TOKEN, 0); }
		public TerminalNode INT() { return getToken(GQLParser.INT, 0); }
		public Skip_statmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_skip_statment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterSkip_statment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitSkip_statment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitSkip_statment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Skip_statmentContext skip_statment() throws RecognitionException {
		Skip_statmentContext _localctx = new Skip_statmentContext(_ctx, getState());
		enterRule(_localctx, 38, RULE_skip_statment);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(286);
			match(SKIP_TOKEN);
			setState(287);
			match(INT);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Order_by_statmentContext extends ParserRuleContext {
		public TerminalNode ORDER() { return getToken(GQLParser.ORDER, 0); }
		public TerminalNode BY() { return getToken(GQLParser.BY, 0); }
		public Order_by_specificationContext order_by_specification() {
			return getRuleContext(Order_by_specificationContext.class,0);
		}
		public Order_by_statmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_order_by_statment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterOrder_by_statment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitOrder_by_statment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitOrder_by_statment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Order_by_statmentContext order_by_statment() throws RecognitionException {
		Order_by_statmentContext _localctx = new Order_by_statmentContext(_ctx, getState());
		enterRule(_localctx, 40, RULE_order_by_statment);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(289);
			match(ORDER);
			setState(290);
			match(BY);
			setState(291);
			order_by_specification();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Order_by_specificationContext extends ParserRuleContext {
		public TerminalNode COLLATE() { return getToken(GQLParser.COLLATE, 0); }
		public Collation_specificationContext collation_specification() {
			return getRuleContext(Collation_specificationContext.class,0);
		}
		public TerminalNode ASC() { return getToken(GQLParser.ASC, 0); }
		public TerminalNode ASCENDING() { return getToken(GQLParser.ASCENDING, 0); }
		public TerminalNode DESC() { return getToken(GQLParser.DESC, 0); }
		public TerminalNode DESCENDING() { return getToken(GQLParser.DESCENDING, 0); }
		public Order_by_specificationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_order_by_specification; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterOrder_by_specification(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitOrder_by_specification(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitOrder_by_specification(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Order_by_specificationContext order_by_specification() throws RecognitionException {
		Order_by_specificationContext _localctx = new Order_by_specificationContext(_ctx, getState());
		enterRule(_localctx, 42, RULE_order_by_specification);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(295);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==COLLATE) {
				{
				setState(293);
				match(COLLATE);
				setState(294);
				collation_specification();
				}
			}

			setState(298);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if ((((_la) & ~0x3f) == 0 && ((1L << _la) & 2111062325329920L) != 0)) {
				{
				setState(297);
				_la = _input.LA(1);
				if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 2111062325329920L) != 0)) ) {
				_errHandler.recoverInline(this);
				}
				else {
					if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
					_errHandler.reportMatch(this);
					consume();
				}
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Collation_specificationContext extends ParserRuleContext {
		public TerminalNode STRING() { return getToken(GQLParser.STRING, 0); }
		public Collation_specificationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_collation_specification; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterCollation_specification(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitCollation_specification(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitCollation_specification(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Collation_specificationContext collation_specification() throws RecognitionException {
		Collation_specificationContext _localctx = new Collation_specificationContext(_ctx, getState());
		enterRule(_localctx, 44, RULE_collation_specification);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(300);
			match(STRING);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Return_statmentContext extends ParserRuleContext {
		public TerminalNode RETURN() { return getToken(GQLParser.RETURN, 0); }
		public TerminalNode MUL() { return getToken(GQLParser.MUL, 0); }
		public Return_itemsContext return_items() {
			return getRuleContext(Return_itemsContext.class,0);
		}
		public Group_by_clauseContext group_by_clause() {
			return getRuleContext(Group_by_clauseContext.class,0);
		}
		public Order_by_statmentContext order_by_statment() {
			return getRuleContext(Order_by_statmentContext.class,0);
		}
		public Limit_statmentContext limit_statment() {
			return getRuleContext(Limit_statmentContext.class,0);
		}
		public Offset_statmentContext offset_statment() {
			return getRuleContext(Offset_statmentContext.class,0);
		}
		public TerminalNode ALL() { return getToken(GQLParser.ALL, 0); }
		public TerminalNode DISTINCT() { return getToken(GQLParser.DISTINCT, 0); }
		public Return_statmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_return_statment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterReturn_statment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitReturn_statment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitReturn_statment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Return_statmentContext return_statment() throws RecognitionException {
		Return_statmentContext _localctx = new Return_statmentContext(_ctx, getState());
		enterRule(_localctx, 46, RULE_return_statment);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(302);
			match(RETURN);
			setState(322);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case MUL:
				{
				setState(303);
				match(MUL);
				}
				break;
			case EOF:
			case T__0:
			case T__2:
			case T__6:
			case LIMIT:
			case NEXT:
			case OFFSET:
			case ORDER:
			case GROUP:
			case ALL:
			case DISTINCT:
			case CASE:
			case COUNT:
			case SUM:
			case AVG:
			case MIN:
			case MAX:
			case COLLECT:
			case EXISTS:
			case UNION:
			case INTERSECT:
			case EXCEPT:
			case DATE:
			case TIME:
			case TIMESTAMP:
			case DURATION:
			case NODES:
			case EDGES:
			case LENGTH:
			case LABELS:
			case ID:
			case NUMBER:
			case STRING:
			case DASH:
				{
				setState(305);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==ALL || _la==DISTINCT) {
					{
					setState(304);
					_la = _input.LA(1);
					if ( !(_la==ALL || _la==DISTINCT) ) {
					_errHandler.recoverInline(this);
					}
					else {
						if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
						_errHandler.reportMatch(this);
						consume();
					}
					}
				}

				setState(308);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if ((((_la) & ~0x3f) == 0 && ((1L << _la) & -139611588448485238L) != 0) || ((((_la - 67)) & ~0x3f) == 0 && ((1L << (_la - 67)) & 113246463L) != 0)) {
					{
					setState(307);
					return_items();
					}
				}

				setState(311);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==GROUP) {
					{
					setState(310);
					group_by_clause();
					}
				}

				setState(314);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==ORDER) {
					{
					setState(313);
					order_by_statment();
					}
				}

				setState(317);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==LIMIT) {
					{
					setState(316);
					limit_statment();
					}
				}

				setState(320);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==OFFSET) {
					{
					setState(319);
					offset_statment();
					}
				}

				}
				break;
			default:
				throw new NoViableAltException(this);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Return_itemsContext extends ParserRuleContext {
		public List<Return_itemContext> return_item() {
			return getRuleContexts(Return_itemContext.class);
		}
		public Return_itemContext return_item(int i) {
			return getRuleContext(Return_itemContext.class,i);
		}
		public Return_itemsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_return_items; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterReturn_items(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitReturn_items(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitReturn_items(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Return_itemsContext return_items() throws RecognitionException {
		Return_itemsContext _localctx = new Return_itemsContext(_ctx, getState());
		enterRule(_localctx, 48, RULE_return_items);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(324);
			return_item();
			setState(329);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__4) {
				{
				{
				setState(325);
				match(T__4);
				setState(326);
				return_item();
				}
				}
				setState(331);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Return_itemContext extends ParserRuleContext {
		public Value_expressionContext value_expression() {
			return getRuleContext(Value_expressionContext.class,0);
		}
		public TerminalNode AS() { return getToken(GQLParser.AS, 0); }
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public Return_itemContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_return_item; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterReturn_item(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitReturn_item(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitReturn_item(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Return_itemContext return_item() throws RecognitionException {
		Return_itemContext _localctx = new Return_itemContext(_ctx, getState());
		enterRule(_localctx, 50, RULE_return_item);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(332);
			value_expression();
			setState(335);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==AS) {
				{
				setState(333);
				match(AS);
				setState(334);
				match(ID);
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Group_by_clauseContext extends ParserRuleContext {
		public TerminalNode GROUP() { return getToken(GQLParser.GROUP, 0); }
		public TerminalNode BY() { return getToken(GQLParser.BY, 0); }
		public List<Groupable_itemContext> groupable_item() {
			return getRuleContexts(Groupable_itemContext.class);
		}
		public Groupable_itemContext groupable_item(int i) {
			return getRuleContext(Groupable_itemContext.class,i);
		}
		public Group_by_clauseContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_group_by_clause; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterGroup_by_clause(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitGroup_by_clause(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitGroup_by_clause(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Group_by_clauseContext group_by_clause() throws RecognitionException {
		Group_by_clauseContext _localctx = new Group_by_clauseContext(_ctx, getState());
		enterRule(_localctx, 52, RULE_group_by_clause);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(337);
			match(GROUP);
			setState(338);
			match(BY);
			setState(339);
			groupable_item();
			setState(344);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__4) {
				{
				{
				setState(340);
				match(T__4);
				setState(341);
				groupable_item();
				}
				}
				setState(346);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Groupable_itemContext extends ParserRuleContext {
		public Property_referenceContext property_reference() {
			return getRuleContext(Property_referenceContext.class,0);
		}
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public TerminalNode INT() { return getToken(GQLParser.INT, 0); }
		public Value_expressionContext value_expression() {
			return getRuleContext(Value_expressionContext.class,0);
		}
		public Groupable_itemContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_groupable_item; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterGroupable_item(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitGroupable_item(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitGroupable_item(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Groupable_itemContext groupable_item() throws RecognitionException {
		Groupable_itemContext _localctx = new Groupable_itemContext(_ctx, getState());
		enterRule(_localctx, 54, RULE_groupable_item);
		try {
			setState(351);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,29,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(347);
				property_reference();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(348);
				match(ID);
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(349);
				match(INT);
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(350);
				value_expression();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class With_statmnetContext extends ParserRuleContext {
		public TerminalNode WITH() { return getToken(GQLParser.WITH, 0); }
		public Return_itemsContext return_items() {
			return getRuleContext(Return_itemsContext.class,0);
		}
		public TerminalNode MUL() { return getToken(GQLParser.MUL, 0); }
		public Group_by_clauseContext group_by_clause() {
			return getRuleContext(Group_by_clauseContext.class,0);
		}
		public TerminalNode ALL() { return getToken(GQLParser.ALL, 0); }
		public TerminalNode DISTINCT() { return getToken(GQLParser.DISTINCT, 0); }
		public With_statmnetContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_with_statmnet; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterWith_statmnet(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitWith_statmnet(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitWith_statmnet(this);
			else return visitor.visitChildren(this);
		}
	}

	public final With_statmnetContext with_statmnet() throws RecognitionException {
		With_statmnetContext _localctx = new With_statmnetContext(_ctx, getState());
		enterRule(_localctx, 56, RULE_with_statmnet);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(353);
			match(WITH);
			setState(355);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ALL || _la==DISTINCT) {
				{
				setState(354);
				_la = _input.LA(1);
				if ( !(_la==ALL || _la==DISTINCT) ) {
				_errHandler.recoverInline(this);
				}
				else {
					if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
					_errHandler.reportMatch(this);
					consume();
				}
				}
			}

			setState(359);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__0:
			case T__2:
			case T__6:
			case CASE:
			case COUNT:
			case SUM:
			case AVG:
			case MIN:
			case MAX:
			case COLLECT:
			case EXISTS:
			case DATE:
			case TIME:
			case TIMESTAMP:
			case DURATION:
			case NODES:
			case EDGES:
			case LENGTH:
			case LABELS:
			case ID:
			case NUMBER:
			case STRING:
			case DASH:
				{
				setState(357);
				return_items();
				}
				break;
			case MUL:
				{
				setState(358);
				match(MUL);
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
			setState(362);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==GROUP) {
				{
				setState(361);
				group_by_clause();
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Graph_patternContext extends ParserRuleContext {
		public Path_pattern_listContext path_pattern_list() {
			return getRuleContext(Path_pattern_listContext.class,0);
		}
		public Where_clauseContext where_clause() {
			return getRuleContext(Where_clauseContext.class,0);
		}
		public Graph_patternContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_graph_pattern; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterGraph_pattern(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitGraph_pattern(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitGraph_pattern(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Graph_patternContext graph_pattern() throws RecognitionException {
		Graph_patternContext _localctx = new Graph_patternContext(_ctx, getState());
		enterRule(_localctx, 58, RULE_graph_pattern);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(364);
			path_pattern_list();
			setState(366);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==WHERE) {
				{
				setState(365);
				where_clause();
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Path_pattern_listContext extends ParserRuleContext {
		public List<Top_level_path_patternContext> top_level_path_pattern() {
			return getRuleContexts(Top_level_path_patternContext.class);
		}
		public Top_level_path_patternContext top_level_path_pattern(int i) {
			return getRuleContext(Top_level_path_patternContext.class,i);
		}
		public Path_pattern_listContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_path_pattern_list; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterPath_pattern_list(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitPath_pattern_list(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitPath_pattern_list(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Path_pattern_listContext path_pattern_list() throws RecognitionException {
		Path_pattern_listContext _localctx = new Path_pattern_listContext(_ctx, getState());
		enterRule(_localctx, 60, RULE_path_pattern_list);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(368);
			top_level_path_pattern();
			setState(373);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__4) {
				{
				{
				setState(369);
				match(T__4);
				setState(370);
				top_level_path_pattern();
				}
				}
				setState(375);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Top_level_path_patternContext extends ParserRuleContext {
		public Path_patternContext path_pattern() {
			return getRuleContext(Path_patternContext.class,0);
		}
		public Path_variableContext path_variable() {
			return getRuleContext(Path_variableContext.class,0);
		}
		public TerminalNode EQ() { return getToken(GQLParser.EQ, 0); }
		public Path_search_prefixContext path_search_prefix() {
			return getRuleContext(Path_search_prefixContext.class,0);
		}
		public Path_modeContext path_mode() {
			return getRuleContext(Path_modeContext.class,0);
		}
		public Top_level_path_patternContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_top_level_path_pattern; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterTop_level_path_pattern(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitTop_level_path_pattern(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitTop_level_path_pattern(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Top_level_path_patternContext top_level_path_pattern() throws RecognitionException {
		Top_level_path_patternContext _localctx = new Top_level_path_patternContext(_ctx, getState());
		enterRule(_localctx, 62, RULE_top_level_path_pattern);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(379);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ID || _la==STRING) {
				{
				setState(376);
				path_variable();
				setState(377);
				match(EQ);
				}
			}

			setState(386);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__2:
				{
				setState(381);
				match(T__2);
				setState(382);
				path_search_prefix();
				}
				break;
			case WALK:
			case ACYCLIC:
			case TRAIL:
				{
				setState(383);
				path_mode();
				setState(384);
				match(T__3);
				}
				break;
			case T__0:
				break;
			default:
				break;
			}
			setState(388);
			path_pattern();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Path_patternContext extends ParserRuleContext {
		public List<Node_patternContext> node_pattern() {
			return getRuleContexts(Node_patternContext.class);
		}
		public Node_patternContext node_pattern(int i) {
			return getRuleContext(Node_patternContext.class,i);
		}
		public List<Edge_patternContext> edge_pattern() {
			return getRuleContexts(Edge_patternContext.class);
		}
		public Edge_patternContext edge_pattern(int i) {
			return getRuleContext(Edge_patternContext.class,i);
		}
		public List<QuantifierContext> quantifier() {
			return getRuleContexts(QuantifierContext.class);
		}
		public QuantifierContext quantifier(int i) {
			return getRuleContext(QuantifierContext.class,i);
		}
		public Path_patternContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_path_pattern; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterPath_pattern(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitPath_pattern(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitPath_pattern(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Path_patternContext path_pattern() throws RecognitionException {
		Path_patternContext _localctx = new Path_patternContext(_ctx, getState());
		enterRule(_localctx, 64, RULE_path_pattern);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(390);
			node_pattern();
			setState(399);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (((((_la - 75)) & ~0x3f) == 0 && ((1L << (_la - 75)) & 262147L) != 0)) {
				{
				{
				setState(391);
				edge_pattern();
				setState(393);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==T__2 || _la==ADD || _la==MUL) {
					{
					setState(392);
					quantifier();
					}
				}

				setState(395);
				node_pattern();
				}
				}
				setState(401);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class QuantifierContext extends ParserRuleContext {
		public TerminalNode MUL() { return getToken(GQLParser.MUL, 0); }
		public TerminalNode ADD() { return getToken(GQLParser.ADD, 0); }
		public List<TerminalNode> INT() { return getTokens(GQLParser.INT); }
		public TerminalNode INT(int i) {
			return getToken(GQLParser.INT, i);
		}
		public QuantifierContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_quantifier; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterQuantifier(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitQuantifier(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitQuantifier(this);
			else return visitor.visitChildren(this);
		}
	}

	public final QuantifierContext quantifier() throws RecognitionException {
		QuantifierContext _localctx = new QuantifierContext(_ctx, getState());
		enterRule(_localctx, 66, RULE_quantifier);
		int _la;
		try {
			setState(417);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,41,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(402);
				match(MUL);
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(403);
				match(ADD);
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(404);
				match(T__2);
				setState(405);
				match(INT);
				setState(410);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==T__4) {
					{
					setState(406);
					match(T__4);
					setState(408);
					_errHandler.sync(this);
					_la = _input.LA(1);
					if (_la==INT) {
						{
						setState(407);
						match(INT);
						}
					}

					}
				}

				setState(412);
				match(T__3);
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(413);
				match(T__2);
				setState(414);
				match(T__4);
				setState(415);
				match(INT);
				setState(416);
				match(T__3);
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Path_search_prefixContext extends ParserRuleContext {
		public TerminalNode ALL() { return getToken(GQLParser.ALL, 0); }
		public TerminalNode ANY() { return getToken(GQLParser.ANY, 0); }
		public TerminalNode SHORTEST() { return getToken(GQLParser.SHORTEST, 0); }
		public TerminalNode CHEAPEST() { return getToken(GQLParser.CHEAPEST, 0); }
		public Path_search_prefixContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_path_search_prefix; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterPath_search_prefix(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitPath_search_prefix(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitPath_search_prefix(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Path_search_prefixContext path_search_prefix() throws RecognitionException {
		Path_search_prefixContext _localctx = new Path_search_prefixContext(_ctx, getState());
		enterRule(_localctx, 68, RULE_path_search_prefix);
		try {
			setState(425);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,42,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(419);
				match(ALL);
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(420);
				match(ANY);
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(421);
				match(ANY);
				setState(422);
				match(SHORTEST);
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(423);
				match(ANY);
				setState(424);
				match(CHEAPEST);
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Path_modeContext extends ParserRuleContext {
		public TerminalNode WALK() { return getToken(GQLParser.WALK, 0); }
		public TerminalNode PATH() { return getToken(GQLParser.PATH, 0); }
		public TerminalNode PATHS() { return getToken(GQLParser.PATHS, 0); }
		public TerminalNode ACYCLIC() { return getToken(GQLParser.ACYCLIC, 0); }
		public TerminalNode TRAIL() { return getToken(GQLParser.TRAIL, 0); }
		public Path_modeContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_path_mode; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterPath_mode(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitPath_mode(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitPath_mode(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Path_modeContext path_mode() throws RecognitionException {
		Path_modeContext _localctx = new Path_modeContext(_ctx, getState());
		enterRule(_localctx, 70, RULE_path_mode);
		int _la;
		try {
			setState(439);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case WALK:
				enterOuterAlt(_localctx, 1);
				{
				setState(427);
				match(WALK);
				setState(429);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==PATH || _la==PATHS) {
					{
					setState(428);
					_la = _input.LA(1);
					if ( !(_la==PATH || _la==PATHS) ) {
					_errHandler.recoverInline(this);
					}
					else {
						if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
						_errHandler.reportMatch(this);
						consume();
					}
					}
				}

				}
				break;
			case ACYCLIC:
				enterOuterAlt(_localctx, 2);
				{
				setState(431);
				match(ACYCLIC);
				setState(433);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==PATH || _la==PATHS) {
					{
					setState(432);
					_la = _input.LA(1);
					if ( !(_la==PATH || _la==PATHS) ) {
					_errHandler.recoverInline(this);
					}
					else {
						if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
						_errHandler.reportMatch(this);
						consume();
					}
					}
				}

				}
				break;
			case TRAIL:
				enterOuterAlt(_localctx, 3);
				{
				setState(435);
				match(TRAIL);
				setState(437);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==PATH || _la==PATHS) {
					{
					setState(436);
					_la = _input.LA(1);
					if ( !(_la==PATH || _la==PATHS) ) {
					_errHandler.recoverInline(this);
					}
					else {
						if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
						_errHandler.reportMatch(this);
						consume();
					}
					}
				}

				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Node_patternContext extends ParserRuleContext {
		public Pattern_fillerContext pattern_filler() {
			return getRuleContext(Pattern_fillerContext.class,0);
		}
		public Node_patternContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_node_pattern; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterNode_pattern(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitNode_pattern(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitNode_pattern(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Node_patternContext node_pattern() throws RecognitionException {
		Node_patternContext _localctx = new Node_patternContext(_ctx, getState());
		enterRule(_localctx, 72, RULE_node_pattern);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(441);
			match(T__0);
			setState(442);
			pattern_filler();
			setState(443);
			match(T__1);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Edge_patternContext extends ParserRuleContext {
		public Full_edge_anyContext full_edge_any() {
			return getRuleContext(Full_edge_anyContext.class,0);
		}
		public Full_edge_leftContext full_edge_left() {
			return getRuleContext(Full_edge_leftContext.class,0);
		}
		public Full_edge_rightContext full_edge_right() {
			return getRuleContext(Full_edge_rightContext.class,0);
		}
		public Abbreviated_edge_anyContext abbreviated_edge_any() {
			return getRuleContext(Abbreviated_edge_anyContext.class,0);
		}
		public Abbreviated_edge_leftContext abbreviated_edge_left() {
			return getRuleContext(Abbreviated_edge_leftContext.class,0);
		}
		public Abbreviated_edge_rightContext abbreviated_edge_right() {
			return getRuleContext(Abbreviated_edge_rightContext.class,0);
		}
		public Edge_patternContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_edge_pattern; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterEdge_pattern(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitEdge_pattern(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitEdge_pattern(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Edge_patternContext edge_pattern() throws RecognitionException {
		Edge_patternContext _localctx = new Edge_patternContext(_ctx, getState());
		enterRule(_localctx, 74, RULE_edge_pattern);
		try {
			setState(451);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,47,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(445);
				full_edge_any();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(446);
				full_edge_left();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(447);
				full_edge_right();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(448);
				abbreviated_edge_any();
				}
				break;
			case 5:
				enterOuterAlt(_localctx, 5);
				{
				setState(449);
				abbreviated_edge_left();
				}
				break;
			case 6:
				enterOuterAlt(_localctx, 6);
				{
				setState(450);
				abbreviated_edge_right();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Full_edge_anyContext extends ParserRuleContext {
		public List<TerminalNode> DASH() { return getTokens(GQLParser.DASH); }
		public TerminalNode DASH(int i) {
			return getToken(GQLParser.DASH, i);
		}
		public Pattern_fillerContext pattern_filler() {
			return getRuleContext(Pattern_fillerContext.class,0);
		}
		public Full_edge_anyContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_full_edge_any; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterFull_edge_any(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitFull_edge_any(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitFull_edge_any(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Full_edge_anyContext full_edge_any() throws RecognitionException {
		Full_edge_anyContext _localctx = new Full_edge_anyContext(_ctx, getState());
		enterRule(_localctx, 76, RULE_full_edge_any);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(453);
			match(DASH);
			setState(454);
			match(T__6);
			setState(455);
			pattern_filler();
			setState(456);
			match(T__7);
			setState(457);
			match(DASH);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Full_edge_leftContext extends ParserRuleContext {
		public TerminalNode ARROW_LEFT() { return getToken(GQLParser.ARROW_LEFT, 0); }
		public Pattern_fillerContext pattern_filler() {
			return getRuleContext(Pattern_fillerContext.class,0);
		}
		public TerminalNode DASH() { return getToken(GQLParser.DASH, 0); }
		public Full_edge_leftContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_full_edge_left; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterFull_edge_left(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitFull_edge_left(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitFull_edge_left(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Full_edge_leftContext full_edge_left() throws RecognitionException {
		Full_edge_leftContext _localctx = new Full_edge_leftContext(_ctx, getState());
		enterRule(_localctx, 78, RULE_full_edge_left);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(459);
			match(ARROW_LEFT);
			setState(460);
			match(T__6);
			setState(461);
			pattern_filler();
			setState(462);
			match(T__7);
			setState(463);
			match(DASH);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Full_edge_rightContext extends ParserRuleContext {
		public TerminalNode DASH() { return getToken(GQLParser.DASH, 0); }
		public Pattern_fillerContext pattern_filler() {
			return getRuleContext(Pattern_fillerContext.class,0);
		}
		public TerminalNode ARROW_RIGHT() { return getToken(GQLParser.ARROW_RIGHT, 0); }
		public Full_edge_rightContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_full_edge_right; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterFull_edge_right(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitFull_edge_right(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitFull_edge_right(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Full_edge_rightContext full_edge_right() throws RecognitionException {
		Full_edge_rightContext _localctx = new Full_edge_rightContext(_ctx, getState());
		enterRule(_localctx, 80, RULE_full_edge_right);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(465);
			match(DASH);
			setState(466);
			match(T__6);
			setState(467);
			pattern_filler();
			setState(468);
			match(T__7);
			setState(469);
			match(ARROW_RIGHT);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Abbreviated_edge_anyContext extends ParserRuleContext {
		public TerminalNode DASH() { return getToken(GQLParser.DASH, 0); }
		public Abbreviated_edge_anyContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_abbreviated_edge_any; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterAbbreviated_edge_any(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitAbbreviated_edge_any(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitAbbreviated_edge_any(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Abbreviated_edge_anyContext abbreviated_edge_any() throws RecognitionException {
		Abbreviated_edge_anyContext _localctx = new Abbreviated_edge_anyContext(_ctx, getState());
		enterRule(_localctx, 82, RULE_abbreviated_edge_any);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(471);
			match(DASH);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Abbreviated_edge_leftContext extends ParserRuleContext {
		public TerminalNode ARROW_LEFT() { return getToken(GQLParser.ARROW_LEFT, 0); }
		public Abbreviated_edge_leftContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_abbreviated_edge_left; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterAbbreviated_edge_left(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitAbbreviated_edge_left(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitAbbreviated_edge_left(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Abbreviated_edge_leftContext abbreviated_edge_left() throws RecognitionException {
		Abbreviated_edge_leftContext _localctx = new Abbreviated_edge_leftContext(_ctx, getState());
		enterRule(_localctx, 84, RULE_abbreviated_edge_left);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(473);
			match(ARROW_LEFT);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Abbreviated_edge_rightContext extends ParserRuleContext {
		public TerminalNode ARROW_RIGHT() { return getToken(GQLParser.ARROW_RIGHT, 0); }
		public Abbreviated_edge_rightContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_abbreviated_edge_right; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterAbbreviated_edge_right(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitAbbreviated_edge_right(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitAbbreviated_edge_right(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Abbreviated_edge_rightContext abbreviated_edge_right() throws RecognitionException {
		Abbreviated_edge_rightContext _localctx = new Abbreviated_edge_rightContext(_ctx, getState());
		enterRule(_localctx, 86, RULE_abbreviated_edge_right);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(475);
			match(ARROW_RIGHT);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Pattern_fillerContext extends ParserRuleContext {
		public Graph_pattern_variableContext graph_pattern_variable() {
			return getRuleContext(Graph_pattern_variableContext.class,0);
		}
		public Is_label_conditionContext is_label_condition() {
			return getRuleContext(Is_label_conditionContext.class,0);
		}
		public Where_clauseContext where_clause() {
			return getRuleContext(Where_clauseContext.class,0);
		}
		public Property_filtersContext property_filters() {
			return getRuleContext(Property_filtersContext.class,0);
		}
		public Cost_expressionContext cost_expression() {
			return getRuleContext(Cost_expressionContext.class,0);
		}
		public Pattern_fillerContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_pattern_filler; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterPattern_filler(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitPattern_filler(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitPattern_filler(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Pattern_fillerContext pattern_filler() throws RecognitionException {
		Pattern_fillerContext _localctx = new Pattern_fillerContext(_ctx, getState());
		enterRule(_localctx, 88, RULE_pattern_filler);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(478);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ID) {
				{
				setState(477);
				graph_pattern_variable();
				}
			}

			setState(481);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==T__8 || _la==IS) {
				{
				setState(480);
				is_label_condition();
				}
			}

			setState(485);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case WHERE:
				{
				setState(483);
				where_clause();
				}
				break;
			case T__2:
				{
				setState(484);
				property_filters();
				}
				break;
			case T__1:
			case T__7:
			case COST:
				break;
			default:
				break;
			}
			setState(488);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==COST) {
				{
				setState(487);
				cost_expression();
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Is_label_conditionContext extends ParserRuleContext {
		public Label_expressionContext label_expression() {
			return getRuleContext(Label_expressionContext.class,0);
		}
		public TerminalNode IS() { return getToken(GQLParser.IS, 0); }
		public Is_label_conditionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_is_label_condition; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterIs_label_condition(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitIs_label_condition(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitIs_label_condition(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Is_label_conditionContext is_label_condition() throws RecognitionException {
		Is_label_conditionContext _localctx = new Is_label_conditionContext(_ctx, getState());
		enterRule(_localctx, 90, RULE_is_label_condition);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(490);
			_la = _input.LA(1);
			if ( !(_la==T__8 || _la==IS) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			setState(491);
			label_expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Label_expressionContext extends ParserRuleContext {
		public List<Label_termContext> label_term() {
			return getRuleContexts(Label_termContext.class);
		}
		public Label_termContext label_term(int i) {
			return getRuleContext(Label_termContext.class,i);
		}
		public List<TerminalNode> OR() { return getTokens(GQLParser.OR); }
		public TerminalNode OR(int i) {
			return getToken(GQLParser.OR, i);
		}
		public Label_expressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_label_expression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterLabel_expression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitLabel_expression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitLabel_expression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Label_expressionContext label_expression() throws RecognitionException {
		Label_expressionContext _localctx = new Label_expressionContext(_ctx, getState());
		enterRule(_localctx, 92, RULE_label_expression);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(493);
			label_term();
			setState(498);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__9 || _la==OR) {
				{
				{
				setState(494);
				_la = _input.LA(1);
				if ( !(_la==T__9 || _la==OR) ) {
				_errHandler.recoverInline(this);
				}
				else {
					if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
					_errHandler.reportMatch(this);
					consume();
				}
				setState(495);
				label_term();
				}
				}
				setState(500);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Label_termContext extends ParserRuleContext {
		public Label_factorContext label_factor() {
			return getRuleContext(Label_factorContext.class,0);
		}
		public List<Label_termContext> label_term() {
			return getRuleContexts(Label_termContext.class);
		}
		public Label_termContext label_term(int i) {
			return getRuleContext(Label_termContext.class,i);
		}
		public List<TerminalNode> AND() { return getTokens(GQLParser.AND); }
		public TerminalNode AND(int i) {
			return getToken(GQLParser.AND, i);
		}
		public Label_termContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_label_term; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterLabel_term(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitLabel_term(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitLabel_term(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Label_termContext label_term() throws RecognitionException {
		Label_termContext _localctx = new Label_termContext(_ctx, getState());
		enterRule(_localctx, 94, RULE_label_term);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(501);
			label_factor();
			setState(506);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,53,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(502);
					_la = _input.LA(1);
					if ( !(_la==T__10 || _la==AND) ) {
					_errHandler.recoverInline(this);
					}
					else {
						if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
						_errHandler.reportMatch(this);
						consume();
					}
					setState(503);
					label_term();
					}
					} 
				}
				setState(508);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,53,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Label_factorContext extends ParserRuleContext {
		public Label_primaryContext label_primary() {
			return getRuleContext(Label_primaryContext.class,0);
		}
		public TerminalNode NOT() { return getToken(GQLParser.NOT, 0); }
		public Label_factorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_label_factor; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterLabel_factor(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitLabel_factor(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitLabel_factor(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Label_factorContext label_factor() throws RecognitionException {
		Label_factorContext _localctx = new Label_factorContext(_ctx, getState());
		enterRule(_localctx, 96, RULE_label_factor);
		int _la;
		try {
			setState(515);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__0:
			case NOT:
			case ID:
				enterOuterAlt(_localctx, 1);
				{
				setState(510);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==NOT) {
					{
					setState(509);
					match(NOT);
					}
				}

				setState(512);
				label_primary();
				}
				break;
			case T__11:
				enterOuterAlt(_localctx, 2);
				{
				setState(513);
				match(T__11);
				setState(514);
				label_primary();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Label_primaryContext extends ParserRuleContext {
		public Property_referenceContext property_reference() {
			return getRuleContext(Property_referenceContext.class,0);
		}
		public Label_expressionContext label_expression() {
			return getRuleContext(Label_expressionContext.class,0);
		}
		public Label_primaryContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_label_primary; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterLabel_primary(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitLabel_primary(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitLabel_primary(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Label_primaryContext label_primary() throws RecognitionException {
		Label_primaryContext _localctx = new Label_primaryContext(_ctx, getState());
		enterRule(_localctx, 98, RULE_label_primary);
		try {
			setState(522);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case ID:
				enterOuterAlt(_localctx, 1);
				{
				setState(517);
				property_reference();
				}
				break;
			case T__0:
				enterOuterAlt(_localctx, 2);
				{
				setState(518);
				match(T__0);
				setState(519);
				label_expression();
				setState(520);
				match(T__1);
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Graph_pattern_variableContext extends ParserRuleContext {
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public Graph_pattern_variableContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_graph_pattern_variable; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterGraph_pattern_variable(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitGraph_pattern_variable(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitGraph_pattern_variable(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Graph_pattern_variableContext graph_pattern_variable() throws RecognitionException {
		Graph_pattern_variableContext _localctx = new Graph_pattern_variableContext(_ctx, getState());
		enterRule(_localctx, 100, RULE_graph_pattern_variable);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(524);
			match(ID);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Cost_expressionContext extends ParserRuleContext {
		public TerminalNode COST() { return getToken(GQLParser.COST, 0); }
		public Math_expressionContext math_expression() {
			return getRuleContext(Math_expressionContext.class,0);
		}
		public Cost_expressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_cost_expression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterCost_expression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitCost_expression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitCost_expression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Cost_expressionContext cost_expression() throws RecognitionException {
		Cost_expressionContext _localctx = new Cost_expressionContext(_ctx, getState());
		enterRule(_localctx, 102, RULE_cost_expression);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(526);
			match(COST);
			setState(527);
			math_expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Where_clauseContext extends ParserRuleContext {
		public TerminalNode WHERE() { return getToken(GQLParser.WHERE, 0); }
		public Boolean_expressionContext boolean_expression() {
			return getRuleContext(Boolean_expressionContext.class,0);
		}
		public Where_clauseContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_where_clause; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterWhere_clause(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitWhere_clause(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitWhere_clause(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Where_clauseContext where_clause() throws RecognitionException {
		Where_clauseContext _localctx = new Where_clauseContext(_ctx, getState());
		enterRule(_localctx, 104, RULE_where_clause);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(529);
			match(WHERE);
			setState(530);
			boolean_expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Property_filtersContext extends ParserRuleContext {
		public Property_listContext property_list() {
			return getRuleContext(Property_listContext.class,0);
		}
		public Property_filtersContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_property_filters; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterProperty_filters(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitProperty_filters(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitProperty_filters(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Property_filtersContext property_filters() throws RecognitionException {
		Property_filtersContext _localctx = new Property_filtersContext(_ctx, getState());
		enterRule(_localctx, 106, RULE_property_filters);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(532);
			match(T__2);
			setState(533);
			property_list();
			setState(534);
			match(T__3);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Property_listContext extends ParserRuleContext {
		public List<Property_assignmentContext> property_assignment() {
			return getRuleContexts(Property_assignmentContext.class);
		}
		public Property_assignmentContext property_assignment(int i) {
			return getRuleContext(Property_assignmentContext.class,i);
		}
		public Property_listContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_property_list; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterProperty_list(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitProperty_list(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitProperty_list(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Property_listContext property_list() throws RecognitionException {
		Property_listContext _localctx = new Property_listContext(_ctx, getState());
		enterRule(_localctx, 108, RULE_property_list);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(536);
			property_assignment();
			setState(541);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__4) {
				{
				{
				setState(537);
				match(T__4);
				setState(538);
				property_assignment();
				}
				}
				setState(543);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Property_assignmentContext extends ParserRuleContext {
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public Value_expressionContext value_expression() {
			return getRuleContext(Value_expressionContext.class,0);
		}
		public Property_assignmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_property_assignment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterProperty_assignment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitProperty_assignment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitProperty_assignment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Property_assignmentContext property_assignment() throws RecognitionException {
		Property_assignmentContext _localctx = new Property_assignmentContext(_ctx, getState());
		enterRule(_localctx, 110, RULE_property_assignment);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(544);
			match(ID);
			setState(545);
			match(T__8);
			setState(546);
			value_expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Path_variableContext extends ParserRuleContext {
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public TerminalNode STRING() { return getToken(GQLParser.STRING, 0); }
		public Path_variableContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_path_variable; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterPath_variable(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitPath_variable(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitPath_variable(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Path_variableContext path_variable() throws RecognitionException {
		Path_variableContext _localctx = new Path_variableContext(_ctx, getState());
		enterRule(_localctx, 112, RULE_path_variable);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(548);
			_la = _input.LA(1);
			if ( !(_la==ID || _la==STRING) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Boolean_expressionContext extends ParserRuleContext {
		public List<Boolean_expression_andContext> boolean_expression_and() {
			return getRuleContexts(Boolean_expression_andContext.class);
		}
		public Boolean_expression_andContext boolean_expression_and(int i) {
			return getRuleContext(Boolean_expression_andContext.class,i);
		}
		public List<TerminalNode> OR() { return getTokens(GQLParser.OR); }
		public TerminalNode OR(int i) {
			return getToken(GQLParser.OR, i);
		}
		public Boolean_expressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_boolean_expression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterBoolean_expression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitBoolean_expression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitBoolean_expression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Boolean_expressionContext boolean_expression() throws RecognitionException {
		Boolean_expressionContext _localctx = new Boolean_expressionContext(_ctx, getState());
		enterRule(_localctx, 114, RULE_boolean_expression);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(550);
			boolean_expression_and();
			setState(555);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==OR) {
				{
				{
				setState(551);
				match(OR);
				setState(552);
				boolean_expression_and();
				}
				}
				setState(557);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Boolean_expression_andContext extends ParserRuleContext {
		public List<Boolean_expression_innerContext> boolean_expression_inner() {
			return getRuleContexts(Boolean_expression_innerContext.class);
		}
		public Boolean_expression_innerContext boolean_expression_inner(int i) {
			return getRuleContext(Boolean_expression_innerContext.class,i);
		}
		public List<TerminalNode> AND() { return getTokens(GQLParser.AND); }
		public TerminalNode AND(int i) {
			return getToken(GQLParser.AND, i);
		}
		public Boolean_expression_andContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_boolean_expression_and; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterBoolean_expression_and(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitBoolean_expression_and(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitBoolean_expression_and(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Boolean_expression_andContext boolean_expression_and() throws RecognitionException {
		Boolean_expression_andContext _localctx = new Boolean_expression_andContext(_ctx, getState());
		enterRule(_localctx, 116, RULE_boolean_expression_and);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(558);
			boolean_expression_inner();
			setState(563);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==AND) {
				{
				{
				setState(559);
				match(AND);
				setState(560);
				boolean_expression_inner();
				}
				}
				setState(565);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Boolean_expression_innerContext extends ParserRuleContext {
		public TerminalNode NOT() { return getToken(GQLParser.NOT, 0); }
		public Boolean_expression_innerContext boolean_expression_inner() {
			return getRuleContext(Boolean_expression_innerContext.class,0);
		}
		public Boolean_expressionContext boolean_expression() {
			return getRuleContext(Boolean_expressionContext.class,0);
		}
		public Comparison_expressionContext comparison_expression() {
			return getRuleContext(Comparison_expressionContext.class,0);
		}
		public Value_expressionContext value_expression() {
			return getRuleContext(Value_expressionContext.class,0);
		}
		public TerminalNode IS() { return getToken(GQLParser.IS, 0); }
		public TerminalNode NULL_TOKEN() { return getToken(GQLParser.NULL_TOKEN, 0); }
		public Boolean_expression_innerContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_boolean_expression_inner; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterBoolean_expression_inner(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitBoolean_expression_inner(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitBoolean_expression_inner(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Boolean_expression_innerContext boolean_expression_inner() throws RecognitionException {
		Boolean_expression_innerContext _localctx = new Boolean_expression_innerContext(_ctx, getState());
		enterRule(_localctx, 118, RULE_boolean_expression_inner);
		int _la;
		try {
			setState(580);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,61,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(566);
				match(NOT);
				setState(567);
				boolean_expression_inner();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(568);
				match(T__0);
				setState(569);
				boolean_expression();
				setState(570);
				match(T__1);
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(572);
				comparison_expression();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(573);
				value_expression();
				setState(574);
				match(IS);
				setState(576);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==NOT) {
					{
					setState(575);
					match(NOT);
					}
				}

				setState(578);
				match(NULL_TOKEN);
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Comparison_expressionContext extends ParserRuleContext {
		public List<Value_expressionContext> value_expression() {
			return getRuleContexts(Value_expressionContext.class);
		}
		public Value_expressionContext value_expression(int i) {
			return getRuleContext(Value_expressionContext.class,i);
		}
		public Comparison_operatorContext comparison_operator() {
			return getRuleContext(Comparison_operatorContext.class,0);
		}
		public Comparison_expressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_comparison_expression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterComparison_expression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitComparison_expression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitComparison_expression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Comparison_expressionContext comparison_expression() throws RecognitionException {
		Comparison_expressionContext _localctx = new Comparison_expressionContext(_ctx, getState());
		enterRule(_localctx, 120, RULE_comparison_expression);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(582);
			value_expression();
			setState(583);
			comparison_operator();
			setState(584);
			value_expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Value_expressionContext extends ParserRuleContext {
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public Property_referenceContext property_reference() {
			return getRuleContext(Property_referenceContext.class,0);
		}
		public TerminalNode STRING() { return getToken(GQLParser.STRING, 0); }
		public Math_expressionContext math_expression() {
			return getRuleContext(Math_expressionContext.class,0);
		}
		public List_literalContext list_literal() {
			return getRuleContext(List_literalContext.class,0);
		}
		public Map_literalContext map_literal() {
			return getRuleContext(Map_literalContext.class,0);
		}
		public Temporal_literalContext temporal_literal() {
			return getRuleContext(Temporal_literalContext.class,0);
		}
		public Path_functionContext path_function() {
			return getRuleContext(Path_functionContext.class,0);
		}
		public Case_expressionContext case_expression() {
			return getRuleContext(Case_expressionContext.class,0);
		}
		public Aggregate_functionContext aggregate_function() {
			return getRuleContext(Aggregate_functionContext.class,0);
		}
		public Exists_predicateContext exists_predicate() {
			return getRuleContext(Exists_predicateContext.class,0);
		}
		public Value_expressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_value_expression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterValue_expression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitValue_expression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitValue_expression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Value_expressionContext value_expression() throws RecognitionException {
		Value_expressionContext _localctx = new Value_expressionContext(_ctx, getState());
		enterRule(_localctx, 122, RULE_value_expression);
		try {
			setState(597);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,62,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(586);
				match(ID);
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(587);
				property_reference();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(588);
				match(STRING);
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(589);
				math_expression();
				}
				break;
			case 5:
				enterOuterAlt(_localctx, 5);
				{
				setState(590);
				list_literal();
				}
				break;
			case 6:
				enterOuterAlt(_localctx, 6);
				{
				setState(591);
				map_literal();
				}
				break;
			case 7:
				enterOuterAlt(_localctx, 7);
				{
				setState(592);
				temporal_literal();
				}
				break;
			case 8:
				enterOuterAlt(_localctx, 8);
				{
				setState(593);
				path_function();
				}
				break;
			case 9:
				enterOuterAlt(_localctx, 9);
				{
				setState(594);
				case_expression();
				}
				break;
			case 10:
				enterOuterAlt(_localctx, 10);
				{
				setState(595);
				aggregate_function();
				}
				break;
			case 11:
				enterOuterAlt(_localctx, 11);
				{
				setState(596);
				exists_predicate();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class List_literalContext extends ParserRuleContext {
		public List<Value_expressionContext> value_expression() {
			return getRuleContexts(Value_expressionContext.class);
		}
		public Value_expressionContext value_expression(int i) {
			return getRuleContext(Value_expressionContext.class,i);
		}
		public List_literalContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_list_literal; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterList_literal(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitList_literal(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitList_literal(this);
			else return visitor.visitChildren(this);
		}
	}

	public final List_literalContext list_literal() throws RecognitionException {
		List_literalContext _localctx = new List_literalContext(_ctx, getState());
		enterRule(_localctx, 124, RULE_list_literal);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(599);
			match(T__6);
			setState(608);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if ((((_la) & ~0x3f) == 0 && ((1L << _la) & -139611588448485238L) != 0) || ((((_la - 67)) & ~0x3f) == 0 && ((1L << (_la - 67)) & 113246463L) != 0)) {
				{
				setState(600);
				value_expression();
				setState(605);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==T__4) {
					{
					{
					setState(601);
					match(T__4);
					setState(602);
					value_expression();
					}
					}
					setState(607);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				}
			}

			setState(610);
			match(T__7);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Map_literalContext extends ParserRuleContext {
		public List<Map_entryContext> map_entry() {
			return getRuleContexts(Map_entryContext.class);
		}
		public Map_entryContext map_entry(int i) {
			return getRuleContext(Map_entryContext.class,i);
		}
		public Map_literalContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_map_literal; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterMap_literal(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitMap_literal(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitMap_literal(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Map_literalContext map_literal() throws RecognitionException {
		Map_literalContext _localctx = new Map_literalContext(_ctx, getState());
		enterRule(_localctx, 126, RULE_map_literal);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(612);
			match(T__2);
			setState(621);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ID || _la==STRING) {
				{
				setState(613);
				map_entry();
				setState(618);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==T__4) {
					{
					{
					setState(614);
					match(T__4);
					setState(615);
					map_entry();
					}
					}
					setState(620);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				}
			}

			setState(623);
			match(T__3);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Map_entryContext extends ParserRuleContext {
		public Value_expressionContext value_expression() {
			return getRuleContext(Value_expressionContext.class,0);
		}
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public TerminalNode STRING() { return getToken(GQLParser.STRING, 0); }
		public Map_entryContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_map_entry; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterMap_entry(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitMap_entry(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitMap_entry(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Map_entryContext map_entry() throws RecognitionException {
		Map_entryContext _localctx = new Map_entryContext(_ctx, getState());
		enterRule(_localctx, 128, RULE_map_entry);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(625);
			_la = _input.LA(1);
			if ( !(_la==ID || _la==STRING) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			setState(626);
			match(T__8);
			setState(627);
			value_expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Temporal_literalContext extends ParserRuleContext {
		public TerminalNode DATE() { return getToken(GQLParser.DATE, 0); }
		public TerminalNode TIME() { return getToken(GQLParser.TIME, 0); }
		public TerminalNode TIMESTAMP() { return getToken(GQLParser.TIMESTAMP, 0); }
		public TerminalNode DURATION() { return getToken(GQLParser.DURATION, 0); }
		public TerminalNode STRING() { return getToken(GQLParser.STRING, 0); }
		public Temporal_literalContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_temporal_literal; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterTemporal_literal(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitTemporal_literal(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitTemporal_literal(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Temporal_literalContext temporal_literal() throws RecognitionException {
		Temporal_literalContext _localctx = new Temporal_literalContext(_ctx, getState());
		enterRule(_localctx, 130, RULE_temporal_literal);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(629);
			_la = _input.LA(1);
			if ( !(((((_la - 67)) & ~0x3f) == 0 && ((1L << (_la - 67)) & 15L) != 0)) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			setState(634);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case STRING:
				{
				setState(630);
				match(STRING);
				}
				break;
			case T__0:
				{
				setState(631);
				match(T__0);
				setState(632);
				match(STRING);
				setState(633);
				match(T__1);
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Path_functionContext extends ParserRuleContext {
		public TerminalNode NODES() { return getToken(GQLParser.NODES, 0); }
		public TerminalNode EDGES() { return getToken(GQLParser.EDGES, 0); }
		public TerminalNode LENGTH() { return getToken(GQLParser.LENGTH, 0); }
		public TerminalNode LABELS() { return getToken(GQLParser.LABELS, 0); }
		public TerminalNode ID() { return getToken(GQLParser.ID, 0); }
		public TerminalNode STRING() { return getToken(GQLParser.STRING, 0); }
		public Path_functionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_path_function; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterPath_function(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitPath_function(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitPath_function(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Path_functionContext path_function() throws RecognitionException {
		Path_functionContext _localctx = new Path_functionContext(_ctx, getState());
		enterRule(_localctx, 132, RULE_path_function);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(636);
			_la = _input.LA(1);
			if ( !(((((_la - 71)) & ~0x3f) == 0 && ((1L << (_la - 71)) & 15L) != 0)) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			setState(637);
			match(T__0);
			setState(638);
			_la = _input.LA(1);
			if ( !(_la==ID || _la==STRING) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			setState(639);
			match(T__1);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Case_expressionContext extends ParserRuleContext {
		public TerminalNode CASE() { return getToken(GQLParser.CASE, 0); }
		public TerminalNode END() { return getToken(GQLParser.END, 0); }
		public List<Value_expressionContext> value_expression() {
			return getRuleContexts(Value_expressionContext.class);
		}
		public Value_expressionContext value_expression(int i) {
			return getRuleContext(Value_expressionContext.class,i);
		}
		public List<TerminalNode> WHEN() { return getTokens(GQLParser.WHEN); }
		public TerminalNode WHEN(int i) {
			return getToken(GQLParser.WHEN, i);
		}
		public List<Boolean_expressionContext> boolean_expression() {
			return getRuleContexts(Boolean_expressionContext.class);
		}
		public Boolean_expressionContext boolean_expression(int i) {
			return getRuleContext(Boolean_expressionContext.class,i);
		}
		public List<TerminalNode> THEN() { return getTokens(GQLParser.THEN); }
		public TerminalNode THEN(int i) {
			return getToken(GQLParser.THEN, i);
		}
		public TerminalNode ELSE() { return getToken(GQLParser.ELSE, 0); }
		public Case_expressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_case_expression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterCase_expression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitCase_expression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitCase_expression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Case_expressionContext case_expression() throws RecognitionException {
		Case_expressionContext _localctx = new Case_expressionContext(_ctx, getState());
		enterRule(_localctx, 134, RULE_case_expression);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(641);
			match(CASE);
			setState(643);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if ((((_la) & ~0x3f) == 0 && ((1L << _la) & -139611588448485238L) != 0) || ((((_la - 67)) & ~0x3f) == 0 && ((1L << (_la - 67)) & 113246463L) != 0)) {
				{
				setState(642);
				value_expression();
				}
			}

			setState(650); 
			_errHandler.sync(this);
			_la = _input.LA(1);
			do {
				{
				{
				setState(645);
				match(WHEN);
				setState(646);
				boolean_expression();
				setState(647);
				match(THEN);
				setState(648);
				value_expression();
				}
				}
				setState(652); 
				_errHandler.sync(this);
				_la = _input.LA(1);
			} while ( _la==WHEN );
			setState(656);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ELSE) {
				{
				setState(654);
				match(ELSE);
				setState(655);
				value_expression();
				}
			}

			setState(658);
			match(END);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Aggregate_functionContext extends ParserRuleContext {
		public Value_expressionContext value_expression() {
			return getRuleContext(Value_expressionContext.class,0);
		}
		public TerminalNode COUNT() { return getToken(GQLParser.COUNT, 0); }
		public TerminalNode SUM() { return getToken(GQLParser.SUM, 0); }
		public TerminalNode AVG() { return getToken(GQLParser.AVG, 0); }
		public TerminalNode MIN() { return getToken(GQLParser.MIN, 0); }
		public TerminalNode MAX() { return getToken(GQLParser.MAX, 0); }
		public TerminalNode COLLECT() { return getToken(GQLParser.COLLECT, 0); }
		public TerminalNode DISTINCT() { return getToken(GQLParser.DISTINCT, 0); }
		public Aggregate_functionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_aggregate_function; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterAggregate_function(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitAggregate_function(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitAggregate_function(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Aggregate_functionContext aggregate_function() throws RecognitionException {
		Aggregate_functionContext _localctx = new Aggregate_functionContext(_ctx, getState());
		enterRule(_localctx, 136, RULE_aggregate_function);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(660);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 9079256848778919936L) != 0)) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			setState(661);
			match(T__0);
			setState(663);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==DISTINCT) {
				{
				setState(662);
				match(DISTINCT);
				}
			}

			setState(665);
			value_expression();
			setState(666);
			match(T__1);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Exists_predicateContext extends ParserRuleContext {
		public TerminalNode EXISTS() { return getToken(GQLParser.EXISTS, 0); }
		public Graph_patternContext graph_pattern() {
			return getRuleContext(Graph_patternContext.class,0);
		}
		public Exists_predicateContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_exists_predicate; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterExists_predicate(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitExists_predicate(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitExists_predicate(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Exists_predicateContext exists_predicate() throws RecognitionException {
		Exists_predicateContext _localctx = new Exists_predicateContext(_ctx, getState());
		enterRule(_localctx, 138, RULE_exists_predicate);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(668);
			match(EXISTS);
			setState(669);
			match(T__2);
			setState(670);
			graph_pattern();
			setState(671);
			match(T__3);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Math_expressionContext extends ParserRuleContext {
		public List<Math_expression_mulContext> math_expression_mul() {
			return getRuleContexts(Math_expression_mulContext.class);
		}
		public Math_expression_mulContext math_expression_mul(int i) {
			return getRuleContext(Math_expression_mulContext.class,i);
		}
		public List<TerminalNode> ADD() { return getTokens(GQLParser.ADD); }
		public TerminalNode ADD(int i) {
			return getToken(GQLParser.ADD, i);
		}
		public List<SubContext> sub() {
			return getRuleContexts(SubContext.class);
		}
		public SubContext sub(int i) {
			return getRuleContext(SubContext.class,i);
		}
		public Math_expressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_math_expression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterMath_expression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitMath_expression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitMath_expression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Math_expressionContext math_expression() throws RecognitionException {
		Math_expressionContext _localctx = new Math_expressionContext(_ctx, getState());
		enterRule(_localctx, 140, RULE_math_expression);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(673);
			math_expression_mul();
			setState(681);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ADD || _la==DASH) {
				{
				{
				setState(676);
				_errHandler.sync(this);
				switch (_input.LA(1)) {
				case ADD:
					{
					setState(674);
					match(ADD);
					}
					break;
				case DASH:
					{
					setState(675);
					sub();
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(678);
				math_expression_mul();
				}
				}
				setState(683);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Math_expression_mulContext extends ParserRuleContext {
		public List<Math_expression_innerContext> math_expression_inner() {
			return getRuleContexts(Math_expression_innerContext.class);
		}
		public Math_expression_innerContext math_expression_inner(int i) {
			return getRuleContext(Math_expression_innerContext.class,i);
		}
		public List<TerminalNode> MUL() { return getTokens(GQLParser.MUL); }
		public TerminalNode MUL(int i) {
			return getToken(GQLParser.MUL, i);
		}
		public List<TerminalNode> DIV() { return getTokens(GQLParser.DIV); }
		public TerminalNode DIV(int i) {
			return getToken(GQLParser.DIV, i);
		}
		public List<TerminalNode> MOD() { return getTokens(GQLParser.MOD); }
		public TerminalNode MOD(int i) {
			return getToken(GQLParser.MOD, i);
		}
		public Math_expression_mulContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_math_expression_mul; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterMath_expression_mul(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitMath_expression_mul(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitMath_expression_mul(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Math_expression_mulContext math_expression_mul() throws RecognitionException {
		Math_expression_mulContext _localctx = new Math_expression_mulContext(_ctx, getState());
		enterRule(_localctx, 142, RULE_math_expression_mul);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(684);
			math_expression_inner();
			setState(689);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (((((_la - 84)) & ~0x3f) == 0 && ((1L << (_la - 84)) & 7L) != 0)) {
				{
				{
				setState(685);
				_la = _input.LA(1);
				if ( !(((((_la - 84)) & ~0x3f) == 0 && ((1L << (_la - 84)) & 7L) != 0)) ) {
				_errHandler.recoverInline(this);
				}
				else {
					if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
					_errHandler.reportMatch(this);
					consume();
				}
				setState(686);
				math_expression_inner();
				}
				}
				setState(691);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Math_expression_innerContext extends ParserRuleContext {
		public Math_expressionContext math_expression() {
			return getRuleContext(Math_expressionContext.class,0);
		}
		public SubContext sub() {
			return getRuleContext(SubContext.class,0);
		}
		public Math_expression_innerContext math_expression_inner() {
			return getRuleContext(Math_expression_innerContext.class,0);
		}
		public Numeric_literalContext numeric_literal() {
			return getRuleContext(Numeric_literalContext.class,0);
		}
		public Property_referenceContext property_reference() {
			return getRuleContext(Property_referenceContext.class,0);
		}
		public Math_expression_innerContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_math_expression_inner; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterMath_expression_inner(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitMath_expression_inner(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitMath_expression_inner(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Math_expression_innerContext math_expression_inner() throws RecognitionException {
		Math_expression_innerContext _localctx = new Math_expression_innerContext(_ctx, getState());
		enterRule(_localctx, 144, RULE_math_expression_inner);
		try {
			setState(706);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,75,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(692);
				match(T__0);
				setState(693);
				math_expression();
				setState(694);
				match(T__1);
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(696);
				sub();
				setState(697);
				match(T__0);
				setState(698);
				math_expression();
				setState(699);
				match(T__1);
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(701);
				sub();
				setState(702);
				math_expression_inner();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(704);
				numeric_literal();
				}
				break;
			case 5:
				enterOuterAlt(_localctx, 5);
				{
				setState(705);
				property_reference();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Comparison_operatorContext extends ParserRuleContext {
		public TerminalNode EQ() { return getToken(GQLParser.EQ, 0); }
		public TerminalNode NEQ() { return getToken(GQLParser.NEQ, 0); }
		public TerminalNode GT() { return getToken(GQLParser.GT, 0); }
		public TerminalNode GTE() { return getToken(GQLParser.GTE, 0); }
		public TerminalNode LT() { return getToken(GQLParser.LT, 0); }
		public TerminalNode LTE() { return getToken(GQLParser.LTE, 0); }
		public TerminalNode IN() { return getToken(GQLParser.IN, 0); }
		public Comparison_operatorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_comparison_operator; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterComparison_operator(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitComparison_operator(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitComparison_operator(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Comparison_operatorContext comparison_operator() throws RecognitionException {
		Comparison_operatorContext _localctx = new Comparison_operatorContext(_ctx, getState());
		enterRule(_localctx, 146, RULE_comparison_operator);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(708);
			_la = _input.LA(1);
			if ( !(((((_la - 33)) & ~0x3f) == 0 && ((1L << (_la - 33)) & 1108307720798209L) != 0)) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class SubContext extends ParserRuleContext {
		public TerminalNode DASH() { return getToken(GQLParser.DASH, 0); }
		public SubContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_sub; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterSub(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitSub(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitSub(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SubContext sub() throws RecognitionException {
		SubContext _localctx = new SubContext(_ctx, getState());
		enterRule(_localctx, 148, RULE_sub);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(710);
			match(DASH);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Numeric_literalContext extends ParserRuleContext {
		public TerminalNode NUMBER() { return getToken(GQLParser.NUMBER, 0); }
		public SubContext sub() {
			return getRuleContext(SubContext.class,0);
		}
		public Numeric_literalContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_numeric_literal; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterNumeric_literal(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitNumeric_literal(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitNumeric_literal(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Numeric_literalContext numeric_literal() throws RecognitionException {
		Numeric_literalContext _localctx = new Numeric_literalContext(_ctx, getState());
		enterRule(_localctx, 150, RULE_numeric_literal);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(713);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==DASH) {
				{
				setState(712);
				sub();
				}
			}

			setState(715);
			match(NUMBER);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Property_referenceContext extends ParserRuleContext {
		public List<TerminalNode> ID() { return getTokens(GQLParser.ID); }
		public TerminalNode ID(int i) {
			return getToken(GQLParser.ID, i);
		}
		public List<TerminalNode> DOT() { return getTokens(GQLParser.DOT); }
		public TerminalNode DOT(int i) {
			return getToken(GQLParser.DOT, i);
		}
		public Property_referenceContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_property_reference; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).enterProperty_reference(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof GQLListener ) ((GQLListener)listener).exitProperty_reference(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof GQLVisitor ) return ((GQLVisitor<? extends T>)visitor).visitProperty_reference(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Property_referenceContext property_reference() throws RecognitionException {
		Property_referenceContext _localctx = new Property_referenceContext(_ctx, getState());
		enterRule(_localctx, 152, RULE_property_reference);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(717);
			match(ID);
			setState(722);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==DOT) {
				{
				{
				setState(718);
				match(DOT);
				setState(719);
				match(ID);
				}
				}
				setState(724);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static final String _serializedATN =
		"\u0004\u0001^\u02d6\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002"+
		"\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004\u0002"+
		"\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007\u0007\u0007\u0002"+
		"\b\u0007\b\u0002\t\u0007\t\u0002\n\u0007\n\u0002\u000b\u0007\u000b\u0002"+
		"\f\u0007\f\u0002\r\u0007\r\u0002\u000e\u0007\u000e\u0002\u000f\u0007\u000f"+
		"\u0002\u0010\u0007\u0010\u0002\u0011\u0007\u0011\u0002\u0012\u0007\u0012"+
		"\u0002\u0013\u0007\u0013\u0002\u0014\u0007\u0014\u0002\u0015\u0007\u0015"+
		"\u0002\u0016\u0007\u0016\u0002\u0017\u0007\u0017\u0002\u0018\u0007\u0018"+
		"\u0002\u0019\u0007\u0019\u0002\u001a\u0007\u001a\u0002\u001b\u0007\u001b"+
		"\u0002\u001c\u0007\u001c\u0002\u001d\u0007\u001d\u0002\u001e\u0007\u001e"+
		"\u0002\u001f\u0007\u001f\u0002 \u0007 \u0002!\u0007!\u0002\"\u0007\"\u0002"+
		"#\u0007#\u0002$\u0007$\u0002%\u0007%\u0002&\u0007&\u0002\'\u0007\'\u0002"+
		"(\u0007(\u0002)\u0007)\u0002*\u0007*\u0002+\u0007+\u0002,\u0007,\u0002"+
		"-\u0007-\u0002.\u0007.\u0002/\u0007/\u00020\u00070\u00021\u00071\u0002"+
		"2\u00072\u00023\u00073\u00024\u00074\u00025\u00075\u00026\u00076\u0002"+
		"7\u00077\u00028\u00078\u00029\u00079\u0002:\u0007:\u0002;\u0007;\u0002"+
		"<\u0007<\u0002=\u0007=\u0002>\u0007>\u0002?\u0007?\u0002@\u0007@\u0002"+
		"A\u0007A\u0002B\u0007B\u0002C\u0007C\u0002D\u0007D\u0002E\u0007E\u0002"+
		"F\u0007F\u0002G\u0007G\u0002H\u0007H\u0002I\u0007I\u0002J\u0007J\u0002"+
		"K\u0007K\u0002L\u0007L\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0005\u0001\u00a1\b\u0001\n\u0001\f\u0001\u00a4"+
		"\t\u0001\u0001\u0002\u0001\u0002\u0001\u0002\u0003\u0002\u00a9\b\u0002"+
		"\u0001\u0002\u0005\u0002\u00ac\b\u0002\n\u0002\f\u0002\u00af\t\u0002\u0001"+
		"\u0003\u0005\u0003\u00b2\b\u0003\n\u0003\f\u0003\u00b5\t\u0003\u0001\u0003"+
		"\u0003\u0003\u00b8\b\u0003\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004"+
		"\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004"+
		"\u0003\u0004\u00c4\b\u0004\u0001\u0005\u0003\u0005\u00c7\b\u0005\u0001"+
		"\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001"+
		"\u0005\u0001\u0005\u0001\u0006\u0001\u0006\u0001\u0006\u0005\u0006\u00d4"+
		"\b\u0006\n\u0006\f\u0006\u00d7\t\u0006\u0003\u0006\u00d9\b\u0006\u0001"+
		"\u0007\u0001\u0007\u0003\u0007\u00dd\b\u0007\u0001\u0007\u0001\u0007\u0001"+
		"\b\u0001\b\u0001\b\u0001\b\u0001\b\u0001\b\u0001\b\u0003\b\u00e8\b\b\u0003"+
		"\b\u00ea\b\b\u0001\t\u0001\t\u0001\t\u0001\t\u0003\t\u00f0\b\t\u0001\n"+
		"\u0001\n\u0001\n\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0005"+
		"\u000b\u00f9\b\u000b\n\u000b\f\u000b\u00fc\t\u000b\u0001\f\u0001\f\u0001"+
		"\f\u0001\f\u0001\r\u0001\r\u0001\r\u0001\u000e\u0003\u000e\u0106\b\u000e"+
		"\u0001\u000e\u0001\u000e\u0003\u000e\u010a\b\u000e\u0001\u000e\u0001\u000e"+
		"\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f"+
		"\u0001\u0010\u0001\u0010\u0001\u0011\u0001\u0011\u0001\u0011\u0001\u0011"+
		"\u0003\u0011\u011a\b\u0011\u0001\u0012\u0001\u0012\u0001\u0012\u0001\u0013"+
		"\u0001\u0013\u0001\u0013\u0001\u0014\u0001\u0014\u0001\u0014\u0001\u0014"+
		"\u0001\u0015\u0001\u0015\u0003\u0015\u0128\b\u0015\u0001\u0015\u0003\u0015"+
		"\u012b\b\u0015\u0001\u0016\u0001\u0016\u0001\u0017\u0001\u0017\u0001\u0017"+
		"\u0003\u0017\u0132\b\u0017\u0001\u0017\u0003\u0017\u0135\b\u0017\u0001"+
		"\u0017\u0003\u0017\u0138\b\u0017\u0001\u0017\u0003\u0017\u013b\b\u0017"+
		"\u0001\u0017\u0003\u0017\u013e\b\u0017\u0001\u0017\u0003\u0017\u0141\b"+
		"\u0017\u0003\u0017\u0143\b\u0017\u0001\u0018\u0001\u0018\u0001\u0018\u0005"+
		"\u0018\u0148\b\u0018\n\u0018\f\u0018\u014b\t\u0018\u0001\u0019\u0001\u0019"+
		"\u0001\u0019\u0003\u0019\u0150\b\u0019\u0001\u001a\u0001\u001a\u0001\u001a"+
		"\u0001\u001a\u0001\u001a\u0005\u001a\u0157\b\u001a\n\u001a\f\u001a\u015a"+
		"\t\u001a\u0001\u001b\u0001\u001b\u0001\u001b\u0001\u001b\u0003\u001b\u0160"+
		"\b\u001b\u0001\u001c\u0001\u001c\u0003\u001c\u0164\b\u001c\u0001\u001c"+
		"\u0001\u001c\u0003\u001c\u0168\b\u001c\u0001\u001c\u0003\u001c\u016b\b"+
		"\u001c\u0001\u001d\u0001\u001d\u0003\u001d\u016f\b\u001d\u0001\u001e\u0001"+
		"\u001e\u0001\u001e\u0005\u001e\u0174\b\u001e\n\u001e\f\u001e\u0177\t\u001e"+
		"\u0001\u001f\u0001\u001f\u0001\u001f\u0003\u001f\u017c\b\u001f\u0001\u001f"+
		"\u0001\u001f\u0001\u001f\u0001\u001f\u0001\u001f\u0003\u001f\u0183\b\u001f"+
		"\u0001\u001f\u0001\u001f\u0001 \u0001 \u0001 \u0003 \u018a\b \u0001 \u0001"+
		" \u0005 \u018e\b \n \f \u0191\t \u0001!\u0001!\u0001!\u0001!\u0001!\u0001"+
		"!\u0003!\u0199\b!\u0003!\u019b\b!\u0001!\u0001!\u0001!\u0001!\u0001!\u0003"+
		"!\u01a2\b!\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0003\"\u01aa"+
		"\b\"\u0001#\u0001#\u0003#\u01ae\b#\u0001#\u0001#\u0003#\u01b2\b#\u0001"+
		"#\u0001#\u0003#\u01b6\b#\u0003#\u01b8\b#\u0001$\u0001$\u0001$\u0001$\u0001"+
		"%\u0001%\u0001%\u0001%\u0001%\u0001%\u0003%\u01c4\b%\u0001&\u0001&\u0001"+
		"&\u0001&\u0001&\u0001&\u0001\'\u0001\'\u0001\'\u0001\'\u0001\'\u0001\'"+
		"\u0001(\u0001(\u0001(\u0001(\u0001(\u0001(\u0001)\u0001)\u0001*\u0001"+
		"*\u0001+\u0001+\u0001,\u0003,\u01df\b,\u0001,\u0003,\u01e2\b,\u0001,\u0001"+
		",\u0003,\u01e6\b,\u0001,\u0003,\u01e9\b,\u0001-\u0001-\u0001-\u0001.\u0001"+
		".\u0001.\u0005.\u01f1\b.\n.\f.\u01f4\t.\u0001/\u0001/\u0001/\u0005/\u01f9"+
		"\b/\n/\f/\u01fc\t/\u00010\u00030\u01ff\b0\u00010\u00010\u00010\u00030"+
		"\u0204\b0\u00011\u00011\u00011\u00011\u00011\u00031\u020b\b1\u00012\u0001"+
		"2\u00013\u00013\u00013\u00014\u00014\u00014\u00015\u00015\u00015\u0001"+
		"5\u00016\u00016\u00016\u00056\u021c\b6\n6\f6\u021f\t6\u00017\u00017\u0001"+
		"7\u00017\u00018\u00018\u00019\u00019\u00019\u00059\u022a\b9\n9\f9\u022d"+
		"\t9\u0001:\u0001:\u0001:\u0005:\u0232\b:\n:\f:\u0235\t:\u0001;\u0001;"+
		"\u0001;\u0001;\u0001;\u0001;\u0001;\u0001;\u0001;\u0001;\u0003;\u0241"+
		"\b;\u0001;\u0001;\u0003;\u0245\b;\u0001<\u0001<\u0001<\u0001<\u0001=\u0001"+
		"=\u0001=\u0001=\u0001=\u0001=\u0001=\u0001=\u0001=\u0001=\u0001=\u0003"+
		"=\u0256\b=\u0001>\u0001>\u0001>\u0001>\u0005>\u025c\b>\n>\f>\u025f\t>"+
		"\u0003>\u0261\b>\u0001>\u0001>\u0001?\u0001?\u0001?\u0001?\u0005?\u0269"+
		"\b?\n?\f?\u026c\t?\u0003?\u026e\b?\u0001?\u0001?\u0001@\u0001@\u0001@"+
		"\u0001@\u0001A\u0001A\u0001A\u0001A\u0001A\u0003A\u027b\bA\u0001B\u0001"+
		"B\u0001B\u0001B\u0001B\u0001C\u0001C\u0003C\u0284\bC\u0001C\u0001C\u0001"+
		"C\u0001C\u0001C\u0004C\u028b\bC\u000bC\fC\u028c\u0001C\u0001C\u0003C\u0291"+
		"\bC\u0001C\u0001C\u0001D\u0001D\u0001D\u0003D\u0298\bD\u0001D\u0001D\u0001"+
		"D\u0001E\u0001E\u0001E\u0001E\u0001E\u0001F\u0001F\u0001F\u0003F\u02a5"+
		"\bF\u0001F\u0005F\u02a8\bF\nF\fF\u02ab\tF\u0001G\u0001G\u0001G\u0005G"+
		"\u02b0\bG\nG\fG\u02b3\tG\u0001H\u0001H\u0001H\u0001H\u0001H\u0001H\u0001"+
		"H\u0001H\u0001H\u0001H\u0001H\u0001H\u0001H\u0001H\u0003H\u02c3\bH\u0001"+
		"I\u0001I\u0001J\u0001J\u0001K\u0003K\u02ca\bK\u0001K\u0001K\u0001L\u0001"+
		"L\u0001L\u0005L\u02d1\bL\nL\fL\u02d4\tL\u0001L\u0000\u0000M\u0000\u0002"+
		"\u0004\u0006\b\n\f\u000e\u0010\u0012\u0014\u0016\u0018\u001a\u001c\u001e"+
		" \"$&(*,.02468:<>@BDFHJLNPRTVXZ\\^`bdfhjlnprtvxz|~\u0080\u0082\u0084\u0086"+
		"\u0088\u008a\u008c\u008e\u0090\u0092\u0094\u0096\u0098\u0000\r\u0001\u0000"+
		"@B\u0002\u0000##33\u0001\u0000/2\u0001\u0000*+\u0002\u0000\t\t--\u0002"+
		"\u0000\n\n\u001e\u001e\u0002\u0000\u000b\u000b\u001f\u001f\u0002\u0000"+
		"YY\\\\\u0001\u0000CF\u0001\u0000GJ\u0001\u00009>\u0001\u0000TV\u0002\u0000"+
		"!!MR\u02fd\u0000\u009a\u0001\u0000\u0000\u0000\u0002\u009d\u0001\u0000"+
		"\u0000\u0000\u0004\u00a5\u0001\u0000\u0000\u0000\u0006\u00b3\u0001\u0000"+
		"\u0000\u0000\b\u00c3\u0001\u0000\u0000\u0000\n\u00c6\u0001\u0000\u0000"+
		"\u0000\f\u00d8\u0001\u0000\u0000\u0000\u000e\u00da\u0001\u0000\u0000\u0000"+
		"\u0010\u00e0\u0001\u0000\u0000\u0000\u0012\u00ef\u0001\u0000\u0000\u0000"+
		"\u0014\u00f1\u0001\u0000\u0000\u0000\u0016\u00f4\u0001\u0000\u0000\u0000"+
		"\u0018\u00fd\u0001\u0000\u0000\u0000\u001a\u0101\u0001\u0000\u0000\u0000"+
		"\u001c\u0105\u0001\u0000\u0000\u0000\u001e\u010d\u0001\u0000\u0000\u0000"+
		" \u0113\u0001\u0000\u0000\u0000\"\u0119\u0001\u0000\u0000\u0000$\u011b"+
		"\u0001\u0000\u0000\u0000&\u011e\u0001\u0000\u0000\u0000(\u0121\u0001\u0000"+
		"\u0000\u0000*\u0127\u0001\u0000\u0000\u0000,\u012c\u0001\u0000\u0000\u0000"+
		".\u012e\u0001\u0000\u0000\u00000\u0144\u0001\u0000\u0000\u00002\u014c"+
		"\u0001\u0000\u0000\u00004\u0151\u0001\u0000\u0000\u00006\u015f\u0001\u0000"+
		"\u0000\u00008\u0161\u0001\u0000\u0000\u0000:\u016c\u0001\u0000\u0000\u0000"+
		"<\u0170\u0001\u0000\u0000\u0000>\u017b\u0001\u0000\u0000\u0000@\u0186"+
		"\u0001\u0000\u0000\u0000B\u01a1\u0001\u0000\u0000\u0000D\u01a9\u0001\u0000"+
		"\u0000\u0000F\u01b7\u0001\u0000\u0000\u0000H\u01b9\u0001\u0000\u0000\u0000"+
		"J\u01c3\u0001\u0000\u0000\u0000L\u01c5\u0001\u0000\u0000\u0000N\u01cb"+
		"\u0001\u0000\u0000\u0000P\u01d1\u0001\u0000\u0000\u0000R\u01d7\u0001\u0000"+
		"\u0000\u0000T\u01d9\u0001\u0000\u0000\u0000V\u01db\u0001\u0000\u0000\u0000"+
		"X\u01de\u0001\u0000\u0000\u0000Z\u01ea\u0001\u0000\u0000\u0000\\\u01ed"+
		"\u0001\u0000\u0000\u0000^\u01f5\u0001\u0000\u0000\u0000`\u0203\u0001\u0000"+
		"\u0000\u0000b\u020a\u0001\u0000\u0000\u0000d\u020c\u0001\u0000\u0000\u0000"+
		"f\u020e\u0001\u0000\u0000\u0000h\u0211\u0001\u0000\u0000\u0000j\u0214"+
		"\u0001\u0000\u0000\u0000l\u0218\u0001\u0000\u0000\u0000n\u0220\u0001\u0000"+
		"\u0000\u0000p\u0224\u0001\u0000\u0000\u0000r\u0226\u0001\u0000\u0000\u0000"+
		"t\u022e\u0001\u0000\u0000\u0000v\u0244\u0001\u0000\u0000\u0000x\u0246"+
		"\u0001\u0000\u0000\u0000z\u0255\u0001\u0000\u0000\u0000|\u0257\u0001\u0000"+
		"\u0000\u0000~\u0264\u0001\u0000\u0000\u0000\u0080\u0271\u0001\u0000\u0000"+
		"\u0000\u0082\u0275\u0001\u0000\u0000\u0000\u0084\u027c\u0001\u0000\u0000"+
		"\u0000\u0086\u0281\u0001\u0000\u0000\u0000\u0088\u0294\u0001\u0000\u0000"+
		"\u0000\u008a\u029c\u0001\u0000\u0000\u0000\u008c\u02a1\u0001\u0000\u0000"+
		"\u0000\u008e\u02ac\u0001\u0000\u0000\u0000\u0090\u02c2\u0001\u0000\u0000"+
		"\u0000\u0092\u02c4\u0001\u0000\u0000\u0000\u0094\u02c6\u0001\u0000\u0000"+
		"\u0000\u0096\u02c9\u0001\u0000\u0000\u0000\u0098\u02cd\u0001\u0000\u0000"+
		"\u0000\u009a\u009b\u0003\u0002\u0001\u0000\u009b\u009c\u0005\u0000\u0000"+
		"\u0001\u009c\u0001\u0001\u0000\u0000\u0000\u009d\u00a2\u0003\u0004\u0002"+
		"\u0000\u009e\u009f\u0005\u0013\u0000\u0000\u009f\u00a1\u0003\u0004\u0002"+
		"\u0000\u00a0\u009e\u0001\u0000\u0000\u0000\u00a1\u00a4\u0001\u0000\u0000"+
		"\u0000\u00a2\u00a0\u0001\u0000\u0000\u0000\u00a2\u00a3\u0001\u0000\u0000"+
		"\u0000\u00a3\u0003\u0001\u0000\u0000\u0000\u00a4\u00a2\u0001\u0000\u0000"+
		"\u0000\u00a5\u00ad\u0003\u0006\u0003\u0000\u00a6\u00a8\u0007\u0000\u0000"+
		"\u0000\u00a7\u00a9\u0007\u0001\u0000\u0000\u00a8\u00a7\u0001\u0000\u0000"+
		"\u0000\u00a8\u00a9\u0001\u0000\u0000\u0000\u00a9\u00aa\u0001\u0000\u0000"+
		"\u0000\u00aa\u00ac\u0003\u0006\u0003\u0000\u00ab\u00a6\u0001\u0000\u0000"+
		"\u0000\u00ac\u00af\u0001\u0000\u0000\u0000\u00ad\u00ab\u0001\u0000\u0000"+
		"\u0000\u00ad\u00ae\u0001\u0000\u0000\u0000\u00ae\u0005\u0001\u0000\u0000"+
		"\u0000\u00af\u00ad\u0001\u0000\u0000\u0000\u00b0\u00b2\u0003\b\u0004\u0000"+
		"\u00b1\u00b0\u0001\u0000\u0000\u0000\u00b2\u00b5\u0001\u0000\u0000\u0000"+
		"\u00b3\u00b1\u0001\u0000\u0000\u0000\u00b3\u00b4\u0001\u0000\u0000\u0000"+
		"\u00b4\u00b7\u0001\u0000\u0000\u0000\u00b5\u00b3\u0001\u0000\u0000\u0000"+
		"\u00b6\u00b8\u0003.\u0017\u0000\u00b7\u00b6\u0001\u0000\u0000\u0000\u00b7"+
		"\u00b8\u0001\u0000\u0000\u0000\u00b8\u0007\u0001\u0000\u0000\u0000\u00b9"+
		"\u00c4\u0003\n\u0005\u0000\u00ba\u00c4\u0003\u000e\u0007\u0000\u00bb\u00c4"+
		"\u0003\u0010\b\u0000\u00bc\u00c4\u0003\u0016\u000b\u0000\u00bd\u00c4\u0003"+
		"\u001a\r\u0000\u00be\u00c4\u0003\u001c\u000e\u0000\u00bf\u00c4\u0003$"+
		"\u0012\u0000\u00c0\u00c4\u0003(\u0014\u0000\u00c1\u00c4\u0003&\u0013\u0000"+
		"\u00c2\u00c4\u00038\u001c\u0000\u00c3\u00b9\u0001\u0000\u0000\u0000\u00c3"+
		"\u00ba\u0001\u0000\u0000\u0000\u00c3\u00bb\u0001\u0000\u0000\u0000\u00c3"+
		"\u00bc\u0001\u0000\u0000\u0000\u00c3\u00bd\u0001\u0000\u0000\u0000\u00c3"+
		"\u00be\u0001\u0000\u0000\u0000\u00c3\u00bf\u0001\u0000\u0000\u0000\u00c3"+
		"\u00c0\u0001\u0000\u0000\u0000\u00c3\u00c1\u0001\u0000\u0000\u0000\u00c3"+
		"\u00c2\u0001\u0000\u0000\u0000\u00c4\t\u0001\u0000\u0000\u0000\u00c5\u00c7"+
		"\u0005\u001b\u0000\u0000\u00c6\u00c5\u0001\u0000\u0000\u0000\u00c6\u00c7"+
		"\u0001\u0000\u0000\u0000\u00c7\u00c8\u0001\u0000\u0000\u0000\u00c8\u00c9"+
		"\u0005\u000e\u0000\u0000\u00c9\u00ca\u0005\u0001\u0000\u0000\u00ca\u00cb"+
		"\u0003\f\u0006\u0000\u00cb\u00cc\u0005\u0002\u0000\u0000\u00cc\u00cd\u0005"+
		"\u0003\u0000\u0000\u00cd\u00ce\u0003\u0000\u0000\u0000\u00ce\u00cf\u0005"+
		"\u0004\u0000\u0000\u00cf\u000b\u0001\u0000\u0000\u0000\u00d0\u00d5\u0005"+
		"Y\u0000\u0000\u00d1\u00d2\u0005\u0005\u0000\u0000\u00d2\u00d4\u0005Y\u0000"+
		"\u0000\u00d3\u00d1\u0001\u0000\u0000\u0000\u00d4\u00d7\u0001\u0000\u0000"+
		"\u0000\u00d5\u00d3\u0001\u0000\u0000\u0000\u00d5\u00d6\u0001\u0000\u0000"+
		"\u0000\u00d6\u00d9\u0001\u0000\u0000\u0000\u00d7\u00d5\u0001\u0000\u0000"+
		"\u0000\u00d8\u00d0\u0001\u0000\u0000\u0000\u00d8\u00d9\u0001\u0000\u0000"+
		"\u0000\u00d9\r\u0001\u0000\u0000\u0000\u00da\u00dc\u0005\u000f\u0000\u0000"+
		"\u00db\u00dd\u0005\u001d\u0000\u0000\u00dc\u00db\u0001\u0000\u0000\u0000"+
		"\u00dc\u00dd\u0001\u0000\u0000\u0000\u00dd\u00de\u0001\u0000\u0000\u0000"+
		"\u00de\u00df\u0003r9\u0000\u00df\u000f\u0001\u0000\u0000\u0000\u00e0\u00e1"+
		"\u0005\u0010\u0000\u0000\u00e1\u00e2\u0005\\\u0000\u0000\u00e2\u00e3\u0005"+
		"!\u0000\u0000\u00e3\u00e9\u0003\u0012\t\u0000\u00e4\u00e5\u0005\u0016"+
		"\u0000\u0000\u00e5\u00e7\u0005\u0017\u0000\u0000\u00e6\u00e8\u0003\u0014"+
		"\n\u0000\u00e7\u00e6\u0001\u0000\u0000\u0000\u00e7\u00e8\u0001\u0000\u0000"+
		"\u0000\u00e8\u00ea\u0001\u0000\u0000\u0000\u00e9\u00e4\u0001\u0000\u0000"+
		"\u0000\u00e9\u00ea\u0001\u0000\u0000\u0000\u00ea\u0011\u0001\u0000\u0000"+
		"\u0000\u00eb\u00f0\u0003|>\u0000\u00ec\u00f0\u0005Y\u0000\u0000\u00ed"+
		"\u00f0\u0003\u0098L\u0000\u00ee\u00f0\u0005\"\u0000\u0000\u00ef\u00eb"+
		"\u0001\u0000\u0000\u0000\u00ef\u00ec\u0001\u0000\u0000\u0000\u00ef\u00ed"+
		"\u0001\u0000\u0000\u0000\u00ef\u00ee\u0001\u0000\u0000\u0000\u00f0\u0013"+
		"\u0001\u0000\u0000\u0000\u00f1\u00f2\u0005\u001c\u0000\u0000\u00f2\u00f3"+
		"\u0005\\\u0000\u0000\u00f3\u0015\u0001\u0000\u0000\u0000\u00f4\u00f5\u0005"+
		"\u0011\u0000\u0000\u00f5\u00fa\u0003\u0018\f\u0000\u00f6\u00f7\u0005\u0005"+
		"\u0000\u0000\u00f7\u00f9\u0003\u0018\f\u0000\u00f8\u00f6\u0001\u0000\u0000"+
		"\u0000\u00f9\u00fc\u0001\u0000\u0000\u0000\u00fa\u00f8\u0001\u0000\u0000"+
		"\u0000\u00fa\u00fb\u0001\u0000\u0000\u0000\u00fb\u0017\u0001\u0000\u0000"+
		"\u0000\u00fc\u00fa\u0001\u0000\u0000\u0000\u00fd\u00fe\u0005\\\u0000\u0000"+
		"\u00fe\u00ff\u0005R\u0000\u0000\u00ff\u0100\u0003z=\u0000\u0100\u0019"+
		"\u0001\u0000\u0000\u0000\u0101\u0102\u0005\u0012\u0000\u0000\u0102\u0103"+
		"\u0005[\u0000\u0000\u0103\u001b\u0001\u0000\u0000\u0000\u0104\u0106\u0005"+
		"\u001b\u0000\u0000\u0105\u0104\u0001\u0000\u0000\u0000\u0105\u0106\u0001"+
		"\u0000\u0000\u0000\u0106\u0107\u0001\u0000\u0000\u0000\u0107\u0109\u0005"+
		"\r\u0000\u0000\u0108\u010a\u0003\u001e\u000f\u0000\u0109\u0108\u0001\u0000"+
		"\u0000\u0000\u0109\u010a\u0001\u0000\u0000\u0000\u010a\u010b\u0001\u0000"+
		"\u0000\u0000\u010b\u010c\u0003:\u001d\u0000\u010c\u001d\u0001\u0000\u0000"+
		"\u0000\u010d\u010e\u0005\u0006\u0000\u0000\u010e\u010f\u0003 \u0010\u0000"+
		"\u010f\u0110\u0005R\u0000\u0000\u0110\u0111\u0003\"\u0011\u0000\u0111"+
		"\u0112\u0005\u0004\u0000\u0000\u0112\u001f\u0001\u0000\u0000\u0000\u0113"+
		"\u0114\u0005Y\u0000\u0000\u0114!\u0001\u0000\u0000\u0000\u0115\u011a\u0005"+
		"Y\u0000\u0000\u0116\u011a\u0005\\\u0000\u0000\u0117\u011a\u0003\u008c"+
		"F\u0000\u0118\u011a\u0005W\u0000\u0000\u0119\u0115\u0001\u0000\u0000\u0000"+
		"\u0119\u0116\u0001\u0000\u0000\u0000\u0119\u0117\u0001\u0000\u0000\u0000"+
		"\u0119\u0118\u0001\u0000\u0000\u0000\u011a#\u0001\u0000\u0000\u0000\u011b"+
		"\u011c\u0005\u0017\u0000\u0000\u011c\u011d\u0005[\u0000\u0000\u011d%\u0001"+
		"\u0000\u0000\u0000\u011e\u011f\u0005\u0015\u0000\u0000\u011f\u0120\u0005"+
		"[\u0000\u0000\u0120\'\u0001\u0000\u0000\u0000\u0121\u0122\u0005\u0018"+
		"\u0000\u0000\u0122\u0123\u0005\u0019\u0000\u0000\u0123\u0124\u0003*\u0015"+
		"\u0000\u0124)\u0001\u0000\u0000\u0000\u0125\u0126\u0005.\u0000\u0000\u0126"+
		"\u0128\u0003,\u0016\u0000\u0127\u0125\u0001\u0000\u0000\u0000\u0127\u0128"+
		"\u0001\u0000\u0000\u0000\u0128\u012a\u0001\u0000\u0000\u0000\u0129\u012b"+
		"\u0007\u0002\u0000\u0000\u012a\u0129\u0001\u0000\u0000\u0000\u012a\u012b"+
		"\u0001\u0000\u0000\u0000\u012b+\u0001\u0000\u0000\u0000\u012c\u012d\u0005"+
		"\\\u0000\u0000\u012d-\u0001\u0000\u0000\u0000\u012e\u0142\u0005\u0014"+
		"\u0000\u0000\u012f\u0143\u0005T\u0000\u0000\u0130\u0132\u0007\u0001\u0000"+
		"\u0000\u0131\u0130\u0001\u0000\u0000\u0000\u0131\u0132\u0001\u0000\u0000"+
		"\u0000\u0132\u0134\u0001\u0000\u0000\u0000\u0133\u0135\u00030\u0018\u0000"+
		"\u0134\u0133\u0001\u0000\u0000\u0000\u0134\u0135\u0001\u0000\u0000\u0000"+
		"\u0135\u0137\u0001\u0000\u0000\u0000\u0136\u0138\u00034\u001a\u0000\u0137"+
		"\u0136\u0001\u0000\u0000\u0000\u0137\u0138\u0001\u0000\u0000\u0000\u0138"+
		"\u013a\u0001\u0000\u0000\u0000\u0139\u013b\u0003(\u0014\u0000\u013a\u0139"+
		"\u0001\u0000\u0000\u0000\u013a\u013b\u0001\u0000\u0000\u0000\u013b\u013d"+
		"\u0001\u0000\u0000\u0000\u013c\u013e\u0003\u001a\r\u0000\u013d\u013c\u0001"+
		"\u0000\u0000\u0000\u013d\u013e\u0001\u0000\u0000\u0000\u013e\u0140\u0001"+
		"\u0000\u0000\u0000\u013f\u0141\u0003$\u0012\u0000\u0140\u013f\u0001\u0000"+
		"\u0000\u0000\u0140\u0141\u0001\u0000\u0000\u0000\u0141\u0143\u0001\u0000"+
		"\u0000\u0000\u0142\u012f\u0001\u0000\u0000\u0000\u0142\u0131\u0001\u0000"+
		"\u0000\u0000\u0143/\u0001\u0000\u0000\u0000\u0144\u0149\u00032\u0019\u0000"+
		"\u0145\u0146\u0005\u0005\u0000\u0000\u0146\u0148\u00032\u0019\u0000\u0147"+
		"\u0145\u0001\u0000\u0000\u0000\u0148\u014b\u0001\u0000\u0000\u0000\u0149"+
		"\u0147\u0001\u0000\u0000\u0000\u0149\u014a\u0001\u0000\u0000\u0000\u014a"+
		"1\u0001\u0000\u0000\u0000\u014b\u0149\u0001\u0000\u0000\u0000\u014c\u014f"+
		"\u0003z=\u0000\u014d\u014e\u0005\u001c\u0000\u0000\u014e\u0150\u0005Y"+
		"\u0000\u0000\u014f\u014d\u0001\u0000\u0000\u0000\u014f\u0150\u0001\u0000"+
		"\u0000\u0000\u01503\u0001\u0000\u0000\u0000\u0151\u0152\u0005\u001a\u0000"+
		"\u0000\u0152\u0153\u0005\u0019\u0000\u0000\u0153\u0158\u00036\u001b\u0000"+
		"\u0154\u0155\u0005\u0005\u0000\u0000\u0155\u0157\u00036\u001b\u0000\u0156"+
		"\u0154\u0001\u0000\u0000\u0000\u0157\u015a\u0001\u0000\u0000\u0000\u0158"+
		"\u0156\u0001\u0000\u0000\u0000\u0158\u0159\u0001\u0000\u0000\u0000\u0159"+
		"5\u0001\u0000\u0000\u0000\u015a\u0158\u0001\u0000\u0000\u0000\u015b\u0160"+
		"\u0003\u0098L\u0000\u015c\u0160\u0005Y\u0000\u0000\u015d\u0160\u0005["+
		"\u0000\u0000\u015e\u0160\u0003z=\u0000\u015f\u015b\u0001\u0000\u0000\u0000"+
		"\u015f\u015c\u0001\u0000\u0000\u0000\u015f\u015d\u0001\u0000\u0000\u0000"+
		"\u015f\u015e\u0001\u0000\u0000\u0000\u01607\u0001\u0000\u0000\u0000\u0161"+
		"\u0163\u0005\u0016\u0000\u0000\u0162\u0164\u0007\u0001\u0000\u0000\u0163"+
		"\u0162\u0001\u0000\u0000\u0000\u0163\u0164\u0001\u0000\u0000\u0000\u0164"+
		"\u0167\u0001\u0000\u0000\u0000\u0165\u0168\u00030\u0018\u0000\u0166\u0168"+
		"\u0005T\u0000\u0000\u0167\u0165\u0001\u0000\u0000\u0000\u0167\u0166\u0001"+
		"\u0000\u0000\u0000\u0168\u016a\u0001\u0000\u0000\u0000\u0169\u016b\u0003"+
		"4\u001a\u0000\u016a\u0169\u0001\u0000\u0000\u0000\u016a\u016b\u0001\u0000"+
		"\u0000\u0000\u016b9\u0001\u0000\u0000\u0000\u016c\u016e\u0003<\u001e\u0000"+
		"\u016d\u016f\u0003h4\u0000\u016e\u016d\u0001\u0000\u0000\u0000\u016e\u016f"+
		"\u0001\u0000\u0000\u0000\u016f;\u0001\u0000\u0000\u0000\u0170\u0175\u0003"+
		">\u001f\u0000\u0171\u0172\u0005\u0005\u0000\u0000\u0172\u0174\u0003>\u001f"+
		"\u0000\u0173\u0171\u0001\u0000\u0000\u0000\u0174\u0177\u0001\u0000\u0000"+
		"\u0000\u0175\u0173\u0001\u0000\u0000\u0000\u0175\u0176\u0001\u0000\u0000"+
		"\u0000\u0176=\u0001\u0000\u0000\u0000\u0177\u0175\u0001\u0000\u0000\u0000"+
		"\u0178\u0179\u0003p8\u0000\u0179\u017a\u0005R\u0000\u0000\u017a\u017c"+
		"\u0001\u0000\u0000\u0000\u017b\u0178\u0001\u0000\u0000\u0000\u017b\u017c"+
		"\u0001\u0000\u0000\u0000\u017c\u0182\u0001\u0000\u0000\u0000\u017d\u017e"+
		"\u0005\u0003\u0000\u0000\u017e\u0183\u0003D\"\u0000\u017f\u0180\u0003"+
		"F#\u0000\u0180\u0181\u0005\u0004\u0000\u0000\u0181\u0183\u0001\u0000\u0000"+
		"\u0000\u0182\u017d\u0001\u0000\u0000\u0000\u0182\u017f\u0001\u0000\u0000"+
		"\u0000\u0182\u0183\u0001\u0000\u0000\u0000\u0183\u0184\u0001\u0000\u0000"+
		"\u0000\u0184\u0185\u0003@ \u0000\u0185?\u0001\u0000\u0000\u0000\u0186"+
		"\u018f\u0003H$\u0000\u0187\u0189\u0003J%\u0000\u0188\u018a\u0003B!\u0000"+
		"\u0189\u0188\u0001\u0000\u0000\u0000\u0189\u018a\u0001\u0000\u0000\u0000"+
		"\u018a\u018b\u0001\u0000\u0000\u0000\u018b\u018c\u0003H$\u0000\u018c\u018e"+
		"\u0001\u0000\u0000\u0000\u018d\u0187\u0001\u0000\u0000\u0000\u018e\u0191"+
		"\u0001\u0000\u0000\u0000\u018f\u018d\u0001\u0000\u0000\u0000\u018f\u0190"+
		"\u0001\u0000\u0000\u0000\u0190A\u0001\u0000\u0000\u0000\u0191\u018f\u0001"+
		"\u0000\u0000\u0000\u0192\u01a2\u0005T\u0000\u0000\u0193\u01a2\u0005S\u0000"+
		"\u0000\u0194\u0195\u0005\u0003\u0000\u0000\u0195\u019a\u0005[\u0000\u0000"+
		"\u0196\u0198\u0005\u0005\u0000\u0000\u0197\u0199\u0005[\u0000\u0000\u0198"+
		"\u0197\u0001\u0000\u0000\u0000\u0198\u0199\u0001\u0000\u0000\u0000\u0199"+
		"\u019b\u0001\u0000\u0000\u0000\u019a\u0196\u0001\u0000\u0000\u0000\u019a"+
		"\u019b\u0001\u0000\u0000\u0000\u019b\u019c\u0001\u0000\u0000\u0000\u019c"+
		"\u01a2\u0005\u0004\u0000\u0000\u019d\u019e\u0005\u0003\u0000\u0000\u019e"+
		"\u019f\u0005\u0005\u0000\u0000\u019f\u01a0\u0005[\u0000\u0000\u01a0\u01a2"+
		"\u0005\u0004\u0000\u0000\u01a1\u0192\u0001\u0000\u0000\u0000\u01a1\u0193"+
		"\u0001\u0000\u0000\u0000\u01a1\u0194\u0001\u0000\u0000\u0000\u01a1\u019d"+
		"\u0001\u0000\u0000\u0000\u01a2C\u0001\u0000\u0000\u0000\u01a3\u01aa\u0005"+
		"#\u0000\u0000\u01a4\u01aa\u0005$\u0000\u0000\u01a5\u01a6\u0005$\u0000"+
		"\u0000\u01a6\u01aa\u0005%\u0000\u0000\u01a7\u01a8\u0005$\u0000\u0000\u01a8"+
		"\u01aa\u0005&\u0000\u0000\u01a9\u01a3\u0001\u0000\u0000\u0000\u01a9\u01a4"+
		"\u0001\u0000\u0000\u0000\u01a9\u01a5\u0001\u0000\u0000\u0000\u01a9\u01a7"+
		"\u0001\u0000\u0000\u0000\u01aaE\u0001\u0000\u0000\u0000\u01ab\u01ad\u0005"+
		"\'\u0000\u0000\u01ac\u01ae\u0007\u0003\u0000\u0000\u01ad\u01ac\u0001\u0000"+
		"\u0000\u0000\u01ad\u01ae\u0001\u0000\u0000\u0000\u01ae\u01b8\u0001\u0000"+
		"\u0000\u0000\u01af\u01b1\u0005(\u0000\u0000\u01b0\u01b2\u0007\u0003\u0000"+
		"\u0000\u01b1\u01b0\u0001\u0000\u0000\u0000\u01b1\u01b2\u0001\u0000\u0000"+
		"\u0000\u01b2\u01b8\u0001\u0000\u0000\u0000\u01b3\u01b5\u0005)\u0000\u0000"+
		"\u01b4\u01b6\u0007\u0003\u0000\u0000\u01b5\u01b4\u0001\u0000\u0000\u0000"+
		"\u01b5\u01b6\u0001\u0000\u0000\u0000\u01b6\u01b8\u0001\u0000\u0000\u0000"+
		"\u01b7\u01ab\u0001\u0000\u0000\u0000\u01b7\u01af\u0001\u0000\u0000\u0000"+
		"\u01b7\u01b3\u0001\u0000\u0000\u0000\u01b8G\u0001\u0000\u0000\u0000\u01b9"+
		"\u01ba\u0005\u0001\u0000\u0000\u01ba\u01bb\u0003X,\u0000\u01bb\u01bc\u0005"+
		"\u0002\u0000\u0000\u01bcI\u0001\u0000\u0000\u0000\u01bd\u01c4\u0003L&"+
		"\u0000\u01be\u01c4\u0003N\'\u0000\u01bf\u01c4\u0003P(\u0000\u01c0\u01c4"+
		"\u0003R)\u0000\u01c1\u01c4\u0003T*\u0000\u01c2\u01c4\u0003V+\u0000\u01c3"+
		"\u01bd\u0001\u0000\u0000\u0000\u01c3\u01be\u0001\u0000\u0000\u0000\u01c3"+
		"\u01bf\u0001\u0000\u0000\u0000\u01c3\u01c0\u0001\u0000\u0000\u0000\u01c3"+
		"\u01c1\u0001\u0000\u0000\u0000\u01c3\u01c2\u0001\u0000\u0000\u0000\u01c4"+
		"K\u0001\u0000\u0000\u0000\u01c5\u01c6\u0005]\u0000\u0000\u01c6\u01c7\u0005"+
		"\u0007\u0000\u0000\u01c7\u01c8\u0003X,\u0000\u01c8\u01c9\u0005\b\u0000"+
		"\u0000\u01c9\u01ca\u0005]\u0000\u0000\u01caM\u0001\u0000\u0000\u0000\u01cb"+
		"\u01cc\u0005L\u0000\u0000\u01cc\u01cd\u0005\u0007\u0000\u0000\u01cd\u01ce"+
		"\u0003X,\u0000\u01ce\u01cf\u0005\b\u0000\u0000\u01cf\u01d0\u0005]\u0000"+
		"\u0000\u01d0O\u0001\u0000\u0000\u0000\u01d1\u01d2\u0005]\u0000\u0000\u01d2"+
		"\u01d3\u0005\u0007\u0000\u0000\u01d3\u01d4\u0003X,\u0000\u01d4\u01d5\u0005"+
		"\b\u0000\u0000\u01d5\u01d6\u0005K\u0000\u0000\u01d6Q\u0001\u0000\u0000"+
		"\u0000\u01d7\u01d8\u0005]\u0000\u0000\u01d8S\u0001\u0000\u0000\u0000\u01d9"+
		"\u01da\u0005L\u0000\u0000\u01daU\u0001\u0000\u0000\u0000\u01db\u01dc\u0005"+
		"K\u0000\u0000\u01dcW\u0001\u0000\u0000\u0000\u01dd\u01df\u0003d2\u0000"+
		"\u01de\u01dd\u0001\u0000\u0000\u0000\u01de\u01df\u0001\u0000\u0000\u0000"+
		"\u01df\u01e1\u0001\u0000\u0000\u0000\u01e0\u01e2\u0003Z-\u0000\u01e1\u01e0"+
		"\u0001\u0000\u0000\u0000\u01e1\u01e2\u0001\u0000\u0000\u0000\u01e2\u01e5"+
		"\u0001\u0000\u0000\u0000\u01e3\u01e6\u0003h4\u0000\u01e4\u01e6\u0003j"+
		"5\u0000\u01e5\u01e3\u0001\u0000\u0000\u0000\u01e5\u01e4\u0001\u0000\u0000"+
		"\u0000\u01e5\u01e6\u0001\u0000\u0000\u0000\u01e6\u01e8\u0001\u0000\u0000"+
		"\u0000\u01e7\u01e9\u0003f3\u0000\u01e8\u01e7\u0001\u0000\u0000\u0000\u01e8"+
		"\u01e9\u0001\u0000\u0000\u0000\u01e9Y\u0001\u0000\u0000\u0000\u01ea\u01eb"+
		"\u0007\u0004\u0000\u0000\u01eb\u01ec\u0003\\.\u0000\u01ec[\u0001\u0000"+
		"\u0000\u0000\u01ed\u01f2\u0003^/\u0000\u01ee\u01ef\u0007\u0005\u0000\u0000"+
		"\u01ef\u01f1\u0003^/\u0000\u01f0\u01ee\u0001\u0000\u0000\u0000\u01f1\u01f4"+
		"\u0001\u0000\u0000\u0000\u01f2\u01f0\u0001\u0000\u0000\u0000\u01f2\u01f3"+
		"\u0001\u0000\u0000\u0000\u01f3]\u0001\u0000\u0000\u0000\u01f4\u01f2\u0001"+
		"\u0000\u0000\u0000\u01f5\u01fa\u0003`0\u0000\u01f6\u01f7\u0007\u0006\u0000"+
		"\u0000\u01f7\u01f9\u0003^/\u0000\u01f8\u01f6\u0001\u0000\u0000\u0000\u01f9"+
		"\u01fc\u0001\u0000\u0000\u0000\u01fa\u01f8\u0001\u0000\u0000\u0000\u01fa"+
		"\u01fb\u0001\u0000\u0000\u0000\u01fb_\u0001\u0000\u0000\u0000\u01fc\u01fa"+
		"\u0001\u0000\u0000\u0000\u01fd\u01ff\u0005 \u0000\u0000\u01fe\u01fd\u0001"+
		"\u0000\u0000\u0000\u01fe\u01ff\u0001\u0000\u0000\u0000\u01ff\u0200\u0001"+
		"\u0000\u0000\u0000\u0200\u0204\u0003b1\u0000\u0201\u0202\u0005\f\u0000"+
		"\u0000\u0202\u0204\u0003b1\u0000\u0203\u01fe\u0001\u0000\u0000\u0000\u0203"+
		"\u0201\u0001\u0000\u0000\u0000\u0204a\u0001\u0000\u0000\u0000\u0205\u020b"+
		"\u0003\u0098L\u0000\u0206\u0207\u0005\u0001\u0000\u0000\u0207\u0208\u0003"+
		"\\.\u0000\u0208\u0209\u0005\u0002\u0000\u0000\u0209\u020b\u0001\u0000"+
		"\u0000\u0000\u020a\u0205\u0001\u0000\u0000\u0000\u020a\u0206\u0001\u0000"+
		"\u0000\u0000\u020bc\u0001\u0000\u0000\u0000\u020c\u020d\u0005Y\u0000\u0000"+
		"\u020de\u0001\u0000\u0000\u0000\u020e\u020f\u0005,\u0000\u0000\u020f\u0210"+
		"\u0003\u008cF\u0000\u0210g\u0001\u0000\u0000\u0000\u0211\u0212\u0005\u001d"+
		"\u0000\u0000\u0212\u0213\u0003r9\u0000\u0213i\u0001\u0000\u0000\u0000"+
		"\u0214\u0215\u0005\u0003\u0000\u0000\u0215\u0216\u0003l6\u0000\u0216\u0217"+
		"\u0005\u0004\u0000\u0000\u0217k\u0001\u0000\u0000\u0000\u0218\u021d\u0003"+
		"n7\u0000\u0219\u021a\u0005\u0005\u0000\u0000\u021a\u021c\u0003n7\u0000"+
		"\u021b\u0219\u0001\u0000\u0000\u0000\u021c\u021f\u0001\u0000\u0000\u0000"+
		"\u021d\u021b\u0001\u0000\u0000\u0000\u021d\u021e\u0001\u0000\u0000\u0000"+
		"\u021em\u0001\u0000\u0000\u0000\u021f\u021d\u0001\u0000\u0000\u0000\u0220"+
		"\u0221\u0005Y\u0000\u0000\u0221\u0222\u0005\t\u0000\u0000\u0222\u0223"+
		"\u0003z=\u0000\u0223o\u0001\u0000\u0000\u0000\u0224\u0225\u0007\u0007"+
		"\u0000\u0000\u0225q\u0001\u0000\u0000\u0000\u0226\u022b\u0003t:\u0000"+
		"\u0227\u0228\u0005\u001e\u0000\u0000\u0228\u022a\u0003t:\u0000\u0229\u0227"+
		"\u0001\u0000\u0000\u0000\u022a\u022d\u0001\u0000\u0000\u0000\u022b\u0229"+
		"\u0001\u0000\u0000\u0000\u022b\u022c\u0001\u0000\u0000\u0000\u022cs\u0001"+
		"\u0000\u0000\u0000\u022d\u022b\u0001\u0000\u0000\u0000\u022e\u0233\u0003"+
		"v;\u0000\u022f\u0230\u0005\u001f\u0000\u0000\u0230\u0232\u0003v;\u0000"+
		"\u0231\u022f\u0001\u0000\u0000\u0000\u0232\u0235\u0001\u0000\u0000\u0000"+
		"\u0233\u0231\u0001\u0000\u0000\u0000\u0233\u0234\u0001\u0000\u0000\u0000"+
		"\u0234u\u0001\u0000\u0000\u0000\u0235\u0233\u0001\u0000\u0000\u0000\u0236"+
		"\u0237\u0005 \u0000\u0000\u0237\u0245\u0003v;\u0000\u0238\u0239\u0005"+
		"\u0001\u0000\u0000\u0239\u023a\u0003r9\u0000\u023a\u023b\u0005\u0002\u0000"+
		"\u0000\u023b\u0245\u0001\u0000\u0000\u0000\u023c\u0245\u0003x<\u0000\u023d"+
		"\u023e\u0003z=\u0000\u023e\u0240\u0005-\u0000\u0000\u023f\u0241\u0005"+
		" \u0000\u0000\u0240\u023f\u0001\u0000\u0000\u0000\u0240\u0241\u0001\u0000"+
		"\u0000\u0000\u0241\u0242\u0001\u0000\u0000\u0000\u0242\u0243\u0005\"\u0000"+
		"\u0000\u0243\u0245\u0001\u0000\u0000\u0000\u0244\u0236\u0001\u0000\u0000"+
		"\u0000\u0244\u0238\u0001\u0000\u0000\u0000\u0244\u023c\u0001\u0000\u0000"+
		"\u0000\u0244\u023d\u0001\u0000\u0000\u0000\u0245w\u0001\u0000\u0000\u0000"+
		"\u0246\u0247\u0003z=\u0000\u0247\u0248\u0003\u0092I\u0000\u0248\u0249"+
		"\u0003z=\u0000\u0249y\u0001\u0000\u0000\u0000\u024a\u0256\u0005Y\u0000"+
		"\u0000\u024b\u0256\u0003\u0098L\u0000\u024c\u0256\u0005\\\u0000\u0000"+
		"\u024d\u0256\u0003\u008cF\u0000\u024e\u0256\u0003|>\u0000\u024f\u0256"+
		"\u0003~?\u0000\u0250\u0256\u0003\u0082A\u0000\u0251\u0256\u0003\u0084"+
		"B\u0000\u0252\u0256\u0003\u0086C\u0000\u0253\u0256\u0003\u0088D\u0000"+
		"\u0254\u0256\u0003\u008aE\u0000\u0255\u024a\u0001\u0000\u0000\u0000\u0255"+
		"\u024b\u0001\u0000\u0000\u0000\u0255\u024c\u0001\u0000\u0000\u0000\u0255"+
		"\u024d\u0001\u0000\u0000\u0000\u0255\u024e\u0001\u0000\u0000\u0000\u0255"+
		"\u024f\u0001\u0000\u0000\u0000\u0255\u0250\u0001\u0000\u0000\u0000\u0255"+
		"\u0251\u0001\u0000\u0000\u0000\u0255\u0252\u0001\u0000\u0000\u0000\u0255"+
		"\u0253\u0001\u0000\u0000\u0000\u0255\u0254\u0001\u0000\u0000\u0000\u0256"+
		"{\u0001\u0000\u0000\u0000\u0257\u0260\u0005\u0007\u0000\u0000\u0258\u025d"+
		"\u0003z=\u0000\u0259\u025a\u0005\u0005\u0000\u0000\u025a\u025c\u0003z"+
		"=\u0000\u025b\u0259\u0001\u0000\u0000\u0000\u025c\u025f\u0001\u0000\u0000"+
		"\u0000\u025d\u025b\u0001\u0000\u0000\u0000\u025d\u025e\u0001\u0000\u0000"+
		"\u0000\u025e\u0261\u0001\u0000\u0000\u0000\u025f\u025d\u0001\u0000\u0000"+
		"\u0000\u0260\u0258\u0001\u0000\u0000\u0000\u0260\u0261\u0001\u0000\u0000"+
		"\u0000\u0261\u0262\u0001\u0000\u0000\u0000\u0262\u0263\u0005\b\u0000\u0000"+
		"\u0263}\u0001\u0000\u0000\u0000\u0264\u026d\u0005\u0003\u0000\u0000\u0265"+
		"\u026a\u0003\u0080@\u0000\u0266\u0267\u0005\u0005\u0000\u0000\u0267\u0269"+
		"\u0003\u0080@\u0000\u0268\u0266\u0001\u0000\u0000\u0000\u0269\u026c\u0001"+
		"\u0000\u0000\u0000\u026a\u0268\u0001\u0000\u0000\u0000\u026a\u026b\u0001"+
		"\u0000\u0000\u0000\u026b\u026e\u0001\u0000\u0000\u0000\u026c\u026a\u0001"+
		"\u0000\u0000\u0000\u026d\u0265\u0001\u0000\u0000\u0000\u026d\u026e\u0001"+
		"\u0000\u0000\u0000\u026e\u026f\u0001\u0000\u0000\u0000\u026f\u0270\u0005"+
		"\u0004\u0000\u0000\u0270\u007f\u0001\u0000\u0000\u0000\u0271\u0272\u0007"+
		"\u0007\u0000\u0000\u0272\u0273\u0005\t\u0000\u0000\u0273\u0274\u0003z"+
		"=\u0000\u0274\u0081\u0001\u0000\u0000\u0000\u0275\u027a\u0007\b\u0000"+
		"\u0000\u0276\u027b\u0005\\\u0000\u0000\u0277\u0278\u0005\u0001\u0000\u0000"+
		"\u0278\u0279\u0005\\\u0000\u0000\u0279\u027b\u0005\u0002\u0000\u0000\u027a"+
		"\u0276\u0001\u0000\u0000\u0000\u027a\u0277\u0001\u0000\u0000\u0000\u027b"+
		"\u0083\u0001\u0000\u0000\u0000\u027c\u027d\u0007\t\u0000\u0000\u027d\u027e"+
		"\u0005\u0001\u0000\u0000\u027e\u027f\u0007\u0007\u0000\u0000\u027f\u0280"+
		"\u0005\u0002\u0000\u0000\u0280\u0085\u0001\u0000\u0000\u0000\u0281\u0283"+
		"\u00054\u0000\u0000\u0282\u0284\u0003z=\u0000\u0283\u0282\u0001\u0000"+
		"\u0000\u0000\u0283\u0284\u0001\u0000\u0000\u0000\u0284\u028a\u0001\u0000"+
		"\u0000\u0000\u0285\u0286\u00056\u0000\u0000\u0286\u0287\u0003r9\u0000"+
		"\u0287\u0288\u00057\u0000\u0000\u0288\u0289\u0003z=\u0000\u0289\u028b"+
		"\u0001\u0000\u0000\u0000\u028a\u0285\u0001\u0000\u0000\u0000\u028b\u028c"+
		"\u0001\u0000\u0000\u0000\u028c\u028a\u0001\u0000\u0000\u0000\u028c\u028d"+
		"\u0001\u0000\u0000\u0000\u028d\u0290\u0001\u0000\u0000\u0000\u028e\u028f"+
		"\u00055\u0000\u0000\u028f\u0291\u0003z=\u0000\u0290\u028e\u0001\u0000"+
		"\u0000\u0000\u0290\u0291\u0001\u0000\u0000\u0000\u0291\u0292\u0001\u0000"+
		"\u0000\u0000\u0292\u0293\u00058\u0000\u0000\u0293\u0087\u0001\u0000\u0000"+
		"\u0000\u0294\u0295\u0007\n\u0000\u0000\u0295\u0297\u0005\u0001\u0000\u0000"+
		"\u0296\u0298\u00053\u0000\u0000\u0297\u0296\u0001\u0000\u0000\u0000\u0297"+
		"\u0298\u0001\u0000\u0000\u0000\u0298\u0299\u0001\u0000\u0000\u0000\u0299"+
		"\u029a\u0003z=\u0000\u029a\u029b\u0005\u0002\u0000\u0000\u029b\u0089\u0001"+
		"\u0000\u0000\u0000\u029c\u029d\u0005?\u0000\u0000\u029d\u029e\u0005\u0003"+
		"\u0000\u0000\u029e\u029f\u0003:\u001d\u0000\u029f\u02a0\u0005\u0004\u0000"+
		"\u0000\u02a0\u008b\u0001\u0000\u0000\u0000\u02a1\u02a9\u0003\u008eG\u0000"+
		"\u02a2\u02a5\u0005S\u0000\u0000\u02a3\u02a5\u0003\u0094J\u0000\u02a4\u02a2"+
		"\u0001\u0000\u0000\u0000\u02a4\u02a3\u0001\u0000\u0000\u0000\u02a5\u02a6"+
		"\u0001\u0000\u0000\u0000\u02a6\u02a8\u0003\u008eG\u0000\u02a7\u02a4\u0001"+
		"\u0000\u0000\u0000\u02a8\u02ab\u0001\u0000\u0000\u0000\u02a9\u02a7\u0001"+
		"\u0000\u0000\u0000\u02a9\u02aa\u0001\u0000\u0000\u0000\u02aa\u008d\u0001"+
		"\u0000\u0000\u0000\u02ab\u02a9\u0001\u0000\u0000\u0000\u02ac\u02b1\u0003"+
		"\u0090H\u0000\u02ad\u02ae\u0007\u000b\u0000\u0000\u02ae\u02b0\u0003\u0090"+
		"H\u0000\u02af\u02ad\u0001\u0000\u0000\u0000\u02b0\u02b3\u0001\u0000\u0000"+
		"\u0000\u02b1\u02af\u0001\u0000\u0000\u0000\u02b1\u02b2\u0001\u0000\u0000"+
		"\u0000\u02b2\u008f\u0001\u0000\u0000\u0000\u02b3\u02b1\u0001\u0000\u0000"+
		"\u0000\u02b4\u02b5\u0005\u0001\u0000\u0000\u02b5\u02b6\u0003\u008cF\u0000"+
		"\u02b6\u02b7\u0005\u0002\u0000\u0000\u02b7\u02c3\u0001\u0000\u0000\u0000"+
		"\u02b8\u02b9\u0003\u0094J\u0000\u02b9\u02ba\u0005\u0001\u0000\u0000\u02ba"+
		"\u02bb\u0003\u008cF\u0000\u02bb\u02bc\u0005\u0002\u0000\u0000\u02bc\u02c3"+
		"\u0001\u0000\u0000\u0000\u02bd\u02be\u0003\u0094J\u0000\u02be\u02bf\u0003"+
		"\u0090H\u0000\u02bf\u02c3\u0001\u0000\u0000\u0000\u02c0\u02c3\u0003\u0096"+
		"K\u0000\u02c1\u02c3\u0003\u0098L\u0000\u02c2\u02b4\u0001\u0000\u0000\u0000"+
		"\u02c2\u02b8\u0001\u0000\u0000\u0000\u02c2\u02bd\u0001\u0000\u0000\u0000"+
		"\u02c2\u02c0\u0001\u0000\u0000\u0000\u02c2\u02c1\u0001\u0000\u0000\u0000"+
		"\u02c3\u0091\u0001\u0000\u0000\u0000\u02c4\u02c5\u0007\f\u0000\u0000\u02c5"+
		"\u0093\u0001\u0000\u0000\u0000\u02c6\u02c7\u0005]\u0000\u0000\u02c7\u0095"+
		"\u0001\u0000\u0000\u0000\u02c8\u02ca\u0003\u0094J\u0000\u02c9\u02c8\u0001"+
		"\u0000\u0000\u0000\u02c9\u02ca\u0001\u0000\u0000\u0000\u02ca\u02cb\u0001"+
		"\u0000\u0000\u0000\u02cb\u02cc\u0005Z\u0000\u0000\u02cc\u0097\u0001\u0000"+
		"\u0000\u0000\u02cd\u02d2\u0005Y\u0000\u0000\u02ce\u02cf\u0005X\u0000\u0000"+
		"\u02cf\u02d1\u0005Y\u0000\u0000\u02d0\u02ce\u0001\u0000\u0000\u0000\u02d1"+
		"\u02d4\u0001\u0000\u0000\u0000\u02d2\u02d0\u0001\u0000\u0000\u0000\u02d2"+
		"\u02d3\u0001\u0000\u0000\u0000\u02d3\u0099\u0001\u0000\u0000\u0000\u02d4"+
		"\u02d2\u0001\u0000\u0000\u0000N\u00a2\u00a8\u00ad\u00b3\u00b7\u00c3\u00c6"+
		"\u00d5\u00d8\u00dc\u00e7\u00e9\u00ef\u00fa\u0105\u0109\u0119\u0127\u012a"+
		"\u0131\u0134\u0137\u013a\u013d\u0140\u0142\u0149\u014f\u0158\u015f\u0163"+
		"\u0167\u016a\u016e\u0175\u017b\u0182\u0189\u018f\u0198\u019a\u01a1\u01a9"+
		"\u01ad\u01b1\u01b5\u01b7\u01c3\u01de\u01e1\u01e5\u01e8\u01f2\u01fa\u01fe"+
		"\u0203\u020a\u021d\u022b\u0233\u0240\u0244\u0255\u025d\u0260\u026a\u026d"+
		"\u027a\u0283\u028c\u0290\u0297\u02a4\u02a9\u02b1\u02c2\u02c9\u02d2";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}