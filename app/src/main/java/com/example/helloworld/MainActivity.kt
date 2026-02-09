package com.example.helloworld

import android.content.Intent
import android.content.pm.ApplicationInfo
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
    private var selectedPackages = ArrayList<String>()
    private var isUpdatingSwitch = false

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val packages = result.data?.getStringArrayListExtra("selected_packages")
            if (!packages.isNullOrEmpty()) {
                selectedPackages = packages
                binding.tvSelectedApps.text = "已选应用 (${packages.size}个):\n${packages.joinToString("\n")}"
                checkAppsSuspendedStatus()
            } else {
                selectedPackages.clear()
                binding.tvSelectedApps.text = "未选择应用"
                isUpdatingSwitch = true
                binding.switchDynamic.isChecked = false
                isUpdatingSwitch = false
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { checkShizukuReady() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            binding.switchDynamic.isEnabled = false
            binding.switchDynamic.text = "Shizuku 服务已断开"
        }
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 100 && grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "授权成功", Toast.LENGTH_SHORT).show()
                executeSuspendAction(binding.switchDynamic.isChecked)
            } else if (requestCode == 100) {
                isUpdatingSwitch = true
                binding.switchDynamic.isChecked = !binding.switchDynamic.isChecked
                isUpdatingSwitch = false
                Toast.makeText(this, "需要 Shizuku 权限", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchDynamic.isEnabled = false
        binding.switchDynamic.text = "等待 Shizuku..."

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        checkShizukuReady()

        binding.switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener

            if (selectedPackages.isEmpty()) {
                Toast.makeText(this, "请先选择应用", Toast.LENGTH_SHORT).show()
                isUpdatingSwitch = true
                binding.switchDynamic.isChecked = !isChecked
                isUpdatingSwitch = false
                return@setOnCheckedChangeListener
            }

            if (checkPermission()) {
                executeSuspendAction(isChecked)
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

    private fun checkShizukuReady() {
        if (Shizuku.pingBinder()) {
            binding.switchDynamic.isEnabled = true
            binding.switchDynamic.text = "暂停选中的应用"
        }
    }

    private fun checkPermission(): Boolean {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return true
        } else {
            Shizuku.requestPermission(100)
            return false
        }
    }

    private fun checkAppsSuspendedStatus() {
        if (selectedPackages.isEmpty()) {
            isUpdatingSwitch = true
            binding.switchDynamic.isChecked = false
            isUpdatingSwitch = false
            return
        }

        var allSuspended = true
        val pm = packageManager
        for (pkg in selectedPackages) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                if ((appInfo.flags and ApplicationInfo.FLAG_SUSPENDED) == 0) {
                    allSuspended = false
                    break
                }
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
        }

        isUpdatingSwitch = true
        binding.switchDynamic.isChecked = allSuspended
        isUpdatingSwitch = false
    }

    /**
     * 执行 Shell 命令 (已修复为使用反射)
     * @param suspend true=暂停, false=恢复
     */
    private fun executeSuspendAction(suspend: Boolean) {
        val actionWord = if (suspend) "暂停" else "恢复"
        val cmdKeyword = if (suspend) "suspend" else "unsuspend"

        val commandBuilder = StringBuilder("cmd package $cmdKeyword")
        selectedPackages.forEach { pkg ->
            commandBuilder.append(" ").append(pkg)
        }
        val finalCommand = commandBuilder.toString()
        Log.d("Shizuku", "Executing: $finalCommand")

        Thread {
            try {
                // --- 关键修复：使用反射调用 newProcess ---
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true
                val commandArray = arrayOf("sh", "-c", finalCommand)
                val process = newProcessMethod.invoke(null, commandArray, null, null) as Process
                // --- 修复结束 ---
                
                val exitCode = process.waitFor()

                runOnUiThread {
                    if (exitCode == 0) {
                        Toast.makeText(this, "已$actionWord ${selectedPackages.size} 个应用", Toast.LENGTH_SHORT).show()
                    } else {
                        val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                        val errorMsg = errorReader.readText()
                        binding.tvSelectedApps.text = "执行失败 ($exitCode):\n$errorMsg"
                        isUpdatingSwitch = true
                        binding.switchDynamic.isChecked = !suspend
                        isUpdatingSwitch = false
                    }
                    checkAppsSuspendedStatus()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.tvSelectedApps.text = "发生错误:\n${e.message}"
                    isUpdatingSwitch = true
                    binding.switchDynamic.isChecked = !suspend
                    isUpdatingSwitch = false
                }
            }
        }.start()
    }
}