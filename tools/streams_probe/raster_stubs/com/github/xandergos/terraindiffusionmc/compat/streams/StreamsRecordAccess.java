package com.github.xandergos.terraindiffusionmc.compat.streams;
import java.lang.reflect.RecordComponent;
import java.util.Map;
public final class StreamsRecordAccess {
    private StreamsRecordAccess() { }
    public static Object read(Object value, String key) {
        try { return value.getClass().getMethod(key).invoke(value); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    public static double number(Object value, String key) { return ((Number) read(value, key)).doubleValue(); }
    public static Object copy(Object value, Map<String, Object> replacements) {
        try {
            RecordComponent[] components = value.getClass().getRecordComponents();
            Class<?>[] types = new Class<?>[components.length]; Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = replacements.containsKey(components[i].getName()) ? replacements.get(components[i].getName()) : components[i].getAccessor().invoke(value);
            }
            return value.getClass().getConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
