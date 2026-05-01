grammar GlTrace;

@header {
package dumpanalyzer.parser;
}

traceFile
    : line* EOF
    ;

line
    : commentLine EOL*
    | callLine EOL*
    | EOL+
    ;

commentLine
    : COMMENT
    ;

callLine
    : INT WS+ GL_FUNCTION WS* LPAREN WS* argList? WS* RPAREN WS* returnPart? WS* trailingComment?
    ;

returnPart
    : EQ WS* value
    ;

trailingComment
    : COMMENT
    ;

argList
    : argument (WS* COMMA WS* argument)*
    ;

argument
    : NAME WS* EQ WS* value
    ;

value
    : valueAtom (WS* PIPE WS* valueAtom)*
    ;

valueAtom
    : pointerStruct
    | pointerScalar
    | bracedValue
    | functionLikeValue
    | prefixedNumber
    | scalar
    ;

pointerStruct
    : AMP bracedValue
    ;

bracedValue
    : LBRACE WS* valueList? WS* RBRACE
    ;

valueList
    : bracedItem (WS* COMMA WS* bracedItem)*
    ;

bracedItem
    : NAME WS* EQ WS* value
    | value
    ;

functionLikeValue
    : NAME WS* LPAREN WS* valueList? WS* RPAREN
    ;

prefixedNumber
    : AMP INT
    ;

pointerScalar
    : AMP scalar
    ;

scalar
    : HEX_NUMBER
    | INT
    | NUMBER
    | STRING
    | TRUE
    | FALSE
    | NULL
    | NAME
    ;

INT: [0-9]+;
GL_FUNCTION: 'gl' [A-Za-z0-9_]*;
NAME: [A-Za-z_][A-Za-z0-9_]*;
HEX_NUMBER: '0x' [0-9A-Fa-f]+;
NUMBER: [+-]? [0-9]+ '.' [0-9]+ ([eE] [+-]? [0-9]+)?
      | [+-]? [0-9]+ [eE] [+-]? [0-9]+
      | [+-]? [0-9]+
      ;
STRING: '"' ('\\' . | ~["\\])* '"';
TRUE: 'True';
FALSE: 'False';
NULL: 'NULL';

LPAREN: '(';
RPAREN: ')';
LBRACE: '{';
RBRACE: '}';
COMMA: ',';
EQ: '=';
PIPE: '|';
AMP: '&';

COMMENT: '//' ~[\r\n]*;
WS: [ \t]+;
EOL: '\r'? '\n';
