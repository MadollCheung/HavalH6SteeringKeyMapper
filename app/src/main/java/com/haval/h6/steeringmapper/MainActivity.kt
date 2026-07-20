package com.haval.h6.steeringmapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 主界面
 * 功能：
 * 1. 显示无障碍服务开关状态
 * 2. 显示当前按键映射列表
 * 3. 新增/编辑/删除按键映射
 * 4. 进入按键检测模式
 * 5. 重置为默认配置
 */
class MainActivity : AppCompatActivity() {

    private lateinit var keyMappingManager: KeyMappingManager
    private lateinit var tvServiceStatus: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnDetectKey: Button
    private lateinit var btnAddMapping: Button
    private lateinit var btnReset: Button
    private lateinit var rvMappings: RecyclerView
    private lateinit var tvLastAction: TextView

    private val adapter = KeyMappingAdapter()

    // 监听服务状态变化和按键事件广播
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                SteeringKeyService.ACTION_SERVICE_STARTED -> updateServiceStatus(true)
                SteeringKeyService.ACTION_SERVICE_STOPPED -> updateServiceStatus(false)
                SteeringKeyService.ACTION_KEY_DETECTED -> {
                    val actionName = intent.getStringExtra(SteeringKeyService.EXTRA_ACTION_NAME)
                    val keyName = intent.getStringExtra(SteeringKeyService.EXTRA_KEY_NAME)
                    val keyCode = intent.getIntExtra(SteeringKeyService.EXTRA_KEYCODE, -1)
                    if (actionName != null) {
                        tvLastAction.text = "✅ 已执行：$actionName"
                    } else if (keyName != null) {
                        tvLastAction.text = "🔑 检测到按键：$keyName (keyCode=$keyCode)"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        keyMappingManager = KeyMappingManager(this)
        initViews()
        setupRecyclerView()
        registerReceivers()
        updateServiceStatus(SteeringKeyService.isRunning)
        refreshMappingList()
    }

    private fun initViews() {
        tvServiceStatus = findViewById(R.id.tv_service_status)
        btnToggleService = findViewById(R.id.btn_toggle_service)
        btnDetectKey = findViewById(R.id.btn_detect_key)
        btnAddMapping = findViewById(R.id.btn_add_mapping)
        btnReset = findViewById(R.id.btn_reset)
        rvMappings = findViewById(R.id.rv_mappings)
        tvLastAction = findViewById(R.id.tv_last_action)

        btnToggleService.setOnClickListener {
            if (SteeringKeyService.isRunning) {
                // 跳转到无障碍设置关闭
                openAccessibilitySettings()
            } else {
                openAccessibilitySettings()
            }
        }

        btnDetectKey.setOnClickListener {
            startActivity(Intent(this, KeyDetectActivity::class.java))
        }

        btnAddMapping.setOnClickListener {
            showAddMappingDialog()
        }

        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("确认重置")
                .setMessage("将重置为默认按键映射配置，自定义配置将丢失。")
                .setPositiveButton("确认重置") { _, _ ->
                    keyMappingManager.resetToDefault()
                    refreshMappingList()
                    Toast.makeText(this, "已重置为默认配置", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupRecyclerView() {
        rvMappings.layoutManager = LinearLayoutManager(this)
        rvMappings.adapter = adapter

        adapter.onDeleteClick = { mapping ->
            AlertDialog.Builder(this)
                .setTitle("删除映射")
                .setMessage("确认删除「${mapping.getDisplayName()}」？")
                .setPositiveButton("删除") { _, _ ->
                    keyMappingManager.deleteMapping(mapping.id)
                    refreshMappingList()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        adapter.onToggleEnabled = { mapping, enabled ->
            keyMappingManager.updateMapping(mapping.copy(enabled = enabled))
        }
    }

    /**
     * 添加新映射的对话框
     */
    private fun showAddMappingDialog() {
        // 选择功能键（第二个键）
        val keyOptions = arrayOf(
            "上键 (DPAD_UP)", "下键 (DPAD_DOWN)",
            "左键 (DPAD_LEFT)", "右键 (DPAD_RIGHT)",
            "确认键 (CENTER)", "音量+ ", "音量-",
            "接听键", "挂断键"
        )
        val keyCodes = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL
        )

        // 选择动作
        val actions = HvacAction.values()
        val actionOptions = actions.map { "${it.displayName}  -  ${it.description}" }.toTypedArray()

        var selectedKeyIndex = 0
        var selectedActionIndex = 0

        val view = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)

        AlertDialog.Builder(this)
            .setTitle("新增按键映射")
            .setMessage("选择方向盘功能键：")
            .setSingleChoiceItems(keyOptions, 0) { _, which -> selectedKeyIndex = which }
            .setPositiveButton("下一步") { _, _ ->
                // 第二步：选择动作
                AlertDialog.Builder(this)
                    .setTitle("选择空调功能")
                    .setSingleChoiceItems(actionOptions, 0) { _, which ->
                        selectedActionIndex = which
                    }
                    .setPositiveButton("确认添加") { _, _ ->
                        val mapping = KeyMapping(
                            id = "custom_${System.currentTimeMillis()}",
                            modifierKeyCode = keyMappingManager.getStarKeyCode(),
                            actionKeyCode = keyCodes[selectedKeyIndex],
                            action = actions[selectedActionIndex],
                            description = "自定义: * + ${keyOptions[selectedKeyIndex]} → ${actions[selectedActionIndex].displayName}"
                        )
                        keyMappingManager.addMapping(mapping)
                        refreshMappingList()
                        Toast.makeText(this, "映射已添加", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateServiceStatus(running: Boolean) {
        if (running) {
            tvServiceStatus.text = "● 服务运行中"
            tvServiceStatus.setTextColor(0xFF4CAF50.toInt())
            btnToggleService.text = "前往无障碍设置"
        } else {
            tvServiceStatus.text = "● 服务未启动"
            tvServiceStatus.setTextColor(0xFFF44336.toInt())
            btnToggleService.text = "开启无障碍服务"
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(
            this,
            "请找到「方向盘按键映射工具」并开启",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshMappingList() {
        adapter.submitList(keyMappingManager.getAllMappings())
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(SteeringKeyService.ACTION_SERVICE_STARTED)
            addAction(SteeringKeyService.ACTION_SERVICE_STOPPED)
            addAction(SteeringKeyService.ACTION_KEY_DETECTED)
        }
        registerReceiver(serviceReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
    }
}

// =================== RecyclerView Adapter ===================

class KeyMappingAdapter : RecyclerView.Adapter<KeyMappingAdapter.ViewHolder>() {

    private val items = mutableListOf<KeyMapping>()
    var onDeleteClick: ((KeyMapping) -> Unit)? = null
    var onToggleEnabled: ((KeyMapping, Boolean) -> Unit)? = null

    fun submitList(list: List<KeyMapping>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_key_mapping, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_mapping_name)
        private val tvDesc: TextView = itemView.findViewById(R.id.tv_mapping_desc)
        private val swEnabled: Switch = itemView.findViewById(R.id.sw_enabled)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)

        fun bind(mapping: KeyMapping) {
            tvName.text = mapping.getDisplayName()
            tvDesc.text = mapping.description.ifEmpty { mapping.action.description }
            swEnabled.isChecked = mapping.enabled
            swEnabled.setOnCheckedChangeListener { _, checked ->
                onToggleEnabled?.invoke(mapping, checked)
            }
            btnDelete.setOnClickListener { onDeleteClick?.invoke(mapping) }
        }
    }
}