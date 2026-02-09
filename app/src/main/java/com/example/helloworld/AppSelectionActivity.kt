package com.example.helloworld

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.example.helloworld.databinding.ActivityAppSelectionBinding
import com.example.helloworld.databinding.ItemAppBinding

// 数据模型
data class AppInfo(
    val name: String,
    val icon: Drawable,
    val packageName: String,
    var isSelected: Boolean = false
)

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectionBinding
    private val appList = mutableListOf<AppInfo>()
    private val adapter = AppAdapter(appList)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置 RecyclerView
        binding.recyclerViewApps.adapter = adapter

        // 异步加载应用列表（简单起见使用 Thread，生产环境推荐 Coroutines）
        Thread {
            val loadedApps = loadInstalledApps()
            runOnUiThread {
                appList.clear()
                appList.addAll(loadedApps)
                adapter.notifyDataSetChanged()
            }
        }.start()

        // 保存按钮点击事件
        binding.fabSave.setOnClickListener {
            val selectedNames = appList.filter { it.isSelected }.map { it.name }
            val intent = Intent().apply {
                putStringArrayListExtra("selected_apps", ArrayList(selectedNames))
            }
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    private fun loadInstalledApps(): List<AppInfo> {
        val pm = packageManager
        // 获取所有已安装应用，过滤掉没有启动入口的系统组件，让列表更干净
        val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
        val apps = mutableListOf<AppInfo>()

        for (pkg in packages) {
            // 简单过滤：只显示有应用名称的应用
            val appName = pkg.applicationInfo.loadLabel(pm).toString()
            val icon = pkg.applicationInfo.loadIcon(pm)
            // 排除系统应用可选逻辑：if ((pkg.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0)
            apps.add(AppInfo(appName, icon, pkg.packageName))
        }
        return apps.sortedBy { it.name }
    }
}

// 适配器
class AppAdapter(private val items: List<AppInfo>) :
    RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = items[position]
        holder.binding.ivIcon.setImageDrawable(app.icon)
        holder.binding.tvName.text = app.name
        
        // 防止 CheckBox 复用导致状态错乱，先移除监听
        holder.binding.checkbox.setOnCheckedChangeListener(null)
        holder.binding.checkbox.isChecked = app.isSelected
        
        holder.binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
            app.isSelected = isChecked
        }
        
        // 点击整行也能切换勾选
        holder.itemView.setOnClickListener {
            holder.binding.checkbox.isChecked = !holder.binding.checkbox.isChecked
        }
    }

    override fun getItemCount() = items.size
}