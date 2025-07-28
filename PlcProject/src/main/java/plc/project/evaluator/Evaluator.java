package plc.project.evaluator;

import plc.project.parser.Ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;

public final class Evaluator implements Ast.Visitor<RuntimeValue, EvaluateException> {

    private Scope scope;

    public Evaluator(Scope scope) {
        this.scope = scope;
    }

    @Override
    public RuntimeValue visit(Ast.Source ast) throws EvaluateException {
        RuntimeValue value = new RuntimeValue.Primitive(null);
        try {
            for (var stmt : ast.statements()) {
                value = visit(stmt);
            }
        } catch (ReturnException e) {
            throw new EvaluateException("RETURN called outside of a function.");
        }
        return value;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Let ast) throws EvaluateException {
//        Defines a variable in the current scope, with the following behavior:
//        Ensure name is not already defined in the current scope.
//        Define name in the current scope with an initial value of the result of evaluating value, if present, or else NIL.
//                Return the value of the defined variable, for REPL/testing.

        if (scope.get(ast.name(), true).isPresent()) {
            throw new EvaluateException("Variable already defined: " + ast.name());
        }
        RuntimeValue value = ast.value().isPresent() ? visit(ast.value().get()) : new RuntimeValue.Primitive(null);
        scope.define(ast.name(), value);
        return value;
    }


    @Override
    public RuntimeValue visit(Ast.Stmt.Def ast) throws EvaluateException {
        if (scope.get(ast.name(), true).isPresent()) {
            throw new EvaluateException("Function is already defined in the current scope: " + ast.name());
        }

        var paramNames = ast.parameters();
        if (paramNames.size() != paramNames.stream().distinct().count()) {
            throw new EvaluateException("Function parameters must be unique.");
        }

        RuntimeValue.Function function = new RuntimeValue.Function(ast.name(), arguments -> {
            if (arguments.size() != ast.parameters().size()) {
                throw new EvaluateException("Incorrect number of arguments for function " + ast.name());
            }
            Scope functionScope = new Scope(scope);
            for (int i = 0; i < ast.parameters().size(); i++) {
                functionScope.define(ast.parameters().get(i), arguments.get(i));
            }

            Evaluator functionEvaluator = new Evaluator(functionScope);
            try {
                for (Ast.Stmt stmt : ast.body()) {
                    functionEvaluator.visit(stmt);
                }
            } catch (ReturnException e) {
                return e.value;
            }
            return new RuntimeValue.Primitive(null); // functions without RETURN
        });

        scope.define(ast.name(), function);
        return function;
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

        if (!(condition instanceof RuntimeValue.Primitive(Object value) && value instanceof Boolean)) {
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
        RuntimeValue iterableValue = visit(ast.expression());

        if (!(iterableValue instanceof RuntimeValue.Primitive(Object value)) || !(value instanceof List<?> list)) {
            throw new EvaluateException("For-loop value must be a list.");
        }
        RuntimeValue result = new RuntimeValue.Primitive(null);
        for (Object item : list) {
            if (!(item instanceof RuntimeValue.Primitive p) || !(p.value() instanceof BigInteger)) {
                throw new EvaluateException("Iterable elements must be integers.");
            }
            Scope iterationScope = new Scope(scope);
            iterationScope.define(ast.name(), p);
            Evaluator loopEvaluator = new Evaluator(iterationScope);

            for (Ast.Stmt stmt : ast.body()) {
                loopEvaluator.visit(stmt);
            }
        }
        return result;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Return ast) throws EvaluateException {
        RuntimeValue returnValue = new RuntimeValue.Primitive(null);
        if (ast.value().isPresent()) {
            returnValue = visit(ast.value().get());
        }
        throw new ReturnException(returnValue);
    }

    private static class ReturnException extends RuntimeException {
        final RuntimeValue value;

        ReturnException(RuntimeValue value) {
            this.value = value;
        }
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Expression ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Assignment ast) throws EvaluateException {
        if (ast.expression() instanceof Ast.Expr.Variable variable) {
            String name = variable.name();
            if (scope.get(name, false).isEmpty()) {
                throw new EvaluateException("Variable is not defined: " + name);
            }
            RuntimeValue value = visit(ast.value());
            scope.set(name, value);
            return value;
        } else if (ast.expression() instanceof Ast.Expr.Property property) {
            RuntimeValue receiver = visit(property.receiver());
            if (!(receiver instanceof RuntimeValue.ObjectValue object)) {
                throw new EvaluateException("Receiver must be an object.");
            }
            RuntimeValue value = visit(ast.value());
            if (object.scope().get(property.name(), true).isEmpty()) {
                throw new EvaluateException("Property is not defined: " + property.name());
            }
            object.scope().set(property.name(), value);
            return value;
        } else {
            throw new EvaluateException("Invalid assignment target.");
        }
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
    public RuntimeValue visit(Ast.Expr.Binary ast) throws EvaluateException { //TODO: refactor?
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
                            yield new RuntimeValue.Primitive(String.valueOf(leftValue) + String.valueOf(rightValue));
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
        Optional<RuntimeValue> variable = scope.get(ast.name(), false);

        if (variable.isEmpty()) {
            throw new EvaluateException("Undefined variable: " + ast.name());
        }

        RuntimeValue value = variable.get();

        // If the value is a primitive, return the value directly
        if (value instanceof RuntimeValue.Primitive) {
            return value;
        } else {
            return value; // this might actually be doing nothing idk just trying to figure something out :(
        }
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Property ast) throws EvaluateException {
        // Evaluates a property, with the following behavior:
        //Evaluate the receiver and ensure it is a RuntimeValue.Object.
        //Ensure that the variable name is defined by the receiver.
        //Return the variable value.

        RuntimeValue receiver = visit(ast.receiver());

        // 1. Evaluate the receiver
        if (!(receiver instanceof RuntimeValue.ObjectValue object)) {
            throw new EvaluateException("Receiver must be an object.");
        }

        // 2. Ensure the var name is defined by receiver
        Optional<RuntimeValue> propertyValue = object.scope().get(ast.name(), false);
        if (propertyValue.isEmpty()) {
            throw new EvaluateException("Property not defined: " + ast.name());
        }
        // 3. Return the var value
        return propertyValue.get();
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Function ast) throws EvaluateException {
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
        // Evaluates a method, with the following behavior:
        //Evaluate the receiver and ensure it is a RuntimeValue.Object.
        //Ensure that the method name is defined by the receiver and that the value is actually a RuntimeValue.Function.
        //Evaluate all arguments sequentially.
        //Return the result of invoking the method with the first argument being the receiver followed by any explicit arguments.
        //This is a common calling convention for methods.

        RuntimeValue receiver = visit(ast.receiver());
        if (!(receiver instanceof RuntimeValue.ObjectValue object)) {
            throw new EvaluateException("Receiver must be an object.");
        }

        Optional<RuntimeValue> methodOpt = object.scope().get(ast.name(), false);
        if (methodOpt.isEmpty() || !(methodOpt.get() instanceof RuntimeValue.Function function)) {
            throw new EvaluateException("Method not found: " + ast.name());
        }

        List<RuntimeValue> evaluatedArgs = new ArrayList<>();
        for (Ast.Expr arg : ast.arguments()) {
            evaluatedArgs.add(visit(arg));
        }

        evaluatedArgs.add(0, object); // Add receiver as the first argument
        return function.definition().invoke(evaluatedArgs);
    }

    @Override
    public RuntimeValue visit(Ast.Expr.ObjectExpr ast) throws EvaluateException {
        RuntimeValue.ObjectValue object = new RuntimeValue.ObjectValue(ast.name(), new Scope(scope));

        for (Ast.Stmt.Let field : ast.fields()) {
            if (object.scope().get(field.name(), true).isPresent()) {
                throw new EvaluateException("Field is already defined in the object's scope: " + field.name());
            }
            object.scope().define(field.name(), field.value().isPresent() ? visit(field.value().get()) : new RuntimeValue.Primitive(null));
        }

        for (Ast.Stmt.Def method : ast.methods()) {
            if (object.scope().get(method.name(), true).isPresent()) {
                throw new EvaluateException("Method is already defined in the object's scope: " + method.name());
            }
            var function = new RuntimeValue.Function(method.name(), arguments -> {
                if (arguments.size() != method.parameters().size() + 1) { // +1 for 'this'
                    throw new EvaluateException("Incorrect number of arguments for method " + method.name());
                }
                Scope methodScope = new Scope(object.scope());
                methodScope.define("this", arguments.get(0));

                for (int i = 0; i < method.parameters().size(); i++) {
                    methodScope.define(method.parameters().get(i), arguments.get(i + 1)); // Skip 'this'
                }

                Evaluator methodEvaluator = new Evaluator(methodScope);
                try {
                    for (Ast.Stmt stmt : method.body()) {
                        methodEvaluator.visit(stmt);
                    }
                } catch (ReturnException e) {
                    return e.value;
                }
                return new RuntimeValue.Primitive(null);
            });
            object.scope().define(method.name(), function);
        }
        return object;
    }

    /**
     * Helper function for extracting RuntimeValues of specific types. If the
     * type is subclass of {@link RuntimeValue} the check applies to the value
     * itself, otherwise the value is expected to be a {@link RuntimeValue.Primitive}
     * and the check applies to the primitive value.
     */
    static <T> T requireType(RuntimeValue value, Class<T> type) throws EvaluateException {
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
