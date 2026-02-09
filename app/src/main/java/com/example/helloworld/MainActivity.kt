package com.example.helloworld

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.helloworld.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 新增：注册 Activity Result Launcher 用于接收返回的数据
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

        binding.switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            val message = if (isChecked) "已开启" else "已关闭"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        // 新增：按钮点击事件
        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(this, AppSelectionActivity::class.java)
            selectAppsLauncher.launch(intent)
        }
    }
}