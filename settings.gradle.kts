import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = Properties()
val localPropertiesFile = file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
// An unset repository secret arrives as an empty string, not null, so the chain has to test for
// blank at every step or it never reaches its fallback.
fun String?.orBlank(): String? = this?.takeUnless { it.isBlank() }
val ghUsername = localProperties.getProperty("gpr.user").orBlank()
    ?: System.getenv("GH_PACKAGES_USER").orBlank()
    ?: System.getenv("GITHUB_ACTOR").orBlank()
val ghPassword = localProperties.getProperty("gpr.key").orBlank()
    ?: System.getenv("GH_PACKAGES_TOKEN").orBlank()
    ?: System.getenv("GITHUB_TOKEN").orBlank()

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages-Keyboard"
            url = uri("https://maven.pkg.github.com/lightphone/light-keyboard")
            credentials {
                username = ghUsername
                password = ghPassword
            }
        }
        // com.gios:light-common — shake-to-report and the crash offer shared by the Bright* apps.
        maven {
            name = "GitHubPackages-BrightCommon"
            url = uri("https://maven.pkg.github.com/gi-os/BrightCommon")
            credentials {
                username = ghUsername
                password = ghPassword
            }
        }
    }
}

rootProject.name = "light-rss"

includeBuild("plugin")
include(":lint-rules")
include(":sdk:shared")
include(":sdk:ui")
include(":sdk:client")
include(":sdk:server")
include(":sdk:emulator")
include(":tool")
include(":examples:ui-demo")
project(":examples:ui-demo").projectDir = file("examples/ui-demo")
include(":examples:weather")
project(":examples:weather").projectDir = file("examples/weather")
include(":examples:authenticator")
project(":examples:authenticator").projectDir = file("examples/authenticator")
