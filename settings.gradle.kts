pluginManagement {
    repositories {
        // 国内镜像优先，回退到官方仓库；用于本机离线/弱网构建。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        // 随包本地 AAR（sherpa-onnx 官方发布物）以 libs 目录形式接入
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "VoiceTodo"
include(":app")
