package com.github.ksoichiro.mcmod

import org.gradle.api.GradleException
import spock.lang.Specification
import spock.lang.Unroll

class NbtConversionTasksTest extends Specification {

    @Unroll
    def "getDataVersion maps #mcVersion to #expected"() {
        expect:
        NbtConversionTasks.getDataVersion(mcVersion) == expected

        where:
        mcVersion | expected
        '1.16.5'  | 2586
        '1.17.1'  | 2730
        '1.18.2'  | 2975
        '1.19.2'  | 3120
        '1.20.1'  | 3465
        '1.21.1'  | 3955
    }

    def "getDataVersion rejects an unmapped version"() {
        when:
        NbtConversionTasks.getDataVersion('1.99.9')

        then:
        thrown(GradleException)
    }

    @Unroll
    def "a 1.21 source converts to the legacy item format for target #targetVersion"() {
        given:
        def ext = new McmodExtension.NbtConversionExtension(
                sourceVersion: '1.21.1', targetVersion: targetVersion)

        expect:
        NbtConversionTasks.resolveConverter(ext) instanceof V1_21ToV1_20NbtConverter

        where:
        // Every target below 1.20.5, where items moved to data components.
        targetVersion << ['1.16.5', '1.17.1', '1.18.2', '1.19.2', '1.20.1', '1.20.4']
    }

    @Unroll
    def "a 1.21 source only restamps the DataVersion for target #targetVersion"() {
        given:
        def ext = new McmodExtension.NbtConversionExtension(
                sourceVersion: '1.21.1', targetVersion: targetVersion)

        expect:
        NbtConversionTasks.resolveConverter(ext) instanceof DefaultNbtConverter

        where:
        // 1.20.5 and later already use data components, so no item rewrite is needed.
        targetVersion << ['1.20.5', '1.20.6', '1.21']
    }

    def "an explicit converterClass wins over the version pair"() {
        given:
        def ext = new McmodExtension.NbtConversionExtension(
                sourceVersion: '1.21.1', targetVersion: '1.20.1',
                converterClass: DefaultNbtConverter)

        expect:
        NbtConversionTasks.resolveConverter(ext) instanceof DefaultNbtConverter
    }
}
