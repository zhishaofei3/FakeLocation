package com.fakelocation.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 模拟定位前台服务。
 *
 * 持续向系统的 GPS / NETWORK 测试 Provider 推送伪造的经纬度，
 * 使其他应用读取到的位置为目标位置。
 *
 * 注意：本应用必须已在「开发者选项 - 选择模拟位置信息应用」中被选中，
 * 否则 addTestProvider 会抛出 SecurityException。
 */
class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var targetLat = 0.0
    private var targetLng = 0.0
    private var targetName = ""

    private val pushRunnable = object : Runnable {
        override fun run() {
            pushMockLocation()
            // 每秒刷新一次，确保被其他应用读取为「最新位置」
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMock()
            return START_NOT_STICKY
        }

        targetLat = intent?.getDoubleExtra(EXTRA_LAT, 0.0) ?: 0.0
        targetLng = intent?.getDoubleExtra(EXTRA_LNG, 0.0) ?: 0.0
        targetName = intent?.getStringExtra(EXTRA_NAME) ?: ""

        // 使用 ServiceCompat 显式声明前台服务类型，兼容 Android 14
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(targetName), type)
        startMock()

        return START_STICKY
    }

    private fun startMock() {
        try {
            // 避免重复运行时叠加多个定时任务
            handler.removeCallbacks(pushRunnable)
            setupTestProvider(LocationManager.GPS_PROVIDER)
            setupTestProvider(LocationManager.NETWORK_PROVIDER)
            handler.post(pushRunnable)
        } catch (e: SecurityException) {
            // 应用未被设置为模拟位置应用
            stopSelf()
        }
    }

    /** 注册一个测试 Provider。 */
    private fun setupTestProvider(provider: String) {
        try {
            locationManager.removeTestProvider(provider)
        } catch (_: Exception) {
            // 尚未注册时忽略
        }
        locationManager.addTestProvider(
            provider,
            false,        // requiresNetwork
            false,        // requiresSatellite
            false,        // requiresCell
            false,        // hasMonetaryCost
            true,         // supportsAltitude
            true,         // supportsSpeed
            true,         // supportsBearing
            android.location.Criteria.POWER_LOW,
            android.location.Criteria.ACCURACY_FINE
        )
        locationManager.setTestProviderEnabled(provider, true)
    }

    /** 构造一个 Location 并推送给测试 Provider。 */
    private fun pushMockLocation() {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            try {
                val loc = Location(provider).apply {
                    latitude = targetLat
                    longitude = targetLng
                    altitude = 0.0
                    accuracy = 1.0f
                    speed = 0.0f
                    bearing = 0.0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                // 兼容 API 26+ 的 extras 设置
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val extras = Bundle().apply {
                        putInt("satellites", 12)
                    }
                    loc.extras = extras
                }
                locationManager.setTestProviderLocation(provider, loc)
            } catch (_: Exception) {
                // Provider 被移除等情况，忽略
            }
        }
    }

    private fun stopMock() {
        handler.removeCallbacks(pushRunnable)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pushRunnable)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // region 通知
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(name: String): Notification {
        val text = if (name.isNotEmpty())
            getString(R.string.notification_text, name)
        else
            getString(R.string.notification_title)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    // endregion

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mock_location_service"
        private const val UPDATE_INTERVAL_MS = 1000L

        const val ACTION_START = "com.fakelocation.app.ACTION_START_MOCK"
        const val ACTION_STOP = "com.fakelocation.app.ACTION_STOP_MOCK"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_NAME = "extra_name"

        /** 构造启动服务的 Intent。 */
        fun startIntent(context: Context, lat: Double, lng: Double, name: String): Intent {
            return Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LNG, lng)
                putExtra(EXTRA_NAME, name)
            }
        }

        /** 构造停止服务的 Intent。 */
        fun stopIntent(context: Context): Intent {
            return Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
