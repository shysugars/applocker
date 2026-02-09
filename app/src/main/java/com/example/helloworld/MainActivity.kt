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
    
    // 保存当前选中的包名列表
    private var selectedPackages = ArrayList<String>()
    // 标记是否正在通过代码修改开关状态，防止触发监听器循环
    private var isUpdatingSwitch = false

    // Activity Result 回调
    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val packages = result.data?.getStringArrayListExtra("selected_packages")
            if (!packages.isNullOrEmpty()) {
                selectedPackages = packages
                // 显示选中的包名
                binding.tvSelectedApps.text = "已选应用 (${packages.size}个):\n${packages.joinToString("\n")}"
                // 检查这些应用当前的状态，设置开关
                checkAppsSuspendedStatus()
            } else {
                selectedPackages.clear()
                binding.tvSelectedApps.text = "未选择应用"
                binding.switchDynamic.isChecked = false
            }
        }
    }

    // Shizuku 连接监听
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            checkShizukuReady()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            binding.switchDynamic.isEnabled = false
            binding.switchDynamic.text = "Shizuku 服务已断开"
        }
    }

    // 权限回调
    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 100 && grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "授权成功", Toast.LENGTH_SHORT).show()
                // 授权后立即执行刚才的操作
                executeSuspendAction(binding.switchDynamic.isChecked)
            } else if (requestCode == 100) {
                // 拒接后恢复开关状态
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

        // 初始 UI 状态
        binding.switchDynamic.isEnabled = false
        binding.switchDynamic.text = "等待 Shizuku..."

        // 注册监听
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        // 尝试初始化状态
        checkShizukuReady()

        // 开关逻辑
        binding.switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener

            if (selectedPackages.isEmpty()) {
                Toast.makeText(this, "请先选择应用", Toast.LENGTH_SHORT).show()
                // 恢复开关状态
                isUpdatingSwitch = true
                binding.switchDynamic.isChecked = !isChecked
                isUpdatingSwitch = false
                return@setOnCheckedChangeListener
            }

            // 检查权限并执行
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

    /**
     * 检查 Shizuku 是否连接
     */
    private fun checkShizukuReady() {
        if (Shizuku.pingBinder()) {
            binding.switchDynamic.isEnabled = true
            binding.switchDynamic.text = "暂停选中的应用"
        }
    }

    /**
     * 检查权限，如果没有则请求
     */
    private fun checkPermission(): Boolean {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return true
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // 用户之前拒绝过，应该解释一下（这里简单处理直接再次请求）
            Shizuku.requestPermission(100)
            return false
        } else {
            Shizuku.requestPermission(100)
            return false
        }
    }

    /**
     * 检查选中的应用是否处于暂停状态
     * 逻辑：如果所有选中的应用都暂停了，开关设为 ON；否则设为 OFF。
     */
    private fun checkAppsSuspendedStatus() {
        if (selectedPackages.isEmpty()) return

        var allSuspended = true
        val pm = packageManager

        for (pkg in selectedPackages) {
            try {
                // 使用 FLAG_SUSPENDED 标志位判断
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val isSuspended = (appInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
                if (!isSuspended) {
                    allSuspended = false
                    break
                }
            } catch (e: PackageManager.NameNotFoundException) {
                // 应用可能被卸载了
                continue
            }
        }

        // 更新 UI，但不触发监听器
        isUpdatingSwitch = true
        binding.switchDynamic.isChecked = allSuspended
        isUpdatingSwitch = false
    }

    /**
     * 执行 Shell 命令
     * @param suspend true=暂停, false=恢复
     */
    private fun executeSuspendAction(suspend: Boolean) {
        val actionWord = if (suspend) "暂停" else "恢复"
        val cmdKeyword = if (suspend) "suspend" else "unsuspend"
        
        // 构造命令：cmd package suspend com.a com.b ...
        // 这种方式比执行多次 pm suspend 效率高
        val commandBuilder = StringBuilder("cmd package $cmdKeyword")
        selectedPackages.forEach { pkg ->
            commandBuilder.append(" ").append(pkg)
        }

        Thread {
            try {
                // 执行命令
                val process = Shizuku.newProcess(arrayOf("sh", "-c", commandBuilder.toString()), null, null)
                val exitCode = process.waitFor()

                runOnUiThread {
                    if (exitCode == 0) {
                        Toast.makeText(this, "已$actionWord ${selectedPackages.size} 个应用", Toast.LENGTH_SHORT).show()
                    } else {
                        // 失败处理：读取错误输出
                        val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                        val errorMsg = errorReader.readText()
                        binding.tvSelectedApps.text = "执行失败 ($exitCode):\n$errorMsg"
                        
                        // 开关回弹
                        isUpdatingSwitch = true
                        binding.switchDynamic.isChecked = !suspend
                        isUpdatingSwitch = false
                    }
                    // 再次确认状态，确保 UI 和实际一致
                    checkAppsSuspendedStatus()
                }
            } catch (e: Exception) {
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