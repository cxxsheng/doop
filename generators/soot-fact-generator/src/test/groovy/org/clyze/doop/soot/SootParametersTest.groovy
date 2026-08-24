package org.clyze.doop.soot

import spock.lang.Specification

class SootParametersTest extends Specification {
    def "Application regex limits initially loaded input classes"() {
        given:
        SootParameters parameters = new SootParameters()
        parameters.initFromArgs([
            "--application-regex", "com.android.server.power.**",
            "--load-regex", "com.android.server.power.**",
            "-i", "services.jar",
            "-d", "out-dir"
        ] as String[])

        when:
        Set<String> selected = BasicJavaSupport_Soot.selectInitialApplicationClasses([
            "com.android.server.power.PowerManagerService",
            "com.android.server.power.PowerManagerService\$BinderService",
            "com.android.server.power.batterysaver.BatterySaverController",
            "com.android.server.am.ActivityManagerService"
        ], parameters)

        then:
        selected == [
            "com.android.server.power.PowerManagerService",
            "com.android.server.power.PowerManagerService\$BinderService",
            "com.android.server.power.batterysaver.BatterySaverController"
        ] as Set
    }

    def "load regex can be broader than application classification"() {
        given:
        SootParameters parameters = new SootParameters()
        parameters.initFromArgs([
            "--application-regex", "com.example.model.**",
            "--load-regex", "com.example.**",
            "-i", "services.jar",
            "-d", "out-dir"
        ] as String[])

        expect:
        parameters.isApplicationClass("com.example.runtime.Root") == false
        parameters.isInitiallyLoadedClass("com.example.runtime.Root") == true
    }

    def "Default application regex keeps all input classes"() {
        given:
        SootParameters parameters = new SootParameters()
        parameters.initFromArgs([
            "-i", "services.jar",
            "-d", "out-dir"
        ] as String[])

        expect:
        BasicJavaSupport_Soot.selectInitialApplicationClasses(
            ["a.A", "b.B"], parameters) == ["a.A", "b.B"] as Set
    }

    def "Application regex exclusions suppress eager loading"() {
        given:
        SootParameters parameters = new SootParameters()
        parameters.initFromArgs([
            "--application-regex", "com.example.**" + File.pathSeparator + "!com.example.generated.**",
            "-i", "application.jar",
            "-d", "out-dir"
        ] as String[])

        expect:
        BasicJavaSupport_Soot.selectInitialApplicationClasses([
            "com.example.Service",
            "com.example.generated.Proxy",
            "com.other.Type"
        ], parameters) == ["com.example.Service"] as Set
    }

    def "SootParameters parsing"() {
        given:
        String[] args = [
            "--application-regex", "XYZ",
            "--main", "Main",
            "--fact-gen-cores", "2",
            "--ignore-wrong-staticness",
            "--generate-jimple",
            "-i", "a.jar",
            "-i", "b.aar",
            "-ld", "d1.jar",
            "-ld", "d2.apk",
            "-l", "android.jar",
            "-l", "path/to/layoutlib.jar",
            "-l", "jce.jar",
            "--android-jars", "android.jar",
            "--ssa",
            "-d", "out-dir"
        ] as String[]
        SootParameters sootParameters = new SootParameters()

        when:
        sootParameters.initFromArgs(args)

        then:

        "Main" == sootParameters._main

        2 == sootParameters._cores.intValue()

        true == sootParameters._ignoreWrongStaticness

        true == sootParameters._generateJimple

        2 == sootParameters.inputs.size()
        "a.jar" == sootParameters.inputs.get(0)
        "b.aar" == sootParameters.inputs.get(1)

        2 == sootParameters.dependencies.size()
        "d1.jar" == sootParameters.dependencies.get(0)
        "d2.apk" == sootParameters.dependencies.get(1)

        3 == sootParameters.platformLibs.size()
        "android.jar" == sootParameters.platformLibs.get(0)
        "path/to/layoutlib.jar" == sootParameters.platformLibs.get(1)
        "jce.jar" == sootParameters.platformLibs.get(2)

        true == sootParameters._ssa

        true == sootParameters._android
        "android.jar" == sootParameters._androidJars

        "out-dir" == sootParameters.outputDir
    }
}
