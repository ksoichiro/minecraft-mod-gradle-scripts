package com.github.ksoichiro.mcmod

class ChangelogUtils {
    static String readChangelog(File projectRootDir, String modVersion) {
        def versionFile = new File(projectRootDir, "changelog-${modVersion}.md")
        if (versionFile.exists()) {
            return versionFile.text
        }

        def changelogFile = new File(projectRootDir, 'CHANGELOG.md')
        if (changelogFile.exists()) {
            def lines = changelogFile.readLines()
            def found = false
            def result = []
            for (line in lines) {
                if (line.startsWith('## [')) {
                    if (found) break
                    if (line.contains("## [${modVersion}]")) {
                        found = true
                        continue
                    }
                }
                if (found) result.add(line)
            }
            return result.join('\n').trim()
        }

        return ''
    }
}

