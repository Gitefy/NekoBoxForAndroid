package io.nekohasekai.sagernet.utils

data class AppearanceBootstrap(
    val appTheme: Int,
    val nightTheme: Int,
    val appLanguage: String,
) {
    companion object {
        fun fromCommitted(appTheme: Int, nightTheme: Int, appLanguage: String) =
            AppearanceBootstrap(appTheme, nightTheme, appLanguage)
    }
}
