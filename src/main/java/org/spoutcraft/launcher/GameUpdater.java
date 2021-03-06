/*
 * This file is part of Spoutcraft Launcher (http://www.spout.org/).
 *
 * Spoutcraft Launcher is licensed under the SpoutDev License Version 1.
 *
 * Spoutcraft Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the SpoutDev License Version 1.
 *
 * Spoutcraft Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the SpoutDev license version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://www.spout.org/SpoutDevLicenseV1.txt> for the full license,
 * including the MIT license.
 */
package org.spoutcraft.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import SevenZip.LzmaAlone;

import org.spoutcraft.launcher.async.Download;
import org.spoutcraft.launcher.async.DownloadListener;
import org.spoutcraft.launcher.exception.NoMirrorsAvailableException;
import org.spoutcraft.launcher.exception.UnsupportedOSException;
import org.spoutcraft.launcher.logs.SystemConsoleListener;

public class GameUpdater implements DownloadListener {
	/* Minecraft Updating Arguments */
	public String user = "Player";
	public String downloadTicket = "1";

	/* Files */
	public static final File binDir = new File(PlatformUtils.getWorkingDirectory(), "bin");
	public static final File binCacheDir = new File(binDir, "cache");
	public static final File updateDir = new File(PlatformUtils.getWorkingDirectory(), "temp");
	public static final File backupDir = new File(PlatformUtils.getWorkingDirectory(), "backups");
	public static final File spoutcraftDir = new File(PlatformUtils.getWorkingDirectory(), "spoutcraft");
	public static final File savesDir = new File(PlatformUtils.getWorkingDirectory(), "saves");

	/* Minecraft Updating Arguments */
	public final String baseURL = "http://s3.amazonaws.com/MinecraftDownload/";
	public final String latestLWJGLURL = "http://get.spout.org/Libraries/lwjgl/";

	private DownloadListener listener;
	public GameUpdater() {
	}

	public void updateMC() throws Exception {
		binDir.mkdir();
		binCacheDir.mkdir();
		if (updateDir.exists()) {
			FileUtils.deleteDirectory(updateDir);
		}
		updateDir.mkdir();

		String minecraftMD5 = MD5Utils.getMD5(FileType.minecraft);
		String jinputMD5 = MD5Utils.getMD5(FileType.jinput);
		String lwjglMD5 = MD5Utils.getMD5(FileType.lwjgl);
		String lwjgl_utilMD5 = MD5Utils.getMD5(FileType.lwjgl_util);

		SpoutcraftBuild build = SpoutcraftBuild.getSpoutcraftBuild();

		// Processs minecraft.jar \\
		File mcCache = new File(binCacheDir, "minecraft_" + build.getMinecraftVersion() + ".jar");
		if (!mcCache.exists() || !minecraftMD5.equals(MD5Utils.getMD5(mcCache))) {
			String minecraftURL = baseURL + "minecraft.jar?user=" + user + "&ticket=" + downloadTicket;
			String output = updateDir + File.separator + "minecraft.jar";
			MinecraftDownloadUtils.downloadMinecraft(minecraftURL, output, build, listener);
		}
		copy(mcCache, new File(binDir, "minecraft.jar"));

		File nativesDir = new File(binDir.getPath(), "natives");
		nativesDir.mkdir();

		// Process other Downloads
		mcCache = new File(binCacheDir, "jinput.jar");
		if (!mcCache.exists() || !jinputMD5.equals(MD5Utils.getMD5(mcCache))) {
			DownloadUtils.downloadFile(getNativesUrl() + "jinput.jar",binDir.getPath() + File.separator + "jinput.jar", "jinput.jar");
		} else {
			copy(mcCache, new File(binDir, "jinput.jar"));
		}

		mcCache = new File(binCacheDir, "lwjgl.jar");
		if (!mcCache.exists() || !lwjglMD5.equals(MD5Utils.getMD5(mcCache))) {
			DownloadUtils.downloadFile(getNativesUrl() + "lwjgl.jar", binDir.getPath() + File.separator + "lwjgl.jar", "lwjgl.jar");
		} else {
			copy(mcCache, new File(binDir, "lwjgl.jar"));
		}

		mcCache = new File(binCacheDir, "lwjgl_util.jar");
		if (!mcCache.exists() || !lwjgl_utilMD5.equals(MD5Utils.getMD5(mcCache))) {
			DownloadUtils.downloadFile(getNativesUrl() + "lwjgl_util.jar", binDir.getPath() + File.separator + "lwjgl_util.jar", "lwjgl_util.jar");
		} else {
			copy(mcCache, new File(binDir, "lwjgl_util.jar"));
		}

		getNatives();

		stateChanged("Extracting Files...", 0);
		// Extract Natives
		try {
			extractNatives(nativesDir, new File(GameUpdater.updateDir.getPath() + File.separator + "natives.zip"));
		} catch (FileNotFoundException inUse) {
			//If we previously loaded this dll with a failed launch, we will be unable to access the files
			//This is because the previous classloader opened them with the parent classloader, and while the mc classloader
			//has been gc'd, the parent classloader is still around, holding the file open. In that case, we have to assume
			//the files are good, since they got loaded last time...
		}

		MinecraftYML.setInstalledVersion(build.getMinecraftVersion());
	}

	public String getNativesUrl() {
		//if (SettingsUtil.isLatestLWJGL()) {
		//	return latestLWJGLURL;
		//}
		return baseURL;
	}

	public String getNativesUrl(String fileName) {
		if (SettingsUtil.isLatestLWJGL()) {
			return latestLWJGLURL + fileName + ".zip";
		}
		return baseURL + fileName + ".jar.lzma";
	}

	public boolean checkMCUpdate(String message) throws Exception {
		int steps = 7;
		if (!GameUpdater.binDir.exists()) {
			return true;
		}
		stateChanged(message, 100F / steps);
		if (!new File(binDir, "natives").exists()) {
			return true;
		}
		stateChanged(message, 200F / steps);
		File minecraft = new File(binDir, "minecraft.jar");
		if (!minecraft.exists()) {
			return true;
		}
		stateChanged(message, 300F / steps);
		File lib = new File(binDir, "jinput.jar");
		if (!lib.exists()) {
			return true;
		}
		stateChanged(message, 400F / steps);
		lib = new File(binDir, "lwjgl.jar");
		if (!lib.exists()) {
			return true;
		}
		stateChanged(message, 500F / steps);
		lib = new File(binDir, "lwjgl_util.jar");
		if (!lib.exists()) {
			return true;
		}
		stateChanged(message, 600F / steps);
		SpoutcraftBuild build = SpoutcraftBuild.getSpoutcraftBuild();
		String installed = MinecraftYML.getInstalledVersion();
		stateChanged(message, 700F / steps);
		String required = build.getMinecraftVersion();
		return !installed.equals(required);
	}

	private void extractNatives(File nativesDir, File nativesJar) throws Exception {
		if (!nativesDir.exists()) {
			nativesDir.mkdir();
		}

		JarFile jar = new JarFile(nativesJar);
		Enumeration<JarEntry> entries = jar.entries();

		float progressStep = 100F / jar.size();
		float progress = 0;

		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			String name = entry.getName();
			if (entry.isDirectory()) {
				continue;
			}
			if (name.startsWith("META-INF")) {
				continue;
			}
			InputStream inputStream = jar.getInputStream(entry);
			File outFile = new File(nativesDir.getPath() + File.separator + name);
			if (!outFile.exists()) {
				outFile.createNewFile();
			}
			OutputStream out = new FileOutputStream(new File(nativesDir.getPath() + File.separator + name));

			int read;
			byte[] bytes = new byte[1024];

			while ((read = inputStream.read(bytes)) != -1) {
				out.write(bytes, 0, read);
			}

			progress += progressStep;
			stateChanged("Extracting Files...", progress);

			inputStream.close();
			out.flush();
			out.close();
		}
	}

	private File getNatives() throws Exception {
		String osName = System.getProperty("os.name").toLowerCase();
		String fname;

		if (osName.contains("win")) {
			fname = "windows_natives";
		} else if (osName.contains("mac")) {
			fname = "macosx_natives";
		} else if (osName.contains("solaris") || osName.contains("sunos")) {
			fname = "solaris_natives";
		} else if (osName.contains("linux") || osName.contains("unix")) {
			fname = "linux_natives";
		} else {
			throw new UnsupportedOSException();
		}

		if (!updateDir.exists()) {
			updateDir.mkdir();
		}

		DownloadUtils.downloadFile(getNativesUrl(fname), updateDir.getPath() + File.separator + (!SettingsUtil.isLatestLWJGL() ? "natives.jar.lzma" : "natives.zip"));

		if (!SettingsUtil.isLatestLWJGL()) {
			extractLZMA(GameUpdater.updateDir.getPath() + File.separator + "natives.jar.lzma", GameUpdater.updateDir.getPath() + File.separator + "natives.zip");
		}

		return new File(updateDir.getPath() + File.separator + "natives.jar.lzma");
	}

	private String removeExtension(File file) {
		String name = file.getName();
		int ind = name.lastIndexOf('.');
		if(ind <= 0) {
			return name;
		}
		return name.substring(0, ind);
	}

	public void cleanupBinFoldersFor(SpoutcraftBuild build) {
		try {
			if(!binDir.exists()) {
				return;
			}

			HashSet<String> neededBinFiles = new HashSet<String>(Arrays.asList(new String[]{"spoutcraft.jar", "minecraft.jar", "lwjgl.jar", "lwjgl_util.jar", "jinput.jar"}));
			for(File file : binDir.listFiles()) {
				if(!file.isFile()) {
					continue;
				}
				if(neededBinFiles.contains(file.getName())) {
					continue;
				}
				file.delete();
			}

			File libDir = new File(binDir, "lib");
			if(libDir.exists()) {
				for(File file : libDir.listFiles()) {
					if(!file.isFile()) {
						continue;
					}
					if(build.getLibraries().containsKey(removeExtension(file))) {
						continue;
					}
					file.delete();
				}
			}
		} catch(Exception e) {
			System.out.println("Error while cleaning unnecessary junk... :c");
			e.printStackTrace();
		}
	}

	public void updateSpoutcraft() throws Exception {
		performBackup();
		SpoutcraftBuild build = SpoutcraftBuild.getSpoutcraftBuild();
		cleanupBinFoldersFor(build);

		updateDir.mkdirs();
		binCacheDir.mkdirs();
		spoutcraftDir.mkdirs();
		File cacheDir = new File(binDir, "cache");
		cacheDir.mkdir();

		File mcCache = new File(binCacheDir, "minecraft_" + build.getMinecraftVersion() + ".jar");
		File updateMC = new File(updateDir.getPath() + File.separator + "minecraft.jar");
		if (mcCache.exists()) {
			copy(mcCache, updateMC);
		}

		File spoutcraft = new File(binDir, "spoutcraft.jar");
		if (spoutcraft.exists() && build.getInstalledBuild() > 0) {
			//Save our installed copy
			File spoutcraftCache = new File(cacheDir, "spoutcraft_" + build.getInstalledBuild() + ".jar");
			if (!spoutcraftCache.exists()) {
				copy(spoutcraft, spoutcraftCache);
			}
			spoutcraft.delete();
			//Check for an old copy of this build if it is already saved
			spoutcraftCache = new File(cacheDir, "spoutcraft_" + build.getBuild() + ".jar");
			if (spoutcraftCache.exists()) {
				copy(spoutcraftCache, spoutcraft);
			}
		}

		stateChanged("Looking Up Mirrors...", 0F);
		build.setDownloadListener(this);

		String url = build.getSpoutcraftURL();

		if (url == null) {
			throw new NoMirrorsAvailableException();
		}

		if (!spoutcraft.exists()) {
			Download download = DownloadUtils.downloadFile(url, updateDir + File.separator + "spoutcraft.jar", null, build.getMD5(), this);
			if (download.isSuccess()) {
				copy(download.getOutFile(), spoutcraft);
			}
		}

		File libDir = new File(binDir, "lib");
		libDir.mkdir();

		Map<String, Object> libraries = build.getLibraries();
		Iterator<Entry<String, Object>> i = libraries.entrySet().iterator();
		while (i.hasNext()) {
			Entry<String, Object> lib = i.next();
			String version = String.valueOf(lib.getValue());
			String name = lib.getKey() + "-" + version;

			File libraryFile = new File(libDir, lib.getKey() + ".jar");
			String MD5 = LibrariesYML.getMD5(lib.getKey(), version);

			if (libraryFile.exists()) {
				String computedMD5 = MD5Utils.getMD5(libraryFile);
				if (!computedMD5.equals(MD5)) {
					libraryFile.delete();
				}
			}

			if (!libraryFile.exists()) {
				String mirrorURL = "/Libraries/" + lib.getKey() + "/" + name + ".jar";
				String fallbackURL = "http://get.spout.org/Libraries/" + lib.getKey() + "/" + name + ".jar";
				url = MirrorUtils.getMirrorUrl(mirrorURL, fallbackURL, this);
				DownloadUtils.downloadFile(url, libraryFile.getPath(), null, MD5, this);
			}
		}

		build.install();

		//TODO: remove this once this build has been out for a few weeks
		File spoutcraftVersion = new File(GameUpdater.spoutcraftDir, "versionSpoutcraft");
		spoutcraftVersion.delete();
	}

	public boolean isSpoutcraftUpdateAvailable(String message) throws IOException {
		if (!PlatformUtils.getWorkingDirectory().exists()) {
			return true;
		}
		if (!GameUpdater.spoutcraftDir.exists()) {
			return true;
		}

		SpoutcraftBuild build = SpoutcraftBuild.getSpoutcraftBuild();
		Map<String, Object> libraries = build.getLibraries();
		int steps = libraries.size() + 2;
		float progress = 100F;

		if (build.getBuild() != build.getInstalledBuild()) {
			return true;
		}
		stateChanged(message, progress / steps);
		progress += 100F;
		File spoutcraft = new File(binDir, "spoutcraft.jar");
		if (!spoutcraft.exists()) {
			return true;
		}
		stateChanged(message, progress / steps);
		progress += 100F;
		File libDir = new File(binDir, "lib");
		libDir.mkdir();

		Iterator<Entry<String, Object>> i = libraries.entrySet().iterator();
		while (i.hasNext()) {
			Entry<String, Object> lib = i.next();
			File libraryFile = new File(libDir, lib.getKey() + ".jar");
			if (!libraryFile.exists()) {
				return true;
			}
			stateChanged(message, progress / steps);
			progress += 100F;
		}

		return false;
	}

	public static long copy(InputStream input, OutputStream output) throws IOException {
		byte[] buffer = new byte[1024 * 4];
		long count = 0;
		int n = 0;
		while (-1 != (n = input.read(buffer))) {
			output.write(buffer, 0, n);
			count += n;
		}
		return count;
	}

	public static void copy(File input, File output) throws IOException {
		FileInputStream inputStream = null;
		FileOutputStream outputStream = null;
		try {
			inputStream = new FileInputStream(input);
			outputStream = new FileOutputStream(output);
			copy(inputStream, outputStream);
		}
		finally {
			if (inputStream != null) {
				inputStream.close();
			}
			if (outputStream != null) {
				outputStream.close();
			}
		}
	}

	public void performBackup() throws IOException {
		if (!backupDir.exists()) {
			backupDir.mkdir();
		}

		SpoutcraftBuild build = SpoutcraftBuild.getSpoutcraftBuild();

		File zip = new File(GameUpdater.backupDir, build.getBuild() + "-backup.zip");

		if (!zip.exists()) {
			String rootDir = PlatformUtils.getWorkingDirectory() + File.separator;
			HashSet<File> exclude = new HashSet<File>();
			exclude.add(GameUpdater.backupDir);
			if (!SettingsUtil.isWorldBackup()) {
				exclude.add(GameUpdater.savesDir);
			}
			exclude.add(GameUpdater.updateDir);
			exclude.add(SystemConsoleListener.logDir);

			File[] existingBackups = backupDir.listFiles();
			(new BackupCleanupThread(existingBackups)).start();
			zip.createNewFile();
			addFilesToExistingZip(zip, getFiles(PlatformUtils.getWorkingDirectory(), exclude, rootDir), rootDir, false);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public boolean canPlayOffline() {
		try {
			String path = (String) AccessController.doPrivileged(new PrivilegedExceptionAction() {
				public Object run() throws Exception {
					return PlatformUtils.getWorkingDirectory() + File.separator + "bin" + File.separator;
				}
			});
			File dir = new File(path);
			if (!dir.exists()) {
				return false;
			}

			dir = new File(dir, "minecraft.jar");
			if (!dir.exists()) {
				return false;
			}
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		return false;
	}

	public void addFilesToExistingZip(File zipFile, Set<ClassFile> files, String rootDir, boolean progressBar) throws IOException {
		File tempFile = File.createTempFile(zipFile.getName(), null, zipFile.getParentFile());
		tempFile.delete();

		copy(zipFile, tempFile);
		boolean renameOk = zipFile.renameTo(tempFile);;
		if (!renameOk) {
			if (tempFile.exists()) {
				zipFile.delete();
			} else {
				throw new RuntimeException("could not rename the file " + zipFile.getAbsolutePath() + " to " + tempFile.getAbsolutePath());
			}
		}
		byte[] buf = new byte[1024];

		float progress = 0F;
		float progressStep = 0F;
		if (progressBar) {
			int jarSize = new JarFile(tempFile).size();
			progressStep = 100F / (files.size() + jarSize);
		}

		ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(tempFile)));
		ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
		ZipEntry entry = zin.getNextEntry();
		while (entry != null) {
			String name = entry.getName();
			ClassFile entryFile = new ClassFile(name);
			if (!name.contains("META-INF") && !files.contains(entryFile)) {
				out.putNextEntry(new ZipEntry(name));
				int len;
				while ((len = zin.read(buf)) > 0) {
					out.write(buf, 0, len);
				}
			}
			entry = zin.getNextEntry();

			progress += progressStep;
			if (progressBar) {
				stateChanged("Merging Spoutcraft Files Into Minecraft Jar...", progress);
			}
		}
		zin.close();
		for (ClassFile file : files) {
			try {
				InputStream in = new FileInputStream(file.getFile());

				String path = file.getPath();
				path = path.replace(rootDir, "");
				path = path.replaceAll("\\\\", "/");
				out.putNextEntry(new ZipEntry(path));

				int len;
				while ((len = in.read(buf)) > 0) {
					out.write(buf, 0, len);
				}

				progress += progressStep;
				if (progressBar) {
					stateChanged("Merging Spoutcraft Files Into Minecraft Jar...", progress);
				}

				out.closeEntry();
				in.close();
			} catch (Exception e) {
			}
		}

		out.close();
	}

	// I know that is is not the best method but screw it, I am tired of trying to do it myself :P
	private void extractLZMA(String in, String out) throws Exception {
		String[] args = { "d", in, out };
		LzmaAlone.main(args);
	}

	@SuppressWarnings("unused")
	private void extractPack(String in, String out) throws Exception {
		File f = new File(in);
		if (!f.exists()) {
			return;
		}

		FileOutputStream fostream = new FileOutputStream(out);
		JarOutputStream jostream = new JarOutputStream(fostream);

		Pack200.Unpacker unpacker = Pack200.newUnpacker();
		unpacker.unpack(f, jostream);
		jostream.close();

		f.delete();
	}

	public Set<ClassFile> getFiles(File dir, String rootDir) {
		return getFiles(dir, new HashSet<File>(), rootDir);
	}

	public Set<ClassFile> getFiles(File dir, Set<File> exclude, String rootDir) {
		HashSet<ClassFile> result = new HashSet<ClassFile>();
		for (File file : dir.listFiles()) {
			if (!exclude.contains(dir)) {
				if (file.isDirectory()) {
					result.addAll(this.getFiles(file, exclude, rootDir));
					continue;
				}
				result.add(new ClassFile(file, rootDir));
			}
		}
		return result;
	}

	public void stateChanged(String fileName, float progress) {
		this.listener.stateChanged(fileName, progress);
	}

	public void setListener(DownloadListener listener) {
		this.listener = listener;
	}
}
