package org.clyze.doop.util.filter;

public interface MethodFilter {
    boolean matches(String className, String methodName);
}
