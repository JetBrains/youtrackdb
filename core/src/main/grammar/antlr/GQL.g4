grammar GQL;

graph_query: graph_decl multi_linear_query_statement EOF;

multi_linear_query_statement: linear_query_statement (NEXT linear_query_statement)*;
linear_query_statement: simple_linear_query_statement | composite_linear_query_statement;

simple_linear_query_statement: (primitive_query_statement)* RETURN;
primitive_query_statement: GRAPH | CALL | FILTER | FOR | LET |LIMIT | MATCH | OFFSET | ORDER_BY |
                          RETURN | SKIP_TOKEN | WITH | GQL;

composite_linear_query_statement: ' TODO '; //contains set, to be added later

graph_decl: GRAPH Property_graph_name;

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
Property_graph_name: [a-zA-Z_][a-zA-Z_0-9]* ;
