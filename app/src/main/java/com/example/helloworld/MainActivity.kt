package com.example.helloworld

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.helloworld.databinding.ActivityMainBinding
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

        // 4. 开关逻辑
        binding.switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener

            if (selectedPackages.isEmpty()) {
                Toast.makeText(this, "请先选择应用", Toast.LENGTH_SHORT).show()
                revertSwitchState(!isChecked)
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                if (checkPermission()) {
                    executeSuspendAction(true)
                }
            } else {
                authenticateAndUnsuspend()
            }
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(this, AppSelectionActivity::class.java)
            selectAppsLauncher.launch(intent)
        }
        
        // --- 新增：无障碍保活逻辑 ---
        binding.btnAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "请在列表中找到本应用并开启", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_SHORT).show()
            }
        }
        
        updateSelectButtonState()
    }

    override fun onResume() {
        super.onResume()
        // 每次回到页面时检查无障碍状态
        updateAccessibilityStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }

    // --- 新增：检查无障碍服务状态 ---
    private fun updateAccessibilityStatus() {
        if (isAccessibilityServiceEnabled()) {
            binding.btnAccessibility.text = "保活服务已开启"
            binding.btnAccessibility.isEnabled = false // 开启后禁用按钮，或者改为“已开启”状态
            // 可以选择在这里设置按钮颜色等样式
        } else {
            binding.btnAccessibility.text = "开启保活 (无障碍)"
            binding.btnAccessibility.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, KeepAliveAccessibilityService::class.java)
        
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
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
                    // 忽略用户主动取消的情况
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && 
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                         Toast.makeText(applicationContext, "验证错误: $errString", Toast.LENGTH_SHORT).show()
                    }
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

    // --- 核心逻辑 ---

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
