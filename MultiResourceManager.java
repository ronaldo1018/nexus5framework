/*
 *  framework/base/core/java/android/os/MultiResourceManager.java
 */

package android.os;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

public class MultiResourceManager {
	private final String TAG = "MultiResourceManager";

	public static enum SIMILARITY {
		HIGH, MID, LOW
	}

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

	private static enum HARDWARE_ENERGY_LEVEL {
		ONLY_CPU(13.5f), NETWORK_WIFI(0.5f), NETWORK_MOBILE(48.5f), VIBRATION(10.5f)
			, SOUND(10.5f), SCREEN(635f), SENSOR_ACC(47.5f), AGPS_WIFI(217f)
			, AGPS_MOBILE(252.5f), GPS(635f);
		private float weight;

		private HARDWARE_ENERGY_LEVEL(float weight) {
			this.weight = weight;
		}

		float weight(){
			return weight;
		}
	}

	private IMultiResourceManagerService mService;

	public MultiResourceManager(IMultiResourceManagerService service) {
		mService = service;
	}

	public static boolean isPerceivable(int[] hardwareUsage){
		if(hardwareUsage[HARDWARE_VIBRATION] > 0){
			return true;
		}
		if(hardwareUsage[HARDWARE_SOUND] > 0){
			return true;
		}
		if(hardwareUsage[HARDWARE_SCREEN] > 0){
			return true;
		}
		return false;
	}

	public static SIMILARITY getTimeSimilarity(long aWindowStart, long aWindowEnd, long aIntervalStart, long aIntervalEnd, long bWindowStart, long bWindowEnd, long bIntervalStart, long bIntervalEnd){
		if(bWindowEnd >= aWindowStart && bWindowStart <= aWindowEnd){
			return SIMILARITY.HIGH;
		} else if(bIntervalEnd >= aIntervalStart && bIntervalStart <= aIntervalEnd){
			return SIMILARITY.MID;
		} else {
			return SIMILARITY.LOW;
		}
	}

	public static SIMILARITY getHardwareSimilarity(int[] aHardwareUsage, int[] bHardwareUsage){
		if(aHardwareUsage == null || bHardwareUsage == null)	return SIMILARITY.LOW;
		int aHardware = 0, bHardware = 0;
		for(int i = 0; i < NUM_HARDWARE; i++){
			if(aHardwareUsage[i] > 0){
				aHardware += 2^i;
			}
			if(bHardwareUsage[i] > 0){
				bHardware += 2^i;
			}
		}

		if(aHardware != 0 && bHardware != 0){
			if( aHardware == (aHardware | bHardware) || bHardware == (aHardware | bHardware) ){
				// Subset.
				return SIMILARITY.HIGH;
			} else if ( (aHardware & bHardware) != 0){
				// Share at least one hardware.
				return SIMILARITY.MID;
			}
			return SIMILARITY.LOW;
		} else if (aHardware == 0 && bHardware == 0){
			// Both only use CPU.
			return SIMILARITY.HIGH;
		} else {
			return SIMILARITY.LOW;
		}
	}

	public static float getHardwareWeight(int[] hardwareUsage){
		float weight = 0.f;
		
		for(int i = 0; i < NUM_HARDWARE; i++){
			if(hardwareUsage[i] > 0){
				switch(i){
					case HARDWARE_NETWORK:
						// Need to check the network type.
						weight += HARDWARE_ENERGY_LEVEL.NETWORK_WIFI.weight();
						break;
					case HARDWARE_VIBRATION:
						weight += HARDWARE_ENERGY_LEVEL.VIBRATION.weight();
						break;
					case HARDWARE_SOUND:
						weight += HARDWARE_ENERGY_LEVEL.SOUND.weight();
						break;
					case HARDWARE_SCREEN:
						weight += HARDWARE_ENERGY_LEVEL.SCREEN.weight();
						break;
					case HARDWARE_AGPS:
						// Need to check the network type.
						weight += HARDWARE_ENERGY_LEVEL.AGPS_WIFI.weight();
						break;
					case HARDWARE_GPS: 
						weight += HARDWARE_ENERGY_LEVEL.GPS.weight();
						break;
					case HARDWARE_SENSOR_ACC:
						weight += HARDWARE_ENERGY_LEVEL.SENSOR_ACC.weight();
						break;
				}
			}
		}
	
		return weight;
	}

	public static float getHardwareWeight(int[] aHardwareUsage, boolean isWakeup){
		float weight = 0.f;
		if(isWakeup){
			weight += HARDWARE_ENERGY_LEVEL.ONLY_CPU.weight();
		}

		if(aHardwareUsage == null){
			return weight;
		}
		
		weight += getHardwareWeight(aHardwareUsage);

		return weight;
	}
}
