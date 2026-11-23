pluginManagement {
    repositories {
        if (providers.environmentVariable("GRADLE_USE_ALIYUN_MIRRORS").orNull != "false") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.environmentVariable("GRADLE_USE_ALIYUN_MIRRORS").orNull != "false") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// 仅 Android 快速构建开关：-PandroidOnly 时跳过 :desktopApp，
// 与 shared 模块的 iOS/JVM 目标裁剪配套使用，缩短配置期。默认关闭，行为不变。
val androidOnly = providers.gradleProperty("androidOnly").isPresent

rootProject.name = "shiguangschedule"

include(":androidApp")
include(":shared")
if (!androidOnly) {
    include(":desktopApp")
}