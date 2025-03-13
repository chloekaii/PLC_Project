package plc.project.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * The lexer works through a combination of {@link #lex()}, which repeatedly
 * calls {@link #lexToken()} and skips over whitespace/comments, and
 * {@link #lexToken()}, which determines the type of the next token and
 * delegates to the corresponding lex method.
 *
 * <p>Additionally, {@link CharStream} manages the lexer state and contains
 * {@link CharStream#peek} and {@link CharStream#match}. These are helpful
 * utilities for working with character state and building tokens.
 */
public final class Lexer {

    private final CharStream chars;

    public Lexer(String input) {
        chars = new CharStream(input);
    }

    public List<Token> lex() throws LexException {
        List<Token> tokens = new ArrayList<>();
        while (chars.has(0)) {
            Token token = lexToken();
            if (token != null) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private void lexComment() throws LexException {
        if (chars.peek("/", "/")) {
            chars.match("/", "/");
            while (chars.has(0) && !chars.peek("[\\n\\r]")) {
                chars.match(".");
            }
        } else {
            throw new LexException("Expected two / for comment");
        }
    }

    private Token lexToken() throws LexException {
        if (chars.peek("[ \\u0008\\t\\n\\r]")) { // skip whitespace
            chars.match("[ \\u0008\\t\\n\\r]");
            chars.emit(); // get rid of whitespace
            return null;
        }

        if (chars.peek("/", "/")) { // skip comments
            lexComment();
            chars.emit(); // get rid of comment
            return null;
        }

        if (chars.peek("[A-Za-z_]")) { // identifier
            return lexIdentifier();
        }

        if (chars.peek("[0-9]") || chars.peek("[+-]", "[0-9]")) { // number
            return lexNumber();
        }

        if (chars.peek("\'")) { // char
            return lexCharacter();
        }

        if (chars.peek("\"")) { // string
            return lexString();
        }

        if (chars.peek("[<>!=]") || chars.peek(".")) { // operator
            return lexOperator();
        }

        throw new LexException("Unexpected character: " + chars.peek());
    }

    private Token lexIdentifier() throws LexException {
        // [A-Za-z_] [A-Za-z0-9_-]*
        if (!chars.peek("[A-Za-z_]")) { // invalid identifier
            throw new LexException("Expected an identifier but found: " + (chars.has(0) ? chars.input.charAt(chars.index) : "EOF"));
        }

        chars.match("[A-Za-z_]");
        while (chars.has(0) && chars.peek("[A-Za-z0-9_-]")) {
            chars.match("[A-Za-z0-9_-]");
        }
        return new Token(Token.Type.IDENTIFIER, chars.emit());
    }

    private Token lexNumber() throws LexException {
        // [+\-]? [0-9]+ ('.' [0-9]+)? ('e' [0-9]+)?
        boolean isDecimal = false;

        if (chars.peek("[+-]")) {
            chars.match("[+-]");
        }

        // at least one digit must be in num
        if (!chars.peek("[0-9]")) {
            throw new LexException("Expected digit in number");
        }

        while (chars.peek("[0-9]")) {
            chars.match("[0-9]");
        }

        if (chars.peek("\\.")) { // Decimal point
            // lex multiple digits
            if (chars.peek("\\.", "[0-9]")) {  // if '.' followed by digit, it's a decimal (as opposed to an int with . operator)
                isDecimal = true;
                chars.match("\\.");
                while (chars.peek("[0-9]")) { // match nums
                    chars.match("[0-9]");
                }
            }
        }

        if (chars.peek("e")) { // Exponent
            // lex multiple digits
            if (chars.peek("e", "[0-9]")) {  // if 'e' followed by digit, it's an exponent (as opposed to an int with e operator)
                chars.match("e");
                while (chars.peek("[0-9]")) {
                    chars.match("[0-9]");
                }
            }
        }

        return new Token(isDecimal ? Token.Type.DECIMAL : Token.Type.INTEGER, chars.emit());
    }

    private Token lexCharacter() throws LexException {
        // ['] ([^'\n\r\\] | escape) [']
        if (!chars.peek("\'")) {
            throw new LexException("Expected ' before character");
        }
        chars.match("\'");

        if (chars.peek("[^'\\n\\r\\\\]")) {
            chars.match("[^'\\n\\r\\\\]");  // Matches any non-special character
        } else if (chars.peek("\\\\")) {  // Escape sequence
            chars.match("\\\\");  // Match the backslash
            lexEscape();          // Process the escape sequence
        } else {
            throw new LexException("Invalid character or escape sequence inside the character literal");
        }

        if (!chars.peek("\'")) {
            throw new LexException("Expected ' after character");
        }
        chars.match("\'");

        return new Token(Token.Type.CHARACTER, chars.emit());
    }

    private Token lexString() throws LexException {
        //  '"' ([^"\n\r\\] | escape)* '"'
        if (!chars.peek("\"")) {
            throw new LexException("Expected \" before string");
        }
        chars.match("\"");

        while (chars.peek("[^\"\\n\\r\\\\]") || chars.peek("\\\\")) {
            if (chars.peek("[^\"\\n\\r\\\\]")) {
                chars.match("[^\"\\n\\r\\\\]");
            } else { // escape char
                chars.match("\\\\");
                lexEscape();
            }
        }

        if (!chars.peek("\"")) {
            throw new LexException("Expected closing quote (\") after string");
        }
        chars.match("\"");

        return new Token(Token.Type.STRING, chars.emit());
    }

    private void lexEscape() throws LexException {
        if (chars.peek("[bnrt'\"\\\\]")) {
            chars.match("[bnrt'\"\\\\]");  // Match valid escape sequences
        } else {
            throw new LexException("Invalid escape sequence");
        }
    }

    public Token lexOperator() {
        //  [<>!=] '='? | 'any other character'
        if (chars.peek("[<>!=]")) {
            chars.match("[<>!=]");
            if (chars.peek("=")) {
                chars.match("=");
            }
        } else {
            chars.match("."); // Match any other character as a generic operator
        }
        return new Token(Token.Type.OPERATOR, chars.emit());
    }


    /**
     * A helper class for maintaining the state of the character stream (input)
     * and methods for building up token literals.
     */
    private static final class CharStream {

        private final String input;
        private int index = 0;
        private int length = 0;

        public CharStream(String input) {
            this.input = input;
        }

        public boolean has(int offset) {
            return index + offset < input.length();
        }

        /**
         * Returns true if the next characters match their corresponding
         * pattern. Each pattern is a regex matching only ONE character!
         */
        public boolean peek(String... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var character = input.charAt(index + offset);
                if (!String.valueOf(character).matches(patterns[offset])) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the character stream.
         */
        public boolean match(String... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
                length += patterns.length;
            }
            return peek;
        }

        /**
         * Returns the literal built by all characters matched since the last
         * call to emit(); also resetting the length for subsequent tokens.
         */
        public String emit() {
            var literal = input.substring(index - length, index);
            length = 0;
            return literal;
        }

    }

}
