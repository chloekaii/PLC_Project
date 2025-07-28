package plc.project.analyzer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * IMPORTANT: DO NOT CHANGE! This file is part of our project's API and should
 * not be modified by your solution.
 */
public final class Scope {

    private final Scope parent;
    private final Map<String, Type> variables = new LinkedHashMap<>();

    public Scope(Scope parent) {
        this.parent = parent;
    }

    public void define(String name, Type type) {
        if (!variables.containsKey(name)) {
            variables.put(name, type);
        } else {
            throw new IllegalStateException("Variable is already defined.");
        }
    }

    public Optional<Type> get(String name, boolean current) {
        if (variables.containsKey(name)) {
            return Optional.of(variables.get(name));
        } else if (parent != null && !current) {
            return parent.get(name, false);
        } else {
            return Optional.empty();
        }
    }

//    No set - this operation isn't meaningful in the Analyzer!
//    public void set(String name, Type object) {
//        if (variables.containsKey(name)) {
//            variables.put(name, object);
//        } else if (parent != null) {
//            parent.set(name, object);
//        } else {
//            throw new IllegalStateException("Variable is not defined.");
//        }
//    }

    public Map<String, Type> collect(boolean current) {
        if (current || parent == null) {
            return new LinkedHashMap<>(variables);
        } else {
            var map = parent.collect(false);
            map.putAll(variables);
            return map;
        }
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Scope other &&
            Objects.equals(parent, other.parent) &&
            variables.equals(other.variables);
    }

    @Override
    public String toString() {
        return "Scope[parent=" + parent + ", variables=" + variables + "]";
    }

}
