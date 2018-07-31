package com.doctorsfab.breathcare.airflowgateway;

import java.io.UnsupportedEncodingException;

public class BluetoothHelper {

    public static String bytesToHexString(final byte[] array) {
        final StringBuilder sb = new StringBuilder("");
        if (array == null || array.length <= 0) {
            return null;
        }
        for (int i = 0; i < array.length; ++i) {
            final String hexString = Integer.toHexString(array[i] & 0xFF);
            if (hexString.length() < 2) {
                sb.append(0);
            }
            sb.append(hexString);
        }
        return sb.toString();
    }

    public static String getWind(byte[] array, final int n, final int n2) {
        final byte[] bytes = subBytes(array, n, n2);

        String result = "";
        try {
            result = new String(bytes, "ascii");
        }
        catch (UnsupportedEncodingException unsupportedEncodingException) {
            unsupportedEncodingException.printStackTrace();
        }
        return result;
    }

    public static byte[] subBytes(final byte[] array, final int n, final int n2) {
        final byte[] array2 = new byte[n2];
        for (int i = n; i < n + n2; ++i) {
            array2[i - n] = array[i];
        }
        return array2;
    }
}
