package com.techyshishy.beadmanager.di

import javax.inject.Qualifier

/**
 * Qualifier for the application-scoped [kotlinx.coroutines.CoroutineScope].
 * Prevents Hilt injection ambiguity if additional scopes are ever added.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope
