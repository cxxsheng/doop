package org.clyze.doop.util

import org.clyze.doop.util.filter.GlobMethodFilter
import spock.lang.Specification

class GlobMethodFilterTest extends Specification {
    def "class and method globs are both enforced"() {
        given:
        def filter = new GlobMethodFilter(
                "com.example.**#*" + File.pathSeparator +
                "!com.example.generated.**#*" + File.pathSeparator +
                "!com.example.Service#dump*")

        expect:
        filter.matches("com.example.Service", "run")
        !filter.matches("com.example.Service", "dumpState")
        !filter.matches("com.example.generated.Proxy", "run")
        !filter.matches("other.Service", "run")
    }

    def "exclusion-only expression starts from all methods"() {
        given:
        def filter = new GlobMethodFilter("!java.**#*")

        expect:
        !filter.matches("java.lang.String", "valueOf")
        filter.matches("com.example.Service", "valueOf")
    }

    def "malformed term is rejected"() {
        when:
        new GlobMethodFilter("com.example.**")

        then:
        thrown(IllegalArgumentException)
    }
}
