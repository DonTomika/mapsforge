/*
 * Copyright 2010, 2011, 2012 mapsforge.org
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.mapsforge.android.maps.mapgenerator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mapsforge.android.AndroidUtils;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.IOUtils;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.os.Environment;

/**
 * A thread-safe cache for image files with a variable size and LRU policy.
 */
public class FileSystemTileCache implements TileCache {
	private static final class ImageFileNameFilter implements FilenameFilter {
		static final FilenameFilter INSTANCE = new ImageFileNameFilter();

		private ImageFileNameFilter() {
			// do nothing
		}

		@Override
		public boolean accept(File directory, String fileName) {
			return fileName.endsWith(IMAGE_FILE_NAME_EXTENSION);
		}
	}

	/**
	 * Path to the caching folder on the external storage.
	 */
	public static String CACHE_DIRECTORY = null;

	/**
	 * File name extension for cached images.
	 */
	private static final String IMAGE_FILE_NAME_EXTENSION = ".tile";

	/**
	 * Load factor of the internal HashMap.
	 */
	private static final float LOAD_FACTOR = 0.6f;

	private static final Logger LOGGER = Logger.getLogger(FileSystemTileCache.class.getName());

	/**
	 * Name of the file used for serialization of the cache map.
	 */
	private static final String SERIALIZATION_FILE_NAME = "cache.ser";

	private static final int TILE_SIZE_IN_BYTES = Tile.TILE_SIZE * Tile.TILE_SIZE * 2;

	private static Map<MapGeneratorJob, File> createMap(final int mapCapacity) {
		int initialCapacity = (int) (mapCapacity / LOAD_FACTOR) + 2;

		return new LinkedHashMap<MapGeneratorJob, File>(initialCapacity, LOAD_FACTOR, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<MapGeneratorJob, File> eldestEntry) {
				if (size() > mapCapacity) {
					remove(eldestEntry.getKey());
					if (!eldestEntry.getValue().delete()) {
						eldestEntry.getValue().deleteOnExit();
					}
				}
				return false;
			}
		};
	}

	/**
	 * Restores the serialized cache map if possible.
	 * 
	 * @param directory
	 *            the directory of the serialized map file.
	 * @return the deserialized map or null, in case of an error.
	 */
	private static Map<MapGeneratorJob, File> deserializeMap(File directory) {
		File serializedMapFile = new File(directory, SERIALIZATION_FILE_NAME);
		if (!serializedMapFile.exists() || !serializedMapFile.isFile() || !serializedMapFile.canRead()) {
			return null;
		}

		FileInputStream fileInputStream = null;
		ObjectInputStream objectInputStream = null;
		try {
			fileInputStream = new FileInputStream(serializedMapFile);
			objectInputStream = new ObjectInputStream(fileInputStream);

			// the compiler warning in the following line cannot be avoided unfortunately
			Map<MapGeneratorJob, File> map = (Map<MapGeneratorJob, File>) objectInputStream.readObject();

			if (!serializedMapFile.delete()) {
				serializedMapFile.deleteOnExit();
			}

			return map;
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, null, e);
			return null;
		} catch (ClassNotFoundException e) {
			LOGGER.log(Level.SEVERE, null, e);
			return null;
		} finally {
			IOUtils.closeQuietly(objectInputStream);
			IOUtils.closeQuietly(fileInputStream);
		}
	}

	private final Bitmap bitmapGet;
	private final ByteBuffer byteBuffer;
	private File cacheDirectory;
	private long cacheId;
	private int mapViewId;
	private int capacity;
	private Map<MapGeneratorJob, File> map;
	private boolean persistent;

	private File createCacheDirectory() {
		String cacheDirectoryPath = CACHE_DIRECTORY + this.mapViewId;
		File file = new File(cacheDirectoryPath);
		if (!file.exists() && !file.mkdirs()) {
			LOGGER.log(Level.SEVERE, "could not create directory: ", file);
			file = null;
		} else if (!file.isDirectory()) {
			LOGGER.log(Level.SEVERE, "not a directory", file);
			file = null;
		} else if (!file.canRead()) {
			LOGGER.log(Level.SEVERE, "cannot read directory", file);
			file = null;
		} else if (!file.canWrite()) {
			LOGGER.log(Level.SEVERE, "cannot write directory", file);
			file = null;
		}
		return file;
	}

	private int checkCapacity(int requestedCapacity) {
		if (requestedCapacity < 0) {
			throw new IllegalArgumentException("capacity must not be negative: " + requestedCapacity);
		} else if (AndroidUtils.applicationRunsOnAndroidEmulator()) {
			return 0;
		}

		String state = Environment.getExternalStorageState();
		if (!Environment.MEDIA_MOUNTED.equals(state)) {
			// no writable external media available
			return 0;
		}

		this.cacheDirectory = createCacheDirectory();
		if (this.cacheDirectory == null) {
			return 0;
		}

		return requestedCapacity;
	}

	/**
	 * Serializes the cache map.
	 * 
	 * @param directory
	 *            the directory of the serialized map file.
	 * @param map
	 *            the map to be serialized.
	 * @return true if the map was serialized successfully, false otherwise.
	 */
	private static boolean serializeMap(File directory, Map<MapGeneratorJob, File> map) {
		File serializedMapFile = new File(directory, SERIALIZATION_FILE_NAME);
		if (serializedMapFile.exists() && !serializedMapFile.delete()) {
			return false;
		}

		FileOutputStream fileOutputStream = null;
		ObjectOutputStream objectOutputStream = null;
		try {
			fileOutputStream = new FileOutputStream(serializedMapFile);
			objectOutputStream = new ObjectOutputStream(fileOutputStream);
			objectOutputStream.writeObject(map);
			return true;
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, null, e);
			return false;
		} finally {
			IOUtils.closeQuietly(objectOutputStream);
			IOUtils.closeQuietly(fileOutputStream);
		}
	}

	/**
	 * @param capacity
	 *            the maximum number of entries in this cache.
	 * @param mapViewId
	 *            the ID of the MapView to separate caches for different MapViews.
	 * @throws IllegalArgumentException
	 *             if the capacity is negative.
	 */
	public FileSystemTileCache(int capacity, int mapViewId) {
		this.mapViewId = mapViewId;
		this.capacity = checkCapacity(capacity);

		if (this.capacity > 0 && this.cacheDirectory != null) {
			Map<MapGeneratorJob, File> deserializedMap = deserializeMap(this.cacheDirectory);
			if (deserializedMap == null) {
				this.map = createMap(this.capacity);
			} else {
				this.map = deserializedMap;
			}
			this.byteBuffer = ByteBuffer.allocate(TILE_SIZE_IN_BYTES);
			this.bitmapGet = Bitmap.createBitmap(Tile.TILE_SIZE, Tile.TILE_SIZE, Config.RGB_565);
		} else {
			this.byteBuffer = null;
			this.bitmapGet = null;
			this.map = createMap(0);
		}
	}

	@Override
	public synchronized boolean containsKey(MapGeneratorJob mapGeneratorJob) {
		return this.map.containsKey(mapGeneratorJob);
	}

	@Override
	public synchronized void destroy() {
		if (this.bitmapGet != null) {
			this.bitmapGet.recycle();
		}

		if (this.capacity == 0) {
			return;
		}

		if (!this.persistent || !serializeMap(this.cacheDirectory, this.map)) {
			for (File file : this.map.values()) {
				if (!file.delete()) {
					file.deleteOnExit();
				}
			}
			this.map.clear();
			if (this.cacheDirectory != null) {
				File[] filesToDelete = this.cacheDirectory.listFiles(ImageFileNameFilter.INSTANCE);
				if (filesToDelete != null) {
					for (File file : filesToDelete) {
						if (!file.delete()) {
							file.deleteOnExit();
						}
					}
				}

				if (!this.cacheDirectory.delete()) {
					this.cacheDirectory.deleteOnExit();
				}
			}
		}
	}

	@Override
	public synchronized Bitmap get(MapGeneratorJob mapGeneratorJob) {
		if (this.capacity == 0) {
			return null;
		}

		FileInputStream fileInputStream = null;
		try {
			File inputFile = this.map.get(mapGeneratorJob);

			fileInputStream = new FileInputStream(inputFile);
			byte[] array = this.byteBuffer.array();
			int bytesRead = fileInputStream.read(array);

			if (bytesRead == array.length) {
				this.byteBuffer.rewind();
				this.bitmapGet.copyPixelsFromBuffer(this.byteBuffer);
				return this.bitmapGet;
			}

			return null;
		} catch (FileNotFoundException e) {
			this.map.remove(mapGeneratorJob);
			return null;
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, null, e);
			return null;
		} finally {
			try {
				if (fileInputStream != null) {
					fileInputStream.close();
				}
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, null, e);
			}
		}
	}

	@Override
	public synchronized int getCapacity() {
		return this.capacity;
	}

	@Override
	public synchronized boolean isPersistent() {
		return this.persistent;
	}

	@Override
	public synchronized void put(MapGeneratorJob mapGeneratorJob, Bitmap bitmap) {
		if (this.capacity == 0) {
			return;
		}

		FileOutputStream fileOutputStream = null;
		try {
			File outputFile;
			do {
				++this.cacheId;
				outputFile = new File(this.cacheDirectory, this.cacheId + IMAGE_FILE_NAME_EXTENSION);
			} while (outputFile.exists());

			this.byteBuffer.rewind();
			bitmap.copyPixelsToBuffer(this.byteBuffer);
			byte[] array = this.byteBuffer.array();

			fileOutputStream = new FileOutputStream(outputFile);
			fileOutputStream.write(array, 0, array.length);

			this.map.put(mapGeneratorJob, outputFile);
		} catch (IOException e) {
			// this is the exception thrown when the external storage
			// is full. We do not want the error to be repeated
			// over and over, so we set capacity to 0.
			LOGGER.log(Level.SEVERE, "external storage appears full", e);
			this.capacity = 0;
		} finally {
			try {
				if (fileOutputStream != null) {
					fileOutputStream.close();
				}
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, null, e);
			}
		}
	}

	@Override
	public synchronized void setCapacity(int capacity) {
		if (this.capacity == capacity) {
			return;
		}

		this.capacity = checkCapacity(capacity);
		if (this.capacity != 0) {
			Map<MapGeneratorJob, File> newMap = createMap(this.capacity);
			if (this.map != null) {
				newMap.putAll(this.map);
			}
			this.map = newMap;
		}
	}

	@Override
	public synchronized void setPersistent(boolean persistent) {
		this.persistent = persistent;
	}
}
