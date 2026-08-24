package org.clyze.doop.util.filter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.clyze.doop.util.PackageUtils;

public class GlobClassFilter implements ClassFilter {
    private final PatternSet included;
    private final PatternSet excluded;
    private final boolean hasIncludedPatterns;
    private final boolean hasExcludedPatterns;

    public GlobClassFilter(String glob) {
        String[] patterns = glob.split(File.pathSeparator);
        ArrayList<String> includedPatterns = new ArrayList<>(patterns.length);
        ArrayList<String> excludedPatterns = new ArrayList<>(patterns.length);

        for (String pattern : patterns) {
            // A leading ! denotes a subtractive pattern. Keep a bare ! as an
            // ordinary exact class name, matching the historical behavior.
            if (pattern.length() > 1 && pattern.charAt(0) == '!')
                excludedPatterns.add(pattern.substring(1));
            else
                includedPatterns.add(pattern);
        }

        this.included = new PatternSet(includedPatterns);
        this.excluded = new PatternSet(excludedPatterns);
        this.hasIncludedPatterns = !includedPatterns.isEmpty();
        this.hasExcludedPatterns = !excludedPatterns.isEmpty();
    }

    @Override
    public boolean matches(String className) {
        // With only exclusions, start from the complete universe. This makes
        // !pkg.** useful as a standalone exclusion policy while retaining the
        // old behavior for every expression that contains only positive terms.
        boolean includedMatch = hasIncludedPatterns ? included.matches(className) : hasExcludedPatterns;
        return includedMatch && !excluded.matches(className);
    }

    /** The optimized matcher for one positive or negative pattern set. */
    private static final class PatternSet {
        private final ArrayList<String> packages;
        private final ArrayList<String> prefixes;
        private final ArrayList<String> otherPatterns;
        private final boolean matchEverything;

        PatternSet(List<String> patterns) {
            ArrayList<String> packages0 = new ArrayList<>(patterns.size());
            ArrayList<String> prefixes0 = new ArrayList<>(patterns.size());
            ArrayList<String> otherPatterns0 = new ArrayList<>(patterns.size());
            boolean matchEverything0 = false;

            for (String pattern : patterns) {
                // Keep the historical positive matching semantics: .** is a
                // class-name prefix match and .* is a package-prefix match.
                if (pattern.endsWith(".**")) {
                    String prefix = pattern.substring(0, pattern.length() - 2);
                    prefixes0.add(prefix);
                } else if (pattern.endsWith(".*")) {
                    String pkg = pattern.substring(0, pattern.length() - 2);
                    packages0.add(pkg);
                } else if ("**".equals(pattern))
                    matchEverything0 = true;
                else
                    otherPatterns0.add(pattern);
            }

            // Try to eliminate big data structures (this matters for big
            // programs with long auto-generated regex patterns).
            this.matchEverything = matchEverything0;
            if (matchEverything0) {
                this.packages = null;
                this.prefixes = null;
                this.otherPatterns = null;
            } else {
                // Make repeated matching faster with null checks.
                packages0.trimToSize();
                this.packages = packages0.size() == 0 ? null : packages0;
                prefixes0.trimToSize();
                this.prefixes = prefixes0.size() == 0 ? null : prefixes0;
                otherPatterns0.trimToSize();
                this.otherPatterns = otherPatterns0.size() == 0 ? null : otherPatterns0;
            }
        }

        boolean matches(String className) {
            if (matchEverything)
                return true;

            if (prefixes != null)
                for (String prefix : prefixes)
                    if (className.startsWith(prefix))
                        return true;

            // Package name, lazily initialized at most once.
            String pkgName;

            if (otherPatterns != null)
                for (String pattern : otherPatterns) {
                    if (pattern.equals("*")) {
                        return PackageUtils.getPackageName(className).isEmpty();
                    } else if (className.equals(pattern))
                        return true;
                }

            if (packages != null) {
                pkgName = PackageUtils.getPackageName(className);
                for (String pkg : packages)
                    if (pkgName.startsWith(pkg))
                        return true;
            }
            return false;
        }
    }
}
