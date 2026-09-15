package com.example.chameleon

import android.app.Application
import com.example.chameleon.di.AppContainer
import com.example.chameleon.di.DefaultAppContainer

class ChameleonApplication : Application() {

    /** 依赖容器：进程内唯一，ViewModel 工厂从它取依赖 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
