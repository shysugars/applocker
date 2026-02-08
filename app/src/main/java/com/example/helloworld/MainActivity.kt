package com.example.helloworld

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.helloworld.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 使用 ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置开关监听
        binding.switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            val message = if (isChecked) "已开启" else "已关闭"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}