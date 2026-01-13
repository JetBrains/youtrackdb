grammar GQL;

graph_query: multi_linear_query_statement EOF;

multi_linear_query_statement: linear_query_statement (NEXT linear_query_statement)*;
linear_query_statement: simple_linear_query_statement | composite_linear_query_statement;

simple_linear_query_statement: (primitive_query_statement)* RETURN;
primitive_query_statement: call_statment | filter_statment | for_statment | let_statmnet |
                           limit_statment | match_statment | offset_statment | order_by_statment |
                           return_statment | skip_statment | with_statmnet;

composite_linear_query_statement: ' TODO '; //contains set, to be added later

call_statment: OPTIONAL? CALL '(' call_parameters ')' '{' graph_query '}';
call_parameters: (ID (',' ID)*)?;

filter_statment: FILTER WHERE? boolean_expression;

for_statment: FOR STRING IN list (WITH OFFSET (as_statment)?)?;
list: '['STRING(',' STRING)']' | '[]' | NULL;

as_statment: AS STRING;

let_statmnet: LET linear_graph_variable(',' linear_graph_variable)*;
linear_graph_variable: STRING EQ value_expression;

limit_statment: LIMIT INT;

match_statment: OPTIONAL? MATCH match_hint? graph_pattern;
match_hint: '@{' hint_key EQ hint_value '}';
hint_key: ID;
hint_value: ID | STRING | NUMBER | BOOL;

offset_statment: OFFSET INT;
skip_statment: SKIP_TOKEN INT;

order_by_statment: ORDER_BY order_by_specification;
order_by_specification: (COLLATE collation_specification)? (ASC | ASCENDING | DESC | DESCENDING)?;
collation_specification: STRING;

return_statment: RETURN ('*' | (ALL | DISTINCT)? return_items? group_by_clause?
                 order_by_statment? limit_statment? offset_statment?);

return_items: return_item (',' return_item)*;
return_item: value_expression (AS ID)?;
group_by_clause: GROUP_BY groupable_item (',' groupable_item)*;
groupable_item: property_reference | ID | INT | value_expression;

with_statmnet: WITH (ALL | DISTINCT)? (return_items | '*') group_by_clause?;

graph_pattern: path_pattern_list (where_clause)?;
path_pattern_list: top_level_path_pattern (',' top_level_path_pattern)*;
top_level_path_pattern: (path_variable EQ)? ('{' path_search_prefix | path_mode '}')? path_pattern;
path_pattern: node_pattern (edge_pattern quantifier? node_pattern)*;
quantifier: '*' | '+' | '{' INT (',' INT?)? '}' | '{' ',' INT '}';
path_search_prefix: ALL | ANY | ANY_SHORTEST | ANY_CHEAPEST;
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
                (where_clause | property_filters)? cost_expression?;

is_label_condition: (IS | ':') label_expression;
label_expression: property_reference;
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
                          comparison_expression;

comparison_expression: value_expression comparison_operator value_expression;

value_expression: ID | property_reference | STRING | NUMBER | math_expression |
                  case_expression | aggregate_function | exists_predicate;

case_expression: CASE (value_expression)? (WHEN boolean_expression THEN value_expression)+
                 (ELSE value_expression)? END;

aggregate_function: (COUNT | SUM | AVG | MIN | MAX | COLLECT) '(' (DISTINCT)? value_expression ')';
exists_predicate: EXISTS '{' graph_pattern '}';

math_expression: math_expression_mul ((ADD | sub) math_expression_mul)*;
math_expression_mul: math_expression_inner ((MUL | DIV | MOD) math_expression_inner)*;
math_expression_inner: '(' math_expression ')' | sub '(' math_expression ')' |
                      sub math_expression_inner | NUMBER | property_reference;
comparison_operator: EQ | NEQ | GT | GTE | LT | LTE | IN | IS_NULL | IS_NOT_NULL;
sub: DASH;
property_reference : ID (DOT ID)* ;

MATCH: 'MATCH';
CALL: 'CALL';
FILTER: 'FILTER';
FOR: 'FOR';
LET: 'LET';
LIMIT: 'LIMIT';
NEXT: 'NEXT';
RETURN: 'RETURN';
SKIP_TOKEN : 'SKIP';
WITH: 'WITH';
GQL: 'GQL';
OFFSET: 'OFFSET';
ORDER_BY: 'ORDER BY';
GROUP_BY: 'GROUP BY';
ORDER : 'ORDER';
BY    : 'BY';
OPTIONAL: 'OPTIONAL';
AS: 'AS';
WHERE: 'WHERE';
OR: 'OR';
AND: 'AND';
NOT: 'NOT';
IN: 'IN' | 'in';
NULL: 'NULL';
ALL: 'ALL';
ANY: 'ANY';
ANY_SHORTEST: 'ANY SHORTEST';
ANY_CHEAPEST: 'ANY CHEAPEST';
WALK: 'WALK';
ACYCLIC: 'ACYCLIC';
TRAIL: 'TRAIL';
PATH: 'PATH';
PATHS: 'PATHS';
COST: 'COST';
IS: 'IS';
COLLATE : 'COLLATE';
ASC : 'ASC';
ASCENDING : 'ASCENDING';
DESC : 'DESC';
DESCENDING : 'DESCENDING';
DISTINCT : 'DISTINCT';
CASE : 'CASE';
ELSE : 'ELSE';
WHEN : 'WHEN';
THEN : 'THEN';
END : 'END';
COUNT : 'COUNT';
SUM : 'SUM';
AVG : 'AVG';
MIN : 'MIN';
MAX : 'MAX';
COLLECT : 'COLLECT';
EXISTS : 'EXISTS';
IS_NULL : 'IS_NULL';
IS_NOT_NULL : 'IS_NOT_NULL';
ARROW_RIGHT : '->';
ARROW_LEFT  : '<-';
ID: [a-zA-Z_][a-zA-Z_0-9]* ;
NUMBER: '-'? [0-9]+ (DOT [0-9]+)?;
INT: [0-9]+;
STRING: '\'' ( ~['\r\n\\] | '\\' . )* '\'';
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
BOOL: 'TRUE' | 'FALSE';
DOT : '.' ;
DASH: '-';

WS : [ \t\r\n]+ -> skip ;