grammar GQL;

graph_query: graph_decl multi_linear_query_statement EOF;
graph_decl: GRAPH PROPERTY_GRAPH_NAME;

multi_linear_query_statement: linear_query_statement (NEXT linear_query_statement)*;
linear_query_statement: simple_linear_query_statement | composite_linear_query_statement;

simple_linear_query_statement: (primitive_query_statement)* RETURN;
primitive_query_statement: call_statment | filter_statment | for_statment | let_statmnet
                           | limit_statment | match_statment | offset_statmnet | order_by_statment
                           | return_statment | skip_statment | with_statmnet | gql_statment;

composite_linear_query_statement: ' TODO '; //contains set, to be added later


call_statment: CALL ;
filter_statment: FILTER ;
for_statment: FOR ;
let_statmnet: LET ;
limit_statment: LIMIT ;
match_statment: MATCH ;
offset_statmnet: OFFSET ;
order_by_statment: ORDER_BY ;
return_statment: RETURN ;
skip_statment: SKIP_TOKEN ;
with_statmnet: WITH ;
gql_statment: GQL ;

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
ORDER_BY: 'ORDER BY';
PROPERTY_GRAPH_NAME: [a-zA-Z_][a-zA-Z_0-9]* ;
