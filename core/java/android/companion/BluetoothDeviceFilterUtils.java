/*
 * Copyright (C) 2017 The Android Open Source Project
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


package android.companion;

import static android.text.TextUtils.firstNotEmpty;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;
import android.net.wifi.ScanResult;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** @hide */
public class BluetoothDeviceFilterUtils {
    private BluetoothDeviceFilterUtils() {}

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "BluetoothDeviceFilterUtils";

    @Nullable
    static String patternToString(@Nullable Pattern p) {
        return p == null ? null : p.pattern();
    }

    @Nullable
    static Pattern patternFromString(@Nullable String s) {
        return s == null ? null : Pattern.compile(s);
    }

    static boolean matches(ScanFilter filter, BluetoothDevice device) {
        boolean result = matchesAddress(filter.getDeviceAddress(), device)
                && matchesServiceUuid(filter.getServiceUuid(), filter.getServiceUuidMask(), device);
        if (DEBUG) debugLogMatchResult(result, device, filter);
        return result;
    }

    static boolean matchesAddress(String deviceAddress, BluetoothDevice device) {
        final boolean result = deviceAddress == null
                || (device != null && deviceAddress.equals(device.getAddress()));
        if (DEBUG) debugLogMatchResult(result, device, deviceAddress);
        return result;
    }

    static boolean matchesServiceUuids(List<ParcelUuid> serviceUuids,
            List<ParcelUuid> serviceUuidMasks, BluetoothDevice device) {
        for (int i = 0; i < serviceUuids.size(); i++) {
            ParcelUuid uuid = serviceUuids.get(i);
            ParcelUuid uuidMask = serviceUuidMasks.get(i);
            if (!matchesServiceUuid(uuid, uuidMask, device)) {
                return false;
            }
        }
        return true;
    }

    static boolean matchesServiceUuid(ParcelUuid serviceUuid, ParcelUuid serviceUuidMask,
            BluetoothDevice device) {
        ParcelUuid[] uuids = device.getUuids();
        final boolean result = serviceUuid == null ||
                ScanFilter.matchesServiceUuids(
                        serviceUuid,
                        serviceUuidMask,
                        uuids == null ? Collections.emptyList() : Arrays.asList(uuids));
        if (DEBUG) debugLogMatchResult(result, device, serviceUuid);
        return result;
    }

    static boolean matchesName(@Nullable Pattern namePattern, BluetoothDevice device) {
        boolean result;
        if (namePattern == null)  {
            result = true;
        } else if (device == null) {
            result = false;
        } else {
            final String name = device.getName();
            result = name != null && namePattern.matcher(name).find();
        }
        if (DEBUG) debugLogMatchResult(result, device, namePattern);
        return result;
    }

    static boolean matchesName(@Nullable Pattern namePattern, ScanResult device) {
        boolean result;
        if (namePattern == null)  {
            result = true;
        } else if (device == null) {
            result = false;
        } else {
            final String name = device.SSID;
            result = name != null && namePattern.matcher(name).find();
        }
        if (DEBUG) debugLogMatchResult(result, device, namePattern);
        return result;
    }

    private static void debugLogMatchResult(
            boolean result, BluetoothDevice device, Object criteria) {
        Log.i(LOG_TAG, getDeviceDisplayNameInternal(device) + (result ? " ~ " : " !~ ") + criteria);
    }

    private static void debugLogMatchResult(
            boolean result, ScanResult device, Object criteria) {
        Log.i(LOG_TAG, getDeviceDisplayNameInternal(device) + (result ? " ~ " : " !~ ") + criteria);
    }

    @UnsupportedAppUsage
    public static String getDeviceDisplayNameInternal(@NonNull BluetoothDevice device) {
        return firstNotEmpty(device.getAlias(), device.getAddress());
    }

    @UnsupportedAppUsage
    public static String getDeviceDisplayNameInternal(@NonNull ScanResult device) {
        return firstNotEmpty(device.SSID, device.BSSID);
    }

    @UnsupportedAppUsage
    public static String getDeviceMacAddress(@NonNull Parcelable device) {
        if (device instanceof BluetoothDevice) {
            return ((BluetoothDevice) device).getAddress();
        } else if (device instanceof ScanResult) {
            return ((ScanResult) device).BSSID;
        } else if (device instanceof android.bluetooth.le.ScanResult) {
            return getDeviceMacAddress(((android.bluetooth.le.ScanResult) device).getDevice());
        } else {
            throw new IllegalArgumentException("Unknown device type: " + device);
        }
    }
}
