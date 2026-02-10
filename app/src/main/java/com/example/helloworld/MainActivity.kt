package com.example.helloworld

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
            if (packages != null) { // 允许空列表，代表清空
                selectedPackages = packages
                saveSelectedPackages() // 保存到本地
                
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
                // 授权后执行暂停操作（授权时肯定是开启状态）
                executeSuspendAction(true)
            } else if (requestCode == 100) {
                revertSwitchState(false) // 拒绝则回弹
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
                // --- 开启开关 (暂停应用) ---
                // 不需要验证，直接检查权限并执行
                if (checkPermission()) {
                    executeSuspendAction(true)
                }
            } else {
                // --- 关闭开关 (恢复应用) ---
                // 需要验证指纹
                authenticateAndUnsuspend()
            }
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(this, AppSelectionActivity::class.java)
            // 传递当前已选列表，以便在选择页回显（需自行在 AppSelectionActivity 处理，此处仅逻辑准备）
            // intent.putStringArrayListExtra("current_selection", selectedPackages) 
            selectAppsLauncher.launch(intent)
        }
        
        // 初始化按钮状态
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
        // SharedPreferences 不能直接存 ArrayList，转为 Set
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
                    // 验证成功，执行恢复操作
                    if (checkPermission()) {
                        executeSuspendAction(false)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "验证错误: $errString", Toast.LENGTH_SHORT).show()
                    // 验证失败或取消，开关回弹（变回开启状态）
                    revertSwitchState(true)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "验证失败", Toast.LENGTH_SHORT).show()
                    // 指纹不匹配，通常系统会允许重试几次，这里暂不回弹，等 Error 回调
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("身份验证")
            .setSubtitle("验证指纹以恢复应用")
            // 允许使用密码/图案作为备选（提升兼容性）
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
    }

    private fun authenticateAndUnsuspend() {
        // 检查设备是否支持生物识别
        val biometricManager = BiometricManager.from(this)
        // 简单检查即可，Prompt 会处理大部分情况
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) {
            // 如果不支持指纹，直接放行（或者你可以选择禁止）
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
            // Shizuku 准备好后，检查一次状态
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
        // 更新按钮状态：如果全部暂停（开关开），则禁用列表修改
        updateSelectButtonState()
    }

    // 更新“选择应用”按钮的可点击状态
    private fun updateSelectButtonState() {
        // 规则：未取消暂停时（即处于暂停状态/开关开启），不可改变应用列表
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
                // 反射调用 Shizuku.newProcess
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
                        revertSwitchState(!suspend) // 失败回弹
                    }
                    checkAppsSuspendedStatus() // 再次检查并更新 UI
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

    // 辅助方法：静默更新 Switch 状态（不触发 Listener）
    private fun updateSwitchSilent(checked: Boolean) {
        if (binding.switchDynamic.isChecked != checked) {
            isUpdatingSwitch = true
            binding.switchDynamic.isChecked = checked
            isUpdatingSwitch = false
        }
    }

    // 辅助方法：回弹 Switch 状态
    private fun revertSwitchState(targetState: Boolean) {
        isUpdatingSwitch = true
        binding.switchDynamic.isChecked = targetState
        isUpdatingSwitch = false
    }
}