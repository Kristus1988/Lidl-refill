# Selenium
-keep class org.openqa.selenium.** { *; }
-keep class org.apache.** { *; }
-keep class io.netty.** { *; }
-keep class com.google.** { *; }

# WebDriverManager
-keep class io.github.bonigarcia.** { *; }

# Keep our application classes
-keep class com.example.lidlrefill.** { *; }

# Keep annotations
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
