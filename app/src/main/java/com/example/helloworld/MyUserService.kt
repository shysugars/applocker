// --- START OF FILE new/MyUserService.kt ---
package com.example.helloworld

import android.os.Bundle
import com.example.helloworld.IUserService
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

class MyUserService(bundle: Bundle) : IUserService.Stub() {

    // 这个构造函数是 Shizuku 要求的
    init {
        // 这里可以执行初始化逻辑
    }

    override fun destroy() {
        exit()
    }

    override fun exit() {
        exitProcess(0)
    }

    // 在 shell 权限进程中直接执行命令
    override fun runCommand(cmd: String): Int {
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            process.waitFor()
        } catch (e: Exception) {
            -1
        }
    }
}