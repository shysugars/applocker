package com.example.helloworld

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.helloworld.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 1. 定义 Shizuku 服务连接状态监听器
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        // 当 Shizuku 服务连接成功时回调
        Log.d("Shizuku", "Binder received")
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            // 如果已有权限且连接成功，可以在这里更新 UI 状态
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        // 当 Shizuku 服务断开时回调
        Log.d("Shizuku", "Binder dead")
        // 如果服务断开，关闭开关
        binding.switchDynamic.isChecked = false
    }

    // 2. 权限请求回调
    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 0) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
                    execWhoami()
                } else {
                    Toast.makeText(this, "Shizuku 权限被拒绝", Toast.LENGTH_SHORT).show()
                    binding.switchDynamic.isChecked = false
                }
            }
        }

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedApps = result.data?.getStringArrayListExtra("selected_apps")
            if (!selectedApps.isNullOrEmpty()) {
                binding.tvSelectedApps.text = "已选应用：\n${selectedApps.joinToString("\n")}"
            } else {
                binding.tvSelectedApps.text = "未选择应用"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. 注册监听器 (一定要在 onCreate 中注册)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        binding.switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkAndRunShizuku()
            } else {
                binding.tvSelectedApps.text = "功能已关闭"
            }
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(this, AppSelectionActivity::class.java)
            selectAppsLauncher.launch(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 4. 移除监听器，防止内存泄漏
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    private fun checkAndRunShizuku() {
        // 检查服务是否连接
        if (!Shizuku.pingBinder()) {
            // 尝试再次检查（有时可能有极短延迟）
            Toast.makeText(this, "Shizuku 未运行或未连接\n请确保 Shizuku App 已启动", Toast.LENGTH_SHORT).show()
            binding.switchDynamic.isChecked = false
            return
        }

        // 检查权限
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            execWhoami()
        } else {
            // 只有当 Shizuku 正在运行时，才能请求权限
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // 用户之前拒绝过，可以在这里提示用户
            }
            Shizuku.requestPermission(0)
        }
    }

    private fun execWhoami() {
        Thread {
            try {
                // === 反射调用 newProcess ===
                // 此时 Shizuku.newProcess 实际上是存在的，只是被隐藏了
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                val command = arrayOf("sh", "-c", "whoami")
                val process = newProcessMethod.invoke(null, command, null, null) as Process
                
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val result = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    result.append(line).append("\n")
                }

                val exitCode = process.waitFor()

                runOnUiThread {
                    if (exitCode == 0) {
                        binding.tvSelectedApps.text = "Shizuku 执行结果 (whoami):\n$result"
                    } else {
                        binding.tvSelectedApps.text = "执行失败，退出码: $exitCode"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.tvSelectedApps.text = "错误: ${e.message}\n请检查 Shizuku 状态"
                    binding.switchDynamic.isChecked = false
                }
            }
        }.start()
    }
}