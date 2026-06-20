package com.github.ksoichiro.mcmod

import java.util.regex.Pattern

/**
 * Version-aware ordering for release JARs.
 *
 * Sorting JAR files by name lexically puts "1.21.10" before "1.21.2", so the
 * newest Minecraft version is not uploaded last. Because CurseForge marks the
 * most recently uploaded file as the Main File, the latest version must come
 * last. These helpers compare versions numerically (segment by segment) and
 * treat pre-release suffixes (alpha/beta/rc) as lower than the released version.
 */
class VersionUtils {

    /**
     * Compares two version strings using natural numeric ordering, with
     * semantic-versioning-style handling of pre-release suffixes:
     * "1.21.9" &lt; "1.21.10", "1.21.2-beta" &lt; "1.21.2", "1.0-alpha" &lt; "1.0-beta".
     */
    static int compareVersions(String a, String b) {
        def pa = parse(a)
        def pb = parse(b)

        def na = pa.numbers
        def nb = pb.numbers
        int len = Math.max(na.size(), nb.size())
        for (int i = 0; i < len; i++) {
            int va = i < na.size() ? na[i] : 0
            int vb = i < nb.size() ? nb[i] : 0
            if (va != vb) {
                return va <=> vb
            }
        }

        // Numeric parts are equal: a release outranks a pre-release (1.0 > 1.0-beta).
        if (pa.pre.isEmpty() && pb.pre.isEmpty()) {
            return 0
        }
        if (pa.pre.isEmpty()) {
            return 1
        }
        if (pb.pre.isEmpty()) {
            return -1
        }
        return comparePre(pa.pre, pb.pre)
    }

    /**
     * Returns the JARs ordered so the newest version is last. Ordering is by
     * Minecraft game version, then mod version, then loader, all numerically.
     * JARs whose names do not match {@code jarPattern} are ordered last by name.
     */
    static List<File> sortJars(List<File> jars, Pattern jarPattern) {
        return jars.sort(false) { File f1, File f2 ->
            compareJarNames(f1.name, f2.name, jarPattern)
        }
    }

    /**
     * Maps a mod version's pre-release suffix to a CurseForge/Modrinth release
     * channel: "alpha" for {@code -alpha*}, "beta" for any other pre-release
     * suffix ({@code -beta}, {@code -rc}, {@code -pre}, ...), and "release" when
     * there is no suffix.
     */
    static String releaseChannel(String modVersion) {
        def pre = parse(modVersion).pre
        if (pre.isEmpty()) {
            return 'release'
        }
        return pre.toLowerCase().startsWith('alpha') ? 'alpha' : 'beta'
    }

    private static int compareJarNames(String n1, String n2, Pattern jarPattern) {
        def m1 = jarPattern.matcher(n1)
        def m2 = jarPattern.matcher(n2)
        boolean ok1 = m1.matches()
        boolean ok2 = m2.matches()
        if (!ok1 || !ok2) {
            // Keep unmatched names deterministic and after matched ones.
            if (ok1 != ok2) {
                return ok1 ? -1 : 1
            }
            return n1 <=> n2
        }
        // group(1)=mod version, group(2)=game version, group(3)=loader.
        int c = compareVersions(m1.group(2), m2.group(2))
        if (c != 0) {
            return c
        }
        c = compareVersions(m1.group(1), m2.group(1))
        if (c != 0) {
            return c
        }
        return m1.group(3) <=> m2.group(3)
    }

    private static Map parse(String version) {
        def trimmed = (version ?: '').trim()
        def m = (trimmed =~ /^(\d+(?:\.\d+)*)/)
        def release = m ? m.group(1) : ''
        def rest = trimmed.substring(release.length())
        // Strip a single leading separator (-, +, _, .) before the suffix.
        def pre = rest.replaceFirst(/^[._+\-]/, '')
        def numbers = release ? release.split('\\.').collect { it as int } : []
        return [numbers: numbers, pre: pre]
    }

    private static int comparePre(String a, String b) {
        def ta = tokenize(a)
        def tb = tokenize(b)
        int len = Math.max(ta.size(), tb.size())
        for (int i = 0; i < len; i++) {
            // A shorter pre-release sorts first (1.0-beta < 1.0-beta.1).
            if (i >= ta.size()) {
                return -1
            }
            if (i >= tb.size()) {
                return 1
            }
            def x = ta[i]
            def y = tb[i]
            if (x instanceof Integer && y instanceof Integer) {
                if (x != y) {
                    return x <=> y
                }
            } else if (x instanceof Integer) {
                // Numeric identifiers rank lower than alphanumeric ones.
                return -1
            } else if (y instanceof Integer) {
                return 1
            } else {
                int c = (x as String) <=> (y as String)
                if (c != 0) {
                    return c
                }
            }
        }
        return 0
    }

    private static List tokenize(String s) {
        def tokens = []
        def m = (s =~ /(\d+|[A-Za-z]+)/)
        while (m.find()) {
            def g = m.group(1)
            tokens << (g ==~ /\d+/ ? (g as int) : g.toLowerCase())
        }
        return tokens
    }
}
