package android.os;

import android.app.Notification;
import android.os.WorkSource;

interface IMultiResourceManagerService
{
	boolean getIsGrant(int uid, long startRtc, long stopRtc, int hardware);
	long getLastGrantTime(int uid, int hardware);
	void grant(int uid, int hardware);
	
	boolean isServeNotification(in String pkg, in String tag, int id, int callingUid, int callingPid, int userId, int score, inout Notification notification);
	boolean isServeScreen(int uid);
	boolean isServeWakeLock(int flags, in String tag, in WorkSource ws, int uid, int pid);
	
	void focusChanged(int uid);
	long getLastFocusTime(int uid);
	float getAppUsage(int uid);

	int getConnectivityType();
	boolean isUserPerceivable(int uid);
}
