package com.winlator.cmod.core;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class TarCompressorUtils {
    public enum Type {XZ, ZSTD}

    // Interface to define the exclusion filter
    public interface ExclusionFilter {
        boolean shouldInclude(File file);
    }


    private static void addFile(ArchiveOutputStream tar, File file, String entryName) {
        try {
            tar.putArchiveEntry(tar.createArchiveEntry(file, entryName));
            try (BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(file), StreamUtils.BUFFER_SIZE)) {
                StreamUtils.copy(inStream, tar);
            }
            tar.closeArchiveEntry();
        }
        catch (Exception e) {}
    }

    private static void addLinkFile(ArchiveOutputStream tar, File file, String entryName) {
        try {
            TarArchiveEntry entry = new TarArchiveEntry(entryName, TarConstants.LF_SYMLINK);
            entry.setLinkName(FileUtils.readSymlink(file));
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        }
        catch (Exception e) {}
    }

    private static void addDirectory(ArchiveOutputStream tar, File folder, String basePath, ExclusionFilter filter) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (filter != null && !filter.shouldInclude(file)) {
                continue; // Skip files that should be excluded
            }
            if (FileUtils.isSymlink(file)) {
                addLinkFile(tar, file, basePath + file.getName());
            } else if (file.isDirectory()) {
                String entryName = basePath + file.getName() + "/";
                tar.putArchiveEntry(tar.createArchiveEntry(folder, entryName));
                tar.closeArchiveEntry();
                addDirectory(tar, file, entryName, filter);
            } else {
                addFile(tar, file, basePath + file.getName());
            }
        }
    }
    public static void compress(Type type, File file, File destination, int level) {
        compress(type, new File[]{file}, destination, level, null);
    }

    public static void compress(Type type, File file, File destination, int level, ExclusionFilter filter) {
        compress(type, new File[]{file}, destination, level, filter);
    }

    public static void compress(Type type, File[] files, File destination, int level, ExclusionFilter filter) {
        try (OutputStream outStream = getCompressorOutputStream(type, destination, level);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (File file : files) {
                if (filter != null && !filter.shouldInclude(file)) {
                    continue; // Skip files that should be excluded
                }
                if (FileUtils.isSymlink(file)) {
                    addLinkFile(tar, file, file.getName());
                } else if (file.isDirectory()) {
                    String basePath = file.getName() + "/";
                    tar.putArchiveEntry(tar.createArchiveEntry(file, basePath));
                    tar.closeArchiveEntry();
                    addDirectory(tar, file, basePath, filter);
                } else {
                    addFile(tar, file, file.getName());
                }
            }
            tar.finish();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static boolean extract(Type type, Context context, String assetFile, File destination) {
        return extract(type, context, assetFile, destination, null);
    }

    public static boolean extract(Type type, Context context, String assetFile, File destination, OnExtractFileListener onExtractFileListener) {
        try {
            return extract(type, context.getAssets().open(assetFile), destination, onExtractFileListener);
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, Context context, Uri source, File destination) {
        return extract(type, context, source, destination, null);
    }

    public static boolean extract(Type type, Context context, Uri source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) return false;
        try {
            if (source.toString().startsWith("/")) {
                return extract(type, new FileInputStream(source.toString()), destination, onExtractFileListener);
            } else {
                return extract(type, context.getContentResolver().openInputStream(source), destination, onExtractFileListener);
            }
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    public static boolean extract(Type type, File source, File destination) {
        return extract(type, source, destination, null);
    }

    public static boolean extract(Type type, File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) return false;
        try {
            return extract(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE), destination, onExtractFileListener);
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    private static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) return false;
        try (InputStream inStream = getCompressorInputStream(type, source);
             ArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry)tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;
                File file = new File(destination, entry.getName());

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                }

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                }
                else {
                    if (entry.isSymbolicLink()) {
                        FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                    }
                    else {
                        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(tar, outStream)) return false;
                        }
                    }
                }

                FileUtils.chmod(file, 0771);
            }
            return true;
        }
        catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static InputStream getCompressorInputStream(Type type, InputStream source) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorInputStream(source);
        }
        else if (type == Type.ZSTD) {
            return new ZstdCompressorInputStream(source);
        }
        return null;
    }

    private static OutputStream getCompressorOutputStream(Type type, File destination, int level) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE), level);
        }
        else if (type == Type.ZSTD) {
            return new ZstdCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE), level);
        }
        return null;
    }

    public static void archive(File[] files, File destination, ExclusionFilter filter) {
        try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (File file : files) {
                if (filter != null && !filter.shouldInclude(file)) {
                    continue; // Skip files that should be excluded
                }
                if (FileUtils.isSymlink(file)) {
                    addLinkFile(tar, file, file.getName());
                } else if (file.isDirectory()) {
                    String basePath = file.getName() + "/";
                    tar.putArchiveEntry(tar.createArchiveEntry(file, basePath));
                    tar.closeArchiveEntry();
                    addDirectory(tar, file, basePath, filter);
                } else {
                    addFile(tar, file, file.getName());
                }
            }
            tar.finish();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean extractTar(File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) return false;
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE);
             TarArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            TarArchiveEntry entry;
            String topLevelDirectory = null;
            while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;

                // Get the top-level directory name
                String entryName = entry.getName();
                if (topLevelDirectory == null) {
                    if (entry.isDirectory()) {
                        topLevelDirectory = entryName;
                        continue; // Skip creating the top-level directory
                    }
                }

                // Skip the entire tmp directory
                if (entryName.contains("/tmp/")) {
                    Log.d("RestoreOp", "Skipping tmp directory: " + entryName);
                    continue;
                }

                // Adjust the extraction path to remove the top-level directory
                String adjustedName = entryName.replaceFirst("^" + topLevelDirectory, "");
                File file = new File(destination, adjustedName);

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                }

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                } else {
                    if (entry.isSymbolicLink()) {
                        FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                    } else {
                        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(tar, outStream)) return false;
                        }
                    }
                }

                FileUtils.chmod(file, 0771);
            }
            return true;
        } catch (IOException e) {
            Log.e("RestoreOp", "Failed to extract tar file", e);
            return false;
        }
    }



    /**
     * AnWind v7：解包 gzip 压缩的 tar（.tar.gz/.tgz）。
     * XKB 键盘数据（etc/anwind/x11/xkb.tar.gz）是 gzip 格式，而 Type 枚举
     * 只有 XZ/ZSTD —— 容器会话侧（X11SessionStarter.ensureRunning）此前无法
     * 复用本工具做自愈解压。复用 commons-compress 的 TarArchiveInputStream
     * （自动处理 GNU 长名与 PAX 头），GZIP 流由 java.util.zip 提供，无新增依赖。
     * 语义与终端脚本 `tar -xzf <tarball> -C <dest>` 对齐：条目按原路径落位。
     */
    public static boolean extractTarGz(File source, File destination) {
        return extractTarGz(source, destination, null);
    }

    public static boolean extractTarGz(File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile() || destination == null) return false;
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE);
             TarArchiveInputStream tar = new TarArchiveInputStream(new java.util.zip.GZIPInputStream(inStream, StreamUtils.BUFFER_SIZE))) {
            destination.mkdirs();
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;

                String entryName = entry.getName();
                File file = new File(destination, entryName);

                // 防路径逃逸（归档条目含 "../" 时拒绝写出）
                String canonical = file.getCanonicalPath();
                String destCanonical = destination.getCanonicalPath();
                if (!canonical.equals(destCanonical) && !canonical.startsWith(destCanonical + File.separator)) continue;

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                }

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                }
                else if (entry.isSymbolicLink()) {
                    FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                }
                else {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) continue;
                    try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                        if (!StreamUtils.copy(tar, outStream)) return false;
                    }
                }

                FileUtils.chmod(file, entry.isDirectory() ? 0755 : 0644);
                file.setLastModified(entry.getModTime().getTime());
            }
            return true;
        }
        catch (IOException e) {
            Log.e("TarCompressorUtils", "Failed to extract tar.gz file", e);
            return false;
        }
    }

}







