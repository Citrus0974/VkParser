package edu.vkParser.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DownloadUtils {
    public static void downloadFile(String urlStr, String destinationPath) {
        File file = new File(destinationPath);
        if (file.exists()) return; // Пропускаем, если уже скачали ранее
        System.out.println("downloading file: " + destinationPath);
        try (InputStream in = new URL(urlStr).openStream();
             OutputStream out = Files.newOutputStream(Paths.get(destinationPath))) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            System.out.println("failed to download: " + urlStr);
        }
    }
}
