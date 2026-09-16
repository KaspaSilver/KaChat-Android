package com.kachat.app.util

import com.kachat.app.BuildConfig

/**
 * The version the app shows people: "5.0 (1)" through the betas, plain "5.0" at release -
 * the same shape iOS's About row uses, so a report from either phone says which build it is.
 * versionCode stays Play's business (see app/build.gradle.kts).
 */
object AppVersion {
    val display: String
        get() = if (BuildConfig.KACHAT_IS_RELEASE) BuildConfig.VERSION_NAME
        else "${BuildConfig.VERSION_NAME} (${BuildConfig.KACHAT_BUILD_NUMBER})"
}
