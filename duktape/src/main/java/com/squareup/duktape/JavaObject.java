package com.squareup.duktape;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

public final class JavaObject implements DuktapeJavaObject {
    private final Object target;
    private final Duktape duktape;

    public JavaObject(Duktape duktape, Object target) {
        this.duktape = duktape;
        this.target = target;
    }

    @Override
    public Object getObject(Class clazz) {
        return target;
    }

    public static Method getGetterMethod(String key, Method[] methods) {
        // disabled, tbd.
        if (false && !key.isEmpty()) {
            // try getters, preferred over methods
            // foo -> getFoo()
            // foo -> foo()
            return Duktape.javaObjectGetter.memoize(() -> {
                String getterName = "get" + key.substring(0, 1).toUpperCase() + key.substring(1);
                for (Method method : methods) {
                    // name, no args, and a return type
                    if ((method.getName().equals(key) || method.getName().equals(getterName))
                            && method.getParameterTypes().length == 0
                            && method.getReturnType() != void.class && method.getReturnType() != Void.class)
                        return method;
                }
                return null;
            }, key, methods);
        }
        return null;
    }

    @Override
    public Object get(String key) {
        Object ret = getMap(key);
        if (ret != null)
            return ret;

        Class clazz = target.getClass();
        if (!Proxy.isProxyClass(clazz)) {
            // length is not a field of the array class. it's a language property.
            // Nor can arrays be cast to Array.
            if (clazz.isArray() && "length".equals(key))
                return Array.getLength(target);

            Field f = Duktape.javaObjectFields.memoize(() -> {
                // try to get fields
                for (Field field : clazz.getFields()) {
                    if (field.getName().equals(key))
                        return field;
                }
                return null;
            }, key, clazz.getFields());

            if (f != null) {
                try {
                    return f.get(target);
                } catch (IllegalAccessException e) {
                    throw new IllegalArgumentException(e);
                }
            }
        }

        Method g = getGetterMethod(key, clazz.getMethods());
        if (g != null) {
            try {
                return g.invoke(target);
            }
            catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        Boolean m = Duktape.javaObjectMethods.memoize(() -> {
            // try to get methods
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(key))
                    return true;
                DuktapeMethodName annotation = method.getAnnotation(DuktapeMethodName.class);
                if (annotation != null && annotation.name().equals(key))
                    return true;
            }
            return false;
        }, key, clazz.getMethods());

        if (m)
            return new JavaMethodObject(duktape, key);

        return null;
    }

    @Override
    public Object get(int index) {
        if (target.getClass().isArray())
            return Array.get(target, index);
        if (target instanceof List)
            return ((List)target).get(index);
        return null;
    }

    private Object getMap(Object key) {
        if (target instanceof Map)
            return ((Map)target).get(key);
        return null;
    }

    // duktape entry point
    @Override
    public Object get(Object key) {
        if (key instanceof String)
            return get((String)key);

        if (key instanceof Number) {
            Number number = (Number)key;
            if (number.doubleValue() == number.intValue())
                return get(number.intValue());
        }

        return getMap(key);
    }

    private void noSet() {
        throw new UnsupportedOperationException("can not set value on this JavaObject");
    }

    @Override
    public void set(int index, Object value) {
        if (target instanceof Array) {
            Array.set(target, index, value);
            return;
        }
        if (target instanceof List) {
            ((List)target).set(index, value);
            return;
        }

        noSet();
    }

    private void putMap(Object key, Object value) {
        if (target instanceof Map) {
            ((Map)target).put(key, value);
            return;
        }
        noSet();
    }

    @Override
    public void set(String key, Object value) {
        for (Field field: target.getClass().getFields()) {
            if (field.getName().equals(key)) {
                try {
                    field.set(target, duktape.coerceJavaScriptToJava(field.getType(), value));
                }
                catch (IllegalAccessException e) {
                    throw new IllegalArgumentException(e);
                }
                return;
            }
        }

        putMap(key, value);
    }

    // duktape entry point
    @Override
    public void set(Object key, Object value) {
        if (key instanceof Number) {
            Number number = (Number)key;
            if (number.doubleValue() == number.intValue()) {
                set(number.intValue(), value);
                return;
            }
        }

        if (key instanceof String) {
            set((String)key, value);
            return;
        }

        putMap(key, value);
    }

    @Override
    public Object call(Object... args) {
        throw new UnsupportedOperationException("can not call " + target);
    }

    @Override
    public Object callMethod(Object thiz, Object... args) {
        throw new UnsupportedOperationException("can not call " + target);
    }

    @Override
    public Object callProperty(Object property, Object... args) {
        if (property == null)
            throw new NullPointerException();
        property = get(property);
        if (property instanceof DuktapeObject)
            return ((DuktapeObject)property).callMethod(this, args);
        throw new UnsupportedOperationException("can not call " + target);
    }
}
