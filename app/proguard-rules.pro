# 高德地图 SDK 混淆规则
-keep class com.amap.api.**{*;}
-keep class com.autonavi.**{*;}
-keep class com.loc.**{*;}
-keep class com.a.a.**{*;}
-keep class com.amap.api.location.**{*;}
-keep class com.amap.api.fence.**{*;}
-keep class com.amap.api.maps.**{*;}
-keep class com.amap.api.maps2d.**{*;}
-keep class com.amap.api.navi.**{*;}
-keep class com.amap.api.search.**{*;}
-keep class com.amap.api.services.**{*;}

# 保留 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}
