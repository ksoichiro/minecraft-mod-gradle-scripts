package com.github.ksoichiro.mcmod

import spock.lang.Specification
import spock.lang.Unroll

import java.util.regex.Pattern

class VersionUtilsTest extends Specification {

    @Unroll
    def "compareVersions(#a, #b) has sign #expected"() {
        expect:
        Integer.signum(VersionUtils.compareVersions(a, b)) == expected

        where:
        a            | b            || expected
        '1.21.1'     | '1.21.2'     || -1
        '1.21.2'     | '1.21.1'     || 1
        '1.21.1'     | '1.21.1'     || 0
        // Lexical-sort trap: 1.21.9 must come before 1.21.10
        '1.21.9'     | '1.21.10'    || -1
        '1.21.10'    | '1.21.9'     || 1
        '1.21.10'    | '1.21.11'    || -1
        // Different segment counts
        '1.21'       | '1.21.0'     || 0
        '1.21'       | '1.21.1'     || -1
        '1.21.1'     | '1.22'       || -1
        // Pre-release suffixes rank below the released version
        '1.21.2-beta' | '1.21.2'    || -1
        '1.21.2'      | '1.21.2-beta' || 1
        // alpha < beta < rc
        '1.0-alpha'  | '1.0-beta'   || -1
        '1.0-beta'   | '1.0-rc'     || -1
        '1.0-alpha'  | '1.0-rc'     || -1
        '1.0-beta'   | '1.0-beta'   || 0
        // Numbered suffixes compare numerically, not lexically
        '1.0-beta2'  | '1.0-beta10' || -1
        '1.0-beta.2' | '1.0-beta.10' || -1
    }

    def "sortJars orders by game version with the newest last"() {
        given:
        def pattern = ~/mymod-(\d+(?:\.\d+)+)\+(\d+(?:\.\d+)+)-([a-z]+)\.jar/
        def jars = [
                '1.21.1', '1.21.10', '1.21.11', '1.21.2', '1.21.8', '1.21.9'
        ].collect { new File("mymod-0.2.0+${it}-fabric.jar") }
        // Shuffle-ish: also throw in lexical order to prove it is re-sorted
        Collections.shuffle(jars, new Random(42))

        when:
        def sorted = VersionUtils.sortJars(jars, pattern as Pattern)

        then:
        sorted*.name == [
                'mymod-0.2.0+1.21.1-fabric.jar',
                'mymod-0.2.0+1.21.2-fabric.jar',
                'mymod-0.2.0+1.21.8-fabric.jar',
                'mymod-0.2.0+1.21.9-fabric.jar',
                'mymod-0.2.0+1.21.10-fabric.jar',
                'mymod-0.2.0+1.21.11-fabric.jar',
        ]
    }

    @Unroll
    def "releaseChannel(#modVersion) == #expected"() {
        expect:
        VersionUtils.releaseChannel(modVersion) == expected

        where:
        modVersion      || expected
        '0.2.0'         || 'release'
        '1.0.0'         || 'release'
        '0.2.0-alpha'   || 'alpha'
        '0.2.0-alpha.1' || 'alpha'
        '0.2.0-beta'    || 'beta'
        '0.2.0-beta.3'  || 'beta'
        '0.2.0-rc1'     || 'beta'
        '0.2.0-pre2'    || 'beta'
        '0.2.0-1'       || 'beta'
    }

    def "sortJars handles a pre-release suffix on the mod version"() {
        given:
        def pattern = ~/mymod-(\d+(?:\.\d+)+(?:-[0-9A-Za-z.-]+)?)\+(\d+(?:\.\d+)+)-([a-z]+)\.jar/
        def jars = [
                'mymod-0.2.0+1.21.1-fabric.jar',
                'mymod-0.2.0-rc1+1.21.1-fabric.jar',
                'mymod-0.2.0-beta+1.21.1-fabric.jar',
                'mymod-0.2.0-alpha+1.21.1-fabric.jar',
        ].collect { new File(it) }

        when:
        def sorted = VersionUtils.sortJars(jars, pattern as Pattern)

        then:
        sorted*.name == [
                'mymod-0.2.0-alpha+1.21.1-fabric.jar',
                'mymod-0.2.0-beta+1.21.1-fabric.jar',
                'mymod-0.2.0-rc1+1.21.1-fabric.jar',
                'mymod-0.2.0+1.21.1-fabric.jar',
        ]
    }

    def "sortJars breaks ties by mod version then loader"() {
        given:
        def pattern = ~/mymod-(\d+(?:\.\d+)+)\+(\d+(?:\.\d+)+)-([a-z]+)\.jar/
        def jars = [
                'mymod-0.2.0+1.21.1-neoforge.jar',
                'mymod-0.2.0+1.21.1-fabric.jar',
                'mymod-0.1.0+1.21.1-fabric.jar',
        ].collect { new File(it) }

        when:
        def sorted = VersionUtils.sortJars(jars, pattern as Pattern)

        then:
        sorted*.name == [
                'mymod-0.1.0+1.21.1-fabric.jar',
                'mymod-0.2.0+1.21.1-fabric.jar',
                'mymod-0.2.0+1.21.1-neoforge.jar',
        ]
    }
}
