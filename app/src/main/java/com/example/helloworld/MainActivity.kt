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

    // 监听 Shizuku 服务连接状态
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d("Shizuku", "OnBinderReceived")
        runOnUiThread {
            binding.switchDynamic.isEnabled = true
            binding.switchDynamic.text = "功能开关 (Shizuku 已连接)"
            // 如果已经有权限，自动尝试执行一次或保持状态
            if (checkPermission(false)) {
                // 可以在这里预加载一些东西
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d("Shizuku", "OnBinderDead")
        runOnUiThread {
            binding.switchDynamic.isEnabled = false
            binding.switchDynamic.isChecked = false
            binding.switchDynamic.text = "功能开关 (Shizuku 未运行)"
            binding.tvSelectedApps.text = "Shizuku 服务已断开"
        }
    }

    // 监听权限请求结果
    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 100) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "权限获取成功", Toast.LENGTH_SHORT).show()
                    execWhoami()
                } else {
                    Toast.makeText(this, "用户拒绝了 Shizuku 权限", Toast.LENGTH_SHORT).show()
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

        // 初始化状态：先禁用开关，等待 Shizuku 连接
        binding.switchDynamic.isEnabled = false
        binding.switchDynamic.text = "正在连接 Shizuku..."

        // 注册监听器
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        // 检查当前状态（防止监听器注册晚了）
        checkShizukuStatus()

        binding.switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkPermission(true)) {
                    execWhoami()
                }
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
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    private fun checkShizukuStatus() {
        if (Shizuku.pingBinder()) {
            // 如果已经连接
            binding.switchDynamic.isEnabled = true
            binding.switchDynamic.text = "功能开关 (Shizuku 已连接)"
        } else {
            // 未连接，提示用户
            binding.tvSelectedApps.text = "等待 Shizuku 服务...\n如果长时间无反应，请确保 Shizuku App 正在运行。"
        }
    }

    private fun checkPermission(requestIfNotGranted: Boolean): Boolean {
        if (!Shizuku.pingBinder()) {
            return false
        }
        
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return true
            } else if (requestIfNotGranted) {
                Shizuku.requestPermission(100)
                return false
            }
        } catch (e: Exception) {
            // 处理 API < 23 的罕见情况
            e.printStackTrace()
        }
        return false
    }

    private fun execWhoami() {
        Thread {
            try {
                // 使用反射调用 Shizuku.newProcess (绕过 @hide 限制)
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
                        binding.tvSelectedApps.text = "Shizuku 执行成功 (Root/ADB):\n$result"
                    } else {
                        binding.tvSelectedApps.text = "执行失败，退出码: $exitCode"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.tvSelectedApps.text = "执行错误:\n${e.message}\n请检查 Shizuku 是否正常授权"
                    binding.switchDynamic.isChecked = false
                }
            }
        }.start()
    }
}