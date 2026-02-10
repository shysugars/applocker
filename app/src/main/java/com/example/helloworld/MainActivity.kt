package com.example.helloworld

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.helloworld.databinding.ActivityMainBinding
import com.example.helloworld.service.KeepAliveService // 确保引用了你创建的服务
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private var selectedPackages = ArrayList<String>()
    private var isUpdatingSwitch = false

    // 选择应用的 Activity 回调
    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val packages = result.data?.getStringArrayListExtra("selected_packages")
            if (packages != null) {
                selectedPackages = packages
                saveSelectedPackages()
                
                binding.tvSelectedApps.text = if (packages.isNotEmpty()) {
                    "已选应用 (${packages.size}个):\n${packages.joinToString("\n")}"
                } else {
                    "未选择应用"
                }
                checkAppsSuspendedStatus()
            }
        }
    }

    // Shizuku 连接监听
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { checkShizukuReady() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            binding.switchDynamic.isEnabled = false
            binding.switchDynamic.text = "Shizuku 服务已断开"
        }
    }

    // Shizuku 权限结果监听
    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 100 && grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "授权成功", Toast.LENGTH_SHORT).show()
                // 授权后执行暂停操作（授权时肯定是开启状态）
                executeSuspendAction(true)
            } else if (requestCode == 100) {
                revertSwitchState(false)
                Toast.makeText(this, "需要 Shizuku 权限", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 初始化 SharedPreferences 并加载数据
        sharedPreferences = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        loadSelectedPackages()

        // 2. 初始化生物识别
        setupBiometric()

        // 3. UI 初始化
        binding.switchDynamic.isEnabled = false
        binding.switchDynamic.text = "等待 Shizuku..."

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        checkShizukuReady()
        
        // 4. 【保活策略】检查并忽略电池优化
        checkAndRequestBatteryOptimization()

        // 5. 开关逻辑
        binding.switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener

            if (selectedPackages.isEmpty()) {
                Toast.makeText(this, "请先选择应用", Toast.LENGTH_SHORT).show()
                revertSwitchState(!isChecked)
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                // --- 开启开关 (暂停应用) ---
                if (checkPermission()) {
                    executeSuspendAction(true)
                }
            } else {
                // --- 关闭开关 (恢复应用) ---
                authenticateAndUnsuspend()
            }
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(this, AppSelectionActivity::class.java)
            selectAppsLauncher.launch(intent)
        }
        
        updateSelectButtonState()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    // --- 数据持久化 ---

    private fun saveSelectedPackages() {
        val editor = sharedPreferences.edit()
        editor.putStringSet("saved_packages", selectedPackages.toHashSet())
        editor.apply()
    }

    private fun loadSelectedPackages() {
        val savedSet = sharedPreferences.getStringSet("saved_packages", null)
        if (savedSet != null) {
            selectedPackages = ArrayList(savedSet)
            binding.tvSelectedApps.text = "已选应用 (${selectedPackages.size}个):\n${selectedPackages.joinToString("\n")}"
        } else {
            binding.tvSelectedApps.text = "未选择应用"
        }
    }

    // --- 【核心】保活与电池优化逻辑 ---

    /**
     * 检查是否在电池优化白名单中，如果没有则请求
     * 这是实现“无感”运行的关键，防止系统杀后台
     */
    private fun checkAndRequestBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName
        
        // Android 6.0 (API 23) 以上才需要此操作
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "无法打开电池优化设置，请手动开启", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 启动前台保活服务
     * 在应用被暂停（开关开启）期间运行，防止本应用被杀导致 Shizuku 链接断开
     */
    private fun startKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * 停止前台保活服务
     * 当应用恢复（开关关闭）后，不再需要保活，释放资源
     */
    private fun stopKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java)
        stopService(intent)
    }

    // --- 生物识别逻辑 ---

    private fun setupBiometric() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (checkPermission()) {
                        executeSuspendAction(false)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "验证错误: $errString", Toast.LENGTH_SHORT).show()
                    revertSwitchState(true)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "验证失败", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("身份验证")
            .setSubtitle("验证指纹以恢复应用")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
    }

    private fun authenticateAndUnsuspend() {
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "设备不支持生物识别，直接执行", Toast.LENGTH_SHORT).show()
            if (checkPermission()) executeSuspendAction(false)
            return
        }
        biometricPrompt.authenticate(promptInfo)
    }

    // --- Shizuku 与 命令执行逻辑 ---

    private fun checkShizukuReady() {
        if (Shizuku.pingBinder()) {
            binding.switchDynamic.isEnabled = true
            binding.switchDynamic.text = "暂停选中的应用"
            checkAppsSuspendedStatus()
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
            updateSwitchSilent(false)
            updateSelectButtonState()
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

        // 如果状态是暂停（开启），则启动保活服务以防万一
        if (allSuspended) {
            startKeepAliveService()
        } else {
            // 如果没暂停，理论上不需要保活
            stopKeepAliveService()
        }

        updateSwitchSilent(allSuspended)
        updateSelectButtonState()
    }

    private fun updateSelectButtonState() {
        val isSuspended = binding.switchDynamic.isChecked
        binding.btnSelectApps.isEnabled = !isSuspended
        binding.btnSelectApps.text = if (isSuspended) "需恢复应用后修改列表" else "选择应用"
    }

    private fun executeSuspendAction(suspend: Boolean) {
        val cmdKeyword = if (suspend) "suspend" else "unsuspend"
        val commandBuilder = StringBuilder("cmd package $cmdKeyword")
        selectedPackages.forEach { pkg -> commandBuilder.append(" ").append(pkg) }
        val finalCommand = commandBuilder.toString()

        Thread {
            try {
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true
                val commandArray = arrayOf("sh", "-c", finalCommand)
                val process = newProcessMethod.invoke(null, commandArray, null, null) as Process
                
                val exitCode = process.waitFor()

                runOnUiThread {
                    if (exitCode == 0) {
                        Toast.makeText(this, if (suspend) "已暂停" else "已恢复", Toast.LENGTH_SHORT).show()
                        
                        // 【保活控制关键点】
                        // 暂停应用成功 -> 启动保活服务（防止自己被杀导致无法自动恢复或Shizuku断连）
                        // 恢复应用成功 -> 停止保活服务（节省电量）
                        if (suspend) {
                            startKeepAliveService()
                        } else {
                            stopKeepAliveService()
                        }
                    } else {
                        val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                        binding.tvSelectedApps.text = "执行失败: ${errorReader.readText()}"
                        revertSwitchState(!suspend)
                    }
                    checkAppsSuspendedStatus()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.tvSelectedApps.text = "错误: ${e.message}"
                    revertSwitchState(!suspend)
                }
            }
        }.start()
    }

    private fun updateSwitchSilent(checked: Boolean) {
        if (binding.switchDynamic.isChecked != checked) {
            isUpdatingSwitch = true
            binding.switchDynamic.isChecked = checked
            isUpdatingSwitch = false
        }
    }

    private fun revertSwitchState(targetState: Boolean) {
        isUpdatingSwitch = true
        binding.switchDynamic.isChecked = targetState
        isUpdatingSwitch = false
    }
}