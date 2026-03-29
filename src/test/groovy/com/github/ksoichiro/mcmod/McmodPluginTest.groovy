package com.github.ksoichiro.mcmod

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class McmodPluginTest extends Specification {

    def "plugin applies successfully and creates mcmod extension"() {
        given:
        Project project = ProjectBuilder.builder().build()

        when:
        project.plugins.apply('com.github.ksoichiro.mcmod')

        then:
        project.extensions.findByName('mcmod') instanceof McmodExtension
    }

    def "multiVersion tasks are registered when enabled"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.ext.set('supported_mc_versions', '1.20.1,1.21.1')

        when:
        project.plugins.apply('com.github.ksoichiro.mcmod')
        project.evaluate()

        then:
        project.tasks.findByName('cleanAll') != null
        project.tasks.findByName('buildAll') != null
        project.tasks.findByName('testAll') != null
        project.tasks.findByName('gameTestAll') != null
        project.tasks.findByName('checkAll') != null
        project.tasks.findByName('release') != null
        project.tasks.findByName('collectJars') != null
    }

    def "multiVersion tasks are NOT registered when disabled"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.ext.set('supported_mc_versions', '1.20.1,1.21.1')

        when:
        project.plugins.apply('com.github.ksoichiro.mcmod')
        project.extensions.getByType(McmodExtension).multiVersion.enabled = false
        project.evaluate()

        then:
        project.tasks.findByName('cleanAll') == null
        project.tasks.findByName('buildAll') == null
    }

    def "resourceValidation tasks are registered when enabled"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.ext.set('supported_mc_versions', '1.20.1')
        project.ext.set('archives_name', 'testmod')

        when:
        project.plugins.apply('com.github.ksoichiro.mcmod')
        project.evaluate()

        then:
        project.tasks.findByName('validateResources') != null
        project.tasks.findByName('validateTranslations') != null
    }

    def "resourceValidation tasks are NOT registered when disabled"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.ext.set('supported_mc_versions', '1.20.1')
        project.ext.set('archives_name', 'testmod')

        when:
        project.plugins.apply('com.github.ksoichiro.mcmod')
        project.extensions.getByType(McmodExtension).resourceValidation.enabled = false
        project.evaluate()

        then:
        project.tasks.findByName('validateResources') == null
        project.tasks.findByName('validateTranslations') == null
    }

    def "release tasks are NOT registered when disabled (default)"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.ext.set('supported_mc_versions', '1.20.1')

        when:
        project.plugins.apply('com.github.ksoichiro.mcmod')
        project.evaluate()

        then:
        project.tasks.findByName('releaseModrinth') == null
        project.tasks.findByName('releaseCurseForge') == null
    }

    def "release tasks are registered when enabled"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.ext.set('supported_mc_versions', '1.20.1')
        project.ext.set('archives_name', 'testmod')
        project.ext.set('mod_version', '1.0.0')

        when:
        project.plugins.apply('com.github.ksoichiro.mcmod')
        def ext = project.extensions.getByType(McmodExtension)
        ext.releaseModrinth.enabled = true
        ext.releaseModrinth.projectId = 'test123'
        ext.releaseCurseForge.enabled = true
        ext.releaseCurseForge.projectId = '999'
        project.evaluate()

        then:
        project.tasks.findByName('releaseModrinth') != null
        project.tasks.findByName('releaseCurseForge') != null
    }

    def "extension values fall back to gradle.properties"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.ext.set('supported_mc_versions', '1.20.1,1.21.1')
        project.ext.set('archives_name', 'testmod')
        project.ext.set('mod_version', '2.0.0')
        project.ext.set('hotfix_mc_versions', '1.21.3=1.21.2')
        project.ext.set('fabric_only_mc_versions', '1.20.1')
        project.ext.set('release_project_name', 'Test Mod')

        when:
        project.plugins.apply('com.github.ksoichiro.mcmod')
        project.evaluate()
        def ext = project.extensions.getByType(McmodExtension)

        then:
        ext.supportedVersions == ['1.20.1', '1.21.1']
        ext.archivesName == 'testmod'
        ext.modVersion == '2.0.0'
        ext.hotfixVersions == ['1.21.3': '1.21.2']
        ext.fabricOnlyVersions == ['1.20.1']
        ext.projectDisplayName == 'Test Mod'
    }

    def "per-version build and clean tasks are registered"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.ext.set('supported_mc_versions', '1.20.1,1.21.1')

        when:
        project.plugins.apply('com.github.ksoichiro.mcmod')
        project.evaluate()

        then:
        project.tasks.findByName('clean1_20_1') != null
        project.tasks.findByName('clean1_21_1') != null
        project.tasks.findByName('build1_20_1') != null
        project.tasks.findByName('build1_21_1') != null
    }

    def "per-version runClient tasks are registered"() {
        given:
        Project project = ProjectBuilder.builder().build()
        project.ext.set('supported_mc_versions', '1.20.1,1.21.1')
        project.ext.set('fabric_only_mc_versions', '1.20.1')

        when:
        project.plugins.apply('com.github.ksoichiro.mcmod')
        project.evaluate()

        then:
        project.tasks.findByName('runClientFabric1_20_1') != null
        project.tasks.findByName('runClientNeoForge1_20_1') == null
        project.tasks.findByName('runClientFabric1_21_1') != null
        project.tasks.findByName('runClientNeoForge1_21_1') != null
    }
}
