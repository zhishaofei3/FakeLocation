package com.fakelocation.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Criteria
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.fakelocation.app.databinding.ActivityMainBinding

/**
 * 主界面：高德地图 + 关键字搜索 + 模拟定位开关。
 *
 * 目标位置始终为地图屏幕中心（中心标记），拖动地图即可选择位置，
 * 也可通过关键字搜索后点击结果跳转。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var aMap: AMap
    private lateinit var geocodeSearch: GeocodeSearch
    private lateinit var searchAdapter: SearchAdapter

    private var targetLatLng: LatLng = LatLng(39.908823, 116.397470) // 默认北京天安门
    private var targetName: String = ""
    private var isMockRunning = false
    private var targetMarker: Marker? = null
    private var pendingSearchName: String? = null

    // 防止逆地理编码频繁触发
    private var lastReverseGeocodeTime = 0L

    /** 运行时权限申请 */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果不阻塞核心功能，忽略 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 进入主界面前先检查：开发者选项是否开启 + 本应用是否被设为模拟位置应用
        // 任一条件不满足，跳转到引导页
        if (!isDeveloperOptionsEnabled() || !canMockLocation()) {
            startActivity(Intent(this, GuideActivity::class.java))
            finish()
            return
        }

        requestPermissions()
        initMap(savedInstanceState)
        initSearch()
        initGeocode()
        initActions()
        updateSelectedUI()
    }

    /**
     * 检测系统「开发者选项」是否已开启。
     * 通过读取 Settings.Global.DEVELOPMENT_SETTINGS_ENABLED 判断。
     */
    private fun isDeveloperOptionsEnabled(): Boolean {
        return try {
            Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            // 读取失败时保守返回 true，避免误判导致无法进入
            true
        }
    }

    // region 初始化
    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }

    private fun initMap(savedInstanceState: Bundle?) {
        binding.mapView.onCreate(savedInstanceState)
        aMap = binding.mapView.map

        // UI 设置
        val uiSettings = aMap.uiSettings
        uiSettings.isZoomControlsEnabled = true
        uiSettings.isMyLocationButtonEnabled = true
        // 仅在已授予定位权限时开启「我的位置」蓝点图层
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            // 关键：设置为「只显示蓝点，不移动相机」的定位模式
            // LOCATION_TYPE_SHOW：只显示，不定位、不移动相机
            val myLocationStyle = MyLocationStyle().apply {
                myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
                // 蓝点不自动移动相机，拖动地图后停留在拖动位置
            }
            aMap.myLocationStyle = myLocationStyle
            aMap.isMyLocationEnabled = true
        }

        // 移动到默认位置
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(targetLatLng, 16f))

        // 相机移动监听：屏幕中心即为目标位置
        aMap.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition) {
                targetLatLng = position.target
                updateCoordText()
            }

            override fun onCameraChangeFinish(position: CameraPosition) {
                targetLatLng = position.target
                updateCoordText()
                // 搜索点击产生的相机移动：保留搜索名称，跳过逆地理
                pendingSearchName?.let {
                    targetName = it
                    pendingSearchName = null
                    updateSelectedUI()
                } ?: reverseGeocode(targetLatLng)
            }
        })

        // 长按地图也可选点（与中心标记一致）
        aMap.setOnMapLongClickListener { latLng ->
            targetLatLng = latLng
            aMap.moveCamera(CameraUpdateFactory.changeLatLng(latLng))
        }
    }

    private fun initSearch() {
        searchAdapter = SearchAdapter { result ->
            // 点击搜索结果：移动地图并隐藏结果列表
            hideResults()
            pendingSearchName = result.name
            targetLatLng = result.latLng
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(result.latLng, 16f))
        }
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = searchAdapter

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
    }

    private fun initGeocode() {
        geocodeSearch = GeocodeSearch(this)
        geocodeSearch.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
            override fun onRegeocodeSearched(result: RegeocodeResult?, rCode: Int) {
                if (rCode == 1000 && result?.regeocodeAddress != null) {
                    targetName = result.regeocodeAddress.formatAddress ?: ""
                    updateSelectedUI()
                }
            }

            override fun onGeocodeSearched(result: GeocodeResult?, rCode: Int) {
                // 仅逆地理使用，正向地理不处理
            }
        })
    }

    private fun initActions() {
        binding.btnAction.setOnClickListener {
            if (isMockRunning) {
                stopMockLocation()
            } else {
                startMockLocation()
            }
        }
    }
    // endregion

    // region 搜索
    private fun doSearch() {
        val keyword = binding.editSearch.text.toString().trim()
        if (keyword.isEmpty()) {
            Toast.makeText(this, R.string.error_search_empty, Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()

        val query = PoiSearch.Query(keyword, "", "") // 第三参为空表示全国范围
        query.pageSize = 30
        query.pageNum = 0

        val poiSearch = PoiSearch(this, query)
        poiSearch.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
            override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                runOnUiThread {
                    if (rCode != 1000 || result == null || result.pois.isNullOrEmpty()) {
                        Toast.makeText(
                            this@MainActivity,
                            if (rCode == 1000) getString(R.string.error_no_result)
                            else getString(R.string.error_search_failed, rCode.toString()),
                            Toast.LENGTH_SHORT
                        ).show()
                        hideResults()
                        return@runOnUiThread
                    }
                    showResults(result.pois)
                }
            }

            override fun onPoiItemSearched(item: PoiItem?, rCode: Int) {}
        })
        poiSearch.searchPOIAsyn()
    }

    private fun showResults(pois: List<PoiItem>) {
        val list = pois.mapNotNull { item ->
            val ll = item.latLonPoint ?: return@mapNotNull null
            SearchResult(
                name = item.title ?: "",
                address = item.snippet ?: item.provinceName ?: "",
                latLng = LatLng(ll.latitude, ll.longitude)
            )
        }
        searchAdapter.submit(list)
        binding.recyclerResults.visibility = View.VISIBLE
    }

    private fun hideResults() {
        binding.recyclerResults.visibility = View.GONE
    }
    // endregion

    // region 逆地理编码
    private fun reverseGeocode(latLng: LatLng) {
        val now = System.currentTimeMillis()
        if (now - lastReverseGeocodeTime < 500) return // 节流
        lastReverseGeocodeTime = now

        val query = RegeocodeQuery(
            LatLonPoint(latLng.latitude, latLng.longitude),
            200f,                      // 搜索半径
            GeocodeSearch.AMAP         // 坐标系：高德
        )
        geocodeSearch.getFromLocationAsyn(query)
    }
    // endregion

    // region 模拟定位
    private fun startMockLocation() {
        // 1. 校验开发者选项
        if (!isDeveloperModeEnabled()) {
            Toast.makeText(this, R.string.error_dev_mode, Toast.LENGTH_LONG).show()
            return
        }
        // 2. 校验本应用是否为模拟位置应用
        if (!canMockLocation()) {
            Toast.makeText(this, R.string.error_mock_app, Toast.LENGTH_LONG).show()
            return
        }

        // 在地图上放置目标标记
        addOrUpdateTargetMarker()

        val name = if (targetName.isEmpty()) "地图中心点" else targetName
        val intent = MockLocationService.startIntent(this, targetLatLng.latitude, targetLatLng.longitude, name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isMockRunning = true
        updateActionUI()
        Toast.makeText(this, R.string.mock_running, Toast.LENGTH_SHORT).show()
    }

    private fun stopMockLocation() {
        startService(MockLocationService.stopIntent(this))
        isMockRunning = false
        targetMarker?.remove()
        targetMarker = null
        updateActionUI()
        Toast.makeText(this, R.string.mock_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun addOrUpdateTargetMarker() {
        targetMarker?.remove()
        targetMarker = aMap.addMarker(
            MarkerOptions()
                .position(targetLatLng)
                .title(if (targetName.isEmpty()) "目标位置" else targetName)
                .draggable(false)
        )
    }

    /** 判断开发者选项是否开启。 */
    private fun isDeveloperModeEnabled(): Boolean {
        return try {
            Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1
        } catch (e: Exception) {
            false
        }
    }

    /** 尝试注册并移除一个测试 Provider，判断本应用是否具备模拟定位权限。 */
    private fun canMockLocation(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, false, false, false,
                true, true, true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }
    // endregion

    // region UI 更新
    private fun updateSelectedUI() {
        binding.textSelected.text = if (targetName.isEmpty())
            getString(R.string.tip_select_location)
        else
            getString(R.string.selected_location, targetName)
        updateCoordText()
    }

    private fun updateCoordText() {
        binding.textCoord.text = getString(
            R.string.coord_format, targetLatLng.longitude, targetLatLng.latitude
        )
    }

    private fun updateActionUI() {
        if (isMockRunning) {
            binding.btnAction.text = getString(R.string.stop_mock)
            binding.btnAction.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.red_stop)
            )
        } else {
            binding.btnAction.text = getString(R.string.start_mock)
            binding.btnAction.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.green_start)
            )
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
    // endregion

    // region 生命周期
    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        binding.mapView.onDestroy()
        super.onDestroy()
    }
    // endregion
}
