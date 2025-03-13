package plc.project.evaluator;

import plc.project.parser.Ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

//The evaluator is reasonably complex and many pieces tend to overlap. The recommended path is something like the following:
//
//1. Implement basic expressions like Expr.Group. / Expr.Binary, which don't strictly rely on scope.
//       Implementing Expr.Function below first for log will help if you can work through it!
//2. Implement Expr.Variable/Expr.Function, ensuring that log can be evaluated (critical for testing evaluation order).
//3. Implement basic variable/scope manipulation statements like Stmt.Let, Stmt.If, and Stmt.Assignment (for variables).
//4. Implement object-related evaluation, starting with Expr.Property/Expr.Method and Stmt.Assignment (for properties).
//5. Implement function definition for Stmt.Def and then field/method definition in Expr.ObjectExpr.
//      Pay very close attention to scope behavior here!
//6. Remaining gaps can be filled in as needed; the above covers the main path to get through scoping.

public final class Evaluator implements Ast.Visitor<RuntimeValue, EvaluateException> {

    private Scope scope;

    public Evaluator(Scope scope) {
        this.scope = scope;
    }

    @Override
    public RuntimeValue visit(Ast.Source ast) throws EvaluateException {
        RuntimeValue value = new RuntimeValue.Primitive(null);
        for (var stmt : ast.statements()) {
            value = visit(stmt);
        }
        //TODO: Handle the possibility of RETURN being called outside of a function.
        return value;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Let ast) throws EvaluateException {
//        Defines a variable in the current scope, with the following behavior:
//        Ensure name is not already defined in the current scope.
//        Define name in the current scope with an initial value of the result of evaluating value, if present, or else NIL.
//                Return the value of the defined variable, for REPL/testing.

        if (scope.get(ast.name(), true).isPresent()) {
            throw new EvaluateException("Variable is already defined in the current scope: " + ast.name());
        }

        RuntimeValue initialValue;
        if (ast.value().isPresent()) {
            initialValue = visit(ast.value().get());
        } else {
            initialValue = new RuntimeValue.Primitive(null);
        }
        scope.define(ast.name(), initialValue);

        return initialValue;
    }


    @Override
    public RuntimeValue visit(Ast.Stmt.Def ast) throws EvaluateException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.If ast) throws EvaluateException {
//        Evaluates an IF statement, with the following behavior:
//        Evaluate condition and ensure it is a Boolean.
//        Within a new scope:
//        If condition is TRUE, evaluate thenBody statements.
//        Otherwise (when FALSE), evaluate elseBody statements.
//        Ensure the current scope is restored to the original scope, even if an exception is thrown.
//        Return the value of the last statement executed, or else NIL if empty, for REPL/testing.
        RuntimeValue condition = visit(ast.condition());

        if (!(condition instanceof RuntimeValue.Primitive primitive && primitive.value() instanceof Boolean)) {
            throw new EvaluateException("Condition in IF statement must be a boolean value");
        }

        Scope originalScope = this.scope;
        this.scope = new Scope(originalScope);
        RuntimeValue result = new RuntimeValue.Primitive(null);

        try {
            if ((Boolean) ((RuntimeValue.Primitive) condition).value()) {
                for (Ast.Stmt stmt : ast.thenBody()) {
                    result = visit(stmt);
                }
            } else {
                for (Ast.Stmt stmt : ast.elseBody()) {
                    result = visit(stmt);
                }
            }
        } finally {
            this.scope = originalScope;
        }
        return result;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.For ast) throws EvaluateException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Return ast) throws EvaluateException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Expression ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Assignment ast) throws EvaluateException {
        RuntimeValue value = visit(ast.value());

        if (ast.expression() instanceof Ast.Expr.Variable variable) {
            String name = variable.name();
            if (scope.get(name, false).isEmpty()) {
                throw new EvaluateException("Variable is not defined: " + name);
            }
            scope.set(name, value);
        } else if (ast.expression() instanceof Ast.Expr.Property property) {
            RuntimeValue receiver = visit(property.receiver());
            if (!(receiver instanceof RuntimeValue.ObjectValue object)) {
                throw new EvaluateException("Receiver must be an object.");
            }
            object.scope().set(property.name(), value);
        } else {
            throw new EvaluateException("Invalid assignment target.");
        }

        return value;
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Literal ast) throws EvaluateException {
        return new RuntimeValue.Primitive(ast.value());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Group ast) throws EvaluateException {
        // Evaluates and returns the containing expressions.
        //(expr)
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Binary ast) throws EvaluateException { //TODO: Make sure it short circuits
        var left = visit(ast.left()); // only visits left at first, for short circuiting purposes

        if (!(left instanceof RuntimeValue.Primitive pLeft)) {
            throw new EvaluateException("Only RuntimeValue.Primitive types can be evaluated in binary expressions.");
        }

        Object leftValue = pLeft.value();

        return switch (ast.operator()) { // this is a little wonky, but basically it checks if it is AND or OR first (for short circuits)
            case "AND" -> {
                if (leftValue instanceof Boolean l) {
                    if (!l) yield new RuntimeValue.Primitive(false);
                    var right = visit(ast.right());
                    if (right instanceof RuntimeValue.Primitive pRight && pRight.value() instanceof Boolean r) {
                        yield new RuntimeValue.Primitive(r);
                    }
                }
                throw new EvaluateException("Invalid types for AND operation");
            }
            case "OR" -> {
                if (leftValue instanceof Boolean l) {
                    if (l) yield new RuntimeValue.Primitive(true);
                    var right = visit(ast.right());
                    if (right instanceof RuntimeValue.Primitive pRight && pRight.value() instanceof Boolean r) {
                        yield new RuntimeValue.Primitive(r);
                    }
                }
                throw new EvaluateException("Invalid types for OR operation");
            }
            default -> { // then, if it is not AND or OR right is evaluated and other cases are considered
                var right = visit(ast.right());
                if (!(right instanceof RuntimeValue.Primitive pRight)) {
                    throw new EvaluateException("Only RuntimeValue.Primitive types can be evaluated in binary expressions.");
                }
                Object rightValue = pRight.value();

                yield switch (ast.operator()) {
                    case "+" -> {
                        if (leftValue instanceof String || rightValue instanceof String) {
                            yield new RuntimeValue.Primitive(leftValue.toString() + rightValue.toString());
                        } else if (leftValue instanceof BigDecimal l && rightValue instanceof BigDecimal r) {
                            yield new RuntimeValue.Primitive(l.add(r));
                        } else if (leftValue instanceof BigInteger l && rightValue instanceof BigInteger r) {
                            yield new RuntimeValue.Primitive(l.add(r));
                        }
                        throw new EvaluateException("Addition requires both operands to be BigDecimal, BigInteger, or Strings");
                    }
                    case "-" -> {
                        if (leftValue instanceof BigDecimal l && rightValue instanceof BigDecimal r) {
                            yield new RuntimeValue.Primitive(l.subtract(r));
                        } else if (leftValue instanceof BigInteger l && rightValue instanceof BigInteger r) {
                            yield new RuntimeValue.Primitive(l.subtract(r));
                        }
                        throw new EvaluateException("Subtraction requires both operands to be BigDecimal or BigInteger");
                    }
                    case "*" -> {
                        if (leftValue instanceof BigDecimal l && rightValue instanceof BigDecimal r) {
                            yield new RuntimeValue.Primitive(l.multiply(r));
                        } else if (leftValue instanceof BigInteger l && rightValue instanceof BigInteger r) {
                            yield new RuntimeValue.Primitive(l.multiply(r));
                        }
                        throw new EvaluateException("Multiplication requires both operands to be BigDecimal or BigInteger");
                    }
                    case "/" -> {
                        if (leftValue instanceof BigDecimal l && rightValue instanceof BigDecimal r) {
                            if (r.equals(BigDecimal.ZERO)) throw new EvaluateException("Division by zero");
                            yield new RuntimeValue.Primitive(l.divide(r, RoundingMode.HALF_EVEN));
                        } else if (leftValue instanceof BigInteger l && rightValue instanceof BigInteger r) {
                            if (r.equals(BigInteger.ZERO)) throw new EvaluateException("Division by zero");
                            yield new RuntimeValue.Primitive(l.divide(r));
                        }
                        throw new EvaluateException("Division requires both operands to be BigDecimal or BigInteger");
                    }
                    case "==" -> new RuntimeValue.Primitive(Objects.equals(leftValue, rightValue));
                    case "!=" -> new RuntimeValue.Primitive(!Objects.equals(leftValue, rightValue));
                    case "<" -> {
                        if (leftValue instanceof Comparable l && rightValue instanceof Comparable r) {
                            yield new RuntimeValue.Primitive(l.compareTo(r) < 0);
                        }
                        throw new EvaluateException("Comparison requires Comparable types");
                    }
                    case "<=" -> {
                        if (leftValue instanceof Comparable l && rightValue instanceof Comparable r) {
                            yield new RuntimeValue.Primitive(l.compareTo(r) <= 0);
                        }
                        throw new EvaluateException("Comparison requires Comparable types");
                    }
                    case ">" -> {
                        if (leftValue instanceof Comparable l && rightValue instanceof Comparable r) {
                            yield new RuntimeValue.Primitive(l.compareTo(r) > 0);
                        }
                        throw new EvaluateException("Comparison requires Comparable types");
                    }
                    case ">=" -> {
                        if (leftValue instanceof Comparable l && rightValue instanceof Comparable r) {
                            yield new RuntimeValue.Primitive(l.compareTo(r) >= 0);
                        }
                        throw new EvaluateException("Comparison requires Comparable types");
                    }
                    default -> throw new EvaluateException("Invalid operator");
                };
            }
        };
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Variable ast) throws EvaluateException {
        // Ensures that the variable name is defined and returns it's value.
        Optional<RuntimeValue> variable = scope.get(ast.name(), false);

        if (variable.isEmpty()) {
            throw new EvaluateException("Undefined variable: " + ast.name());
        }

        return variable.get();
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Property ast) throws EvaluateException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Function ast) throws EvaluateException { //TODO: Come back to this
        // 1) Lookup the function in the scope
        Optional<RuntimeValue> functionValueOpt = scope.get(ast.name(), false);

        if (functionValueOpt.isEmpty() || !(functionValueOpt.get() instanceof RuntimeValue.Function function)) {
            throw new EvaluateException("Function not defined: " + ast.name());
        }

        // 2) Evaluate arguments sequentially
        List<RuntimeValue> evaluatedArguments = new ArrayList<>();
        for (Ast.Expr argument : ast.arguments()) {
            evaluatedArguments.add(visit(argument));
        }

        // 3) Invoke the function with the evaluated arguments
        return function.definition().invoke(evaluatedArguments);
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Method ast) throws EvaluateException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public RuntimeValue visit(Ast.Expr.ObjectExpr ast) throws EvaluateException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    /**
     * Helper function for extracting RuntimeValues of specific types. If the
     * type is subclass of {@link RuntimeValue} the check applies to the value
     * itself, otherwise the value is expected to be a {@link RuntimeValue.Primitive}
     * and the check applies to the primitive value.
     */
    private static <T> T requireType(RuntimeValue value, Class<T> type) throws EvaluateException {
        //To be discussed in lecture 3/5.
        if (RuntimeValue.class.isAssignableFrom(type)) {
            if (!type.isInstance(value)) {
                throw new EvaluateException("Expected value to be of type " + type + ", received " + value.getClass() + ".");
            }
            return (T) value;
        } else {
            var primitive = requireType(value, RuntimeValue.Primitive.class);
            if (!type.isInstance(primitive.value())) {
                var received = primitive.value() != null ? primitive.value().getClass() : null;
                throw new EvaluateException("Expected value to be of type " + type + ", received " + received + ".");
            }
            return (T) primitive.value();
        }
    }

}
