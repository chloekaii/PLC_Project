package plc.project.lexer;

/**
 * IMPORTANT: DO NOT CHANGE! This file is part of our project's API and should
 * not be modified by your solution.
 */
public record Token(
    Type type,
    String literal
) {

    public enum Type {
        IDENTIFIER,
        INTEGER,
        DECIMAL,
        CHARACTER,
        STRING,
        OPERATOR
    }

}
