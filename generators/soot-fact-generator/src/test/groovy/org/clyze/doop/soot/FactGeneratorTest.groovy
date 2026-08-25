package org.clyze.doop.soot

import org.clyze.doop.common.Database
import org.clyze.doop.common.Phantoms
import soot.SootClass
import soot.SootField
import soot.SootMethod
import soot.VoidType
import soot.util.Chain
import spock.lang.Specification

import java.lang.reflect.Modifier

class FactGeneratorTest extends Specification {
    def "class hierarchy and field failures do not suppress method signatures"() {
        given:
        File outDir = File.createTempDir("doop-fact-generator-test")
        Database db = new Database(outDir.absolutePath)
        SootParameters parameters = new SootParameters()
        parameters.initFromArgs(["-i", "input.jar", "-d", outDir.absolutePath] as String[])
        Phantoms phantoms = new Phantoms(false)
        FactWriter writer = new FactWriter(db, parameters, new Representation(), phantoms)
        SootDriver driver = new SootDriver(1, 1, writer, parameters, phantoms)
        BrokenResolutionClass broken = new BrokenResolutionClass()
        SootMethod entry = new SootMethod("entry", [], VoidType.v(), Modifier.PUBLIC | Modifier.ABSTRACT)
        broken.addMethod(entry)

        when:
        new FactGenerator(writer, [broken] as Set<SootClass>, driver, parameters, phantoms).run()
        db.flush()

        then:
        new File(outDir, "Method.facts").readLines().any { it.contains(entry.signature) }

        cleanup:
        db?.close()
        outDir?.deleteDir()
    }

    private static class BrokenResolutionClass extends SootClass {
        BrokenResolutionClass() {
            super("library.Broken", SootClass.BODIES)
        }

        @Override
        boolean hasSuperclass() {
            true
        }

        @Override
        SootClass getSuperclass() {
            throw new RuntimeException("HIERARCHY resolution unavailable")
        }

        @Override
        Chain<SootField> getFields() {
            throw new RuntimeException("HIERARCHY field resolution unavailable")
        }
    }
}
