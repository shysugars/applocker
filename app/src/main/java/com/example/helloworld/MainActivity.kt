// --- START OF FILE new/MainActivity.kt ---
package com.example.helloworld

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.helloworld.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private var selectedPackages = ArrayList<String>()
    private var isUpdatingSwitch = false

    // UserService 相关
    private var userService: IUserService? = null
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, MyUserService::class.java.name)
    ).daemon(true) // 设置为守护进程模式，实现保活

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.isBinderAlive) {
                userService = IUserService.Stub.asInterface(binder)
                runOnUiThread {
                    binding.switchDynamic.isEnabled = true
                    binding.switchDynamic.text = "服务已就绪 (保活中)"
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            runOnUiThread {
                binding.switchDynamic.isEnabled = false
                binding.switchDynamic.text = "服务已断开"
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { checkShizukuAndBindService() }
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == 100 && grantResult == PackageManager.PERMISSION_GRANTED) {
                checkShizukuAndBindService()
            } else {
                revertSwitchState(false)
                Toast.makeText(this, "需要 Shizuku 权限", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        loadSelectedPackages()
        setupBiometric()

        binding.switchDynamic.isEnabled = false
        binding.switchDynamic.text = "检查 Shizuku..."

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        checkShizukuAndBindService()

        binding.switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            if (selectedPackages.isEmpty()) {
                Toast.makeText(this, "请先选择应用", Toast.LENGTH_SHORT).show()
                revertSwitchState(!isChecked)
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                executeSuspendAction(true)
            } else {
                authenticateAndUnsuspend()
            }
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(this, AppSelectionActivity::class.java)
            selectAppsLauncher.launch(intent)
        }
    }

    private fun checkShizukuAndBindService() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                // 绑定 UserService
                try {
                    Shizuku.bindUserService(userServiceArgs, serviceConnection)
                } catch (e: Exception) {
                    Log.e("Shizuku", "Bind error", e)
                }
            } else {
                Shizuku.requestPermission(100)
            }
        }
    }

    private fun executeSuspendAction(suspend: Boolean) {
        val service = userService
        if (service == null) {
            Toast.makeText(this, "UserService 未连接", Toast.LENGTH_SHORT).show()
            revertSwitchState(!suspend)
            return
        }

        val cmdKeyword = if (suspend) "suspend" else "unsuspend"
        val commandBuilder = StringBuilder("cmd package $cmdKeyword")
        selectedPackages.forEach { pkg -> commandBuilder.append(" ").append(pkg) }
        val finalCommand = commandBuilder.toString()

        Thread {
            try {
                // 通过 UserService 远程调用 shell 命令
                val exitCode = service.runCommand(finalCommand)
                
                runOnUiThread {
                    if (exitCode == 0) {
                        Toast.makeText(this, if (suspend) "操作成功" else "已恢复", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "执行失败: $exitCode", Toast.LENGTH_SHORT).show()
                        revertSwitchState(!suspend)
                    }
                    checkAppsSuspendedStatus()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { revertSwitchState(!suspend) }
            }
        }.start()
    }

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val packages = result.data?.getStringArrayListExtra("selected_packages")
            if (packages != null) {
                selectedPackages = packages
                saveSelectedPackages()
                binding.tvSelectedApps.text = if (packages.isNotEmpty()) "已选应用 (${packages.size}个)" else "未选择应用"
                checkAppsSuspendedStatus()
            }
        }
    }

    // --- 其他逻辑 (save/load, checkStatus, Biometric) 与之前代码相同 ---
    // 为了篇幅，简略部分重复代码，但功能保持完整

    private fun saveSelectedPackages() {
        sharedPreferences.edit().putStringSet("saved_packages", selectedPackages.toHashSet()).apply()
    }

    private fun loadSelectedPackages() {
        val savedSet = sharedPreferences.getStringSet("saved_packages", null)
        if (savedSet != null) {
            selectedPackages = ArrayList(savedSet)
            binding.tvSelectedApps.text = "已选应用 (${selectedPackages.size}个)"
        }
    }

    private fun setupBiometric() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                executeSuspendAction(false)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                revertSwitchState(true)
            }
        })
        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("验证指纹")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
    }

    private fun authenticateAndUnsuspend() {
        biometricPrompt.authenticate(promptInfo)
    }

    private fun checkAppsSuspendedStatus() {
        if (selectedPackages.isEmpty()) return
        var allSuspended = true
        for (pkg in selectedPackages) {
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                if ((info.flags and ApplicationInfo.FLAG_SUSPENDED) == 0) {
                    allSuspended = false
                    break
                }
            } catch (e: Exception) { continue }
        }
        updateSwitchSilent(allSuspended)
        binding.btnSelectApps.isEnabled = !allSuspended
    }

    private fun updateSwitchSilent(checked: Boolean) {
        isUpdatingSwitch = true
        binding.switchDynamic.isChecked = checked
        isUpdatingSwitch = false
    }

    private fun revertSwitchState(target: Boolean) {
        isUpdatingSwitch = true
        binding.switchDynamic.isChecked = target
        isUpdatingSwitch = false
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {}
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
    }
}