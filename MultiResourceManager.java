/*
 *  framework/base/core/java/android/os/MultiResourceManager.java
 */

package android.os;

import android.util.Log;

public class MultiResourceManager {
	private final String TAG = "MultiResourceManager";

	public static final int HARDWARE_DEFAULT = -1;
	public static final int HARDWARE_NETWORK = 0;
	public static final int HARDWARE_VIBRATION = 1;
	public static final int HARDWARE_SOUND = 2;
	public static final int HARDWARE_SCREEN = 3;
	public static final int HARDWARE_AGPS = 4;
	public static final int HARDWARE_GPS = 5;
	public static final int HARDWARE_SENSOR_ACC = 6;
	public static final int NUM_HARDWARE = 7;

	public static final String[] HARDWARE_STRING = {
		"NETWORK", "VIBRATION", "SOUND", "SCREEN",
		"AGPS", "GPS", "SENSOR_ACC"
	};
        
	private IMultiResourceManagerService mService;

    public MultiResourceManager(IMultiResourceManagerService service) {
		mService = service;
	}
}
