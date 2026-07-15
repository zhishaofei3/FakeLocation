package com.fakelocation.app

import com.amap.api.maps.model.LatLng

/**
 * 搜索结果数据类。坐标使用高德 GCJ-02 坐标系。
 */
data class SearchResult(
    val name: String,
    val address: String,
    val latLng: LatLng
)
