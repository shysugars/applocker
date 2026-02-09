package com.example.helloworld

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.helloworld.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 0 && grantResult == PackageManager.PERMISSION_GRANTED) {
                execWhoami()
            } else {
                Toast.makeText(this, "Shizuku 权限被拒绝", Toast.LENGTH_SHORT).show()
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
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    private fun checkAndRunShizuku() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            binding.switchDynamic.isChecked = false
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            execWhoami()
        } else {
            Shizuku.requestPermission(0)
        }
    }

    private fun execWhoami() {
        Thread {
            try {
                // === 修改开始：使用反射调用 newProcess ===
                // 由于 Shizuku.newProcess 在新版中对 Kotlin 隐藏或设为私有，这里用反射强行调用
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true
                
                // 执行命令: sh -c whoami
                val process = newProcessMethod.invoke(
                    null, 
                    arrayOf("sh", "-c", "whoami"), 
                    null, 
                    null
                ) as Process
                // === 修改结束 ===

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
                        Toast.makeText(this@MainActivity, "执行成功", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.tvSelectedApps.text = "执行失败，退出码: $exitCode"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.tvSelectedApps.text = "错误: ${e.message}\n请确认Shizuku服务正常"
                    binding.switchDynamic.isChecked = false
                }
            }
        }.start()
    }
}