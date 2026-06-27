<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="20dp"
    android:gravity="center">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Lidl Refill Automator"
        android:textSize="24sp"
        android:textStyle="bold"
        android:layout_marginBottom="40dp" />

    <Button
        android:id="@+id/btnRequestPermissions"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="🔓 Berechtigungen erteilen"
        android:backgroundTint="#2196F3"
        android:layout_marginBottom="10dp" />

    <Button
        android:id="@+id/btnCheckPermissions"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="✅ Berechtigungen prüfen"
        android:backgroundTint="#FF9800"
        android:layout_marginBottom="10dp" />

    <Button
        android:id="@+id/btnStartService"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="🚀 Overlay starten"
        android:backgroundTint="#4CAF50" />

</LinearLayout>
