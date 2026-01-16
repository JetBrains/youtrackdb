grammar GQL;

graph_query: multi_linear_query_statement EOF;

multi_linear_query_statement: composite_linear_query_statement (NEXT composite_linear_query_statement)*;

composite_linear_query_statement: simple_linear_query_statement
                                  ( (UNION | INTERSECT | EXCEPT) (ALL | DISTINCT)?
                                  simple_linear_query_statement )* ;

simple_linear_query_statement: (primitive_query_statement)* return_statment?;
primitive_query_statement: call_statment | filter_statment | for_statment | let_statmnet |
                           limit_statment | match_statment | offset_statment | order_by_statment |
                           skip_statment | with_statmnet;


call_statment: OPTIONAL? CALL '(' call_parameters ')' '{' graph_query '}';
call_parameters: (ID (',' ID)*)?;

filter_statment: FILTER WHERE? boolean_expression;

for_statment: FOR STRING IN list (WITH OFFSET (as_statment)?)?;
list: list_literal | ID | property_reference | NULL_TOKEN;

as_statment: AS STRING;

let_statmnet: LET linear_graph_variable(',' linear_graph_variable)*;
linear_graph_variable: STRING EQ value_expression;

limit_statment: LIMIT INT;

match_statment: OPTIONAL? MATCH match_hint? graph_pattern;
match_hint: '@{' hint_key EQ hint_value '}';
hint_key: ID;
hint_value: ID | STRING | math_expression | BOOL;

offset_statment: OFFSET INT;
skip_statment: SKIP_TOKEN INT;

order_by_statment: ORDER BY order_by_specification;
order_by_specification: (COLLATE collation_specification)? (ASC | ASCENDING | DESC | DESCENDING)?;
collation_specification: STRING;

return_statment: RETURN ('*' | (ALL | DISTINCT)? return_items? group_by_clause?
                 order_by_statment? limit_statment? offset_statment?);

return_items: return_item (',' return_item)*;
return_item: value_expression (AS ID)?;
group_by_clause: GROUP BY groupable_item (',' groupable_item)*;
groupable_item: property_reference | ID | INT | value_expression;

with_statmnet: WITH (ALL | DISTINCT)? (return_items | '*') group_by_clause?;

graph_pattern: path_pattern_list (where_clause)?;
path_pattern_list: top_level_path_pattern (',' top_level_path_pattern)*;
top_level_path_pattern: (path_variable EQ)? ('{' path_search_prefix | path_mode '}')? path_pattern;
path_pattern: node_pattern (edge_pattern quantifier? node_pattern)*;
quantifier: '*' | '+' | '{' INT (',' INT?)? '}' | '{' ',' INT '}' | '{' INT ',' INT '}';
path_search_prefix: ALL | ANY | ANY SHORTEST | ANY CHEAPEST;
path_mode: WALK (PATH | PATHS)? | ACYCLIC (PATH | PATHS)? | TRAIL (PATH | PATHS)?;

node_pattern: '(' pattern_filler ')';
edge_pattern: full_edge_any | full_edge_left | full_edge_right | abbreviated_edge_any |
               abbreviated_edge_left | abbreviated_edge_right;
full_edge_any: DASH '[' pattern_filler ']' DASH;
full_edge_left: ARROW_LEFT '[' pattern_filler ']' DASH;
full_edge_right: DASH '[' pattern_filler ']' ARROW_RIGHT;
abbreviated_edge_any: DASH;
abbreviated_edge_left: ARROW_LEFT;
abbreviated_edge_right: ARROW_RIGHT;
pattern_filler: graph_pattern_variable? is_label_condition?
                (where_clause | property_filters)? quantifier? cost_expression?;

is_label_condition: (IS | ':') label_expression;
label_expression: label_term (('|' | OR) label_term)*;
label_term: label_factor (('&' | AND) label_term)*;
label_factor: NOT? label_primary | '!' label_primary;
label_primary: property_reference | '(' label_expression ')';
graph_pattern_variable: ID;

cost_expression: COST math_expression;
where_clause: WHERE boolean_expression;
property_filters: '{' property_list '}';
property_list: property_assignment (',' property_assignment)*;
property_assignment: ID ':' value_expression;
path_variable: ID | STRING;

boolean_expression: boolean_expression_and (OR boolean_expression_and)*;
boolean_expression_and: boolean_expression_inner (AND boolean_expression_inner)*;
boolean_expression_inner: NOT boolean_expression_inner | '(' boolean_expression ')' |
                          comparison_expression | value_expression IS NOT? NULL_TOKEN;

comparison_expression: value_expression comparison_operator value_expression;

value_expression: ID | property_reference | STRING | math_expression |
                  list_literal | map_literal | temporal_literal | path_function |
                  case_expression | aggregate_function | exists_predicate;

list_literal: '[' (value_expression (',' value_expression)*)? ']';
map_literal: '{' (map_entry (',' map_entry)*)? '}';
map_entry: (ID | STRING) ':' value_expression;
temporal_literal: (DATE | TIME | TIMESTAMP | DURATION) (STRING | '(' STRING ')');
path_function: (NODES | EDGES | LENGTH | LABELS) '(' (ID | STRING) ')';

case_expression: CASE (value_expression)? (WHEN boolean_expression THEN value_expression)+
                 (ELSE value_expression)? END;

aggregate_function: (COUNT | SUM | AVG | MIN | MAX | COLLECT) '(' (DISTINCT)? value_expression ')';
exists_predicate: EXISTS '{' graph_pattern '}';

math_expression: math_expression_mul ((ADD | sub) math_expression_mul)*;
math_expression_mul: math_expression_inner ((MUL | DIV | MOD) math_expression_inner)*;
math_expression_inner: '(' math_expression ')' | sub '(' math_expression ')' |
                      sub math_expression_inner | numeric_literal | property_reference;
comparison_operator: EQ | NEQ | GT | GTE | LT | LTE | IN;
sub: DASH;
numeric_literal: (sub)? (NUMBER | INT);
property_reference : ID (DOT ID)* ;

MATCH:   M A T C H;
CALL:    C A L L;
FILTER:  F I L T E R;
FOR:     F O R;
LET:     L E T;
LIMIT:   L I M I T;
NEXT:    N E X T;
RETURN:  R E T U R N;
SKIP_TOKEN: S K I P;
WITH:    W I T H;
OFFSET:  O F F S E T;
ORDER:   O R D E R;
BY:      B Y;
GROUP:   G R O U P;
OPTIONAL: O P T I O N A L;
AS:      A S;
WHERE:   W H E R E;
OR:      O R;
AND:     A N D;
NOT:     N O T;
IN:      I N;
NULL_TOKEN: N U L L;
ALL:     A L L;
ANY:     A N Y;
SHORTEST: S H O R T E S T;
CHEAPEST: C H E A P E S T;
WALK:    W A L K;
ACYCLIC: A C Y C L I C;
TRAIL:   T R A I L;
PATH:    P A T H;
PATHS:   P A T H S;
COST:    C O S T;
IS:      I S;
COLLATE: C O L L A T E;
ASC:     A S C;
ASCENDING: A S C E N D I N G;
DESC:    D E S C;
DESCENDING: D E S C E N D I N G;
DISTINCT: D I S T I N C T;
CASE:    C A S E;
ELSE:    E L S E;
WHEN:    W H E N;
THEN:    T H E N;
END:     E N D;
COUNT:   C O U N T;
SUM:     S U M;
AVG:     A V G;
MIN:     M I N;
MAX:     M A X;
COLLECT: C O L L E C T;
EXISTS:  E X I S T S;
UNION:   U N I O N;
INTERSECT: I N T E R S E C T;
EXCEPT:  E X C E P T;
DATE:    D A T E;
TIME:    T I M E;
TIMESTAMP: T I M E S T A M P;
DURATION: D U R A T I O N;
NODES:   N O D E S;
EDGES:   E D G E S;
LENGTH:  L E N G T H;
LABELS:   L A B E L S;

ARROW_RIGHT : '->';
ARROW_LEFT  : '<-';
NEQ: '!=';
GTE: '>=';
LTE: '<=';
GT: '>';
LT: '<';
EQ: '=';
ADD: '+';
MUL: '*';
DIV: '/';
MOD: '%';
BOOL: T R U E | F A L S E;
DOT : '.' ;
DASH: '-';
ID: [a-zA-Z_][a-zA-Z_0-9]* ;
INT: [0-9]+;
NUMBER: [0-9]+ (DOT [0-9]+)? ([eE] [+-]? [0-9]+)?;
STRING: '\'' ( ~['\r\n\\] | '\\' . )* '\'';

fragment A: [aA]; fragment B: [bB]; fragment C: [cC]; fragment D: [dD];
fragment E: [eE]; fragment F: [fF]; fragment G: [gG]; fragment H: [hH];
fragment I: [iI]; fragment J: [jJ]; fragment K: [kK]; fragment L: [lL];
fragment M: [mM]; fragment N: [nN]; fragment O: [oO]; fragment P: [pP];
fragment Q: [qQ]; fragment R: [rR]; fragment S: [sS]; fragment T: [tT];
fragment U: [uU]; fragment V: [vV]; fragment W: [wW]; fragment X: [xX];
fragment Y: [yY]; fragment Z: [zZ];

WS : [ \t\r\n]+ -> skip ;