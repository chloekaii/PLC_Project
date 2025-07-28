package plc.project.parser;

import plc.project.lexer.Token;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;

/**
 * This style of parser is called <em>recursive descent</em>. Each rule in our
 * grammar has dedicated function, and references to other rules correspond to
 * calling that function. Recursive rules are therefore supported by actual
 * recursive calls, while operator precedence is encoded via the grammar.
 *
 * <p>The parser has a similar architecture to the lexer, just with
 * {@link Token}s instead of characters. As before, {@link TokenStream#peek} and
 * {@link TokenStream#match} help with traversing the token stream. Instead of
 * emitting tokens, you will instead need to extract the literal value via
 * {@link TokenStream#get} to be added to the relevant AST.
 */
public final class Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    public Ast.Source parseSource() throws ParseException {
        List<Ast.Stmt> statements = new ArrayList<>();
        while (tokens.has(0)) {
            statements.add(parseStmt());
        }
        return new Ast.Source(statements);
    }

    public Ast.Stmt parseStmt() throws ParseException {
        // stmt::= let_stmt | def_stmt | if_stmt | for_stmt | return_stmt | expression_or_assignment_stmt
        if (tokens.peek("LET")) {
            return parseLetStmt();
        } else if (tokens.peek("DEF")) {
            return parseDefStmt();
        } else if (tokens.peek("IF")) {
            return parseIfStmt();
        } else if (tokens.peek("FOR")) {
            return parseForStmt();
        } else if (tokens.peek("RETURN")) {
            return parseReturnStmt();
        } else { // expr/assignment
            return parseExpressionOrAssignmentStmt();
        }
    }

    private Ast.Stmt.Let parseLetStmt() throws ParseException {
        // let_stmt ::= 'LET' identifier ('=' expr)? ';'
        if (!tokens.peek("LET")) {
            throw new ParseException("Expected LET");
        }
        tokens.match("LET");

        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier after 'LET'.");
        }
        tokens.match(Token.Type.IDENTIFIER);
        String name = tokens.get(-1).literal();

        Optional<String> type = Optional.empty();
        if (tokens.peek(":")) {
            tokens.match(":");
            if (!tokens.peek(Token.Type.IDENTIFIER)) {
                throw new ParseException("Expected identifier after ':'");
            }
            tokens.match(Token.Type.IDENTIFIER);
            type = Optional.of(tokens.get(-1).literal());
        }

        Optional<Ast.Expr> value = Optional.empty();
        if (tokens.peek("=")) {
            tokens.match("=");
            value = Optional.ofNullable(parseExpr());
        }

        if (!tokens.peek(";")) {
            throw new ParseException("Expected ';' at end of let statement.");
        }
        tokens.match(";");

        return new Ast.Stmt.Let(name, type, value);
    }

    private Ast.Stmt.Def parseDefStmt() throws ParseException {
        // def_stmt ::= 'DEF' identifier '(' (identifier (':' identifier)? (',' identifier (':' identifier)?)*)? ')' (':' identifier)? 'DO' stmt* 'END'
        if (!tokens.peek("DEF")) {
            throw new ParseException("DEF expected");
        }
        tokens.match("DEF");

        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier after 'DEF'.");
        }
        tokens.match(Token.Type.IDENTIFIER);
        String name = tokens.get(-1).literal();

        if (!tokens.peek("(")) {
            throw new ParseException("Expected '('");
        }
        tokens.match("(");

        List<String> parameters = new ArrayList<>();
        List<Optional<String>> parameterTypes = new ArrayList<>();

        if (!tokens.peek(")")) {
            parseParameter(parameters, parameterTypes);

            while (tokens.match(",")) {
                if (!tokens.peek(Token.Type.IDENTIFIER)) {
                    throw new ParseException("Expected parameter after ','");
                }
                parseParameter(parameters, parameterTypes);
            }
        }

        if (!tokens.peek(")")) {
            throw new ParseException("Expected ')'");
        }
        tokens.match(")");

        Optional<String> returnType = Optional.empty();
        if (tokens.peek(":")) {
            tokens.match(":");
            if (!tokens.peek(Token.Type.IDENTIFIER)) {
                throw new ParseException("Expected identifier after ':'");
            }
            tokens.match(Token.Type.IDENTIFIER);
            returnType = Optional.of(tokens.get(-1).literal());
        }

        if (!tokens.peek("DO")) {
            throw new ParseException("DO expected");
        }
        tokens.match("DO");

        List<Ast.Stmt> body = new ArrayList<>();
        while (!tokens.peek("END")) {
            body.add(parseStmt());
        }

        if (!tokens.peek("END")) {
            throw new ParseException("END expected");
        }
        tokens.match("END");

        return new Ast.Stmt.Def(name, parameters, parameterTypes, returnType, body);
    }

    private void parseParameter(List<String> parameters, List<Optional<String>> parameterTypes) throws ParseException {
        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier in parameter list.");
        }
        tokens.match(Token.Type.IDENTIFIER);
        String param = tokens.get(-1).literal();
        parameters.add(param);

        Optional<String> type = Optional.empty();
        if (tokens.peek(":")) {
            tokens.match(":");
            if (!tokens.peek(Token.Type.IDENTIFIER)) {
                throw new ParseException("Expected identifier after ':'");
            }
            tokens.match(Token.Type.IDENTIFIER);
            type = Optional.of(tokens.get(-1).literal());
        }

        parameterTypes.add(type);
    }

    private Ast.Stmt.If parseIfStmt() throws ParseException {
        // if_stmt ::= 'IF' expr 'DO' stmt* ('ELSE' stmt*)? 'END'
        if (!tokens.peek("IF")) {
            throw new ParseException("IF expected");
        }
        tokens.match("IF");

        var condition = parseExpr();

        if (!tokens.peek("DO")) {
            throw new ParseException("DO expected");
        }
        tokens.match("DO");

        List<Ast.Stmt> thenBody = new ArrayList<>();
        while (!tokens.peek("END") && !tokens.peek("ELSE")) {
            thenBody.add(parseStmt());
        }

        List<Ast.Stmt> elseBody = new ArrayList<>();
        if (tokens.peek("ELSE")) {
            tokens.match("ELSE");
            while (!tokens.peek("END")) {
                elseBody.add(parseStmt());
            }
        }

        if (!tokens.peek("END")) {
            throw new ParseException("END expected");
        }
        tokens.match("END");

        return new Ast.Stmt.If(condition, thenBody, elseBody);
    }

    private Ast.Stmt.For parseForStmt() throws ParseException {
        // for_stmt ::= 'FOR' identifier 'IN' expr 'DO' stmt* 'END'
        if (!tokens.peek("FOR")) {
            throw new ParseException("FOR expected");
        }
        tokens.match("FOR");

        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier after 'FOR'.");
        }
        tokens.match(Token.Type.IDENTIFIER);
        String name = tokens.get(-1).literal();

        if (!tokens.peek("IN")) {
            throw new ParseException("IN expected");
        }
        tokens.match("IN");
        var expr = parseExpr();

        if (!tokens.peek("DO")) {
            throw new ParseException("DO expected");
        }
        tokens.match("DO");

        List<Ast.Stmt> body = new ArrayList<>();
        while (!tokens.peek("END")) {
            body.add(parseStmt());
        }

        tokens.match("END");

        return new Ast.Stmt.For(name, expr, body);
    }

    private Ast.Stmt.Return parseReturnStmt() throws ParseException {
        // return_stmt ::= 'RETURN' expr? ';'
        if (!tokens.peek("RETURN")) {
            throw new ParseException("Expected 'RETURN' keyword.");
        }
        tokens.match("RETURN"); // Consume RETURN

        Optional<Ast.Expr> expr = Optional.empty();

        if (!tokens.peek(";")) {
            expr = Optional.of(parseExpr());
        }

        if (!tokens.peek(";")) {
            throw new ParseException("Expected ';' at end of return statement.");
        }
        tokens.match(";");

        return new Ast.Stmt.Return(expr);
    }

    private Ast.Stmt parseExpressionOrAssignmentStmt() throws ParseException {
        // expression_or_assignment_stmt ::= expr ('=' expr)? ';'
        var left = parseExpr();
        if (tokens.peek("=")) {
            tokens.match("=");
            var operator = tokens.get(-1).literal();
            var right = parseExpr();
            if (!tokens.peek(";")) {
                throw new ParseException("Missing semicolon at end of statement");
            }
            tokens.match(";");

            return new Ast.Stmt.Assignment(left, right);
        }
        if (!tokens.peek(";")) {
            throw new ParseException("Missing semicolon at end of statement");
        }
        tokens.match(";");
        return new Ast.Stmt.Expression(left);
    }

    public Ast.Expr parseExpr() throws ParseException {
        return parseLogicalExpr();
    }

    private Ast.Expr parseLogicalExpr() throws ParseException {
        // logical_expr ::= comparison_expr (('AND' | 'OR') comparison_expr)*
        var left = parseComparisonExpr();
        while (tokens.peek("AND") || tokens.peek("OR")) {
            tokens.match(tokens.get(0).literal());
            var operator = tokens.get(-1).literal();
            var right = parseComparisonExpr();

            left = new Ast.Expr.Binary(operator, left, right);
        }

        return left;
    }

    private Ast.Expr parseComparisonExpr() throws ParseException {
        // additive_expr (('<' | '<=' | '>' | '>=' | '==' | '!=') additive_expr)*
        var left = parseAdditiveExpr();
        while (tokens.peek("<") || tokens.peek("<=") || tokens.peek(">") ||
                tokens.peek(">=") ||tokens.peek("==") || tokens.peek("!=")) {
            tokens.match(tokens.get(0).literal());
            var operator = tokens.get(-1).literal();
            var right = parseAdditiveExpr();

            left = new Ast.Expr.Binary(operator, left, right);
        }
        return left; // no comparison found
    }

    private Ast.Expr parseAdditiveExpr() throws ParseException {
        var left = parseMultiplicativeExpr();
        while (tokens.peek("+") || tokens.peek("-")) {
            tokens.match(tokens.get(0).literal()); // consume operator token
            var operator = tokens.get(-1).literal();
            var right = parseMultiplicativeExpr();
            left = new Ast.Expr.Binary(operator, left, right);
        }
        return left; // no +- found
    }

    private Ast.Expr parseMultiplicativeExpr() throws ParseException {
        var left = parseSecondaryExpr();
        while (tokens.peek("*") || tokens.peek("/")) {
            tokens.match(tokens.get(0).literal()); // consume operator token
            var operator = tokens.get(-1).literal();
            var right = parseSecondaryExpr();
            left = new Ast.Expr.Binary(operator, left, right);
        }
        return left; // no */ found
    }

    private Ast.Expr parseSecondaryExpr() throws ParseException {
        var expression = parsePrimaryExpr();

        while (tokens.peek(".")) {
            tokens.match(".");
            if (!tokens.peek(Token.Type.IDENTIFIER)) {
                throw new ParseException("Expected identifier in secondary expression.");
            }
            tokens.match(Token.Type.IDENTIFIER);

            var name = tokens.get(-1).literal();

            if (tokens.peek("(")) { // method call
                tokens.match("(");
                List<Ast.Expr> arguments = new ArrayList<>();

                if (!tokens.peek(")")) {
                    arguments.add(parseExpr());

                    while (tokens.match(",")) {
                        if (tokens.peek(")")) {
                            throw new ParseException("Expected expression after ','");
                        }
                        arguments.add(parseExpr());
                    }
                }

                if (!tokens.peek(")")) {
                    throw new ParseException("Missing ')' at end of method arguments.");
                }
                tokens.match(")");

                expression = new Ast.Expr.Method(expression, name, arguments);
            } else {
                // property
                expression = new Ast.Expr.Property(expression, name);
            }
        }
        return expression;
    }

    private Ast.Expr parsePrimaryExpr() throws ParseException { //TODO: add object exprs
        if (tokens.peek(Token.Type.INTEGER) || tokens.peek(Token.Type.DECIMAL) || tokens.peek(Token.Type.CHARACTER) || tokens.peek(Token.Type.STRING) || tokens.peek("NIL") || tokens.peek("TRUE") || tokens.peek("FALSE")) {
            return parseLiteralExpr();
        } else if (tokens.peek("(")) {
            return parseGroupExpr();
        } else if (tokens.peek("OBJECT")) {
            return parseObjectExpr();
        } else if (tokens.peek(Token.Type.IDENTIFIER)) {
            return parseVariableOrFunctionExpr();
        } else {
            throw new ParseException("Unexpected token: " + (tokens.has(0) ? tokens.get(0).literal() : "empty?"));
        }
    }

    private Ast.Expr.Literal parseLiteralExpr() throws ParseException {
        if (tokens.match("NIL")) {
            return new Ast.Expr.Literal(null);
        } else if (tokens.match("TRUE")) {
            return new Ast.Expr.Literal(Boolean.TRUE);
        } else if (tokens.match("FALSE")) {
            return new Ast.Expr.Literal(Boolean.FALSE);
        } else if (tokens.peek(Token.Type.INTEGER)) {
            var literal = tokens.get(0).literal();
            tokens.match(Token.Type.INTEGER);

            if (literal.contains("e")) {
                var decimal = new java.math.BigDecimal(literal);
                return new Ast.Expr.Literal(decimal.toBigInteger());
            }
            return new Ast.Expr.Literal(new java.math.BigInteger(literal));
        } else if (tokens.peek(Token.Type.DECIMAL)) {
            var literal = tokens.get(0).literal();
            tokens.match(Token.Type.DECIMAL);

            return new Ast.Expr.Literal(new java.math.BigDecimal(literal));
        } else if (tokens.peek(Token.Type.CHARACTER)) {
            var literal = tokens.get(0).literal();
            tokens.match(Token.Type.CHARACTER);

            var content = literal.substring(1, literal.length() - 1); // Remove quotes
            content = processEscapeSequences(content);
            return new Ast.Expr.Literal(content.charAt(0));
        } else if (tokens.peek(Token.Type.STRING)) {
            var literal = tokens.get(0).literal();
            tokens.match(Token.Type.STRING);

            var content = literal.substring(1, literal.length() - 1); // Remove quotes
            content = processEscapeSequences(content);
            return new Ast.Expr.Literal(content);
        } else {
            throw new ParseException("Unexpected token in parseLiteralExpr");
        }
    }

    private String processEscapeSequences(String literal) {
        return literal.replace("\\b", "\b")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\'", "\'")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private Ast.Expr.Group parseGroupExpr() throws ParseException {
        if (!tokens.peek("(")) {
            throw new ParseException("Expected '('");
        }
        tokens.match("(");
        var argument = parseExpr();
        if (!tokens.peek(")")) {
            throw new ParseException("Expected ')'");
        }
        tokens.match(")");
        return new Ast.Expr.Group(argument);
    }

    private Ast.Expr.ObjectExpr parseObjectExpr() throws ParseException {
        // object_expr ::= 'OBJECT' identifier? 'DO' let_stmt* def_stmt* 'END'
        if (!tokens.peek("OBJECT")) {
            throw new ParseException("Expected OBJECT");
        }
        tokens.match("OBJECT");

        Optional<String> name = Optional.empty();
        if (tokens.peek(Token.Type.IDENTIFIER)  && !tokens.peek("DO")) {
            tokens.match(Token.Type.IDENTIFIER);
            name = Optional.of(tokens.get(-1).literal());
        }

        if (!tokens.peek("DO")) {
            throw new ParseException("Expected DO");
        }
        tokens.match("DO");

        List<Ast.Stmt.Let> fields = new ArrayList<>();
        while (tokens.peek("LET")) {
            fields.add(parseLetStmt());
        }

        List<Ast.Stmt.Def> methods = new ArrayList<>();
        while (tokens.peek("DEF")) {
            methods.add(parseDefStmt());
        }

        if (!tokens.peek("END")) {
            throw new ParseException("Expected END");
        }
        tokens.match("END");

        return new Ast.Expr.ObjectExpr(name, fields, methods);
    }

    private Ast.Expr parseVariableOrFunctionExpr() throws ParseException {
        // variable_or_function_expr ::= identifier ('(' (expr (',' expr)*)? ')')?

        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected IDENTIFIER in variable/function expression");
        }
        tokens.match(Token.Type.IDENTIFIER);
        String name = tokens.get(-1).literal();

        if (tokens.peek("(")) {
            tokens.match("(");
            List<Ast.Expr> arguments = new ArrayList<>();

            if (!tokens.peek(")")) {
                // First argument
                arguments.add(parseExpr());

                // Additional arguments (must be comma-separated)
                while (tokens.match(",")) {
                    if (tokens.peek(")")) {
                        throw new ParseException("Expected expression after ','");
                    }
                    arguments.add(parseExpr());
                }
            }

            if (!tokens.peek(")")) {
                throw new ParseException("Expected ')'");
            }
            tokens.match(")");

            return new Ast.Expr.Function(name, arguments);
        }

        return new Ast.Expr.Variable(name); // no parenthesis after name
    }

    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Returns true if there is a token at (index + offset).
         */
        public boolean has(int offset) {
            return index + offset < tokens.size();
        }

        /**
         * Returns the token at (index + offset).
         */
        public Token get(int offset) {
            checkState(has(offset));
            return tokens.get(index + offset);
        }

        /**
         * Returns true if the next characters match their corresponding
         * pattern. Each pattern is either a {@link Token.Type}, matching tokens
         * of that type, or a {@link String}, matching tokens with that literal.
         * In effect, {@code new Token(Token.Type.IDENTIFIER, "literal")} is
         * matched by both {@code peek(Token.Type.IDENTIFIER)} and
         * {@code peek("literal")}.
         */
        public boolean peek(Object... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var token = tokens.get(index + offset);
                var pattern = patterns[offset];
                checkState(pattern instanceof Token.Type || pattern instanceof String, pattern);
                if (!token.type().equals(pattern) && !token.literal().equals(pattern)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the token stream.
         */
        public boolean match(Object... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
            }
            return peek;
        }

    }

}
