package com.techyshishy.beadmanager.di

import javax.inject.Qualifier

/**
 * Qualifier for the app-level DataStore<Preferences> instance.
 * Prevents Hilt injection ambiguity if a second DataStore is ever added.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppDataStore
