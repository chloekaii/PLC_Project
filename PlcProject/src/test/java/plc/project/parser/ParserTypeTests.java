package plc.project.parser;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import plc.project.lexer.Lexer;
import plc.project.lexer.Token;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Standard JUnit5 parameterized tests. See the RegexTests file from Homework 1
 * or the LexerTests file from the last project part for more information.
 */
final class ParserTypeTests {

    public sealed interface Input {
        record Tokens(List<Token> tokens) implements Input {}
        record Program(String program) implements Input {}
    }

    @ParameterizedTest
    @MethodSource
    void testLetStmt(String test, Input input, Ast.Stmt.Let expected) {
        test(input, expected, Parser::parseStmt);
    }

    private static Stream<Arguments> testLetStmt() {
        return Stream.of(
            Arguments.of("Type",
                new Input.Tokens(List.of(
                    new Token(Token.Type.IDENTIFIER, "LET"),
                    new Token(Token.Type.IDENTIFIER, "name"),
                    new Token(Token.Type.OPERATOR, ":"),
                    new Token(Token.Type.IDENTIFIER, "Type"),
                    new Token(Token.Type.OPERATOR, ";")
                )),
                new Ast.Stmt.Let("name", Optional.of("Type"), Optional.empty())
            ),
            Arguments.of("Missing Type",
                new Input.Tokens(List.of(
                    new Token(Token.Type.IDENTIFIER, "LET"),
                    new Token(Token.Type.IDENTIFIER, "name"),
                    new Token(Token.Type.OPERATOR, ":"),
                    new Token(Token.Type.OPERATOR, ";")
                )),
                null //ParseException
            )
        );
    }

    @ParameterizedTest
    @MethodSource
    void testDefStmt(String test, Input input, Ast.Stmt.Def expected) {
        test(input, expected, Parser::parseStmt);
    }

    private static Stream<Arguments> testDefStmt() {
        return Stream.of(
            Arguments.of("Parameter Type",
                new Input.Tokens(List.of(
                    new Token(Token.Type.IDENTIFIER, "DEF"),
                    new Token(Token.Type.IDENTIFIER, "name"),
                    new Token(Token.Type.OPERATOR, "("),
                    new Token(Token.Type.IDENTIFIER, "parameter"),
                    new Token(Token.Type.OPERATOR, ":"),
                    new Token(Token.Type.IDENTIFIER, "Type"),
                    new Token(Token.Type.OPERATOR, ")"),
                    new Token(Token.Type.IDENTIFIER, "DO"),
                    new Token(Token.Type.IDENTIFIER, "END")
                )),
                new Ast.Stmt.Def("name", List.of("parameter"), List.of(Optional.of("Type")), Optional.empty(), List.of())
            ),
            Arguments.of("Return Type",
                new Input.Tokens(List.of(
                    new Token(Token.Type.IDENTIFIER, "DEF"),
                    new Token(Token.Type.IDENTIFIER, "name"),
                    new Token(Token.Type.OPERATOR, "("),
                    new Token(Token.Type.OPERATOR, ")"),
                    new Token(Token.Type.OPERATOR, ":"),
                    new Token(Token.Type.IDENTIFIER, "Type"),
                    new Token(Token.Type.IDENTIFIER, "DO"),
                    new Token(Token.Type.IDENTIFIER, "END")
                )),
                new Ast.Stmt.Def("name", List.of(), List.of(), Optional.of("Type"), List.of())
            )
        );
    }

    interface ParserMethod<T> {
        T invoke(Parser parser) throws ParseException;
    }

    /**
     * Test function for the Parser. The {@link Input} parameter handles parser
     * input, which may either be a direct list of tokens or a String program.
     * Using a String program is easier for tests, but relies on your Lexer
     * working properly!
     */
    private static <T extends Ast> void test(Input input, @Nullable T expected, ParserMethod<T> method) {
        var tokens = switch (input) {
            case Input.Tokens i -> i.tokens();
            case Input.Program i -> Assertions.assertDoesNotThrow(() -> new Lexer(i.program).lex());
        };
        Parser parser = new Parser(tokens);
        if (expected != null) {
            var ast = Assertions.assertDoesNotThrow(() -> method.invoke(parser));
            Assertions.assertEquals(expected, ast);
        } else {
            Assertions.assertThrows(ParseException.class, () -> method.invoke(parser));
        }
    }

}
