package com.kachat.app.util

/**
 * Process-wide switch for verbose per-request HTTP logging, mirrored from the persisted
 * "Verbose API Logging" setting (AppSettingsRepository.verboseApiLogging) by KaChatApplication.
 *
 * A plain @Volatile flag rather than a Flow because the consumer is an OkHttp interceptor
 * (AppModule.provideOkHttpClient) that runs on OkHttp's own threads for every request in the
 * 2-second sync loop; reading a field there is free, collecting a Flow is not. Default false,
 * same as the stored setting, so verbose logging is off from process start until the user's
 * saved value is read.
 */
object ApiLogging {
    @Volatile
    var verbose: Boolean = false
}
