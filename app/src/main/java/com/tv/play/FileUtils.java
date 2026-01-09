package com.tv.play;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class FileUtils {
    public static String getPath(Context context, Uri uri) {
        String fileName = getFileName(context, uri);
        File file = new File(context.getCacheDir(), fileName);
        try {
            InputStream input = context.getContentResolver().openInputStream(uri);
            FileOutputStream output = new FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) > 0) {
                output.write(buffer, 0, length);
            }
            input.close();
            output.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return file.getAbsolutePath();
    }

    private static String getFileName(Context context, Uri uri) {
        String result = "video_boot.mp4";
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex >= 0) {
                result = cursor.getString(nameIndex);
            }
            cursor.close();
        }
        return result;
    }
}
