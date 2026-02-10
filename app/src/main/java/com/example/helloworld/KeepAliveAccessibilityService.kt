package com.example.helloworld

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 这是一个空的无障碍服务。
 * 开启此服务后，Android 系统会将本应用视为用户正在使用的辅助功能，
 * 从而极大提高进程优先级，防止被系统杀后台。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理任何事件，留空即可
    }

    override fun onInterrupt() {
        // 服务被中断时的回调
    }
}
