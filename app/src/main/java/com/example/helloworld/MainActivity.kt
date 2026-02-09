package com.example.helloworld

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.helloworld.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku // 导入 Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Shizuku 权限请求回调监听器
    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 0 && grantResult == PackageManager.PERMISSION_GRANTED) {
                // 权限申请成功，立即执行
                execWhoami()
            } else {
                Toast.makeText(this, "Shizuku 权限被拒绝", Toast.LENGTH_SHORT).show()
                // 权限被拒，把开关拨回去
                binding.switchDynamic.isChecked = false
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

        // 注册 Shizuku 监听器
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        // 修改开关逻辑
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
        // 移除监听器防止内存泄漏
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    // 检查权限并运行
    private fun checkAndRunShizuku() {
        // 1. 检查 Shizuku 服务是否可用
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            binding.switchDynamic.isChecked = false
            return
        }

        // 2. 检查是否有权限
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            execWhoami()
        } else {
            // 3. 请求权限
            Shizuku.requestPermission(0)
        }
    }

    // 执行 Shell 命令
    private fun execWhoami() {
        // 开启子线程执行耗时操作（IO）
        Thread {
            try {
                // 核心代码：使用 Shizuku 创建进程
                // "sh", "-c", "whoami" 等同于在终端执行 whoami
                val process = Shizuku.newProcess(arrayOf("sh", "-c", "whoami"), null, null)
                
                // 读取输出流
                val result = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()

                runOnUiThread {
                    if (exitCode == 0) {
                        // 成功，result 通常是 shell 或者 root
                        binding.tvSelectedApps.text = "Shizuku 执行结果 (whoami):\n$result"
                        Toast.makeText(this@MainActivity, "执行成功", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.tvSelectedApps.text = "执行失败，退出码: $exitCode"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvSelectedApps.text = "错误: ${e.message}"
                    binding.switchDynamic.isChecked = false
                }
            }
        }.start()
    }
}