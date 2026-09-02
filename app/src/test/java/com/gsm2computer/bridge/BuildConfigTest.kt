package com.gsm2computer.bridge

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the release build configuration against silent regressions.
 *
 * The gateway ships as a Magisk system priv-app. An unsigned release APK is
 * rejected by `pm install` with NO_CERTIFICATES and, worse, wipes the existing
 * package — so the priv-app silently disappears after a reboot. This reads the
 * app module's Gradle script and fails the build if the release signing config
 * is removed or detached from the release build type.
 *
 * The Gradle script lives next to the test's working directory (app module
 * root), so it is read directly rather than copied to the test classpath.
 */
class BuildConfigTest {

    private val gradleScript: String =
        java.io.File("build.gradle.kts").readText()

    @Test
    fun releaseSigningConfigIsDeclared() {
        assertTrue(
            "signingConfigs block must declare a 'release' entry; an unsigned " +
                "release APK is rejected as a priv-app and wipes the package",
            Regex("""signingConfigs\s*\{[^}]*create\(\s*"release"\s*\)""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(gradleScript),
        )
    }

    @Test
    fun releaseBuildTypeUsesTheSigningConfig() {
        assertTrue(
            "release build type must wire signingConfig = signingConfigs.getByName(\"release\"); " +
                "without it the release APK ships unsigned",
            gradleScript.contains("signingConfig = signingConfigs.getByName(\"release\")"),
        )
    }

    @Test
    fun defaultHubControlUrlPointsAtTailscaleHub() {
        assertTrue(
            "DEFAULT_HUB_CONTROL_URL must point at the Tailscale hub",
            gradleScript.contains("http://100.101.181.110:8787"),
        )
    }
}
