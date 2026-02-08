package com.example.helloworld

import android.app.Application
import com.google.android.material.color.DynamicColors

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 这一行开启全局动态取色功能
        // 如果设备是 Android 12+，所有 Activity 将自动根据壁纸颜色调整主题
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}