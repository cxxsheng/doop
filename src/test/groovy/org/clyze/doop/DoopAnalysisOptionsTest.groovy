package org.clyze.doop

import org.clyze.doop.core.DoopAnalysisFamily
import spock.lang.Specification

class DoopAnalysisOptionsTest extends Specification {
    def "body scope options are exposed by the Doop family"() {
        given:
        Map options = new DoopAnalysisFamily().supportedOptionsAsMap()

        expect:
        options.BODY_REGEX.name == "body-regex"
        options.BODY_METHOD_REGEX.name == "body-method-regex"
        options.NO_FULL.name == "no-full"
        !options.NO_FULL.value
    }
}
