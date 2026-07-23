package com.google.android.diskusage.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.diskusage.R;
import com.google.android.diskusage.databinding.ActivityCommonBinding;
import com.google.android.diskusage.filesystem.mnt.MountPoint;
import splitties.toast.ToastKt;
import timber.log.Timber;

public class PermissionRequestActivity extends Activity {
    private final static int DISKUSAGE_REQUEST_CODE = 10;
    private final static int PERMISSION_REQUEST_USAGE_ACCESS_CODE = 11;
    private final static int PERMISSION_REQUEST_EXTERNAL_STORAGE_CODE = 12;

    private MountPoint mountPoint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityCommonBinding binding = ActivityCommonBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        Intent i = getIntent();

        final String key = i.getStringExtra(DiskUsage.KEY_KEY);
        if (key == null) {
            finish();
            return;
        }

        mountPoint = MountPoint.getForKey(this, key);
        if (mountPoint == null) {
            finish();
            return;
        }

        if (!isExternalStorageGranted()) {
            showStorageAccessRequest();
        } else {
            checkUsageAccessAndProceed();
        }
    }

    private boolean isExternalStorageGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void showStorageAccessRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_storage_access_title)
                    .setMessage(R.string.dialog_storage_access_desc)
                    .setPositiveButton(R.string.dialog_storage_access_grant, (d, i) -> {
                        try {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivityForResult(
                                    intent, PERMISSION_REQUEST_EXTERNAL_STORAGE_CODE);
                            return;
                        } catch (Exception e) {
                            Timber.d(e, "failed to obtain all files access with package URI");
                        }
                        try {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivityForResult(
                                    intent, PERMISSION_REQUEST_EXTERNAL_STORAGE_CODE);
                            return;
                        } catch (Exception e2) {
                            Timber.d(e2, "failed to obtain all files access");
                        }
                        checkUsageAccessAndProceed();
                    })
                    .setNegativeButton(R.string.dialog_storage_access_skip, (d, i) -> {
                        ToastKt.toast(R.string.dialog_external_storage_access_error);
                        checkUsageAccessAndProceed();
                    })
                    .show();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                checkUsageAccessAndProceed();
            } else {
                requestPermissions(
                        new String[] {
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        },
                        PERMISSION_REQUEST_EXTERNAL_STORAGE_CODE
                );
            }
        }
    }

    private void checkUsageAccessAndProceed() {
        if (mountPoint.hasApps() && !isAccessGranted()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_usage_access_title)
                    .setMessage(R.string.dialog_usage_access_desc)
                    .setPositiveButton(android.R.string.ok, (dialogInterface, i1) -> {
                        Intent intent = new Intent(
                                Settings.ACTION_USAGE_ACCESS_SETTINGS);
                        startActivityForResult(
                                intent, PERMISSION_REQUEST_USAGE_ACCESS_CODE);
                    })
                    .setNegativeButton(android.R.string.cancel, (dialogInterface, i12) ->
                            forwardToDiskUsage()).create().show();
        } else {
            forwardToDiskUsage();
        }
    }

    public void forwardToDiskUsage() {
        Intent input = getIntent();
        Intent diskusage = new Intent(this, DiskUsage.class);
        diskusage.putExtra(DiskUsage.KEY_KEY,
                input.getStringExtra(DiskUsage.KEY_KEY));
        diskusage.putExtra(DiskUsage.STATE_KEY,
                input.getBundleExtra(DiskUsage.STATE_KEY));
        startActivityForResult(diskusage, DISKUSAGE_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DISKUSAGE_REQUEST_CODE) {
            setResult(0, data);
            finish();
        } else if (requestCode == PERMISSION_REQUEST_EXTERNAL_STORAGE_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    checkUsageAccessAndProceed();
                } else {
                    ToastKt.toast(R.string.dialog_external_storage_access_error);
                    checkUsageAccessAndProceed();
                }
            }
        } else if (requestCode == PERMISSION_REQUEST_USAGE_ACCESS_CODE) {
            forwardToDiskUsage();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_EXTERNAL_STORAGE_CODE) {
            boolean granted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                checkUsageAccessAndProceed();
            } else {
                ToastKt.toast(R.string.dialog_external_storage_access_error);
                checkUsageAccessAndProceed();
            }
        }
    }

    private boolean isAccessGranted() {
        try {
            PackageManager packageManager = getPackageManager();
            ApplicationInfo applicationInfo =
                    packageManager.getApplicationInfo(getPackageName(), 0);
            AppOpsManager appOpsManager =
                    (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    applicationInfo.uid, applicationInfo.packageName);
            return (mode == AppOpsManager.MODE_ALLOWED);

        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
