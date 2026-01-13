grammar GQL;

graph_query: graph_decl multi_linear_query_statement EOF;
graph_decl: GRAPH PROPERTY_GRAPH_NAME;

multi_linear_query_statement: linear_query_statement (NEXT linear_query_statement)*;
linear_query_statement: simple_linear_query_statement | composite_linear_query_statement;

simple_linear_query_statement: (primitive_query_statement)* RETURN;
primitive_query_statement: call_statment | filter_statment | for_statment | let_statmnet |
                           limit_statment | match_statment | offset_statmnet | order_by_statment |
                           return_statment | skip_statment | with_statmnet | gql_statment |
                           graph_decl;

composite_linear_query_statement: ' TODO '; //contains set, to be added later

call_statment: OPTIONAL? CALL '(' call_parameters ')' '{' graph_query '}';
call_parameters: (ID (',' ID)*)?;

filter_statment: FILTER WHERE? boolean_expression;

for_statment: FOR STRING IN list (WITH OFFSET (as_statment)?)?;
list: '['STRING(',' STRING)']' | '[]' | NULL;

as_statment: AS STRING;

let_statmnet: LET linear_graph_variable(',' linear_graph_variable)*;
linear_graph_variable: STRING EQ value_expression;

limit_statment: LIMIT ;
match_statment: MATCH ;
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

GRAPH: 'GRAPH';
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

PROPERTY_GRAPH_NAME: [a-zA-Z_][a-zA-Z_0-9]*;
ID: [a-zA-Z_][a-zA-Z_0-9]* ;
PROPERTY_REFERENCE: ID(.ID)+;
NUMBER: '-'? [0-9]+ ('.' [0-9]+)?;
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

WS : [ \t\r\n]+ -> skip ;