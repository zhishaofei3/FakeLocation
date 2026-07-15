package com.fakelocation.app

import android.app.Application
import com.amap.api.maps.MapsInitializer

/**
 * 应用入口。
 *
 * 高德 SDK 自 2021 年起要求在使用任何接口前先完成隐私合规声明：
 *  - updatePrivacyShow：声明已向用户展示隐私政策
 *  - updatePrivacyAgree：声明用户已同意隐私政策
 *
 * 否则 SDK 会在初始化 GeocodeSearch / PoiSearch 等组件时直接抛 AMapException 崩溃。
 * MapsInitializer 的隐私接口会同时作用于 3D 地图、定位、搜索三个 SDK。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 更新隐私合规状态，参数 true 表示已弹窗告知且用户已同意
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
    }
}
