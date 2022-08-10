package com.kursor.chroniclesofww2.di

import com.kursor.chroniclesofww2.managers.UserManager
import org.koin.dsl.module

val appModule = module {
    single {
        UserManager(userRepository = get())
    }
}