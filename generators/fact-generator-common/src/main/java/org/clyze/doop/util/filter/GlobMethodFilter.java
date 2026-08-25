package org.clyze.doop.util.filter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A method filter expressed as path-separated {@code class-glob#method-glob}
 * terms. Class globs use {@link GlobClassFilter}; method names support {@code *}
 * as a substring wildcard. A leading {@code !} subtracts a term.
 */
public final class GlobMethodFilter implements MethodFilter {
    private final List<Term> included;
    private final List<Term> excluded;

    public GlobMethodFilter(String expression) {
        included = new ArrayList<>();
        excluded = new ArrayList<>();
        for (String raw : expression.split(Pattern.quote(File.pathSeparator))) {
            boolean negative = raw.length() > 1 && raw.charAt(0) == '!';
            String value = negative ? raw.substring(1) : raw;
            Term term = new Term(value);
            (negative ? excluded : included).add(term);
        }
        if (included.isEmpty() && excluded.isEmpty())
            throw new IllegalArgumentException("empty method filter");
    }

    @Override
    public boolean matches(String className, String methodName) {
        boolean selected = included.isEmpty() || matchesAny(included, className, methodName);
        return selected && !matchesAny(excluded, className, methodName);
    }

    private static boolean matchesAny(List<Term> terms, String className, String methodName) {
        for (Term term : terms)
            if (term.matches(className, methodName))
                return true;
        return false;
    }

    private static final class Term {
        private final GlobClassFilter owner;
        private final Pattern method;

        Term(String value) {
            int separator = value.lastIndexOf('#');
            if (separator <= 0 || separator == value.length() - 1)
                throw new IllegalArgumentException(
                        "method pattern must use class-glob#method-glob: " + value);
            owner = new GlobClassFilter(value.substring(0, separator));
            method = Pattern.compile(globToRegex(value.substring(separator + 1)));
        }

        boolean matches(String className, String methodName) {
            return owner.matches(className) && method.matcher(methodName).matches();
        }

        private static String globToRegex(String glob) {
            StringBuilder regex = new StringBuilder("^");
            int start = 0;
            for (int index = 0; index < glob.length(); index++) {
                if (glob.charAt(index) == '*') {
                    regex.append(Pattern.quote(glob.substring(start, index)));
                    regex.append(".*");
                    start = index + 1;
                }
            }
            regex.append(Pattern.quote(glob.substring(start)));
            return regex.append('$').toString();
        }
    }
}
