grammar Form;

form
   : (section | (table | para) (NL NL NL* (table | para))*?) (NL NL NL* section)*
   ;

section
   : (ranges | part) NL? '-' NL? heading (NL NL NL* (table | para))*?
   ;

heading
   : .*?
   ;

table
   : column (NL NL NL* column)*
   ;

column
   : COLUMN NL? id = (INT | ID) NL? para
   ;

COLUMN
   : C O L U M N
   ;

para
   : (ranges ':' NL?)? (rest | SUBTOTAL)? instructions sep*? (amount = '^' | amount = (INT | LITERAL)?)
   ;

sep
   : (amount = '^' NL (ranges ':' NL?)? (rest | SUBTOTAL)? | amount = (INT | LITERAL)? NL (ranges ':' NL? (rest | SUBTOTAL)? | rest | SUBTOTAL | SPECIFY | TAG)) instructions
   ;

SPECIFY
   : '('? S P E C I F Y ')'?
   ;

SUBTOTAL
   : S U B T O T A L
   | R E S U L T
   ;

instructions
   : .*? (instruction .*?)*
   ;

instruction
   : (OTHERWISE ','? NL?)? (conditionalClause ','? NL?)? (expr | stat | enter (','? NL? AND NL? enter)?) (NL? (ONLY NL?)? conditionalClause)?
   | conditionalClause
   ;

OTHERWISE
   : O T H E R W I S E
   ;

ONLY
   : O N L Y
   ;

expr
   : ((ADD | ops += (WHICHEVER_IS_MORE | WHICHEVER_IS_LESS) ':'?) text*)? (FROM NL?)? ranges (NL? rest | ','? NL? ops += (WHICHEVER_IS_MORE | WHICHEVER_IS_LESS))? # aggregate
   | MULTIPLY text* (FROM NL?)? a = ranges NL? ('('? conditionalClause (NL? ONLY)? ')'?)? BY text* b = conditional # multiply
   | ENTER_LITERAL NL? '"'? (INT | LITERAL) '"'? # enterLiteral
   | SPOUSES_NET_INCOME # spousesNetIncome
   | DEPENDANTS_NET_INCOME # dependantsNetIncome
   | UNUSED_RRSP_CONTRIBUTIONS # unusedRrspContributions
   | THEIR NL? THEIR_INFORMATION # theirInformation
   ;

ADD
   : A D D
   | T O T A L WS O F
   ;

MULTIPLY
   : M U L T I P L Y
   ;

ENTER_LITERAL
   : C L A I M
   | N O N '-' R E F U N D A B L E WS T A X WS C R E D I T WS R A T E
   ;

SPOUSES_NET_INCOME
   : N E T WS I N C O M E WS O F WS Y O U R WS S P O U S E WS O R WS C O M M O N '-' L A W WS P A R T N E R
   | S P O U S E '\'' S WS O R WS C O M M O N '-' L A W WS P A R T N E R '\'' S WS N E T WS I N C O M E
   ;

DEPENDANTS_NET_INCOME
   : E L I G I B L E WS D E P E N D A N T '\'' S WS N E T WS I N C O M E
   ;

UNUSED_RRSP_CONTRIBUTIONS
   : U N U S E D WS R R S P ('/' P R P P)? WS C O N T R I B U T I O N S
   ;

THEIR_INFORMATION
   : S I N
   | F I R S T WS N A M E
   ;

rest
   : (op = (PLUS | MINUS) | op = (MULTIPLIED | DIVIDED) NL? BY) ':'? text* conditional
   ;

PLUS
   : P L U S
   ;

MINUS
   : M I N U S
   ;

MULTIPLIED
   : M U L T I P L I E D
   ;

DIVIDED
   : D I V I D E D
   ;

BY
   : B Y
   ;

stat
   : CANNOT_BE NL? condition # cannotBe
   | CANNOT_EXCEED text* conditional # cannotExceed
   | MAXIMUM text* operand # maximum
   | FOCUS_SPOUSE # focusSpouse
   | FOCUS_DEPENDANT # focusDependant
   | USE_COLUMN NL? id = (INT | ID) # useColumn
   | (COMPLETE | ATTACH) text* ref # completeForm
   | COMPLETE text* ranges # completeLines
   ;

CANNOT_BE
   : C A N N O T WS B E
   ;

CANNOT_EXCEED
   : C A N N O T WS E X C E E D
   ;

MAXIMUM
   : M A X I M U M
   ;

MAXIMUM_AMOUNT
   : M A X I M U M WS (A M O U N T | S U P P L E M E N T)
   ;

FOCUS_SPOUSE
   : I N F O R M A T I O N WS A B O U T WS Y O U R WS S P O U S E WS O R WS C O M M O N '-' L A W WS P A R T N E R
   | S P O U S E WS O R WS C O M M O N '-' L A W WS P A R T N E R WS A M O U N T
   ;

FOCUS_DEPENDANT
   : A M O U N T WS F O R WS A N WS E L I G I B L E WS D E P E N D A N T
   ;

TAG
   : M A X I M U M WS (B A S I C WS C P P WS E X E M P T I O N | C P P WS P E N S I O N A B L E WS E A R N I N G S)
   | N E T WS F E D E R A L WS T A X
   | P O L I T I C A L WS C O N T R I B U T I O N S
   | P R O V I N C I A L WS O R WS T E R R I T O R I A L WS T A X
   | T H I S WS I S WS Y O U R WS (N E T WS I N C O M E | R E F U N D WS O R WS B A L A N C E WS O W I N G)
   | U N U S E D WS (R R S P ('/' P R P P)? WS)? C O N T R I B U T I O N S WS A V A I L A B L E WS T O WS C A R R Y WS F O R W A R D WS T O WS A WS F U T U R E WS Y E A R
   ;

USE_COLUMN
   : U S E WS C O L U M N
   ;

COMPLETE
   : C O M P L E T E
   ;

ATTACH
   : A T T A C H
   | S E E
   | U S E
   ;

enter
   : ENTER (onClause | text* conditional) onClause?
   ;

ENTER
   : E N T E R
   ;

onClause
   : text* ON NL? ranges (','? NL? AND NL? ON NL? ranges)?
   ;

ON
   : O N
   ;

CLAIMED_ON
   : (C L A I M E D | I N C L U D E D) WS O N
   ;

conditionalClause
   : IF text* ((operand NL?)? IS NL?)? condition (NL? BUT NL? condition)?
   ;

IF
   : I F
   ;

IS
   : I S
   | A R E
   | W A S
   | W E R E
   ;

BUT
   : B U T
   ;

condition
   : POSITIVE # positive
   | NEGATIVE # negative
   | (op = (MORE_THAN | LESS_THAN) text* conditional | operand NL? op = (OR_MORE | OR_LESS)) # comparison
   | '"'? (INT | LITERAL) '"'? # equals
   | NOT NL? condition # not
   ;

POSITIVE
   : P O S I T I V E
   ;

NEGATIVE
   : N E G A T I V E
   ;

NOT
   : N O T
   ;

MORE_THAN
   : (M O R E | G R E A T E R) WS T H A N
   ;

LESS_THAN
   : L E S S WS T H A N
   ;

OR_MORE
   : O R WS (M O R E | G R E A T E R)
   ;

OR_LESS
   : O R WS L E S S
   ;

conditional
   : (ops += (WHICHEVER_IS_MORE | WHICHEVER_IS_LESS) ':'? text*)? operand (','? NL? (AND | OR) text* operand)? (','? NL? ops += (WHICHEVER_IS_MORE | WHICHEVER_IS_LESS))?
   ;

WHICHEVER_IS_MORE
   : W H I C H E V E R WS I S WS (M O R E | G R E A T E R)
   ;

WHICHEVER_IS_LESS
   : W H I C H E V E R WS I S WS L E (S S | A S T)
   | L E S S E R WS O F
   ;

operand
   : '('? (FROM NL?)? ranges (NL? rest)? ')'? # lines
   | '"'? (INT | LITERAL) '"'? (NL? OF text* operand)? # literal
   | YOUR_NET_INCOME # yourNetIncome
   | THEIR_NET_INCOME # theirNetIncome
   | YOUR_TOTAL_CONTRIBUTIONS # yourTotalContributions
   | ADD text* (FROM NL?)? ranges # add
   | AS_A_POSITIVE # asAPositive
   | TAG # tag
   ;

YOUR_NET_INCOME
   : Y O U R WS N E T WS I N C O M E
   ;

THEIR_NET_INCOME
   : THEIR WS N E T WS I N C O M E
   ;

YOUR_TOTAL_CONTRIBUTIONS
   : Y O U R WS T O T A L WS C O N T R I B U T I O N S
   ;

AS_A_POSITIVE
   : A S WS A WS P O S I T I V E
   ;

ranges
   : LINE NL? range ((',' NL? ((LINE | LINES) NL?)? range)* ','? NL? (AND | OR) NL? ((LINE | LINES) NL?)? range)?
   | LINES NL? range (',' NL? ((LINE | LINES) NL?)? range)* (','? NL? (AND | OR) NL? ((LINE | LINES) NL?)? range)?
   ;

LINE
   : L I N E
   ;

LINES
   : L I N E S
   ;

AND
   : A N D
   ;

OR
   : O R
   ;

range
   : start = (INT | ID) (NL? TO NL? end = (INT | ID))? ofClause?
   ;

TO
   : T O
   | T H R O U G H
   ;

ofClause
   : NL? '('? (OF | FROM | IN | ON) text* (part (NL? (OF | FROM | IN | ON) text* ref)? | ref) ')'?
   ;

OF
   : O F
   ;

FROM
   : F R O M
   ;

IN
   : I N
   ;

part
   : PART NL? id = (INT | ID)
   ;

PART
   : P A R T
   ;

ref
   : REF
   ;

REF
   : T H E WS (P R E V I O U S | N E X T) WS P A G E
   | (T H I S | Y O U R | THEIR) WS R E T U R N
   | T H I S WS ((F E D E R A L | P R O V I N C I A L) WS)? (F O R M | S C H E D U L E | W O R K S H E E T)
   | (THEIR WS)? (F O R M WS (INT | ID) | S C H E D U L E WS (INT | ID) ('(' S .*? ')')? | W O R K S H E E T WS (F O R WS (T H E WS R E T U R N | S C H E D U L E WS (INT | ID)) | INT | ID) | (F E D E R A L | P R O V I N C I A L) WS W O R K S H E E T)
   ;

THEIR
   : T H E I R
   | H I S WS O R WS H E R
   ;

text
   : NL
   | ID_SUBTRAHEND
   | ID
   | AND
   | IF
   | IN
   | OF
   | ON
   | SUBTOTAL
   | TAG
   | YOUR_NET_INCOME
   | YOUR_TOTAL_CONTRIBUTIONS
   | '\''
   | ','
   ;

INT
   : [0-9]+
   ;

LITERAL
   : '$'? [,.0-9]* [0-9] '%'?
   ;

ID_SUBTRAHEND
   : [A-Za-z] [A-Za-z]+
   ;

ID
   : [-0-9A-Z_a-z]+
   ;

COMMENT
   : '--' .*? NL -> channel (HIDDEN)
   ;

fragment WS
   : (NL | SP)+
   ;

NL
   : '\r'? '\n'
   ;

SP
   : ' ' -> channel (HIDDEN)
   ;

OTHER
   : .
   ;

fragment A
   : [Aa]
   ;

fragment B
   : [Bb]
   ;

fragment C
   : [Cc]
   ;

fragment D
   : [Dd]
   ;

fragment E
   : [Ee]
   ;

fragment F
   : [Ff]
   ;

fragment G
   : [Gg]
   ;

fragment H
   : [Hh]
   ;

fragment I
   : [Ii]
   ;

fragment J
   : [Jj]
   ;

fragment K
   : [Kk]
   ;

fragment L
   : [Ll]
   ;

fragment M
   : [Mm]
   ;

fragment N
   : [Nn]
   ;

fragment O
   : [Oo]
   ;

fragment P
   : [Pp]
   ;

fragment Q
   : [Qq]
   ;

fragment R
   : [Rr]
   ;

fragment S
   : [Ss]
   ;

fragment T
   : [Tt]
   ;

fragment U
   : [Uu]
   ;

fragment V
   : [Vv]
   ;

fragment W
   : [Ww]
   ;

fragment X
   : [Xx]
   ;

fragment Y
   : [Yy]
   ;

fragment Z
   : [Zz]
   ;

