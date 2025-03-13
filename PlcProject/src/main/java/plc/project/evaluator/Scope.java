package plc.project.evaluator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class Scope {

    private final Scope parent;
    private final Map<String, RuntimeValue> variables = new LinkedHashMap<>();

    public Scope(Scope parent) {
        this.parent = parent;
    }

    public void define(String name, RuntimeValue object) {
        if (!variables.containsKey(name)) {
            variables.put(name, object);
        } else {
            throw new IllegalStateException("Variable is already defined.");
        }
    }

    public Optional<RuntimeValue> get(String name, boolean current) {
        if (variables.containsKey(name)) {
            return Optional.of(variables.get(name));
        } else if (parent != null && !current) {
            return parent.get(name, false);
        } else {
            return Optional.empty();
        }
    }

    public void set(String name, RuntimeValue object) {
        if (variables.containsKey(name)) {
            variables.put(name, object);
        } else if (parent != null) {
            parent.set(name, object);
        } else {
            throw new IllegalStateException("Variable is not defined.");
        }
    }

    public Map<String, RuntimeValue> collect(boolean current) {
        if (current || parent == null) {
            return new LinkedHashMap<>(variables);
        } else {
            var map = parent.collect(false);
            map.putAll(variables);
            return map;
        }
    }

}
