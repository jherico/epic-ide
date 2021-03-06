header
{
// This source file was generated by ANTLR. Do not edit manually!
package org.epic.core.parser;
}

class LexExpectStringEnd extends Lexer("org.epic.core.parser.LexExpectStringEndBase");
options
{
	k = 2;
	charVocabulary = '\0'..'\uFFFF';
	importVocab = shared;
}
{
	private int pc;
	
	public void setInputState(LexerSharedInputState state)
    {
        super.setInputState(state);
		pc = 0;
    }
}

EOF: '\uFFFF' { uponEOF(); };

STRING_BODY:
	({ if (LA(1) == '\uFFFF' || maxLinesExceeded() ||
		   LA(1) == quoteEndChar && pc == 0) break; } NOT_QUOTE)*;

CLOSE_QUOTE:
	{ LA(1) == quoteEndChar || maxLinesExceeded() }?
	.
	{ getParent().pop(); }
	;

protected
NOT_QUOTE:
	(ESCAPE |
	 OPEN_PAREN | OPEN_CURLY | OPEN_BRACKET |
	 CLOSE_PAREN | CLOSE_CURLY | CLOSE_BRACKET | CLOSE_BRACE |
	 NEWLINE |
	 NOTNEWLINE)
	;

protected OPEN_PAREN:   '(' { if (quoteEndChar == ')') pc++; };
protected OPEN_CURLY:   '{' { if (quoteEndChar == '}') pc++; };
protected OPEN_BRACKET: '[' { if (quoteEndChar == ']') pc++; };
protected OPEN_BRACE:   '<' { if (quoteEndChar == '>') pc++; };

protected CLOSE_PAREN:   ')' { if (quoteEndChar == ')') pc--; };
protected CLOSE_CURLY:   '}' { if (quoteEndChar == '}') pc--; };
protected CLOSE_BRACKET: ']' { if (quoteEndChar == ']') pc--; };
protected CLOSE_BRACE:   '>' { if (quoteEndChar == '>') pc--; };

protected
ESCAPE:
	'\\' ('\uFFFF'!|~('\r' | '\n'))
	;

protected
NEWLINE:
	(
	 '\r' '\n' |	// DOS
     '\r' |			// MacOS
     '\n'			// UNIX
    )
    {
    	newline();
	}
    ;

protected
NOTNEWLINE:
	~('\r' | '\n')
	;