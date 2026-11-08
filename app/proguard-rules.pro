# Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# 我们的数据类（JSON 序列化用字符串 key，不需要反射，但保留以防 R8 误删）
-keep class com.oracole.readit.data.** { *; }

# 网络
-keep class javax.net.ssl.** { *; }
-dontwarn org.json.**
