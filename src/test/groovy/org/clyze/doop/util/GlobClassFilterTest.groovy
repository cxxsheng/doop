package org.clyze.doop.util

import org.clyze.doop.util.filter.GlobClassFilter
import spock.lang.Specification

class GlobClassFilterTest extends Specification {
    def "positive patterns retain historical matching semantics"() {
        given:
        GlobClassFilter filter = new GlobClassFilter("a.*")

        expect:
        filter.matches("a.TopLevel")
        filter.matches("a.nested.Type")
        filter.matches("ab.Other")
        !filter.matches("b.Type")
    }

    def "negative patterns subtract from positive union"() {
        given:
        GlobClassFilter filter = new GlobClassFilter("a.**" + File.pathSeparator + "!a.internal.**")

        expect:
        filter.matches("a.TopLevel")
        filter.matches("a.external.Type")
        !filter.matches("a.internal.Type")
        !filter.matches("b.Type")
    }

    def "standalone negative pattern excludes from complete universe"() {
        given:
        GlobClassFilter filter = new GlobClassFilter("!a.**")

        expect:
        !filter.matches("a.TopLevel")
        !filter.matches("a.internal.Type")
        filter.matches("b.Type")
        filter.matches("DefaultPackageType")
    }

    def "all-pattern can be combined with exclusions"() {
        given:
        GlobClassFilter filter = new GlobClassFilter("**" + File.pathSeparator + "!a.**")

        expect:
        !filter.matches("a.Type")
        filter.matches("b.Type")
    }

    def "bare exclamation mark remains an exact class pattern"() {
        given:
        GlobClassFilter filter = new GlobClassFilter("!")

        expect:
        filter.matches("!")
        !filter.matches("a.Type")
    }
}
