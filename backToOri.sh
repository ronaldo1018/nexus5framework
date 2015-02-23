#!/bin/bash

AOSPPath="/home/howard/Nexus5/AOSP4.4.4/"
OriPath="/home/howard/Nexus5/Nexus5Framework/ori/"

# Custom system service
MultiResourceManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/MultiResourceManagerService.java"
IMultiResourceManagerService=$AOSPPath"frameworks/base/core/java/android/os/IMultiResourceManagerService.aidl"
SystemServer=$AOSPPath"frameworks/base/services/java/com/android/server/SystemServer.java"
Context=$AOSPPath"frameworks/base/core/java/android/content/Context.java"
AndroidMk=$AOSPPath"frameworks/base/Android.mk"

# Notification
NotificationManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/NotificationManagerService.java"
Notification=$AOSPPath"frameworks/base/core/java/android/app/Notification.java"

# Screen
PowerManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/power/PowerManagerService.java"
WindowManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/wm/WindowManagerService.java"

# Active alarm
AlarmManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/AlarmManagerService.java"

# GPS
LocationManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/LocationManagerService.java"

# Sensor
SystemSensorManager=$AOSPPath"frameworks/base/core/java/android/hardware/SystemSensorManager.java"

rm -f $MultiResourceManagerService
rm -f $IMultiResourceManagerService
cp $OriPath"SystemServer.java" $SystemServer
cp $OriPath"Context.java" $Context
cp $OriPath"Android.mk" $AndroidMk

cp $OriPath"NotificationManagerService.java" $NotificationManagerService
cp $OriPath"Notification.java" $Notification

cp $OriPath"PowerManagerService.java" $PowerManagerService
cp $OriPath"WindowManagerService.java" $WindowManagerService

cp $OriPath"AlarmManagerService.java" $AlarmManagerService

cp $OriPath"LocationManagerService.java" $LocationManagerService
cp $OriPath"SystemSensorManager.java" $SystemSensorManager
