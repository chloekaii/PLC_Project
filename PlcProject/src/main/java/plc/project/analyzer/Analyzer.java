package plc.project.analyzer;

import plc.project.evaluator.EvaluateException;
import plc.project.evaluator.Evaluator;
import plc.project.evaluator.RuntimeValue;
import plc.project.parser.Ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Analyzer implements Ast.Visitor<Ir, AnalyzeException> {

    private Scope scope;

    public Analyzer(Scope scope) {
        this.scope = scope;
    }

    @Override
    public Ir.Source visit(Ast.Source ast) throws AnalyzeException {
        var statements = new ArrayList<Ir.Stmt>();
        for (var statement : ast.statements()) {
            statements.add(visit(statement));
        }
        return new Ir.Source(statements);
    }

    private Ir.Stmt visit(Ast.Stmt ast) throws AnalyzeException {
        return (Ir.Stmt) visit((Ast) ast); //helper to cast visit(Ast.Stmt) to Ir.Stmt
    }

    @Override
    public Ir.Stmt.Let visit(Ast.Stmt.Let ast) throws AnalyzeException {
        // LET name (: Type)? (= expr)?;
        if (scope.get(ast.name(), true).isPresent()) {
            throw new AnalyzeException("Variable " + ast.name() + " is already declared");
        }
        Optional<Type> type = Optional.empty();
        if (ast.type().isPresent()) {
            if (!Environment.TYPES.containsKey(ast.type().get())) {
                throw new AnalyzeException("Type " + ast.type().get() + " is not defined.");
            }
            type = Optional.of(Environment.TYPES.get(ast.type().get()));
        }
        Optional<Ir.Expr> value = ast.value().isPresent()
                ? Optional.of(visit(ast.value().get()))
                : Optional.empty();
        var variableType = type.or(() -> value.map(Ir.Expr::type)).orElse(Type.ANY);
        if (value.isPresent()) {
            requireSubtype(value.get().type(), variableType);
        }
        try {
            scope.define(ast.name(), variableType);
        } catch (IllegalStateException e) {
            throw new AnalyzeException(e.getMessage());
        }
        return new Ir.Stmt.Let(ast.name(), variableType, value);
    }

    @Override
    public Ir.Stmt.Def visit(Ast.Stmt.Def ast) throws AnalyzeException {
        // 	Analyzes a DEF statement, with the following behavior:
        //Define the function name (which must not already be defined) in the current scope with a type of Type.Function.
        //Parameter names must be unique.
        //Parameter types and the function's returns type must all be in Environment.TYPES; if not provided explicitly the type is Any.
        //In a new child scope:
        //Define variables for all parameters.
        //Define the variable $RETURNS (which cannot be used as a variable in our language) to store the return type (see Stmt.Return).
        //Analyze all body statements sequentially.
        //Note: We will not be performing control flow analysis to ensure that the function returns a value.
        if (scope.get(ast.name(), true).isPresent()) {
            throw new AnalyzeException("Function " + ast.name() + " is already defined in the current scope.");
        }

        var paramNames = ast.parameters();
        if (paramNames.size() != paramNames.stream().distinct().count()) {
            throw new AnalyzeException("Function parameters must be unique.");
        }

        var paramTypes = new ArrayList<Type>();
        for (var typeName : ast.parameterTypes()) {
            if (typeName.isPresent()) {
                if (!Environment.TYPES.containsKey(typeName.get())) {
                    throw new AnalyzeException("Unknown parameter type: " + typeName.get());
                }
                paramTypes.add(Environment.TYPES.get(typeName.get()));
            } else {
                paramTypes.add(Type.ANY);
            }
        }

        Type returnType = ast.returnType().isPresent()
                ? Environment.TYPES.getOrDefault(ast.returnType().get(), Type.ANY)
                : Type.ANY;

        try {
            scope.define(ast.name(), new Type.Function(paramTypes, returnType));
        } catch (IllegalStateException e) {
            throw new AnalyzeException(e.getMessage());
        }

        Scope functionScope = new Scope(scope);
        for (int i = 0; i < paramNames.size(); i++) {
            try {
                functionScope.define(paramNames.get(i), paramTypes.get(i));
            } catch (IllegalStateException e) {
                throw new AnalyzeException("Duplicate parameter: " + paramNames.get(i));
            }
        }

        try {
            functionScope.define("$RETURNS", returnType);
        } catch (IllegalStateException e) {
            throw new AnalyzeException("$RETURNS already defined unexpectedly.");
        }

        var analyzer = new Analyzer(functionScope);
        var body = new ArrayList<Ir.Stmt>();
        for (var stmt : ast.body()) {
            body.add(analyzer.visit(stmt));
        }

        var irParams = new ArrayList<Ir.Stmt.Def.Parameter>();
        for (int i = 0; i < paramNames.size(); i++) {
            irParams.add(new Ir.Stmt.Def.Parameter(paramNames.get(i), paramTypes.get(i)));
        }

        return new Ir.Stmt.Def(ast.name(), irParams, returnType, body);
    }

    @Override
    public Ir.Stmt.If visit(Ast.Stmt.If ast) throws AnalyzeException {
        // Analyzes an IF statement, with the following behavior:
        //Analyze the ast's condition, which must be a subtype of Boolean.
        //Analyze both the then/else bodies, each within their own new child scope.
        //Evaluation will only evaluate one, but for purposes of compilation we need to look at both!
        Ir.Expr condition = visit(ast.condition());
        requireSubtype(condition.type(), Type.BOOLEAN);

        Scope thenScope = new Scope(scope);
        var thenAnalyzer = new Analyzer(thenScope);
        var thenBody = new ArrayList<Ir.Stmt>();
        for (Ast.Stmt stmt : ast.thenBody()) {
            thenBody.add(thenAnalyzer.visit(stmt));
        }

        Scope elseScope = new Scope(scope);
        var elseAnalyzer = new Analyzer(elseScope);
        var elseBody = new ArrayList<Ir.Stmt>();
        for (Ast.Stmt stmt : ast.elseBody()) {
            elseBody.add(elseAnalyzer.visit(stmt));
        }

        return new Ir.Stmt.If(condition, thenBody, elseBody);
    }

    @Override
    public Ir.Stmt.For visit(Ast.Stmt.For ast) throws AnalyzeException {
        // 	Analyzes a FOR loop, with the following behavior:
        //Analyze the ast's expression, which must be a subtype of Iterable.
        //In a new child scope:
        //Define the variable name to have type Integer (our language will require all Iterables to be of Integers).
        //Analyze all body statements sequentially.

        Ir.Expr expression = visit(ast.expression());
        requireSubtype(expression.type(), Type.ITERABLE);
        Type variableType = Type.INTEGER;

        Scope loopScope = new Scope(scope);
        loopScope.define(ast.name(), variableType);

        Analyzer analyzer = new Analyzer(loopScope);
        List<Ir.Stmt> body = new ArrayList<>();
        for (Ast.Stmt stmt : ast.body()) {
            body.add(analyzer.visit(stmt));
        }

        return new Ir.Stmt.For(ast.name(), variableType, expression, body);
    }

    @Override
    public Ir.Stmt.Return visit(Ast.Stmt.Return ast) throws AnalyzeException {
        // Analyzes a RETURN statement, with the following behavior:
        //Ensure the variable $RETURNS is defined (see DEF), which contains the expected return type.
        //If this variable isn't defined, it means we're returning outside of a function!
        //Verify the type of the return value, which is Nil if absent, is a subtype of $RETURNS.
        Optional<Type> returnTypeOpt = scope.get("$RETURNS", false);
        if (returnTypeOpt.isEmpty()) {
            throw new AnalyzeException("RETURN statement used outside of a function.");
        }
        Type expectedReturnType = returnTypeOpt.get();

        Optional<Ir.Expr> value = Optional.empty();
        if (ast.value().isPresent()) {
            Ir.Expr expr = visit(ast.value().get());
            requireSubtype(expr.type(), expectedReturnType);
            value = Optional.of(expr);
        } else {
            // No return
            requireSubtype(Type.NIL, expectedReturnType);
        }

        return new Ir.Stmt.Return(value);
    }

    @Override
    public Ir.Stmt.Expression visit(Ast.Stmt.Expression ast) throws AnalyzeException {
        var expression = visit(ast.expression());
        return new Ir.Stmt.Expression(expression);
    }

    @Override
    public Ir.Stmt.Assignment visit(Ast.Stmt.Assignment ast) throws AnalyzeException { // TODO: finish
        Ir.Expr value = visit(ast.value());

        if (ast.expression() instanceof Ast.Expr.Variable variable) {
            Optional<Type> varType = scope.get(variable.name(), false);
            if (varType.isEmpty()) {
                throw new AnalyzeException("Variable " + variable.name() + " is not defined.");
            }

            requireSubtype(value.type(), varType.get());
            Ir.Expr.Variable target = visit(variable);
            return new Ir.Stmt.Assignment.Variable(target, value);
        }

        if (ast.expression() instanceof Ast.Expr.Property property) {
            Ir.Expr receiver = visit(property.receiver());
            if (receiver.type().equals(Type.NIL)) {
                throw new AnalyzeException("Cannot assign property on NIL receiver.");
            }
            if (!(receiver.type() instanceof Type.Object objectType)) {
                throw new AnalyzeException("Receiver of property " + property.name() + " must be of object type.");
            }

            Optional<Type> propType = objectType.scope().get(property.name(), false);
            if (propType.isEmpty()) {
                throw new AnalyzeException("Property " + property.name() + " is not defined in object.");
            }

            requireSubtype(value.type(), propType.get());
            return new Ir.Stmt.Assignment.Property(
                    new Ir.Expr.Property(receiver, property.name(), propType.get()),
                    value
            );
        }
        throw new AnalyzeException("Assignment target must be a variable or property.");
    }

    private Ir.Expr visit(Ast.Expr ast) throws AnalyzeException {
        return (Ir.Expr) visit((Ast) ast); //helper to cast visit(Ast.Expr) to Ir.Expr
    }

    @Override
    public Ir.Expr.Literal visit(Ast.Expr.Literal ast) throws AnalyzeException {
        var type = switch (ast.value()) {
            case null -> Type.NIL;
            case Boolean _ -> Type.BOOLEAN;
            case BigInteger _ -> Type.INTEGER;
            case BigDecimal _ -> Type.DECIMAL;
            case String _ -> Type.STRING;
            //If the AST value isn't one of the above types, the Parser is
            //returning an incorrect AST - this is an implementation issue,
            //hence throw AssertionError rather than AnalyzeException.
            default -> throw new AssertionError(ast.value().getClass());
        };
        return new Ir.Expr.Literal(ast.value(), type);
    }

    @Override
    public Ir.Expr.Group visit(Ast.Expr.Group ast) throws AnalyzeException {
        Ir.Expr inner = visit(ast.expression());
        return new Ir.Expr.Group(inner);
    }

    @Override
    public Ir.Expr.Binary visit(Ast.Expr.Binary ast) throws AnalyzeException {
        // Analyzes a binary expression, with the following behavior:
        //+: If either operand is a String, the result is a String. Otherwise, the left operand must be a subtypes of Integer/Decimal and right must be the same type, which is also the result type.
        //-/*//: The left operand must be either an Integer/Decimal and the right must be the same type, which is also the result type.
        //</<=/>/>=: The left operand must be a subtype of Comparable and the right must be the same type. The result is a Boolean.
        //==/!=: Both operands must be a subtype of Equatable. The result is a Boolean.
        //AND/OR: Both operands must be a subtype of Boolean. The result is a Boolean.

        var left = visit(ast.left());
        var right = visit(ast.right());
        String op = ast.operator();
        Type resultType;

        switch (op) {
            case "+" -> {
                if (left.type().equals(Type.STRING) || right.type().equals(Type.STRING)) { // if 1 is string
                    resultType = Type.STRING;
                }
                else if ((left.type().equals(Type.INTEGER) && right.type().equals(Type.INTEGER)) ||
                        (left.type().equals(Type.DECIMAL) && right.type().equals(Type.DECIMAL))) { // both must be decimal or int
                    resultType = left.type();
                } else { // invalid type
                    throw new AnalyzeException("Invalid operand types for +: " + left.type() + ", " + right.type());
                }
            }

            case "-", "*", "/" -> {
                if (left.type().equals(Type.INTEGER) && right.type().equals(Type.INTEGER)) { // both must be decimal or int
                    resultType = Type.INTEGER;
                } else if (left.type().equals(Type.DECIMAL) && right.type().equals(Type.DECIMAL)) {
                    resultType = Type.DECIMAL;
                } else { // invalid types
                    throw new AnalyzeException("Invalid operand types for arithmetic: " + left.type() + ", " + right.type());
                }
            }

            case "<", "<=", ">", ">=" -> { // must both be comparable AND same type
                requireSubtype(left.type(), Type.COMPARABLE);
                requireSubtype(right.type(), left.type());
                resultType = Type.BOOLEAN;
            }

            case "==", "!=" -> { // must both be equatable
                requireSubtype(left.type(), Type.EQUATABLE);
                requireSubtype(right.type(), Type.EQUATABLE);
                resultType = Type.BOOLEAN;
            }

            case "AND", "OR" -> { // must both be bools
                requireSubtype(left.type(), Type.BOOLEAN);
                requireSubtype(right.type(), Type.BOOLEAN);
                resultType = Type.BOOLEAN;
            }

            default -> throw new AnalyzeException("Unknown binary operator: " + op);
        }

        return new Ir.Expr.Binary(op, left, right, resultType);
    }

    @Override
    public Ir.Expr.Variable visit(Ast.Expr.Variable ast) throws AnalyzeException {
        var type = scope.get(ast.name(), false)
                .orElseThrow(() -> new AnalyzeException("Variable " + ast.name() + " is not defined."));
        return new Ir.Expr.Variable(ast.name(), type);
    }

    @Override
    public Ir.Expr.Property visit(Ast.Expr.Property ast) throws AnalyzeException {
        Ir.Expr receiver = visit(ast.receiver());
        if (receiver.type().equals(Type.NIL)) {
            throw new AnalyzeException("Cannot access property on NIL receiver.");
        }

        if (!(receiver.type() instanceof Type.Object objectType)) {
            throw new AnalyzeException("Receiver must be an Object type to access property " + ast.name());
        }

        Optional<Type> propertyType = objectType.scope().get(ast.name(), false);
        if (propertyType.isEmpty()) {
            throw new AnalyzeException("Property " + ast.name() + " is not defined in object.");
        }

        return new Ir.Expr.Property(receiver, ast.name(), propertyType.get());
    }

    @Override
    public Ir.Expr.Function visit(Ast.Expr.Function ast) throws AnalyzeException {
        // 	Analyzes a function expression, with the following behavior:
        //  Ensure the function name is defined and resolve it's type, which must be an instanceof Type.Function.
        //  Analyze all arguments sequentially, ensuring that each argument is a subtype of the function's corresponding parameter type.
        //  The expression type is the function type's return type.
        Optional<Type> t = scope.get(ast.name(), false);

        if (t.isEmpty() || !(t.get() instanceof Type.Function type)) {
            throw new AnalyzeException("Function " + ast.name() + " is not defined or not a function.");
        }

        if (ast.arguments().size() != type.parameters().size()) { // make sure correct amount of args
            throw new AnalyzeException("Function " + ast.name() + " expects " +
                    type.parameters().size() + " arguments but got " + ast.arguments().size());
        }

        var arguments = new ArrayList<Ir.Expr>(); // make sure the subtypes are correct
        for (int i = 0; i < ast.arguments().size(); i++) {
            Ir.Expr arg = visit(ast.arguments().get(i));
            requireSubtype(arg.type(), type.parameters().get(i));
            arguments.add(arg);
        }

        return new Ir.Expr.Function(ast.name(), arguments, type.returns());
    }

    @Override
    public Ir.Expr.Method visit(Ast.Expr.Method ast) throws AnalyzeException {
        // Analyzes a function expression, with the following behavior:
        //Analyze the receiver, which must be an instanceof Type.Object.
        //Ensure the function name is defined in the object's scope and resolve it's type, which must be an instanceof Type.Function.
        //Analyze all arguments sequentially, ensuring that each argument is a subtype of the function's corresponding parameter type.
        //Important: Unlike the Evaluator, the types for methods will not have the receiver as a parameter (thus, arguments and parameters should have the same size). See the changelog for details.
        //The expression type is the function type's return type.
        Ir.Expr receiver = visit(ast.receiver());

        if (receiver.type().equals(Type.NIL)) {
            throw new AnalyzeException("Cannot call method on NIL receiver.");
        }
        if (!(receiver.type() instanceof Type.Object objectType)) {
            throw new AnalyzeException("Receiver must be an Object type to call method " + ast.name());
        }
        Optional<Type> methodType = objectType.scope().get(ast.name(), false);
        if (methodType.isEmpty() || !(methodType.get() instanceof Type.Function functionType)) {
            throw new AnalyzeException("Method " + ast.name() + " is not defined or not a function in object.");
        }
        if (ast.arguments().size() != functionType.parameters().size()) {
            throw new AnalyzeException("Method " + ast.name() + " expects " +
                    functionType.parameters().size() + " arguments but got " + ast.arguments().size());
        }

        List<Ir.Expr> arguments = new ArrayList<>();
        for (int i = 0; i < ast.arguments().size(); i++) {
            Ir.Expr arg = visit(ast.arguments().get(i));
            requireSubtype(arg.type(), functionType.parameters().get(i));
            arguments.add(arg);
        }
        return new Ir.Expr.Method(receiver, ast.name(), arguments, functionType.returns());
    }

    @Override
    public Ir.Expr.ObjectExpr visit(Ast.Expr.ObjectExpr ast) throws AnalyzeException {
        if (ast.name().isPresent() && Environment.TYPES.containsKey(ast.name().get())) {
            throw new AnalyzeException("Object name cannot be in Environment.TYPES: " + ast.name().get());
        }

        Scope objectScope = new Scope(null);
        Type.Object objectType = new Type.Object(objectScope);

        List<Ir.Stmt.Let> irFields = new ArrayList<>();
        for (Ast.Stmt.Let field : ast.fields()) {
            if (objectScope.get(field.name(), true).isPresent()) {
                throw new AnalyzeException("Field is already defined in object scope: " + field.name());
            }

            Optional<Type> declaredType = field.type().isPresent()
                    ? Optional.ofNullable(Environment.TYPES.get(field.type().get()))
                    : Optional.empty();

            Optional<Ir.Expr> value = field.value().isPresent()
                    ? Optional.of(visit(field.value().get()))
                    : Optional.empty();

            Type variableType = declaredType
                    .or(() -> value.map(Ir.Expr::type))
                    .orElse(Type.ANY);

            if (value.isPresent()) {
                requireSubtype(value.get().type(), variableType);
            }

            objectScope.define(field.name(), variableType);
            irFields.add(new Ir.Stmt.Let(field.name(), variableType, value));
        }

        List<Ir.Stmt.Def> irMethods = new ArrayList<>();
        for (Ast.Stmt.Def method : ast.methods()) {
            if (objectScope.get(method.name(), true).isPresent()) {
                throw new AnalyzeException("Method is already defined in object scope: " + method.name());
            }

            List<Type> paramTypes = new ArrayList<>();
            for (var typeName : method.parameterTypes()) {
                if (typeName.isPresent()) {
                    if (!Environment.TYPES.containsKey(typeName.get())) {
                        throw new AnalyzeException("Unknown parameter type: " + typeName.get());
                    }
                    paramTypes.add(Environment.TYPES.get(typeName.get()));
                } else {
                    paramTypes.add(Type.ANY);
                }
            }

            Type returnType = method.returnType().isPresent()
                    ? Environment.TYPES.getOrDefault(method.returnType().get(), Type.ANY)
                    : Type.ANY;

            Type.Function functionType = new Type.Function(paramTypes, returnType);
            objectScope.define(method.name(), functionType);

            Scope methodScope = new Scope(objectScope);
            methodScope.define("this", objectType); // implicit "this" variable

            for (int i = 0; i < method.parameters().size(); i++) {
                methodScope.define(method.parameters().get(i), paramTypes.get(i));
            }

            methodScope.define("$RETURNS", returnType);

            var analyzer = new Analyzer(methodScope);
            List<Ir.Stmt> body = new ArrayList<>();
            for (Ast.Stmt stmt : method.body()) {
                body.add(analyzer.visit(stmt));
            }

            List<Ir.Stmt.Def.Parameter> irParams = new ArrayList<>();
            for (int i = 0; i < method.parameters().size(); i++) {
                irParams.add(new Ir.Stmt.Def.Parameter(method.parameters().get(i), paramTypes.get(i)));
            }

            irMethods.add(new Ir.Stmt.Def(method.name(), irParams, returnType, body));
        }

        return new Ir.Expr.ObjectExpr(ast.name(), irFields, irMethods, objectType);
    }

    public static void requireSubtype(Type type, Type other) throws AnalyzeException {
        if (type.equals(other) || other.equals(Type.ANY)) {
            return;
        }
        if (other.equals(Type.EQUATABLE)) { // checks if it is a valid subtype - comparable = subtype of equatable
            if (type.equals(Type.NIL)
                    || type.equals(Type.COMPARABLE)
                    || type.equals(Type.ITERABLE)
                    || type.equals(Type.BOOLEAN)
                    || type.equals(Type.INTEGER)
                    || type.equals(Type.DECIMAL)
                    || type.equals(Type.STRING)) {
                return;
            }
        }
        if (other.equals(Type.COMPARABLE)) {
            if (type.equals(Type.BOOLEAN)
                    || type.equals(Type.INTEGER)
                    || type.equals(Type.DECIMAL)
                    || type.equals(Type.STRING)) {
                return;
            }
        }
        throw new AnalyzeException("Expected " + type + " to be subtype of " + other + ".");
    }

}
