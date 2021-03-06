/*************************************************************************************
 * Copyright (C) 2012 Kristian Lauszus, TKJ Electronics. All rights reserved.
 *
 * This software may be distributed and modified under the terms of the GNU
 * General Public License version 2 (GPL2) as published by the Free Software
 * Foundation and appearing in the file GPL2.TXT included in the packaging of
 * this file. Please note that GPL2 Section 2[b] requires that all works based
 * on this software must also be made publicly available under the terms of
 * the GPL2 ("Copyleft").
 *
 * Contact information
 * -------------------
 *
 * Kristian Lauszus, TKJ Electronics
 * Web      :  http://www.tkjelectronics.com
 * e-mail   :  kristianl@tkjelectronics.com
 *
 ************************************************************************************/

package com.tkjelectronics.balanduino;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import com.physicaloid.lib.Boards;
import com.physicaloid.lib.Physicaloid;
import com.physicaloid.lib.Physicaloid.UploadCallBack;
import com.physicaloid.lib.programmer.avr.UploadErrors;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public class Upload {
    private static final String TAG = "Upload";
    public final static String flavor = "Usb";
    private static final boolean D = BalanduinoActivity.D;
    private static final String ACTION_USB_PERMISSION = "com.tkjelectronics.balanduino.USB_PERMISSION";

    private static Physicaloid mPhysicaloid;
    private static boolean uploading = false;
    private static ProgressDialog mProgressDialog;
    private final static String fileUrl = "https://raw.github.com/TKJElectronics/Balanduino/master/Firmware/Balanduino/Balanduino.hex";
    private static String fileName;

    /**
     * Closes the serial communication.
     */
    public static void close() {
        if (mPhysicaloid != null) {
            try {
                if (mPhysicaloid.isOpened())
                    mPhysicaloid.close();
            } catch (RuntimeException e) {
                if (D)
                    Log.e(TAG, e.toString());
            }
        }
    }

    /**
     * Start the firmware upload.
     * First it check if the Balanduino is actually connected and then check the permission.
     * If permission is not granted it will ask for permission.
     * After this it will download the firmware and then upload it to the Balanduino via the USB Host port.
     *
     * @return Returns true if a new upload has started.
     */
    public static boolean uploadFirmware() {
        if (uploading) {
            BalanduinoActivity.showToast("Upload is already in progress", Toast.LENGTH_SHORT);
            return false;
        }

        // Check permission before trying to do anything else
        UsbManager mUsbManager = (UsbManager) BalanduinoActivity.activity.getSystemService(BalanduinoActivity.USB_SERVICE);
        if (mUsbManager == null)
            return false;
        Map<String, UsbDevice> map = mUsbManager.getDeviceList();
        if (map == null)
            return false;
        if (D)
            Log.i(TAG, "UsbDevices: " + map);

        boolean deviceFound = false;
        for (UsbDevice mUsbDevice : map.values()) {
            if (mUsbDevice.getVendorId() == 0x0403 && mUsbDevice.getProductId() == 0x6015) { // Check if the robot is connected
                deviceFound = true;
                if (mUsbManager.hasPermission(mUsbDevice)) {
                    if (D)
                        Log.i(TAG, "Already has permission");
                    showDialog();
                } else {
                    if (D)
                        Log.i(TAG, "Requesting permission");
                    PendingIntent mPermissionIntent = PendingIntent.getBroadcast(BalanduinoActivity.context, 0, new Intent(ACTION_USB_PERMISSION), 0);
                    BalanduinoActivity.activity.registerReceiver(mUsbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
                    mUsbManager.requestPermission(mUsbDevice, mPermissionIntent);
                }
            }
        }
        if (!deviceFound) {
            BalanduinoActivity.showToast("Please connect the Balanduino to the USB Host port", Toast.LENGTH_SHORT);
            return false;
        }

        return true;
    }

    /**
     * Check if USB Host is available on the device.
     * I am not sure if this is actually needed or not.
     *
     * @return Return true if USB Host is available.
     */
    public static boolean isUsbHostAvailable() {
        UsbManager mUsbManager = (UsbManager) BalanduinoActivity.activity.getSystemService(BalanduinoActivity.USB_SERVICE);
        if (mUsbManager == null)
            return false;
        Map<String, UsbDevice> map = mUsbManager.getDeviceList();
        if (map == null)
            return false;

        return true;
    }

    private static final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (D)
                            Log.i(TAG, "Permission allowed");
                        showDialog();
                    } else {
                        if (D)
                            Log.e(TAG, "Permission denied");
                    }
                }
            }
        }
    };

    private static void showDialog() {
        if (!BalanduinoActivity.activity.isFinishing()) {
            new AlertDialog.Builder(BalanduinoActivity.activity)
                    .setIcon(R.drawable.ic_dialog_usb)
                    .setTitle("Download firmware")
                    .setMessage("This will download the newest firmware and upload it to the Balanduino via the USB host port")
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            downloadFile();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .create().show();
        }
    }

    private static void downloadFile() {
        // Execute this when the downloader must be fired
        final DownloadTask downloadTask = new DownloadTask(BalanduinoActivity.activity);
        downloadTask.execute(fileUrl);

        mProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                downloadTask.cancel(true);
            }
        });
    }

    private static void upload() {
        if (fileName == null)
            return;
        if (mPhysicaloid == null)
            mPhysicaloid = new Physicaloid(BalanduinoActivity.activity);

        try {
            mPhysicaloid.upload(Boards.BALANDUINO, BalanduinoActivity.context.openFileInput(fileName), mUploadCallback);
        } catch (RuntimeException e) {
            if (D)
                Log.e(TAG, e.toString());
            BalanduinoActivity.showToast("Error opening USB host communication", Toast.LENGTH_SHORT);
        } catch (FileNotFoundException e) {
            if (D)
                Log.e(TAG, e.toString());
            BalanduinoActivity.showToast("Error opening hex file", Toast.LENGTH_SHORT);
        }
    }

    private static UploadCallBack mUploadCallback = new UploadCallBack() {
        @Override
        public void onUploading(int value) {
            uploading = true;
            if (D)
                Log.i(TAG, "Uploading: " + value);
        }

        @Override
        public void onPreUpload() {
            uploading = true;
            BalanduinoActivity.activity.runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(BalanduinoActivity.context, "Uploading...", Toast.LENGTH_SHORT).show();
                }
            });
            if (D)
                Log.i(TAG, "Upload start");
        }

        @Override
        public void onPostUpload(boolean success) {
            uploading = false;
            if (success) {
                BalanduinoActivity.activity.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(BalanduinoActivity.context, "Uploading was successful", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                BalanduinoActivity.activity.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(BalanduinoActivity.context, "Uploading failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

        @Override
        public void onError(UploadErrors err) {
            uploading = false;
            BalanduinoActivity.activity.runOnUiThread(new Runnable() {
                public void run() {
                    BalanduinoActivity.showToast("Uploading error", Toast.LENGTH_SHORT);
                }
            });
            if (D)
                Log.e(TAG, "Error: " + err.toString());
        }
    };

    // Usually, subclasses of AsyncTask are declared inside the activity class.
    // That way, you can easily modify the UI thread from here
    // Source: http://stackoverflow.com/questions/3028306/download-a-file-with-android-and-showing-the-progress-in-a-progressdialog
    private static class DownloadTask extends AsyncTask<String, Integer, String> {
        private static final String TAG = "DownloadTask";
        private static final boolean D = BalanduinoActivity.D;

        private Context context;

        public DownloadTask(Context context) {
            this.context = context;
        }

        @Override
        protected String doInBackground(String... sUrl) {
            String networkStatus = checkNetwork();
            if (networkStatus != null)
                return networkStatus;

            // Take CPU lock to prevent CPU from going off if the user presses the power button during download
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
            wl.acquire();

            try {
                InputStream input = null;
                OutputStream output = null;
                HttpURLConnection connection = null;
                try {
                    fileName = sUrl[0].substring(sUrl[0].lastIndexOf('/') + 1, sUrl[0].length());
                    if (D)
                        Log.i(TAG, "FileName: " + fileName);

                    URL url = new URL(sUrl[0]);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.connect();

                    // Expect HTTP 200 OK, so we do not mistakenly save error report instead of the file
                    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                        return "Server returned HTTP " + connection.getResponseCode() + " " + connection.getResponseMessage();

                    int fileLength = connection.getContentLength(); // This will be useful to display download percentage might be -1: server did not report the length

                    // Download the file
                    input = connection.getInputStream();
                    context.deleteFile(fileName); // Delete old file - if the old hex file was corrupted the upload would for some reason fail
                    output = context.openFileOutput(fileName, Context.MODE_PRIVATE);

                    byte data[] = new byte[4096];
                    long total = 0;
                    int count;
                    while ((count = input.read(data)) != -1) {
                        if (isCancelled()) // Allow canceling with back button
                            return null;
                        total += count;
                        if (fileLength > 0) // Only if total length is known
                            publishProgress((int) (total * 100 / fileLength)); // Publishing the progress....
                        output.write(data, 0, count);
                    }
                } catch (Exception e) {
                    return e.toString();
                } finally {
                    try {
                        if (output != null)
                            output.close();
                        if (input != null)
                            input.close();
                    } catch (IOException ignored) {
                    }

                    if (connection != null)
                        connection.disconnect();
                }
            } finally {
                wl.release();
            }
            return null;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (!BalanduinoActivity.activity.isFinishing()) {
                mProgressDialog = new ProgressDialog(BalanduinoActivity.activity);
                mProgressDialog.setMessage("Downloading file...");
                mProgressDialog.setIndeterminate(true);
                mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER); // STYLE_HORIZONTAL
                mProgressDialog.setCancelable(true);
                mProgressDialog.show();
            }
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            super.onProgressUpdate(progress);
            mProgressDialog.setIndeterminate(false); // If we get here, length is known, now set indeterminate to false
            mProgressDialog.setMax(100);
            mProgressDialog.setProgress(progress[0]);
        }

        @Override
        protected void onPostExecute(String result) {
            mProgressDialog.dismiss();
            if (result != null)
                BalanduinoActivity.showToast("Download error: " + result, Toast.LENGTH_LONG);
            else {
                BalanduinoActivity.showToast("File downloaded", Toast.LENGTH_SHORT);
                upload(); // Upload downloaded file
            }
        }

        private String checkNetwork() {
            ConnectivityManager mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (mConnectivityManager == null)
                return "No connection available";

            NetworkInfo infoMobile = mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            NetworkInfo infoWifi = mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

            if (infoMobile != null && infoWifi != null) {
                if (infoMobile.getState() != NetworkInfo.State.CONNECTED && infoWifi.getState() != NetworkInfo.State.CONNECTED)
                    return "No network available";
            } else if (infoMobile != null) {
                if (infoMobile.getState() != NetworkInfo.State.CONNECTED)
                    return "No mobile network available";
            } else if (infoWifi != null) {
                if (infoWifi.getState() != NetworkInfo.State.CONNECTED)
                    return "No Wifi network available";
            }
            return null;
        }
    }
}