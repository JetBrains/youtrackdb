grammar GQL;

graph_query: multi_linear_query_statement EOF;

multi_linear_query_statement: linear_query_statement (NEXT linear_query_statement)*;
linear_query_statement: simple_linear_query_statement | composite_linear_query_statement;

simple_linear_query_statement: (primitive_query_statement)* RETURN;
primitive_query_statement: call_statment | filter_statment | for_statment | let_statmnet |
                           limit_statment | match_statment | offset_statmnet | order_by_statment |
                           return_statment | skip_statment | with_statmnet | gql_statment;

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

graph_pattern: path_pattern_list (where_clause)?;
path_pattern_list: top_level_path_pattern (',' top_level_path_pattern)*;
top_level_path_pattern: (path_variable EQ)? ('{' path_search_prefix | path_mode '}')? path_pattern;
path_pattern: element_pattern | '(' path_pattern ')';
path_search_prefix: ALL | ANY | ANY_SHORTEST | ANY_CHEAPEST;
path_mode: WALK (PATH | PATHS)? | ACYCLIC (PATH | PATHS)? | TRAIL (PATH | PATHS)?;


element_pattern: '{' node_pattern | edge_pattern'}';
node_pattern: '(' pattern_filler ')';
edge_pattern: '{' full_edge_any | full_edge_left | full_edge_right | abbreviated_edge_any |
               abbreviated_edge_left | abbreviated_edge_right '}';
full_edge_any: '-[' pattern_filler ']-';
full_edge_left: '<-[' pattern_filler ']-';
full_edge_right: '-[' pattern_filler ']->';
abbreviated_edge_any: '-';
abbreviated_edge_left: '<-';
abbreviated_edge_right: '->';
pattern_filler: graph_pattern_variable? is_label_condition?
                ('{' where_clause | property_filters '}')? cost_expression?;

is_label_condition: '{' IS | ':' '}' label_expression;
label_expression: PROPERTY_REFERENCE;
graph_pattern_variable: ID;

cost_expression: COST math_expression;
where_clause: WHERE boolean_expression;
property_filters: '{' property_list '}';
property_list: property_assignment (',' property_assignment)*;
property_assignment: ID ':' value_expression;
path_variable: ID | STRING;

offset_statmnet: OFFSET ;
order_by_statment: ORDER_BY ;
return_statment: RETURN ;
skip_statment: SKIP_TOKEN ;
with_statmnet: WITH ;
gql_statment: GQL ;

boolean_expression: boolean_expression_and (OR boolean_expression_and)*;
boolean_expression_and: boolean_expression_inner (AND boolean_expression_inner)*;
boolean_expression_inner: NOT boolean_expression_inner | '(' boolean_expression ')' |
                          comparison_expression;

comparison_expression: value_expression comparison_operator value_expression;
value_expression: ID | PROPERTY_REFERENCE | STRING | NUMBER | math_expression;
math_expression: math_expression_mul ((ADD | SUB) math_expression_mul)*;
math_expression_mul: math_expression_inner ((MUL | DIV | MOD) math_expression_inner)*;
math_expression_inner: '(' math_expression ')' | SUB '(' math_expression ')' |
                      SUB math_expression_inner | NUMBER | PROPERTY_REFERENCE;
comparison_operator: EQ | NEQ | GT | GTE | LT | LTE;

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
ORDER_BY: ORDER BY;
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

ID: [a-zA-Z_][a-zA-Z_0-9]* ;
PROPERTY_REFERENCE: ID(.ID)+;
NUMBER: '-'? [0-9]+ ('.' [0-9]+)?;
INT: [0-9]+;
STRING: '\'' ( ~['\r\n\\] | '\\' . )* '\'';
EQ: '=';
NEQ: '!=';
GT: '>';
GTE: '>=';
LT: '<';
LTE: '<=';
ADD: '+';
SUB: '-';
MUL: '*';
DIV: '/';
MOD: '%';
BOOL: 'TRUE' | 'FALSE';

WS : [ \t\r\n]+ -> skip ;