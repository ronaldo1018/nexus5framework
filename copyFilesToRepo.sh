#!/bin/bash

AOSPPath="/home/howard/Nexus5/AOSP4.4.4/"

# Add a system service
MultiResourceManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/MultiResourceManagerService.java"
MultiResourceManagerServiceFolder=$AOSPPath"frameworks/base/services/java/com/android/server/"
IMultiResourceManagerService=$AOSPPath"frameworks/base/core/java/android/os/IMultiResourceManagerService.aidl"
IMultiResourceManagerServiceFolder=$AOSPPath"frameworks/base/core/java/android/os/"
SystemServer=$AOSPPath"frameworks/base/services/java/com/android/server/SystemServer.java"
SystemServerFolder=$AOSPPath"frameworks/base/services/java/com/android/server/"
Context=$AOSPPath"frameworks/base/core/java/android/content/Context.java"
ContextFolder=$AOSPPath"frameworks/base/core/java/android/content/"
AndroidMk=$AOSPPath"frameworks/base/Android.mk"
AndroidMkFolder=$AOSPPath"frameworks/base/"
MultiResourceManager=$AOSPPath"frameworks/base/core/java/android/os/MultiResourceManager.java"
MultiResourceManagerFolder=$AOSPPath"frameworks/base/core/java/android/os/"
ContextImpl=$AOSPPath"frameworks/base/core/java/android/app/ContextImpl.java"
ContextImplFolder=$AOSPPath"frameworks/base/core/java/android/app/"

# Schedule active alarm
AlarmManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/AlarmManagerService.java"
AlarmManagerServiceFolder=$AOSPPath"frameworks/base/services/java/com/android/server/"
AlarmManager=$AOSPPath"frameworks/base/core/java/android/app/AlarmManager.java"
AlarmManagerFolder=$AOSPPath"frameworks/base/core/java/android/app/"
IAlarmManager=$AOSPPath"frameworks/base/core/java/android/app/IAlarmManager.aidl"
IAlarmManagerFolder=$AOSPPath"frameworks/base/core/java/android/app/"

# Monitor vibration state
VibratorService=$AOSPPath"frameworks/base/services/java/com/android/server/VibratorService.java"
VibratorServiceFolder=$AOSPPath"frameworks/base/services/java/com/android/server/"

# Monitor audio(ringtone) state
MediaPlayer=$AOSPPath"frameworks/base/media/java/android/media/MediaPlayer.java"
MediaPlayerFolder=$AOSPPath"frameworks/base/media/java/android/media/"

# Monitor screen state
PowerManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/power/PowerManagerService.java"
PowerManagerServiceFolder=$AOSPPath"frameworks/base/services/java/com/android/server/power/"
PowerManager=$AOSPPath"frameworks/base/core/java/android/os/PowerManager.java"
PowerManagerFolder=$AOSPPath"frameworks/base/core/java/android/os/"
WindowManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/wm/WindowManagerService.java"
WindowManagerServiceFolder=$AOSPPath"frameworks/base/services/java/com/android/server/wm/"

# Sensor
SystemSensorManager=$AOSPPath"frameworks/base/core/java/android/hardware/SystemSensorManager.java"
SystemSensorManagerFolder=$AOSPPath"frameworks/base/core/java/android/hardware/"

# Gps
LocationManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/LocationManagerService.java"
LocationManagerServiceFolder=$AOSPPath"frameworks/base/services/java/com/android/server/"

# Monitor the notification state
NotificationManagerService=$AOSPPath"frameworks/base/services/java/com/android/server/NotificationManagerService.java"
NotificationManagerServiceFolder=$AOSPPath"frameworks/base/services/java/com/android/server/"
Notification=$AOSPPath"frameworks/base/core/java/android/app/Notification.java"
NotificationFolder=$AOSPPath"frameworks/base/core/java/android/app/"

if diff $MultiResourceManagerService MultiResourceManagerService.java >/dev/null ; then
	echo "MultiResourceManagerService.java is same"
else
	echo "Copy MultiResourceManagerService.java..."
	rm -f $MultiResourceManagerService
	cp MultiResourceManagerService.java $MultiResourceManagerServiceFolder
fi

if diff $IMultiResourceManagerService IMultiResourceManagerService.aidl >/dev/null ; then
	echo "IMultiResourceManagerService.aidl is same"
else
	echo "Copy IMultiResourceManagerService.aidl..."
	rm -f $IMultiResourceManagerService
	cp IMultiResourceManagerService.aidl $IMultiResourceManagerServiceFolder
fi

if diff $SystemServer SystemServer.java >/dev/null ; then
	echo "SystemServer.java is same"
else
	echo "Copy SystemServer.java..."
	rm -f $SystemServer
	cp SystemServer.java $SystemServerFolder
fi

if diff $Context Context.java >/dev/null ; then
	echo "Context.java is same"
else
	echo "Copy Context.java..."
	rm -f $Context
	cp Context.java $ContextFolder
fi

if diff $AndroidMk Android.mk >/dev/null ; then
	echo "Android.mk is same"
else
	echo "Copy Android.mk..."
	rm -f $AndroidMk
	cp Android.mk $AndroidMkFolder
fi

if diff $MultiResourceManager MultiResourceManager.java >/dev/null ; then
	echo "MultiResourceManager.java is same"
else
	echo "Copy MultiResourceManager.java..."
	rm -f $MultiResourceManager
	cp MultiResourceManager.java $MultiResourceManagerFolder
fi

if diff $ContextImpl ContextImpl.java >/dev/null ; then
	echo "ContextImpl.java is same"
else
	echo "Copy ContextImpl.java..."
	rm -f $ContextImpl
	cp ContextImpl.java $ContextImplFolder
fi


if diff $AlarmManagerService AlarmManagerService.java >/dev/null ; then
	echo "AlarmManagerService.java is same"
else
	echo "Copy AlarmManagerService.java..."
	rm -f $AlarmManagerService
	cp AlarmManagerService.java $AlarmManagerServiceFolder
fi

if diff $AlarmManager AlarmManager.java >/dev/null ; then
	echo "AlarmManager.java is same"
else
	echo "Copy AlarmManager.java..."
	rm -f $AlarmManager
	cp AlarmManager.java $AlarmManagerFolder
fi

if diff $IAlarmManager IAlarmManager.aidl >/dev/null ; then
	echo "IAlarmManager.aidl is same"
else
	echo "Copy IAlarmManager.aidl..."
	rm -f $IAlarmManager
	cp IAlarmManager.aidl $IAlarmManagerFolder
fi


if diff $VibratorService VibratorService.java >/dev/null ; then
	echo "VibratorService.java is same"
else
	echo "Copy VibratorService.java..."
	rm -f $VibratorService
	cp VibratorService.java $VibratorServiceFolder
fi


if diff $MediaPlayer MediaPlayer.java >/dev/null ; then
	echo "MediaPlayer.java is same"
else
	echo "Copy MediaPlayer.java..."
	rm -f $MediaPlayer
	cp MediaPlayer.java $MediaPlayerFolder
fi


if diff $PowerManagerService PowerManagerService.java >/dev/null ; then
	echo "PowerManagerService.java is same"
else
	echo "Copy PowerManagerService.java..."
	rm -f $PowerManagerService
	cp PowerManagerService.java $PowerManagerServiceFolder
fi

if diff $PowerManager PowerManager.java >/dev/null ; then
	echo "PowerManager.java is same"
else
	echo "Copy PowerManager.java..."
	rm -f $PowerManager
	cp PowerManager.java $PowerManagerFolder
fi

if diff $WindowManagerService WindowManagerService.java >/dev/null ; then
	echo "WindowManagerService.java is same"
else
	echo "Copy WindowManagerService.java..."
	rm -f $WindowManagerService
	cp WindowManagerService.java $WindowManagerServiceFolder
fi


if diff $SystemSensorManager SystemSensorManager.java >/dev/null ; then
	echo "SystemSensorManager.java is same"
else
	echo "Copy SystemSensorManager.java..."
	rm -f $SystemSensorManager
	cp SystemSensorManager.java $SystemSensorManagerFolder
fi


if diff $LocationManagerService LocationManagerService.java >/dev/null ; then
	echo "LocationManagerService.java is same"
else
	echo "Copy LocationManagerService.java..."
	rm -f $LocationManagerService
	cp LocationManagerService.java $LocationManagerServiceFolder
fi


if diff $NotificationManagerService NotificationManagerService.java >/dev/null ; then
	echo "NotificationManagerService.java is same"
else
	echo "Copy NotificationManagerService.java..."
	rm -f $NotificationManagerService
	cp NotificationManagerService.java $NotificationManagerServiceFolder
fi

if diff $Notification Notification.java >/dev/null ; then
	echo "Notification.java is same"
else
	echo "Copy Notification.java..."
	rm -f $Notification
	cp Notification.java $NotificationFolder
fi


