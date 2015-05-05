/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.ServiceManager;
import android.os.IMultiResourceManagerService;
import android.os.MultiResourceManager;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.TimeUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.TimeZone;

import static android.app.AlarmManager.RTC_WAKEUP;
import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.ELAPSED_REALTIME;

import com.android.internal.util.LocalLog;

class AlarmManagerService extends IAlarmManager.Stub {
	// The threshold for how long an alarm can be late before we print a
	// warning message.  The time duration is in milliseconds.
	private static final long LATE_ALARM_THRESHOLD = 10 * 1000;

	private static final int RTC_WAKEUP_MASK = 1 << RTC_WAKEUP;
	private static final int RTC_MASK = 1 << RTC;
	private static final int ELAPSED_REALTIME_WAKEUP_MASK = 1 << ELAPSED_REALTIME_WAKEUP; 
	private static final int ELAPSED_REALTIME_MASK = 1 << ELAPSED_REALTIME;
	private static final int TIME_CHANGED_MASK = 1 << 16;
	private static final int IS_WAKEUP_MASK = RTC_WAKEUP_MASK|ELAPSED_REALTIME_WAKEUP_MASK;

	// Mask for testing whether a given alarm type is wakeup vs non-wakeup
	private static final int TYPE_NONWAKEUP_MASK = 0x1; // low bit => non-wakeup

	private static final String TAG = "AlarmManager";
	private static final String HOWARD_TAG = "HOWARD_TAG";
	private static final String ClockReceiver_TAG = "ClockReceiver";
	private static final boolean localLOGV = true;
	private static final boolean DEBUG_BATCH = localLOGV || false;
	private static final boolean DEBUG_VALIDATE = false;
	private static boolean DEBUG_HOWARD = false;
	private static boolean DEBUG_HOWARD_LEVEL2 = false;
	private static final int ALARM_EVENT = 1;
	private static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

	private static final Intent mBackgroundIntent
		= new Intent().addFlags(Intent.FLAG_FROM_BACKGROUND);
	private static final IncreasingTimeOrder sIncreasingTimeOrder = new IncreasingTimeOrder();

	private static final boolean WAKEUP_STATS = true;
	private static final boolean HOWARD_POLICY = false;
	private static final boolean OBSERVATION_APP_BEHAVIOR = false;
	private static final boolean FIXED_INTERVAL = false;
	private static final long FIXED_INTERVAL_LENGTH = 60000;
	private static final boolean EXTREME_CASE = false;
	private static final boolean DISABLE_GOOGLE_LOCATION_ALARM = true;
	private static final int LOOP_UPBOUND = 1000;

	private static final String[][] OFFLINE_SUPPORT = {
		{"ALARM_ACTION(", "900000", "1"},
		{"com.tencent.mm.TrafficStatsReceiver", "300000", "0"},
		{"ComponentInfo{com.tencent.mm/com.tencent.mm.booter.MMReceivers$AlarmReceiver}", "900000", "0"},
		{"com.whatsapp.alarm.CLIENT_PING_TIMEOUT", "240000", "0"},
		{"com.whatsapp.messaging.MessageService.CLIENT_PINGER_ACTION", "240000", "1"},
		{"jp.naver.line.android.legy.SpdyHeartbeatChecker.sendPing", "200000", "1"},
		{"jp.naver.line.android.legy.SpdyHeartbeatChecker.check", "200000", "0"},
		{"com.facebook.push.mqtt.keepalive.KeepaliveManager.ACTION_INEXACT_ALARM.com.facebook.katana", "900000", "1"},
		{"com.facebook.common.executors.WakingExecutorService.ACTION_ALARM.com.facebook.katana","-1", "1"},
		{"on_poll_alarm_evComponentInfo{com.twitter.android/com.twitter.library.platform.TwitterDataSyncService}", "3600000", "1"},
		{"com.gau.go.launcherex.gowidget.weatherwidget.ACTION_AUTO_UPDATE", "3000000", "1"},
		{"io.wecloud.message.action.METHOD", "540000", "1"},
		{"com.viber.voip.action.KEEP_ALIVE_RECEIVE", "600000", "1"},
		{"com.sina.heartbeat.action", "300000", "1"},
		{"com.facebook.push.mqtt.keepalive.b.ACTION_INEXACT_ALARM.com.facebook.orca", "900000", "1"},
		{"com.facebook.common.executors.cv.ACTION_ALARM.com.facebook.orca", "-1", "1"},
		{"ComponentInfo{org.cwb/org.cwb.WidgetUpdateService}", "300000", "0"},
		{"ComponentInfo{com.kakao.talk/com.kakao.talk.service.MessengerService}", "600000", "1"},
		{"com.xiaomi.push.PING_TIMER", "600000", "1"},
		{"ComponentInfo{com.wantoto.gomaji2/com.littlefluffytoys.littlefluffylocationlibrary.LocationBroadcastService}", "900000", "0"},
		{"com.nhn.nni.intent.REGISTER", "202000", "1"},
		{"AlarmTaskSchedule.com.sds.android.ttpod", "360000", "1"},
	};
	
	private static final String EXP_APP_ALARM = "ComponentInfo{com.rakesh.alarmmanagerexample2/com.rakesh.alarmmanagerexample.AlarmManagerBroadcastReceiver}";
	
	private final Context mContext;

	private final LocalLog mLog = new LocalLog(TAG);

	private Object mLock = new Object();

	private int mDescriptor;
	private long mNextWakeup;
	private long mNextNonWakeup;
	private int mBroadcastRefCount = 0;
	private PowerManager.WakeLock mWakeLock;
	private ArrayList<InFlight> mInFlight = new ArrayList<InFlight>();
	private final AlarmThread mWaitThread = new AlarmThread();
	private final AlarmHandler mHandler = new AlarmHandler();
	private ClockReceiver mClockReceiver;
	private UninstallReceiver mUninstallReceiver;
	private final ResultReceiver mResultReceiver = new ResultReceiver();
	private final PendingIntent mTimeTickSender;
	private final PendingIntent mDateChangeSender;

	/*
	 * A record for each wake up event with hardware usage.
	 */
	private static class WakeupEvent {
		public long when;
		public int uid;
		public String id;

		int mType;
		long mDuration;
		long mDelay;
		long mWindow;
		long mRepeatInterval;
		long mRegister2Trigger;
		int[] mHardwareUsage = new int[MultiResourceManager.NUM_HARDWARE];
		int mNetworkRec = 0, mNetworkSnd = 0;

		long mLastFocus;

		public WakeupEvent(long theTime, int theUid, String theId) {
			when = theTime;
			uid = theUid;
			id = new String(theId);
		}

		/*private static String getPackageNameByUid(final int uid) {
		  PackageManager pm = this.getPackageManager();
		  final String[] pkgs = pm.getPackagesForUid(uid);
		  if (pkgs != null && pkgs.length > 0) return pkgs[0];
		  return null; 
		  } */

		public WakeupEvent(long theTime, int theUid, String theId, int type, 
				long duration, long delay, long window, long interval, long register2Trigger, 
				int[] hardwareUsage, long lastFocus, int networkRec, int networkSnd) {
			when = theTime;
			uid = theUid;
			id = new String(theId);
			mType = type;

			mDuration = duration;
			mDelay = delay;
			mWindow = window;
			mRepeatInterval = interval;
			mRegister2Trigger = register2Trigger;
			mHardwareUsage = hardwareUsage;
			mLastFocus = lastFocus;
			mNetworkRec = networkRec;
			mNetworkSnd = networkSnd;
		}

		public String getId(){
			return id;
		}

		public String toString() {
			StringBuffer sb = new StringBuffer(256);

			sb.append("Time: ");
			sb.append(when);

			sb.append(", Duration: ");
			sb.append(mDuration);

			sb.append(", Delay: ");
			sb.append(mDelay);

			sb.append(", Window: ");
			sb.append(mWindow);

			sb.append(", Interval: ");
			sb.append(mRepeatInterval);

			sb.append(", Register2Trigger: ");
			sb.append(mRegister2Trigger);

			sb.append(", Uid: ");
			sb.append(uid);

			sb.append(", Id: ");
			sb.append(id);

			sb.append(", Type: ");
			sb.append(mType);

			sb.append(", NetworkRec: ");
			sb.append(mNetworkRec);

			sb.append(", NetworkSnd: ");
			sb.append(mNetworkSnd);
			
			for(int i = 0; i < mHardwareUsage.length; i++){
				sb.append(", ");
				sb.append(MultiResourceManager.HARDWARE_STRING[i]);				
				sb.append(": ");
				sb.append(mHardwareUsage[i]);
			}

			sb.append(", LastFocus: ");
			sb.append(mLastFocus);

			return sb.toString();
		}
	}

	static class DecisionEvent {
		long rtcTime;
		int numEvent, numOverlappedEvent;

		public DecisionEvent(long _rtcTime, int _numEvent, int _numOverlappedEvent){
			rtcTime = _rtcTime;
			numEvent = _numEvent;
			numOverlappedEvent = _numOverlappedEvent;
		}	

		public String toString() {
			StringBuffer sb = new StringBuffer(256);

			sb.append("Time: ");
			sb.append(rtcTime);

			sb.append(" NumEvent: ");
			sb.append(numEvent);

			sb.append(" NumOverlappedEvent: ");
			sb.append(numOverlappedEvent);

			return sb.toString();
		}
	}

	private final LinkedList<WakeupEvent> mRecentWakeups = new LinkedList<WakeupEvent>();
	private final long RECENT_WAKEUP_PERIOD = 1000L * 60 * 60 * 24; // one day
	private final static HashMap<String, WakeupEvent> mWakeupRecords = new HashMap<String, WakeupEvent>();
	private final LinkedList<DecisionEvent> mRecentDecisionEvent = new LinkedList<DecisionEvent>();    
	private Batch mNextWakeupBatch = null;
	private static final float INTERVAL_RATIO = 0.99f;
	
	static final class Batch {
		long start;     // These endpoints are always in ELAPSED
		long end;
		long intervalStart;
		long intervalEnd;
		long when;
		long deadline;
		int[] hardwareUsage;
		boolean isPerceivable;
		boolean standalone; // certain "batches" don't participate in coalescing
		boolean triggerNextTime;

		final ArrayList<Alarm> alarms = new ArrayList<Alarm>();

		Batch() {
			start = 0;
			end = Long.MAX_VALUE;
			if(HOWARD_POLICY){
				intervalStart = 0;
				intervalEnd = Long.MAX_VALUE;
				when = 0;
				deadline = 0;
				hardwareUsage = null;
				isPerceivable = false;
				triggerNextTime = false;
			}
		}

		Batch(Alarm seed) {
			start = seed.whenElapsed;
			end = seed.maxWhen;
			if(HOWARD_POLICY){
				long[] interval = seed.getInterval();
				intervalStart = interval[0];
				intervalEnd = interval[1];
				isPerceivable = seed.isPerceivable();
				deadline = isPerceivable ? end : intervalEnd;
				when = start;
				hardwareUsage = null;
				addHardwareUsage(seed.getHardwareUsage());
				triggerNextTime = false;
			}
			alarms.add(seed);
		}

		int size() {
			return alarms.size();
		}

		Alarm get(int index) {
			return alarms.get(index);
		}

		boolean canHold(long whenElapsed, long maxWhen) {
			// SIMILARITY.HIGH: Google original policy.
			return (end >= whenElapsed) && (start <= maxWhen);
		}

		boolean canTrigger(long whenElapsed){
			return (when <= whenElapsed && deadline >= whenElapsed);
		}

		void addHardwareUsage(int[] usage){
			if(usage == null)	return;
			int N = MultiResourceManager.NUM_HARDWARE;
			
			if(hardwareUsage == null){
				hardwareUsage = new int[N];
			}

			for(int i = 0; i < N; i++){
				hardwareUsage[i] += usage[i];
			}
		}

		boolean add(Alarm alarm) {
			final boolean hasWakeup = hasWakeups();
			boolean newStart = false;
			// narrows the batch if necessary; presumes that canHold(alarm) is true
			int index = Collections.binarySearch(alarms, alarm, sIncreasingTimeOrder);
			if (index < 0) {
				index = 0 - index - 1;
			}
			alarms.add(index, alarm);
			if (DEBUG_BATCH) {
				Slog.v(TAG, "Adding " + alarm + " to " + this);
			}
			if(HOWARD_POLICY){
				boolean alarmIsP = alarm.isPerceivable();
				if(alarmIsP){
					isPerceivable = alarmIsP;
				}

				addHardwareUsage(alarm.getHardwareUsage());
				
				// Wakeup + non-Wakeup: Don't trim the interval.
				if(hasWakeup && !alarm.isWakeup())	return newStart;
				// non-Wakeup + Wakeup: Use the Wakeup event's interval.
				if(!hasWakeup && alarm.isWakeup()){
					newStart = true;
					
					start = alarm.whenElapsed;
					end = alarm.maxWhen;
					long[] interval = alarm.getInterval();
					intervalStart = interval[0];
					intervalEnd = interval[1];
					deadline = isPerceivable ? end : intervalEnd;
					when = start;
					return newStart;
				}
				// non-Wakeup + non-Wakeup: Trim the interval.
				// Wakeup + Wakeup: Trim the interval.
				if(canHold(alarm.whenElapsed, alarm.maxWhen)){
					long[] window = alarm.getWindow();
					if (window[0] > start) {
						start = window[0];
					}
					if (window[1] < end) {
						end = window[1];
					}
				} else {
					start = -1;
					end = -1;
				}
			
				long[] interval = alarm.getInterval();
				if (interval[0] > intervalStart) {
					intervalStart = interval[0];
				}
				if (interval[1] < intervalEnd) {
					intervalEnd = interval[1];
				}

				deadline = isPerceivable ? end : intervalEnd;
				long originalWhen = when;
				when = isPerceivable ? start : start != -1 ? start : intervalStart;
				if(when != originalWhen)	newStart = true;
			} else {
				if (alarm.whenElapsed > start) {
					start = alarm.whenElapsed;
					newStart = true;
				}
				if (alarm.maxWhen < end) {
					end = alarm.maxWhen;
				}
			}


			if (DEBUG_BATCH) {
				Slog.v(TAG, "    => now " + this);
			}
			return newStart;
		}

		void add(Batch b){
			for(int i = 0; i < b.alarms.size(); i++){
				add(b.alarms.get(i));
			}
		}

		boolean remove(final PendingIntent operation) {
			boolean didRemove = false;
			long newStart = 0;  // recalculate endpoints as we go
			long newEnd = Long.MAX_VALUE;
			for (int i = 0; i < alarms.size(); ) {
				Alarm alarm = alarms.get(i);
				if (alarm.operation.equals(operation)) {
					alarms.remove(i);
					didRemove = true;
				} else {
					if (alarm.whenElapsed > newStart) {
						newStart = alarm.whenElapsed;
					}
					if (alarm.maxWhen < newEnd) {
						newEnd = alarm.maxWhen;
					}
					i++;
				}
			}
			if (didRemove) {
				// commit the new batch bounds
				start = newStart;
				end = newEnd;
			}
			return didRemove;
		}

		boolean remove(final String packageName) {
			boolean didRemove = false;
			long newStart = 0;  // recalculate endpoints as we go
			long newEnd = Long.MAX_VALUE;
			for (int i = 0; i < alarms.size(); ) {
				Alarm alarm = alarms.get(i);
				if (alarm.operation.getTargetPackage().equals(packageName)) {
					alarms.remove(i);
					didRemove = true;
				} else {
					if (alarm.whenElapsed > newStart) {
						newStart = alarm.whenElapsed;
					}
					if (alarm.maxWhen < newEnd) {
						newEnd = alarm.maxWhen;
					}
					i++;
				}
			}
			if (didRemove) {
				// commit the new batch bounds
				start = newStart;
				end = newEnd;
			}
			return didRemove;
		}

		boolean remove(final int userHandle) {
			boolean didRemove = false;
			long newStart = 0;  // recalculate endpoints as we go
			long newEnd = Long.MAX_VALUE;
			for (int i = 0; i < alarms.size(); ) {
				Alarm alarm = alarms.get(i);
				if (UserHandle.getUserId(alarm.operation.getCreatorUid()) == userHandle) {
					alarms.remove(i);
					didRemove = true;
				} else {
					if (alarm.whenElapsed > newStart) {
						newStart = alarm.whenElapsed;
					}
					if (alarm.maxWhen < newEnd) {
						newEnd = alarm.maxWhen;
					}
					i++;
				}
			}
			if (didRemove) {
				// commit the new batch bounds
				start = newStart;
				end = newEnd;
			}
			return didRemove;
		}

		boolean hasPackage(final String packageName) {
			final int N = alarms.size();
			for (int i = 0; i < N; i++) {
				Alarm a = alarms.get(i);
				if (a.operation.getTargetPackage().equals(packageName)) {
					return true;
				}
			}
			return false;
		}

		boolean hasWakeups() {
			final int N = alarms.size();
			for (int i = 0; i < N; i++) {
				Alarm a = alarms.get(i);
				// non-wakeup alarms are types 1 and 3, i.e. have the low bit set
				if ((a.type & TYPE_NONWAKEUP_MASK) == 0) {
					return true;
				}
			}
			return false;
		}

		float getWeight(){
			final int N = alarms.size();
			if(N <= 1){
				return 0.f;
			}

			float weight = 0.f ;
			for (int i = 0; i < N; i++) {
				Alarm a = alarms.get(i);
				weight += MultiResourceManager.getHardwareWeight(a.getHardwareUsage(), a.isWakeup());
			}

			weight -= MultiResourceManager.getHardwareWeight(hardwareUsage, hasWakeups());
			return weight;
		}

		@Override
			public String toString() {
				StringBuilder b = new StringBuilder(40);
				b.append("Batch{"); b.append(Integer.toHexString(this.hashCode()));
				b.append(" num="); b.append(size());
				b.append(" start="); b.append(start);
				b.append(" end="); b.append(end);
				if (HOWARD_POLICY){
					b.append(" intervalStart="); b.append(intervalStart);
					b.append(" intervalEnd="); b.append(intervalEnd);
					b.append(" when="); b.append(when);
					b.append(" deadline="); b.append(deadline);
					b.append(" weight="); b.append(getWeight());
					if(hardwareUsage != null){
						b.append(" hardwareUsage=[");
						for(int i = 0; i < hardwareUsage.length; i++){
							b.append(hardwareUsage[i]);
							b.append(" ");
						}
						b.append("]");
					} else {
						b.append(" hardwareUsage=null");
					}
					if (isPerceivable) {
						b.append(" PERCEIVABLE");
					}
					if (triggerNextTime) {
						b.append(" TRIGGER_NEXT_TIME");
					}
				}
				if (standalone) {
					b.append(" STANDALONE");
				}
				b.append('}');
				return b.toString();
			}

		@Override
			public boolean equals(Object obj) {
				if (obj == null) return false;
				if (obj == this) return true;
				if (!(obj instanceof Batch)) return false;
				Batch o = (Batch) obj;
				if(start != o.start)	return false;
				if(end != o.end)	return false;
				if(size() != o.size())	return false;
				if(alarms != null){
					for(int i = 0; i < size(); i++){
						if(!alarms.get(i).getId().equals(o.alarms.get(i).getId()))	return false;
					}
				}
				return true;
			}

		@Override
			protected Batch clone(){
				Batch b = new Batch();
				b.add(this);
				return b;
			}
	}

	static class BatchTimeOrder implements Comparator<Batch> {
		public int compare(Batch b1, Batch b2) {
			long when1;
			long when2;
			if(HOWARD_POLICY){
				when1 = b1.when;
				when2 = b2.when;
				/* !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11
				when1 = b1.deadline;
				when2 = b2.deadline;
				*/
			}
			else{
				when1 = b1.start;
				when2 = b2.start;
			}
			if (when1 - when2 > 0) {
				return 1;
			}
			if (when1 - when2 < 0) {
				return -1;
			}
			return 0;
		}
	}

	// minimum recurrence period or alarm futurity for us to be able to fuzz it
	private static final long MIN_FUZZABLE_INTERVAL = 10000;
	private static final BatchTimeOrder sBatchOrder = new BatchTimeOrder();
	private final ArrayList<Batch> mAlarmBatches = new ArrayList<Batch>();

	static long convertToElapsed(long when, int type) {
		final boolean isRtc = (type == RTC || type == RTC_WAKEUP);
		if (isRtc) {
			when -= System.currentTimeMillis() - SystemClock.elapsedRealtime();
		}
		return when;
	}

	// Apply a heuristic to { recurrence interval, futurity of the trigger time } to
	// calculate the end of our nominal delivery window for the alarm.
	static long maxTriggerTime(long now, long triggerAtTime, long interval) {
		// Current heuristic: batchable window is 75% of either the recurrence interval
		// [for a periodic alarm] or of the time from now to the desired delivery time,
		// with a minimum delay/interval of 10 seconds, under which we will simply not
		// defer the alarm.
		long futurity = (interval == 0)
			? (triggerAtTime - now)
			: interval;
		if (futurity < MIN_FUZZABLE_INTERVAL) {
			futurity = 0;
		}
		return triggerAtTime + (long)(.75 * futurity);
	}

	// returns true if the batch was added at the head
	static boolean addBatchLocked(ArrayList<Batch> list, Batch newBatch) {
		int index = Collections.binarySearch(list, newBatch, sBatchOrder);
		if (index < 0) {
			index = 0 - index - 1;
		}
		list.add(index, newBatch);
		return (index == 0);
	}

	// Return the index of the matching batch, or -1 if none found.
	int attemptCoalesceLocked(long whenElapsed, long maxWhen, Alarm a) {
		final int N = mAlarmBatches.size();
		if (HOWARD_POLICY){	
			long[] aWindow = a.getWindow();
			long[] aInterval = a.getInterval();
			Batch tmp = new Batch(a);
			MultiResourceManager.SIMILARITY t = MultiResourceManager.SIMILARITY.LOW, 
				h = MultiResourceManager.SIMILARITY.LOW;
			int highestIndex = -1;
			for (int i = 0; i < N; i++) {
				Batch b = mAlarmBatches.get(i);
				if(b.standalone){
					continue;
				}
				MultiResourceManager.SIMILARITY timeSimilarity = MultiResourceManager.getTimeSimilarity(aWindow[0], aWindow[1], aInterval[0], aInterval[1], b.start, b.end, b.intervalStart, b.intervalEnd), hardwareSimilarity = MultiResourceManager.getHardwareSimilarity(a.getHardwareUsage(), b.hardwareUsage);
				if(isMergeable(tmp, b) && MultiResourceManager.isHigherSimilarity(t, h, timeSimilarity, hardwareSimilarity, null, null, null)){
					highestIndex = i;
					t = timeSimilarity;
					h = hardwareSimilarity;
				}			

				/* !!!!!!!!!!!!!!!!!!!!!!!!!!!!!
				if (timeSimilarity == MultiResourceManager.SIMILARITY.HIGH && hardwareSimilarity == MultiResourceManager.SIMILARITY.HIGH) {
					return i;
				}*/
			}

			if (DEBUG_HOWARD) {
				Slog.d(HOWARD_TAG, "");
				Slog.d(HOWARD_TAG, "Original batches:");
				logBatchesLockedHoward(mAlarmBatches);
				Slog.d(HOWARD_TAG, "");
				Slog.d(HOWARD_TAG, "Add batches:");
				logBatchLockedHoward(tmp);
				Slog.d(HOWARD_TAG, "");
				Slog.d(HOWARD_TAG, "Highest index: " + highestIndex);
			}
	
			return highestIndex;
		} else {
			for (int i = 0; i < N; i++) {
				Batch b = mAlarmBatches.get(i);
				if (!b.standalone && b.canHold(whenElapsed, maxWhen)) {
					return i;
				}
			}
		}
		return -1;
	}

	// The RTC clock has moved arbitrarily, so we need to recalculate all the batching
	void rebatchAllAlarms() {
		synchronized (mLock) {
			rebatchAllAlarmsLocked(true);
		}
	}

	void rebatchAllAlarmsLocked(boolean doValidate) {
		ArrayList<Batch> oldSet = (ArrayList<Batch>) mAlarmBatches.clone();
		mAlarmBatches.clear();
		final long nowElapsed = SystemClock.elapsedRealtime();
		final int oldBatches = oldSet.size();
		for (int batchNum = 0; batchNum < oldBatches; batchNum++) {
			Batch batch = oldSet.get(batchNum);
			final int N = batch.size();
			for (int i = 0; i < N; i++) {
				Alarm a = batch.get(i);
				long whenElapsed = convertToElapsed(a.when, a.type);
				final long maxElapsed;
				if (a.whenElapsed == a.maxWhen) {
					// Exact
					maxElapsed = whenElapsed;
				} else {
					// Not exact.  Preserve any explicit window, otherwise recalculate
					// the window based on the alarm's new futurity.  Note that this
					// reflects a policy of preferring timely to deferred delivery.
					maxElapsed = (a.windowLength > 0)
						? (whenElapsed + a.windowLength)
						: maxTriggerTime(nowElapsed, whenElapsed, a.repeatInterval);
				}
				setImplLocked(a.type, a.when, whenElapsed, a.windowLength, maxElapsed,
						a.repeatInterval, a.operation, batch.standalone, doValidate, a.workSource);
			}
		}
	}

	private static final class InFlight extends Intent {
		final PendingIntent mPendingIntent;
		final WorkSource mWorkSource;
		final Pair<String, ComponentName> mTarget;
		final BroadcastStats mBroadcastStats;
		final FilterStats mFilterStats;

		InFlight(AlarmManagerService service, PendingIntent pendingIntent, WorkSource workSource) {
			mPendingIntent = pendingIntent;
			mWorkSource = workSource;
			Intent intent = pendingIntent.getIntent();
			mTarget = intent != null
				? new Pair<String, ComponentName>(intent.getAction(), intent.getComponent())
				: null;
			mBroadcastStats = service.getStatsLocked(pendingIntent);
			FilterStats fs = mBroadcastStats.filterStats.get(mTarget);
			if (fs == null) {
				fs = new FilterStats(mBroadcastStats, mTarget);
				mBroadcastStats.filterStats.put(mTarget, fs);
			}
			mFilterStats = fs;
		}
	}
	
	private static int getOfflineSupport(String id, int index){
		for(int i = 0; i < OFFLINE_SUPPORT.length; i++){
			if(id.contains(OFFLINE_SUPPORT[i][0])){
				return Integer.parseInt(OFFLINE_SUPPORT[i][index]);
			}
		}
		return 0;
	}

	private static boolean isExpApp(PendingIntent pi){
		if(pi.getIntent() != null && pi.getIntent().getComponent() != null
			&& pi.getIntent().getComponent().toString().equals(EXP_APP_ALARM)){
			int action = Integer.parseInt(pi.getIntent().getAction());
			if(action >= 1 && action <= 35){
				return true;
			}
		}

		return false;
	}

	private static int[] getExpAppHardware(PendingIntent pi){
		int[] ret = new int[MultiResourceManager.NUM_HARDWARE];
		int action = Integer.parseInt(pi.getIntent().getAction());

		ret[(action-1)/5]++;

		return ret;
	} 

	private static final class FilterStats {
		final BroadcastStats mBroadcastStats;
		final Pair<String, ComponentName> mTarget;

		long aggregateTime;
		int count;
		int numWakeup;
		long startTime;
		int nesting;

		// Member variable for recent wake up event log
		int tcpReceive;
		int tcpSend;
		long startRtc;
		long delay;
		long window;
		int type;
		long interval;
		long register2Trigger;
		String id;

		FilterStats(BroadcastStats broadcastStats, Pair<String, ComponentName> target) {
			mBroadcastStats = broadcastStats;
			mTarget = target;
		}

		/**
		 * Read an entry in /proc.
		 */
		private String readProc(String path) {
			String procPath = ("/proc/");

			procPath += path;

			try {
				BufferedReader mounts = new BufferedReader(new FileReader(procPath));
				String line, content = new String();

				while ((line = mounts.readLine()) != null) {
					content += line;
				}

				mounts.close();
				return content;
			}
			catch (FileNotFoundException e) {
				Slog.d(HOWARD_TAG, "Cannot find " + procPath + "...");
				return null;
			}
			catch (IOException e) {
				Slog.d(HOWARD_TAG, "Ran into problems reading " + procPath + "...");
			}
			return null;
		}

		/**
		 * Initialize record before execute the alarm event.
		 */
		public void initialRecord(long nowRtc, long nowELAPSED, Alarm alarm) {
			int uid =  alarm.operation.getCreatorUid();
			id = alarm.getId();
			Slog.v(HOWARD_TAG, "Start Alarm: id: " + alarm.getId());
			startRtc = nowRtc;
			window = alarm.windowLength;
			type = alarm.type;
			delay = (alarm.type == AlarmManager.ELAPSED_REALTIME || alarm.type == AlarmManager.ELAPSED_REALTIME_WAKEUP) ? 
				nowELAPSED - alarm.when : nowRtc - alarm.when;
			interval = alarm.repeatInterval;
			register2Trigger = alarm.register2Trigger;

			String str = new String("uid_stat/" + uid + "/tcp_rcv");
			str = readProc(str);
			tcpReceive = str == null ? 0 : Integer.parseInt(str);

			str = new String("uid_stat/" + uid + "/tcp_snd");
			str = readProc(str);
			tcpSend = str == null ? 0 : Integer.parseInt(str);
		}

		/**
		 * Calculate each variable and add the record to history.
		 */
		public WakeupEvent finishRecord(long stopRtc, PendingIntent pi) {
			int uid = pi.getCreatorUid();

			String str = new String("uid_stat/" + uid + "/tcp_rcv");
			str = readProc(str);
			int tcpR = str == null ? 0 : Integer.parseInt(str);

			str = new String("uid_stat/" + uid + "/tcp_snd");
			str = readProc(str);
			int tcpS = str == null ? 0 : Integer.parseInt(str);

			int[] hardwareUsage = new int[MultiResourceManager.NUM_HARDWARE];
			long lastFocus = 0;

			if(isExpApp(pi)){
				hardwareUsage = getExpAppHardware(pi);
			} else {
				IMultiResourceManagerService mrm = IMultiResourceManagerService.Stub.asInterface(ServiceManager.getService(Context.RESOURCE_MANAGER_SERVICE));

				hardwareUsage[0] = tcpR - tcpReceive + tcpS - tcpSend;
				hardwareUsage[0] += getOfflineSupport(id, 2);
				try {
					for(int i = 1; i < hardwareUsage.length; i++){
						hardwareUsage[i] = 	mrm.getIsGrant(uid, startRtc, stopRtc, i) ? 1 : 0;
					}
					lastFocus = mrm.getLastFocusTime(uid);
				} catch(Exception e) {
					e.printStackTrace();
					return null;
				}
			}

			return new WakeupEvent(startRtc, uid, id, type, stopRtc - startRtc, delay, window, interval, register2Trigger, hardwareUsage, lastFocus, tcpR, tcpS);
		}
	}

	private static final class BroadcastStats {
		final String mPackageName;

		long aggregateTime;
		int count;
		int numWakeup;
		long startTime;
		int nesting;
		final HashMap<Pair<String, ComponentName>, FilterStats> filterStats
			= new HashMap<Pair<String, ComponentName>, FilterStats>();

		BroadcastStats(String packageName) {
			mPackageName = packageName;
		}
	}

	private final HashMap<String, BroadcastStats> mBroadcastStats
		= new HashMap<String, BroadcastStats>();

	public AlarmManagerService(Context context) {
		mContext = context;
		mDescriptor = init();
		mNextWakeup = mNextNonWakeup = 0;

		// We have to set current TimeZone info to kernel
		// because kernel doesn't keep this after reboot
		String tz = SystemProperties.get(TIMEZONE_PROPERTY);
		if (tz != null) {
			setTimeZone(tz);
		}

		PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

		mTimeTickSender = PendingIntent.getBroadcastAsUser(context, 0,
				new Intent(Intent.ACTION_TIME_TICK).addFlags(
					Intent.FLAG_RECEIVER_REGISTERED_ONLY
					| Intent.FLAG_RECEIVER_FOREGROUND), 0,
				UserHandle.ALL);
		Intent intent = new Intent(Intent.ACTION_DATE_CHANGED);
		intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
		mDateChangeSender = PendingIntent.getBroadcastAsUser(context, 0, intent,
				Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT, UserHandle.ALL);

		// now that we have initied the driver schedule the alarm
		mClockReceiver= new ClockReceiver();
		mClockReceiver.scheduleTimeTickEvent();
		mClockReceiver.scheduleDateChangedEvent();
		mUninstallReceiver = new UninstallReceiver();

		if (mDescriptor != -1) {
			mWaitThread.start();
		} else {
			Slog.w(TAG, "Failed to open alarm driver. Falling back to a handler.");
		}
	}

	protected void finalize() throws Throwable {
		try {
			close(mDescriptor);
		} finally {
			super.finalize();
		}
	}

	@Override
		public void set(int type, long triggerAtTime, long windowLength, long interval,
				PendingIntent operation, WorkSource workSource) {
			if (workSource != null) {
				mContext.enforceCallingPermission(
						android.Manifest.permission.UPDATE_DEVICE_STATS,
						"AlarmManager.set");
			}

			set(type, triggerAtTime, windowLength, interval, operation, false, workSource);
		}

	public void set(int type, long triggerAtTime, long windowLength, long interval,
			PendingIntent operation, boolean isStandalone, WorkSource workSource) {
		if (operation == null) {
			Slog.w(TAG, "set/setRepeating ignored because there is no intent");
			return;
		}

		// Sanity check the window length.  This will catch people mistakenly
		// trying to pass an end-of-window timestamp rather than a duration.
		if (windowLength > AlarmManager.INTERVAL_HALF_DAY) {
			Slog.w(TAG, "Window length " + windowLength
					+ "ms suspiciously long; limiting to 1 hour");
			windowLength = AlarmManager.INTERVAL_HOUR;
		}

		if (type < RTC_WAKEUP || type > ELAPSED_REALTIME) {
			throw new IllegalArgumentException("Invalid alarm type " + type);
		}

		if (triggerAtTime < 0) {
			final long who = Binder.getCallingUid();
			final long what = Binder.getCallingPid();
			Slog.w(TAG, "Invalid alarm trigger time! " + triggerAtTime + " from uid=" + who
					+ " pid=" + what);
			triggerAtTime = 0;
		}

		if (DISABLE_GOOGLE_LOCATION_ALARM && operation.getIntent() != null && operation.getIntent().getAction() != null
			 && operation.getIntent().getAction().equals("ALARM_WAKEUP_LOCATOR")) {
			Slog.w(TAG, "Disable google play service location alarm.");
			return;
		}

		final long nowElapsed = SystemClock.elapsedRealtime();
		final long triggerElapsed = convertToElapsed(triggerAtTime, type);
		final long maxElapsed;
		if (windowLength == AlarmManager.WINDOW_EXACT) {
			maxElapsed = triggerElapsed;
		} else if (windowLength < 0) {
			maxElapsed = maxTriggerTime(nowElapsed, triggerElapsed, interval);
		} else {
			maxElapsed = triggerElapsed + windowLength;
		}

		synchronized (mLock) {
			if (DEBUG_BATCH) {
				Slog.v(TAG, "set(" + operation + ") : type=" + type
						+ " triggerAtTime=" + triggerAtTime + " win=" + windowLength
						+ " tElapsed=" + triggerElapsed + " maxElapsed=" + maxElapsed
						+ " interval=" + interval + " standalone=" + isStandalone);
			}
			setImplLocked(type, triggerAtTime, triggerElapsed, windowLength, maxElapsed,
					interval, operation, isStandalone, true, workSource);
		}
	}

	private void setImplLocked(int type, long when, long whenElapsed, long windowLength,
			long maxWhen, long interval, PendingIntent operation, boolean isStandalone,
			boolean doValidate, WorkSource workSource) {
		Alarm a = new Alarm(type, when, whenElapsed, windowLength, maxWhen, interval,
				operation, workSource, SystemClock.elapsedRealtime());
		removeLocked(operation);

		int whichBatch = (isStandalone) ? -1 : attemptCoalesceLocked(whenElapsed, maxWhen, a);
		if(OBSERVATION_APP_BEHAVIOR)	whichBatch = -1;
		if (whichBatch < 0) {
			Batch batch = new Batch(a);
			batch.standalone = isStandalone;
			addBatchLocked(mAlarmBatches, batch);
		} else {
			Batch batch = mAlarmBatches.get(whichBatch);
			if (batch.add(a)) {
				// The start time of this batch advanced, so batch ordering may
				// have just been broken.  Move it to where it now belongs.
				mAlarmBatches.remove(whichBatch);
				addBatchLocked(mAlarmBatches, batch);
			}
		}

		if (DEBUG_VALIDATE) {
			if (doValidate && !validateConsistencyLocked()) {
				Slog.v(TAG, "Tipping-point operation: type=" + type + " when=" + when
						+ " when(hex)=" + Long.toHexString(when)
						+ " whenElapsed=" + whenElapsed + " maxWhen=" + maxWhen
						+ " interval=" + interval + " op=" + operation
						+ " standalone=" + isStandalone);
				rebatchAllAlarmsLocked(false);
			}
		}

		rescheduleKernelAlarmsLocked();
	}

	private void logBatchesLocked() {
		ByteArrayOutputStream bs = new ByteArrayOutputStream(2048);
		PrintWriter pw = new PrintWriter(bs);
		final long nowRTC = System.currentTimeMillis();
		final long nowELAPSED = SystemClock.elapsedRealtime();
		final int NZ = mAlarmBatches.size();
		for (int iz = 0; iz < NZ; iz++) {
			Batch bz = mAlarmBatches.get(iz);
			pw.append("Batch "); pw.print(iz); pw.append(": "); pw.println(bz);
			dumpAlarmList(pw, bz.alarms, "  ", nowELAPSED, nowRTC);
			pw.flush();
			Slog.v(TAG, bs.toString());
			bs.reset();
		}
	}

	private boolean validateConsistencyLocked() {
		if (DEBUG_VALIDATE) {
			long lastTime = Long.MIN_VALUE;
			final int N = mAlarmBatches.size();
			for (int i = 0; i < N; i++) {
				Batch b = mAlarmBatches.get(i);
				if (b.start >= lastTime) {
					// duplicate start times are okay because of standalone batches
					lastTime = b.start;
				} else {
					Slog.e(TAG, "CONSISTENCY FAILURE: Batch " + i + " is out of order");
					logBatchesLocked();
					return false;
				}
			}
		}
		return true;
	}

	private Batch findFirstWakeupBatchLocked() {
		final int N = mAlarmBatches.size();
		for (int i = 0; i < N; i++) {
			Batch b = mAlarmBatches.get(i);
			if (b.hasWakeups()) {
				return b;
			}
		}
		return null;
	}

	private void logBatchLockedHoward(Batch b){
		ByteArrayOutputStream bs = new ByteArrayOutputStream(2048);
		PrintWriter pw = new PrintWriter(bs);
		final long nowRTC = System.currentTimeMillis();
		final long nowELAPSED = SystemClock.elapsedRealtime();
		pw.append("Batch "); pw.print("0"); pw.append(": "); pw.println(b);
		dumpAlarmList(pw, b.alarms, "  ", nowELAPSED, nowRTC);
		pw.flush();
		Slog.d(HOWARD_TAG, bs.toString());
		bs.reset();
	}

	private void logBatchesLockedHoward(ArrayList<Batch> batches){
		ByteArrayOutputStream bs = new ByteArrayOutputStream(2048);
		PrintWriter pw = new PrintWriter(bs);
		final long nowRTC = System.currentTimeMillis();
		final long nowELAPSED = SystemClock.elapsedRealtime();
		final int NZ = batches.size();
		for (int iz = 0; iz < NZ; iz++) {
			Batch bz = batches.get(iz);
			pw.append("Batch "); pw.print(iz); pw.append(": "); pw.println(bz);
			dumpAlarmList(pw, bz.alarms, "  ", nowELAPSED, nowRTC);
			pw.flush();
			Slog.d(HOWARD_TAG, bs.toString());
			bs.reset();
		}
	}

	private ArrayList<Batch> getOverlappedAlarms(final Batch b){
		ArrayList<Batch> ret = new ArrayList<Batch>();

		final int N = mAlarmBatches.size();	
		for (int i = 0; i < N; i++) {
			Batch a = mAlarmBatches.get(i);
			MultiResourceManager.SIMILARITY time = MultiResourceManager.getTimeSimilarity(a.start, a.end, a.intervalStart, a.intervalEnd, b.start, b.end, b.intervalStart, b.intervalEnd);
			if(time.equals(MultiResourceManager.SIMILARITY.HIGH)){
				ret.add(a);
				continue;
			}

			if(!a.isPerceivable && !b.isPerceivable){
				if(time.equals(MultiResourceManager.SIMILARITY.MID)){
					ret.add(a);
				}
			}
		}

		return ret;
	}

	private ArrayList<Batch> getProcessedAlarms(final Batch firstWakeup, final ArrayList<Batch> ori){
		ArrayList<Batch> ret = ori;

		boolean changeFlag = true;
		int ite = 0;
		
		if (DEBUG_HOWARD) {
			Slog.d(HOWARD_TAG, "");
			Slog.d(HOWARD_TAG, "Original batches:");
			logBatchesLockedHoward(mAlarmBatches);
		}
		while(changeFlag){
			changeFlag = false;
			ite++;
			if(ite > LOOP_UPBOUND)	break;

			if (DEBUG_HOWARD) {
				Slog.d(HOWARD_TAG, "");
				Slog.d(HOWARD_TAG, ite + " Processed Alarms:");
				logBatchesLockedHoward(ret);
			}
				
			for(int n = 0; n < ret.size(); n++){
				Batch b = ret.get(n);
				MultiResourceManager.SIMILARITY time = MultiResourceManager.getTimeSimilarity(firstWakeup.start, firstWakeup.end, firstWakeup.intervalStart, firstWakeup.intervalEnd, b.start, b.end, b.intervalStart, b.intervalEnd);
				MultiResourceManager.SIMILARITY hardware = MultiResourceManager.getHardwareSimilarity(firstWakeup.hardwareUsage, b.hardwareUsage);
			
				if( (time == MultiResourceManager.SIMILARITY.HIGH && hardware == MultiResourceManager.SIMILARITY.HIGH) ||
					(time == MultiResourceManager.SIMILARITY.HIGH && b.hardwareUsage == null) ){
					continue;
				}

				final int N = mAlarmBatches.size();	
				boolean higherFlag = false;
				for (int i = 0; i < N; i++) {
					Batch a = mAlarmBatches.get(i);		
					if(b.equals(a))	continue;
					if(ret.contains(a))	continue;
					MultiResourceManager.SIMILARITY t = MultiResourceManager.getTimeSimilarity(a.start, a.end, a.intervalStart, a.intervalEnd, b.start, b.end, b.intervalStart, b.intervalEnd);
					MultiResourceManager.SIMILARITY h = MultiResourceManager.getHardwareSimilarity(a.hardwareUsage, b.hardwareUsage);
					
					if (DEBUG_HOWARD) {
						Slog.d(HOWARD_TAG, "Batch b: " + b + ". Batch a: " + a);
						Slog.d(HOWARD_TAG, "Time Similarity: " + t + ". Hardware Similarity: " + h);
					}
	
					if(MultiResourceManager.isHigherSimilarity(time, hardware, t, h, firstWakeup.hardwareUsage, b.hardwareUsage, a.hardwareUsage)){
						Slog.d(HOWARD_TAG, "*Higher*Original Time: " + time + ". Original Hardware: " + hardware);
						higherFlag = true;
						changeFlag = true;
						n--;
						break;
					}
				}
		
				if(higherFlag)	ret.remove(b);
			}

		}
		
		return ret;
	}

	private ArrayList<Batch> cloneList(final ArrayList<Batch> batches){
		ArrayList<Batch> ret = new ArrayList<Batch>(batches.size());
		
		final int N = batches.size();
		for(int i = 0; i < N; i++){
			ret.add(batches.get(i).clone());
		}
		return ret;
	}

	/**
	 * Check the two batches can merge or not.
	 */ 
	private boolean isMergeable(final Batch a, final Batch b){
		if(a.standalone || b.standalone){
			return false;
		}
		MultiResourceManager.SIMILARITY time = MultiResourceManager.getTimeSimilarity(a.start, a.end, a.intervalStart, a.intervalEnd, b.start, b.end, b.intervalStart, b.intervalEnd);
		MultiResourceManager.SIMILARITY hardware = MultiResourceManager.getHardwareSimilarity(a.hardwareUsage, b.hardwareUsage);

		if(time.equals(MultiResourceManager.SIMILARITY.HIGH) && hardware.equals(MultiResourceManager.SIMILARITY.HIGH)){
			// <H, H> case.
			return true;
		}
		if(time.equals(MultiResourceManager.SIMILARITY.HIGH) && hardware.equals(MultiResourceManager.SIMILARITY.MID)){
			// <H, M> case.
			return true;
		}
		if(time.equals(MultiResourceManager.SIMILARITY.HIGH) && hardware.equals(MultiResourceManager.SIMILARITY.LOW)){
			// <H, L> case.
			return true;
		}

		if(!a.isPerceivable && !b.isPerceivable){
			if(time.equals(MultiResourceManager.SIMILARITY.MID) && hardware.equals(MultiResourceManager.SIMILARITY.HIGH)){
				// <M, H> case.
				return true;
			}
			if(time.equals(MultiResourceManager.SIMILARITY.MID) && hardware.equals(MultiResourceManager.SIMILARITY.MID)){
				// <M, M> case.
				return true;
			}
		} 

		return false;
	}

	/**
	 * Find the maximum connected component and return the connected component.
	 */
	private Batch findMaximumConnectedBatch(){
		final Batch firstWakeup = findFirstWakeupBatchLocked();
		if(firstWakeup == null){
			// Doesn't have wakeup alarms.
			return null;
		}

		ArrayList<Batch> overlappedAlarmsOri = getOverlappedAlarms(firstWakeup);
		if (DEBUG_HOWARD) {
			Slog.d(HOWARD_TAG, "");
			Slog.d(HOWARD_TAG, "Overlapped batches:");
			logBatchesLockedHoward(overlappedAlarmsOri);
		}	
		//overlappedAlarmsOri = getProcessedAlarms(firstWakeup, overlappedAlarmsOri);
		ArrayList<Batch> overlappedAlarms = cloneList(overlappedAlarmsOri);
		ArrayList<Batch> connectedComponents = new ArrayList<Batch>();
		ArrayList<ArrayList<Integer>> connectedBatchNumber = new ArrayList<ArrayList<Integer>>();

		if (DEBUG_HOWARD) {
			//Slog.d(HOWARD_TAG, "");
			//Slog.d(HOWARD_TAG, "Original batches:");
			//logBatchesLockedHoward(mAlarmBatches);
			Slog.d(HOWARD_TAG, "");
			Slog.d(HOWARD_TAG, "First wakeup batch:");
			logBatchLockedHoward(firstWakeup);
			Slog.d(HOWARD_TAG, "");
			Slog.d(HOWARD_TAG, "Processed batches:");
			logBatchesLockedHoward(overlappedAlarms);
		}

		// Connect the all mergeable nodes.
		int size = overlappedAlarms.size();
		for(int i = 0; i < size; i++){
			Batch a = overlappedAlarms.get(i);

			int mergeIndex = -1;
			if(!a.standalone){
				int connectedSize = connectedComponents.size();
				for(int j = 0; j < connectedSize; j++){
					Batch b = connectedComponents.get(j);

					if(isMergeable(a, b)){
						mergeIndex = j;
						break;
					}
				}
			}

			if(mergeIndex != -1){
				// Find a mergeable event.
				connectedComponents.get(mergeIndex).add(a);
				connectedBatchNumber.get(mergeIndex).add(i);
			} else {
				connectedComponents.add(a);
				connectedBatchNumber.add(new ArrayList<Integer>());
				connectedBatchNumber.get(connectedBatchNumber.size()-1).add(i);
			}
		}
		
		// Find the connected component with highest weight.
		float highestWeight = -1;
		int highestIndex = -1;
		size = connectedComponents.size();
		for(int i = 0; i < size; i++){
			Batch b = connectedComponents.get(i);
			if(!b.hasWakeups())	continue;
			float weight = b.getWeight(); 
			if(weight > highestWeight){
				highestWeight = weight; 
				highestIndex = i; 
			}
		}

		// Rebatch the original batches.
		ArrayList<Integer> highestBatchNumber = connectedBatchNumber.get(highestIndex);
		Batch b = overlappedAlarmsOri.get(highestBatchNumber.get(0));
		size = highestBatchNumber.size();
		for(int i = 1; i < size; i++){
			b.add(overlappedAlarmsOri.get(highestBatchNumber.get(i)));
		}
		for(int i = 1; i < size; i++){
			mAlarmBatches.remove(overlappedAlarmsOri.get(highestBatchNumber.get(i)));
		}

		if (DEBUG_HOWARD) {
			Slog.d(HOWARD_TAG, "");
			Slog.d(HOWARD_TAG, "Connected batch number:");
			for(int i = 0; i < connectedBatchNumber.size(); i++){
				ArrayList<Integer> cCN = connectedBatchNumber.get(i);
				StringBuffer sb = new StringBuffer();
				if(i == highestIndex){
					sb.append("*Highest Weight*");
				}
				sb.append("Connected component ");
				sb.append(i);
				sb.append(": ");
				for(int j = 0; j < cCN.size(); j++){
					sb.append(cCN.get(j));
					sb.append(" ");
				}
				Slog.d(HOWARD_TAG, sb.toString());
			}

			Slog.d(HOWARD_TAG, "");
			Slog.d(HOWARD_TAG, "Connected components:");
			logBatchesLockedHoward(connectedComponents);
			
			//Slog.d(HOWARD_TAG, "");
			//Slog.d(HOWARD_TAG, "Rebatch batches:");
			//logBatchesLockedHoward(mAlarmBatches);
		}

		return b;
	}

	private void rescheduleKernelAlarmsLocked() {
		if(HOWARD_POLICY){	
			// Schedule the next upcoming wakeup alarm.  If there is a deliverable batch
			// prior to that which contains no wakeups, we schedule that as well.
			if (mAlarmBatches.size() > 0) {
				final Batch firstWakeup = findFirstWakeupBatchLocked();
				final Batch firstBatch = mAlarmBatches.get(0);
				if (firstWakeup != null && mNextWakeup != firstWakeup.when) {
					mNextWakeup = firstWakeup.when;
					setLocked(ELAPSED_REALTIME_WAKEUP, firstWakeup.when);
					if (DEBUG_HOWARD_LEVEL2){
						Slog.d(HOWARD_TAG, "Next wake up batch: " + firstWakeup.when);
						logBatchLockedHoward(firstWakeup);
					}
				}
				if (firstBatch != firstWakeup && mNextNonWakeup != firstBatch.when) {
					mNextNonWakeup = firstBatch.when;
					setLocked(ELAPSED_REALTIME, firstBatch.when);
				}
			}
			/* !!!!!!!!!!!!!!!!!!!!!
			if (mAlarmBatches.size() > 0) {	
				final Batch firstWakeup = findMaximumConnectedBatch();
				final Batch firstBatch = mAlarmBatches.get(0);
				if (firstWakeup != null && mNextWakeup != firstWakeup.when) {
					mNextWakeup = firstWakeup.when;
					if(mNextWakeupBatch != null){
						mNextWakeupBatch.triggerNextTime = false;
					}
					mNextWakeupBatch = firstWakeup;
					mNextWakeupBatch.triggerNextTime = true;
					setLocked(ELAPSED_REALTIME_WAKEUP, firstWakeup.when);
					if (DEBUG_HOWARD_LEVEL2){
						Slog.d(HOWARD_TAG, "Next wake up batch: " + firstWakeup.when);
						logBatchLockedHoward(mNextWakeupBatch);
					}
				}
				if (firstBatch != firstWakeup && mNextNonWakeup != firstBatch.when) {
					mNextNonWakeup = firstBatch.when;
					setLocked(ELAPSED_REALTIME, firstBatch.when);
					if (DEBUG_HOWARD_LEVEL2){
						Slog.d(HOWARD_TAG, "Next non-wake up batch: " + firstBatch.when);
						logBatchLockedHoward(firstBatch);
					}
				}
			}*/
		} else {
			// Schedule the next upcoming wakeup alarm.  If there is a deliverable batch
			// prior to that which contains no wakeups, we schedule that as well.
			if (mAlarmBatches.size() > 0) {
				final Batch firstWakeup = findFirstWakeupBatchLocked();
				final Batch firstBatch = mAlarmBatches.get(0);
				if (firstWakeup != null && mNextWakeup != firstWakeup.start) {
					mNextWakeup = firstWakeup.start;
					setLocked(ELAPSED_REALTIME_WAKEUP, firstWakeup.start);
				}
				if (firstBatch != firstWakeup && mNextNonWakeup != firstBatch.start) {
					mNextNonWakeup = firstBatch.start;
					setLocked(ELAPSED_REALTIME, firstBatch.start);
				}
			}
		}
	}

	/**
	 * Feedback from the resource manager.
	 */
	public void setLastGrantHardware(int uid, int hardware){
		int size = mRecentWakeups.size();
		for(int i = size-1; i >= 0; i--){
			WakeupEvent e = mRecentWakeups.get(i);
			if(e.uid == uid){
				e.mHardwareUsage[hardware]++;
				break;
			}
		}
	
		for (Object key : mWakeupRecords.keySet()) {
			WakeupEvent e = mWakeupRecords.get(key);
			if(e.uid == uid){
				e.mHardwareUsage[hardware]++;
				break;
				
			}
		}	
	}

	public void setTime(long millis) {
		mContext.enforceCallingOrSelfPermission(
				"android.permission.SET_TIME",
				"setTime");

		SystemClock.setCurrentTimeMillis(millis);
	}

	public void setTimeZone(String tz) {
		mContext.enforceCallingOrSelfPermission(
				"android.permission.SET_TIME_ZONE",
				"setTimeZone");

		long oldId = Binder.clearCallingIdentity();
		try {
			if (TextUtils.isEmpty(tz)) return;
			TimeZone zone = TimeZone.getTimeZone(tz);
			// Prevent reentrant calls from stepping on each other when writing
			// the time zone property
			boolean timeZoneWasChanged = false;
			synchronized (this) {
				String current = SystemProperties.get(TIMEZONE_PROPERTY);
				if (current == null || !current.equals(zone.getID())) {
					if (localLOGV) {
						Slog.v(TAG, "timezone changed: " + current + ", new=" + zone.getID());
					}
					timeZoneWasChanged = true;
					SystemProperties.set(TIMEZONE_PROPERTY, zone.getID());
				}

				// Update the kernel timezone information
				// Kernel tracks time offsets as 'minutes west of GMT'
				int gmtOffset = zone.getOffset(System.currentTimeMillis());
				setKernelTimezone(mDescriptor, -(gmtOffset / 60000));
			}

			TimeZone.setDefault(null);

			if (timeZoneWasChanged) {
				Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
				intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
				intent.putExtra("time-zone", zone.getID());
				mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
			}
		} finally {
			Binder.restoreCallingIdentity(oldId);
		}
	}

	public void remove(PendingIntent operation) {
		if (operation == null) {
			return;
		}
		synchronized (mLock) {
			removeLocked(operation);
		}
	}

	public void removeLocked(PendingIntent operation) {
		boolean didRemove = false;
		for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
			Batch b = mAlarmBatches.get(i);
			didRemove |= b.remove(operation);
			if (b.size() == 0) {
				mAlarmBatches.remove(i);
			}
		}

		if (didRemove) {
			if (DEBUG_BATCH) {
				Slog.v(TAG, "remove(operation) changed bounds; rebatching");
			}
			rebatchAllAlarmsLocked(true);
			rescheduleKernelAlarmsLocked();
		}
	}

	public void removeLocked(String packageName) {
		boolean didRemove = false;
		for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
			Batch b = mAlarmBatches.get(i);
			didRemove |= b.remove(packageName);
			if (b.size() == 0) {
				mAlarmBatches.remove(i);
			}
		}

		if (didRemove) {
			if (DEBUG_BATCH) {
				Slog.v(TAG, "remove(package) changed bounds; rebatching");
			}
			rebatchAllAlarmsLocked(true);
			rescheduleKernelAlarmsLocked();
		}
	}

	public void removeUserLocked(int userHandle) {
		boolean didRemove = false;
		for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
			Batch b = mAlarmBatches.get(i);
			didRemove |= b.remove(userHandle);
			if (b.size() == 0) {
				mAlarmBatches.remove(i);
			}
		}

		if (didRemove) {
			if (DEBUG_BATCH) {
				Slog.v(TAG, "remove(user) changed bounds; rebatching");
			}
			rebatchAllAlarmsLocked(true);
			rescheduleKernelAlarmsLocked();
		}
	}

	public boolean lookForPackageLocked(String packageName) {
		for (int i = 0; i < mAlarmBatches.size(); i++) {
			Batch b = mAlarmBatches.get(i);
			if (b.hasPackage(packageName)) {
				return true;
			}
		}
		return false;
	}

	private void setLocked(int type, long when)
	{
		if (mDescriptor != -1)
		{
			if(FIXED_INTERVAL){
				final long nowELAPSED = SystemClock.elapsedRealtime();
				when = nowELAPSED + FIXED_INTERVAL_LENGTH;	
			}

			// The kernel never triggers alarms with negative wakeup times
			// so we ensure they are positive.
			long alarmSeconds, alarmNanoseconds;
			if (when < 0) {
				alarmSeconds = 0;
				alarmNanoseconds = 0;
			} else {
				alarmSeconds = when / 1000;
				alarmNanoseconds = (when % 1000) * 1000 * 1000;
			}

			if(EXTREME_CASE){
				alarmSeconds += 86400*30;
				alarmNanoseconds += 86400*30*1000*1000*1000;
			} 

			set(mDescriptor, type, alarmSeconds, alarmNanoseconds);
		}
		else
		{
			Message msg = Message.obtain();
			msg.what = ALARM_EVENT;

			mHandler.removeMessages(ALARM_EVENT);
			mHandler.sendMessageAtTime(msg, when);
		}
	}

	@Override
		protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
			if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
					!= PackageManager.PERMISSION_GRANTED) {
				pw.println("Permission Denial: can't dump AlarmManager from from pid="
						+ Binder.getCallingPid()
						+ ", uid=" + Binder.getCallingUid());
				return;
			}

			synchronized (mLock) {
				pw.println("Current Alarm Manager state:");
				final long nowRTC = System.currentTimeMillis();
				final long nowELAPSED = SystemClock.elapsedRealtime();
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

				pw.print("nowRTC="); pw.print(nowRTC);
				pw.print("="); pw.print(sdf.format(new Date(nowRTC)));
				pw.print(" nowELAPSED="); pw.println(nowELAPSED);

				long nextWakeupRTC = mNextWakeup + (nowRTC - nowELAPSED);
				long nextNonWakeupRTC = mNextNonWakeup + (nowRTC - nowELAPSED);
				pw.print("Next alarm: "); pw.print(mNextNonWakeup);
				pw.print(" = "); pw.println(sdf.format(new Date(nextNonWakeupRTC)));
				pw.print("Next wakeup: "); pw.print(mNextWakeup);
				pw.print(" = "); pw.println(sdf.format(new Date(nextWakeupRTC)));

				if (mAlarmBatches.size() > 0) {
					pw.println();
					pw.print("Pending alarm batches: ");
					pw.println(mAlarmBatches.size());
					for (Batch b : mAlarmBatches) {
						pw.print(b); pw.println(':');
						dumpAlarmList(pw, b.alarms, "  ", nowELAPSED, nowRTC);
					}
				}

				pw.println();
				pw.print("  Broadcast ref count: "); pw.println(mBroadcastRefCount);
				pw.println();

				if (mLog.dump(pw, "  Recent problems", "    ")) {
					pw.println();
				}

				final FilterStats[] topFilters = new FilterStats[10];
				final Comparator<FilterStats> comparator = new Comparator<FilterStats>() {
					@Override
						public int compare(FilterStats lhs, FilterStats rhs) {
							if (lhs.aggregateTime < rhs.aggregateTime) {
								return 1;
							} else if (lhs.aggregateTime > rhs.aggregateTime) {
								return -1;
							}
							return 0;
						}
				};
				int len = 0;
				for (Map.Entry<String, BroadcastStats> be : mBroadcastStats.entrySet()) {
					BroadcastStats bs = be.getValue();
					for (Map.Entry<Pair<String, ComponentName>, FilterStats> fe
							: bs.filterStats.entrySet()) {
						FilterStats fs = fe.getValue();
						int pos = len > 0
							? Arrays.binarySearch(topFilters, 0, len, fs, comparator) : 0;
						if (pos < 0) {
							pos = -pos - 1;
						}
						if (pos < topFilters.length) {
							int copylen = topFilters.length - pos - 1;
							if (copylen > 0) {
								System.arraycopy(topFilters, pos, topFilters, pos+1, copylen);
							}
							topFilters[pos] = fs;
							if (len < topFilters.length) {
								len++;
							}
						}
					}
				}
				if (len > 0) {
					pw.println("  Top Alarms:");
					for (int i=0; i<len; i++) {
						FilterStats fs = topFilters[i];
						pw.print("    ");
						if (fs.nesting > 0) pw.print("*ACTIVE* ");
						TimeUtils.formatDuration(fs.aggregateTime, pw);
						pw.print(" running, "); pw.print(fs.numWakeup);
						pw.print(" wakeups, "); pw.print(fs.count);
						pw.print(" alarms: "); pw.print(fs.mBroadcastStats.mPackageName);
						pw.println();
						pw.print("      ");
						if (fs.mTarget.first != null) {
							pw.print(" act="); pw.print(fs.mTarget.first);
						}
						if (fs.mTarget.second != null) {
							pw.print(" cmp="); pw.print(fs.mTarget.second.toShortString());
						}
						pw.println();
					}
				}

				pw.println(" ");
				pw.println("  Alarm Stats:");
				final ArrayList<FilterStats> tmpFilters = new ArrayList<FilterStats>();
				for (Map.Entry<String, BroadcastStats> be : mBroadcastStats.entrySet()) {
					BroadcastStats bs = be.getValue();
					pw.print("  ");
					if (bs.nesting > 0) pw.print("*ACTIVE* ");
					pw.print(be.getKey());
					pw.print(" "); TimeUtils.formatDuration(bs.aggregateTime, pw);
					pw.print(" running, "); pw.print(bs.numWakeup);
					pw.println(" wakeups:");
					tmpFilters.clear();
					for (Map.Entry<Pair<String, ComponentName>, FilterStats> fe
							: bs.filterStats.entrySet()) {
						tmpFilters.add(fe.getValue());
					}
					Collections.sort(tmpFilters, comparator);
					for (int i=0; i<tmpFilters.size(); i++) {
						FilterStats fs = tmpFilters.get(i);
						pw.print("    ");
						if (fs.nesting > 0) pw.print("*ACTIVE* ");
						TimeUtils.formatDuration(fs.aggregateTime, pw);
						pw.print(" "); pw.print(fs.numWakeup);
						pw.print(" wakes " ); pw.print(fs.count);
						pw.print(" alarms:");
						if (fs.mTarget.first != null) {
							pw.print(" act="); pw.print(fs.mTarget.first);
						}
						if (fs.mTarget.second != null) {
							pw.print(" cmp="); pw.print(fs.mTarget.second.toShortString());
						}
						pw.println();
					}
				}

				if (WAKEUP_STATS) {
					pw.println();
					pw.println("  Recent Wakeup History:");
					long last = -1;
					for (WakeupEvent event : mRecentWakeups) {
						pw.println(event.toString());
						//                    pw.print("    "); pw.print(sdf.format(new Date(event.when)));
						//                    pw.print('|');
						//                    if (last < 0) {
						//                        pw.print('0');
						//                    } else {
						//                        pw.print(event.when - last);
						//                    }
						//                    last = event.when;
						//                    pw.print('|'); pw.print(event.uid);
						//                    pw.print('|'); pw.print(event.action);
						//                    pw.println();
					}
					pw.println();
					pw.println("  Recent Decision History:");
					for (DecisionEvent event : mRecentDecisionEvent) {
						pw.println(event.toString());
					}
				}
			}
		}

	private static final void dumpAlarmList(PrintWriter pw, ArrayList<Alarm> list,
			String prefix, String label, long now) {
		for (int i=list.size()-1; i>=0; i--) {
			Alarm a = list.get(i);
			pw.print(prefix); pw.print(label); pw.print(" #"); pw.print(i);
			pw.print(": "); pw.println(a);
			a.dump(pw, prefix + "  ", now);
		}
	}

	private static final String labelForType(int type) {
		switch (type) {
			case RTC: return "RTC";
			case RTC_WAKEUP : return "RTC_WAKEUP";
			case ELAPSED_REALTIME : return "ELAPSED";
			case ELAPSED_REALTIME_WAKEUP: return "ELAPSED_WAKEUP";
			default:
						      break;
		}
		return "--unknown--";
	}

	private static final void dumpAlarmList(PrintWriter pw, ArrayList<Alarm> list,
			String prefix, long nowELAPSED, long nowRTC) {
		for (int i=list.size()-1; i>=0; i--) {
			Alarm a = list.get(i);
			final String label = labelForType(a.type);
			long now = (a.type <= RTC) ? nowRTC : nowELAPSED;
			pw.print(prefix); pw.print(label); pw.print(" #"); pw.print(i);
			pw.print(": "); pw.println(a);
			a.dump(pw, prefix + "  ", now);
		}
	}

	private native int init();
	private native void close(int fd);
	private native void set(int fd, int type, long seconds, long nanoseconds);
	private native int waitForAlarm(int fd);
	private native int setKernelTimezone(int fd, int minuteswest);

	private void triggerAlarmsLockedHoward(ArrayList<Alarm> triggerList, long nowELAPSED, long nowRTC) {
		// batches are temporally sorted, so we need only pull from the
		// start of the list until we either empty it or hit a batch
		// that is not yet deliverable
		while (mAlarmBatches.size() > 0) {
			Batch batch = mAlarmBatches.get(0);

			if (batch.when > nowELAPSED) {
				// Everything else is scheduled for the future
				break;
			}

			if(DEBUG_HOWARD || DEBUG_HOWARD_LEVEL2){
				Slog.d(HOWARD_TAG, "Deliver the batch: start= " + batch.when + " nowELAPSED= " + nowELAPSED);
				logBatchLockedHoward(batch);
			}

			// We will (re)schedule some alarms now; don't let that interfere
			// with delivery of this current batch
			mAlarmBatches.remove(0);

			final int N = batch.size();
			for (int i = 0; i < N; i++) {
				Alarm alarm = batch.get(i);
				alarm.count = 1;
				triggerList.add(alarm);

				// Recurring alarms may have passed several alarm intervals while the
				// phone was asleep or off, so pass a trigger count when sending them.
				if (alarm.repeatInterval > 0) {
					// this adjustment will be zero if we're late by
					// less than one full repeat interval
					alarm.count += (nowELAPSED - alarm.whenElapsed) / alarm.repeatInterval;

					// Also schedule its next recurrence
					final long delta = alarm.count * alarm.repeatInterval;
					final long nextElapsed = alarm.whenElapsed + delta;
					setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength,
							maxTriggerTime(nowELAPSED, nextElapsed, alarm.repeatInterval),
							alarm.repeatInterval, alarm.operation, batch.standalone, true,
							alarm.workSource);
				}

			}
		}
		/* !!!!!!!!!!!!!!!!!!!!!!!!!!!1
		final Batch b = mNextWakeupBatch;
		
		if(b.canTrigger(nowELAPSED)){
			mAlarmBatches.remove(b);
			final int S = b.size();
			if(DEBUG_HOWARD || DEBUG_HOWARD_LEVEL2){
				Slog.d(HOWARD_TAG, "Deliver the batch:");
				logBatchLockedHoward(b);
			}
			for (int i = 0; i < S; i++) {
				Alarm alarm = b.get(i);
				alarm.count = 1;
				triggerList.add(alarm);
	
				// Recurring alarms may have passed several alarm intervals while the
				// phone was asleep or off, so pass a trigger count when sending them.
				if (alarm.repeatInterval > 0) {
					// this adjustment will be zero if we're late by
					// less than one full repeat interval
					alarm.count += (nowELAPSED - alarm.whenElapsed) / alarm.repeatInterval;
			
					// Also schedule its next recurrence
					final long delta = alarm.count * alarm.repeatInterval;
					final long nextElapsed = alarm.whenElapsed + delta;
					setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength,
							maxTriggerTime(nowELAPSED, nextElapsed, alarm.repeatInterval),
							alarm.repeatInterval, alarm.operation, b.standalone, true,
							alarm.workSource);
				}
			}
		}

		while (mAlarmBatches.size() > 0) {
			Batch batch = mAlarmBatches.get(0);

			if ( batch.deadline > nowELAPSED) {
				break;
			}

			// We will (re)schedule some alarms now; don't let that interfere
			// with delivery of this current batch
			mAlarmBatches.remove(0);
			if(DEBUG_HOWARD || DEBUG_HOWARD_LEVEL2){
				logBatchLockedHoward(batch);
			}

			final int N = batch.size();
			for (int i = 0; i < N; i++) {
				Alarm alarm = batch.get(i);
				alarm.count = 1;
				triggerList.add(alarm);

				// Recurring alarms may have passed several alarm intervals while the
				// phone was asleep or off, so pass a trigger count when sending them.
				if (alarm.repeatInterval > 0) {
					// this adjustment will be zero if we're late by
					// less than one full repeat interval
					alarm.count += (nowELAPSED - alarm.whenElapsed) / alarm.repeatInterval;

					// Also schedule its next recurrence
					final long delta = alarm.count * alarm.repeatInterval;
					final long nextElapsed = alarm.whenElapsed + delta;
					setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength,
							maxTriggerTime(nowELAPSED, nextElapsed, alarm.repeatInterval),
							alarm.repeatInterval, alarm.operation, batch.standalone, true,
							alarm.workSource);
				}

			}
		}*/
	}

	private void triggerAlarmsLocked(ArrayList<Alarm> triggerList, long nowELAPSED, long nowRTC) {
		// batches are temporally sorted, so we need only pull from the
		// start of the list until we either empty it or hit a batch
		// that is not yet deliverable
		while (mAlarmBatches.size() > 0) {
			Batch batch = mAlarmBatches.get(0);

			if (batch.start > nowELAPSED) {
				// Everything else is scheduled for the future
				break;
			}

			// We will (re)schedule some alarms now; don't let that interfere
			// with delivery of this current batch
			mAlarmBatches.remove(0);

			final int N = batch.size();
			for (int i = 0; i < N; i++) {
				Alarm alarm = batch.get(i);
				alarm.count = 1;
				triggerList.add(alarm);

				// Recurring alarms may have passed several alarm intervals while the
				// phone was asleep or off, so pass a trigger count when sending them.
				if (alarm.repeatInterval > 0) {
					// this adjustment will be zero if we're late by
					// less than one full repeat interval
					alarm.count += (nowELAPSED - alarm.whenElapsed) / alarm.repeatInterval;

					// Also schedule its next recurrence
					final long delta = alarm.count * alarm.repeatInterval;
					final long nextElapsed = alarm.whenElapsed + delta;
					setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength,
							maxTriggerTime(nowELAPSED, nextElapsed, alarm.repeatInterval),
							alarm.repeatInterval, alarm.operation, batch.standalone, true,
							alarm.workSource);
				}

			}
		}
	}

	/**
	 * This Comparator sorts Alarms into increasing time order.
	 */
	public static class IncreasingTimeOrder implements Comparator<Alarm> {
		public int compare(Alarm a1, Alarm a2) {
			long when1 = a1.when;
			long when2 = a2.when;
			if (when1 - when2 > 0) {
				return 1;
			}
			if (when1 - when2 < 0) {
				return -1;
			}
			return 0;
		}
	}

	private static class Alarm {
		public int type;
		public int count;
		public long when;
		public long windowLength;
		public long whenElapsed;    // 'when' in the elapsed time base
		public long maxWhen;        // also in the elapsed time base
		public long repeatInterval;
		public PendingIntent operation;
		public WorkSource workSource;

		public long registerElapsed;
		public long register2Trigger;

		public Alarm(int _type, long _when, long _whenElapsed, long _windowLength, long _maxWhen,
				long _interval, PendingIntent _op, WorkSource _ws, long _registerElapsed) {
			type = _type;
			when = _when;
			whenElapsed = _whenElapsed;
			windowLength = _windowLength;
			maxWhen = _maxWhen;
			repeatInterval = _interval;
			operation = _op;
			workSource = _ws;

			registerElapsed = _registerElapsed;
			int offlineInterval = getOfflineSupport(getId(), 1);
			if(offlineInterval > 0){
				register2Trigger = offlineInterval;	
			} else {
				register2Trigger = repeatInterval > 0 ? repeatInterval : Math.max(whenElapsed-registerElapsed, 0);
				if (register2Trigger > AlarmManager.INTERVAL_HOUR) {
					register2Trigger = AlarmManager.INTERVAL_HOUR;
				}
			}
		}

		public String getId(){
			String ret = new String();
			Intent i = operation.getIntent();
			ret += operation.getCreatorUid();

			if(i != null){
				ret += i.getAction() + i.getComponent();
			} else {
				ret += "null";
			}
			return ret;
		}

		public int[] getHardwareUsage(){
		;	WakeupEvent w = mWakeupRecords.get(getId());
			return w != null? w.mHardwareUsage : null;	
		}

		public boolean isPerceivable(){
			int[] hardwareUsage = getHardwareUsage();
			return hardwareUsage != null? MultiResourceManager.isPerceivable(hardwareUsage) : true;
		}

		public boolean isWakeup(){
			if ((type & TYPE_NONWAKEUP_MASK) == 0) {
				return true;
			}
			return false;
		}

		public long[] getWindow(){
			long[] window = new long[2];
			window[0] = whenElapsed;
			window[1] = maxWhen;
			return window;	
		}		

		public long[] getInterval(){
			long[] interval = new long[2];
			if(repeatInterval > 0){
				interval[0] = whenElapsed - (long)(INTERVAL_RATIO*register2Trigger);
				//interval[0] = whenElapsed;
			} else {
				interval[0] = whenElapsed;
			}
			interval[1] = Math.max(whenElapsed + (long)(INTERVAL_RATIO*register2Trigger), maxWhen);
			return interval;	
		}

		@Override
			public String toString()
			{
				StringBuilder sb = new StringBuilder(128);
				sb.append("Alarm{");
				sb.append(Integer.toHexString(System.identityHashCode(this)));
				sb.append(" type ");
				sb.append(type);
				sb.append(" ");
				sb.append(operation.getTargetPackage());
				sb.append('}');
				return sb.toString();
			}

		public void dump(PrintWriter pw, String prefix, long now) {
			pw.print(prefix); pw.print("type="); pw.print(type);
			pw.print(" whenElapsed="); pw.print(whenElapsed);
			pw.print(" when="); TimeUtils.formatDuration(when, now, pw);
			pw.print(" window="); pw.print(windowLength);
			pw.print(" repeatInterval="); pw.print(repeatInterval);
			pw.print(" count="); pw.println(count);
			pw.print(prefix); pw.print("operation="); pw.println(operation);
		}
	}

	void recordWakeupAlarms(ArrayList<Batch> batches, long nowELAPSED, long nowRTC) {
		final int numBatches = batches.size();
		for (int nextBatch = 0; nextBatch < numBatches; nextBatch++) {
			Batch b = batches.get(nextBatch);
			if (b.start > nowELAPSED) {
				break;
			}

			final int numAlarms = b.alarms.size();
			for (int nextAlarm = 0; nextAlarm < numAlarms; nextAlarm++) {
				Alarm a = b.alarms.get(nextAlarm);
				WakeupEvent e = new WakeupEvent(nowRTC,
						a.operation.getCreatorUid(),
						a.getId());
				mRecentWakeups.add(e);
			}
		}
	}

	private class AlarmThread extends Thread
	{
		public AlarmThread()
		{
			super("AlarmManager");
		}

		public void run()
		{
			ArrayList<Alarm> triggerList = new ArrayList<Alarm>();

			while (true)
			{
				int result = waitForAlarm(mDescriptor);

				triggerList.clear();

				if ((result & TIME_CHANGED_MASK) != 0) {
					// Change time-zone, user change time by itself.
					if (DEBUG_BATCH) {
						Slog.v(TAG, "Time changed notification from kernel; rebatching");
					}
					remove(mTimeTickSender);
					rebatchAllAlarms();
					mClockReceiver.scheduleTimeTickEvent();
					Intent intent = new Intent(Intent.ACTION_TIME_CHANGED);
					intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
							| Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
					mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
				}

				synchronized (mLock) {
					final long nowRTC = System.currentTimeMillis();
					final long nowELAPSED = SystemClock.elapsedRealtime();
					if (localLOGV) Slog.v(
							TAG, "Checking for alarms... rtc=" + nowRTC
							+ ", elapsed=" + nowELAPSED);
				
					if(DEBUG_HOWARD_LEVEL2){
						Slog.d(HOWARD_TAG, "Checking for alarms... rtc=" + nowRTC + ", elapsed=" + nowELAPSED);
					}

					//                    if (WAKEUP_STATS) {
					//                        if ((result & IS_WAKEUP_MASK) != 0) {
					//                            long newEarliest = nowRTC - RECENT_WAKEUP_PERIOD;
					//                            int n = 0;
					//                            for (WakeupEvent event : mRecentWakeups) {
					//                                if (event.when > newEarliest) break;
					//                                n++; // number of now-stale entries at the list head
					//                            }
					//                            for (int i = 0; i < n; i++) {
					//                                mRecentWakeups.remove();
					//                            }
					//
					//                            recordWakeupAlarms(mAlarmBatches, nowELAPSED, nowRTC);
					//                        }
					//                    }

					if(HOWARD_POLICY){
						triggerAlarmsLockedHoward(triggerList, nowELAPSED, nowRTC);
						// !!!!!!!!!!!!!!!!!!!!!!!! triggerAlarmsLockedHoward(triggerList, nowELAPSED, nowRTC);
					} else {
						triggerAlarmsLocked(triggerList, nowELAPSED, nowRTC);
					}
					rescheduleKernelAlarmsLocked();

					// now deliver the alarm intents
					for (int i=0; i<triggerList.size(); i++) {
						Alarm alarm = triggerList.get(i);
						try {
							if (localLOGV) Slog.v(TAG, "sending alarm " + alarm);
							alarm.operation.send(mContext, 0,
									mBackgroundIntent.putExtra(
										Intent.EXTRA_ALARM_COUNT, alarm.count),
									mResultReceiver, mHandler);

							// we have an active broadcast so stay awake.
							if (mBroadcastRefCount == 0) {
								setWakelockWorkSource(alarm.operation, alarm.workSource);
								mWakeLock.acquire();
							}
							final InFlight inflight = new InFlight(AlarmManagerService.this,
									alarm.operation, alarm.workSource);
							mInFlight.add(inflight);
							mBroadcastRefCount++;

							final BroadcastStats bs = inflight.mBroadcastStats;
							bs.count++;
							if (bs.nesting == 0) {
								bs.nesting = 1;
								bs.startTime = nowELAPSED;
							} else {
								bs.nesting++;
							}
							final FilterStats fs = inflight.mFilterStats;
							fs.count++;
							if (fs.nesting == 0) {
								fs.nesting = 1;
								fs.startTime = nowELAPSED;
								if(WAKEUP_STATS || HOWARD_POLICY){
									fs.initialRecord(nowRTC, nowELAPSED, alarm);
								}
							} else {
								fs.nesting++;
							}
							if (alarm.type == ELAPSED_REALTIME_WAKEUP
									|| alarm.type == RTC_WAKEUP) {
								bs.numWakeup++;
								fs.numWakeup++;
								ActivityManagerNative.noteWakeupAlarm(
										alarm.operation);
							}
						} catch (PendingIntent.CanceledException e) {
							if (alarm.repeatInterval > 0) {
								// This IntentSender is no longer valid, but this
								// is a repeating alarm, so toss the hoser.
								remove(alarm.operation);
							}
						} catch (RuntimeException e) {
							Slog.w(TAG, "Failure sending alarm.", e);
						}
					}
				}
			}
		}
	}

	/**
	 * Attribute blame for a WakeLock.
	 * @param pi PendingIntent to attribute blame to if ws is null.
	 * @param ws WorkSource to attribute blame.
	 */
	void setWakelockWorkSource(PendingIntent pi, WorkSource ws) {
		try {
			if (ws != null) {
				mWakeLock.setWorkSource(ws);
				return;
			}

			final int uid = ActivityManagerNative.getDefault()
				.getUidForIntentSender(pi.getTarget());
			if (uid >= 0) {
				mWakeLock.setWorkSource(new WorkSource(uid));
				return;
			}
		} catch (Exception e) {
		}

		// Something went wrong; fall back to attributing the lock to the OS
		mWakeLock.setWorkSource(null);
	}

	private class AlarmHandler extends Handler {
		public static final int ALARM_EVENT = 1;
		public static final int MINUTE_CHANGE_EVENT = 2;
		public static final int DATE_CHANGE_EVENT = 3;

		public AlarmHandler() {
		}

		public void handleMessage(Message msg) {
			if (msg.what == ALARM_EVENT) {
				ArrayList<Alarm> triggerList = new ArrayList<Alarm>();
				synchronized (mLock) {
					final long nowRTC = System.currentTimeMillis();
					final long nowELAPSED = SystemClock.elapsedRealtime();
					if(HOWARD_POLICY){
						triggerAlarmsLocked(triggerList, nowELAPSED, nowRTC);
					} else {
						triggerAlarmsLocked(triggerList, nowELAPSED, nowRTC);
					}
				}

				// now trigger the alarms without the lock held
				for (int i=0; i<triggerList.size(); i++) {
					Alarm alarm = triggerList.get(i);
					try {
						alarm.operation.send();
					} catch (PendingIntent.CanceledException e) {
						if (alarm.repeatInterval > 0) {
							// This IntentSender is no longer valid, but this
							// is a repeating alarm, so toss the hoser.
							remove(alarm.operation);
						}
					}
				}
			}
		}
	}

	class ClockReceiver extends BroadcastReceiver {
		public ClockReceiver() {
			IntentFilter filter = new IntentFilter();
			filter.addAction(Intent.ACTION_TIME_TICK);
			filter.addAction(Intent.ACTION_DATE_CHANGED);
			mContext.registerReceiver(this, filter);
		}

		@Override
			public void onReceive(Context context, Intent intent) {
				if (intent.getAction().equals(Intent.ACTION_TIME_TICK)) {
					if (DEBUG_BATCH) {
						Slog.v(TAG, "Received TIME_TICK alarm; rescheduling");
					}
					scheduleTimeTickEvent();
				} else if (intent.getAction().equals(Intent.ACTION_DATE_CHANGED)) {
					// Since the kernel does not keep track of DST, we need to
					// reset the TZ information at the beginning of each day
					// based off of the current Zone gmt offset + userspace tracked
					// daylight savings information.
					TimeZone zone = TimeZone.getTimeZone(SystemProperties.get(TIMEZONE_PROPERTY));
					int gmtOffset = zone.getOffset(System.currentTimeMillis());
					setKernelTimezone(mDescriptor, -(gmtOffset / 60000));
					scheduleDateChangedEvent();
				}
			}

		public void scheduleTimeTickEvent() {
			final long currentTime = System.currentTimeMillis();
			final long nextTime = 60000 * ((currentTime / 60000) + 1);

			// Schedule this event for the amount of time that it would take to get to
			// the top of the next minute.
			final long tickEventDelay = nextTime - currentTime;

			final WorkSource workSource = null; // Let system take blame for time tick events.
			set(ELAPSED_REALTIME, SystemClock.elapsedRealtime() + tickEventDelay, 0,
					0, mTimeTickSender, true, workSource);
		}

		public void scheduleDateChangedEvent() {
			Calendar calendar = Calendar.getInstance();
			calendar.setTimeInMillis(System.currentTimeMillis());
			calendar.set(Calendar.HOUR, 0);
			calendar.set(Calendar.MINUTE, 0);
			calendar.set(Calendar.SECOND, 0);
			calendar.set(Calendar.MILLISECOND, 0);
			calendar.add(Calendar.DAY_OF_MONTH, 1);

			final WorkSource workSource = null; // Let system take blame for date change events.
			set(RTC, calendar.getTimeInMillis(), 0, 0, mDateChangeSender, true, workSource);
		}
	}

	class UninstallReceiver extends BroadcastReceiver {
		public UninstallReceiver() {
			IntentFilter filter = new IntentFilter();
			filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
			filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
			filter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
			filter.addDataScheme("package");
			mContext.registerReceiver(this, filter);
			// Register for events related to sdcard installation.
			IntentFilter sdFilter = new IntentFilter();
			sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
			sdFilter.addAction(Intent.ACTION_USER_STOPPED);
			mContext.registerReceiver(this, sdFilter);
		}

		@Override
			public void onReceive(Context context, Intent intent) {
				synchronized (mLock) {
					String action = intent.getAction();
					String pkgList[] = null;
					if (Intent.ACTION_QUERY_PACKAGE_RESTART.equals(action)) {
						pkgList = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
						for (String packageName : pkgList) {
							if (lookForPackageLocked(packageName)) {
								setResultCode(Activity.RESULT_OK);
								return;
							}
						}
						return;
					} else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
						pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
					} else if (Intent.ACTION_USER_STOPPED.equals(action)) {
						int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
						if (userHandle >= 0) {
							removeUserLocked(userHandle);
						}
					} else {
						if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
								&& intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
							// This package is being updated; don't kill its alarms.
							return;
						}
						Uri data = intent.getData();
						if (data != null) {
							String pkg = data.getSchemeSpecificPart();
							if (pkg != null) {
								pkgList = new String[]{pkg};
							}
						}
					}
					if (pkgList != null && (pkgList.length > 0)) {
						for (String pkg : pkgList) {
							removeLocked(pkg);
							mBroadcastStats.remove(pkg);
						}
					}
				}
			}
	}

	private final BroadcastStats getStatsLocked(PendingIntent pi) {
		String pkg = pi.getTargetPackage();
		BroadcastStats bs = mBroadcastStats.get(pkg);
		if (bs == null) {
			bs = new BroadcastStats(pkg);
			mBroadcastStats.put(pkg, bs);
		}
		return bs;
	}

	class ResultReceiver implements PendingIntent.OnFinished {
		public void onSendFinished(PendingIntent pi, Intent intent, int resultCode,
				String resultData, Bundle resultExtras) {
			synchronized (mLock) {
				InFlight inflight = null;
				for (int i=0; i<mInFlight.size(); i++) {
					if (mInFlight.get(i).mPendingIntent == pi) {
						inflight = mInFlight.remove(i);
						break;
					}
				}
				if (inflight != null) {
					final long nowRTC = System.currentTimeMillis();
					final long nowELAPSED = SystemClock.elapsedRealtime();
					BroadcastStats bs = inflight.mBroadcastStats;
					bs.nesting--;
					if (bs.nesting <= 0) {
						bs.nesting = 0;
						bs.aggregateTime += nowELAPSED - bs.startTime;
					}
					FilterStats fs = inflight.mFilterStats;
					fs.nesting--;
					if (fs.nesting <= 0) {
						fs.nesting = 0;
						fs.aggregateTime += nowELAPSED - fs.startTime;                        
						if (WAKEUP_STATS || HOWARD_POLICY) {
							WakeupEvent e = fs.finishRecord(nowRTC, pi);    

							if(HOWARD_POLICY){
								if(e != null){
									mWakeupRecords.put(e.getId(), e);
								}
							}

							if(WAKEUP_STATS){
								long newEarliest = nowRTC - RECENT_WAKEUP_PERIOD;
								int n = 0;
								for (WakeupEvent event : mRecentWakeups) {
									if (event.when > newEarliest) break;
									n++; // number of now-stale entries at the list head
								}
								for (int i = 0; i < n; i++) {
									mRecentWakeups.remove();
								}

								if(e != null){
									Slog.v(HOWARD_TAG, "Finish Alarm: " + e.toString());
									mRecentWakeups.add(e);
								}
							}
						}
					}
				} else {
					mLog.w("No in-flight alarm for " + pi + " " + intent);
				}
				mBroadcastRefCount--;
				if (mBroadcastRefCount == 0) {
					mWakeLock.release();
					if (mInFlight.size() > 0) {
						mLog.w("Finished all broadcasts with " + mInFlight.size()
								+ " remaining inflights");
						for (int i=0; i<mInFlight.size(); i++) {
							mLog.w("  Remaining #" + i + ": " + mInFlight.get(i));
						}
						mInFlight.clear();
					}
				} else {
					// the next of our alarms is now in flight.  reattribute the wakelock.
					if (mInFlight.size() > 0) {
						InFlight inFlight = mInFlight.get(0);
						setWakelockWorkSource(inFlight.mPendingIntent, inFlight.mWorkSource);
					} else {
						// should never happen
						mLog.w("Alarm wakelock still held but sent queue empty");
						mWakeLock.setWorkSource(null);
					}
				}
			}
		}
	}
}
