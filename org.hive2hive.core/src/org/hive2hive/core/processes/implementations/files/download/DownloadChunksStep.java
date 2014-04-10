package org.hive2hive.core.processes.implementations.files.download;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.bouncycastle.crypto.DataLengthException;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.hive2hive.core.H2HConstants;
import org.hive2hive.core.file.FileUtil;
import org.hive2hive.core.log.H2HLoggerFactory;
import org.hive2hive.core.model.Chunk;
import org.hive2hive.core.model.FileIndex;
import org.hive2hive.core.model.MetaChunk;
import org.hive2hive.core.model.MetaFileSmall;
import org.hive2hive.core.network.data.IDataManager;
import org.hive2hive.core.network.data.NetworkContent;
import org.hive2hive.core.processes.framework.exceptions.InvalidProcessStateException;
import org.hive2hive.core.processes.framework.exceptions.ProcessExecutionException;
import org.hive2hive.core.processes.implementations.common.base.BaseGetProcessStep;
import org.hive2hive.core.processes.implementations.context.DownloadFileContext;
import org.hive2hive.core.security.H2HEncryptionUtil;
import org.hive2hive.core.security.HybridEncryptedContent;

public class DownloadChunksStep extends BaseGetProcessStep {

	private final static Logger logger = H2HLoggerFactory.getLogger(DownloadChunksStep.class);

	private final DownloadFileContext context;
	private final List<Chunk> chunkBuffer;
	private final Path root;
	private int currentChunkOrder;
	private File destination;

	public DownloadChunksStep(DownloadFileContext context, IDataManager dataManager, Path root) {
		super(dataManager);
		this.context = context;
		this.root = root;
		this.currentChunkOrder = 0;
		this.chunkBuffer = new ArrayList<Chunk>();
	}

	@Override
	protected void doExecute() throws InvalidProcessStateException, ProcessExecutionException {
		MetaFileSmall metaFileSmall = (MetaFileSmall) context.consumeMetaFile();

		// support to download a specific version
		List<MetaChunk> metaChunks;
		if (context.downloadNewestVersion()) {
			metaChunks = metaFileSmall.getNewestVersion().getMetaChunks();
		} else {
			metaChunks = metaFileSmall.getVersionByIndex(context.getVersionToDownload()).getMetaChunks();
		}

		// support to store the file on another location than default (used for recovery)
		if (context.downloadToDefaultDestination()) {
			destination = FileUtil.getPath(root, context.consumeIndex()).toFile();
		} else {
			destination = context.getDestination();
		}

		if (!validateDestination()) {
			throw new ProcessExecutionException(
					"File already exists on disk. Content does match; no download needed.");
		}

		// start the download
		int counter = 0;
		for (MetaChunk metaChunk : metaChunks) {
			logger.info("File " + destination + ": Downloading chunk " + ++counter + "/" + metaChunks.size());
			NetworkContent content = get(metaChunk.getChunkId(), H2HConstants.FILE_CHUNK);
			HybridEncryptedContent encrypted = (HybridEncryptedContent) content;
			try {
				NetworkContent decrypted = H2HEncryptionUtil.decryptHybrid(encrypted, metaFileSmall.getChunkKey()
						.getPrivate());
				chunkBuffer.add((Chunk) decrypted);
			} catch (ClassNotFoundException | InvalidKeyException | DataLengthException
					| IllegalBlockSizeException | BadPaddingException | IllegalStateException
					| InvalidCipherTextException | IllegalArgumentException | IOException e) {
				throw new ProcessExecutionException("Could not decrypt file chunk.", e);
			}

			try {
				writeBufferToDisk();
			} catch (IOException e) {
				throw new ProcessExecutionException(String.format(
						"Could not write file chunk into file '%s'. reason = '%s'", destination.getName(),
						e.getMessage()), e);
			}
		}

		// all chunks downloaded
		if (chunkBuffer.isEmpty()) {
			// normal case: done with the process.
			logger.debug("Finished downloading file '" + destination + "'.");
		} else {
			logger.error("All chunks downloaded but still some in buffer.");
			throw new ProcessExecutionException("Could not write all chunks to disk. We're stuck at chunk "
					+ currentChunkOrder);
		}
	}

	/**
	 * @return true when ok, otherwise false
	 * @throws InvalidProcessStateException
	 */
	private boolean validateDestination() throws InvalidProcessStateException {
		// verify before downloading
		if (destination != null && destination.exists()) {
			try {
				// can be cast because only files are downloaded
				FileIndex fileIndex = (FileIndex) context.consumeIndex();
				if (H2HEncryptionUtil.compareMD5(destination, fileIndex.getMD5())) {
					return false;
				} else {
					logger.warn("File already exists on disk, it will be overwritten");
				}
			} catch (IOException e) {
				// ignore and just download the file
			}
		}

		return true;
	}

	/**
	 * Writes the buffered chunks to the disk (in the correct order)
	 * 
	 * @throws IOException
	 */
	private void writeBufferToDisk() throws IOException {
		List<Chunk> wroteToDisk = new ArrayList<Chunk>();
		do {
			wroteToDisk.clear();
			for (Chunk chunk : chunkBuffer) {
				if (chunk.getOrder() == currentChunkOrder) {
					// append only if already written a chunk, else overwrite the possibly existent file
					boolean append = currentChunkOrder != 0;

					FileUtils.writeByteArrayToFile(destination, chunk.getData(), append);
					wroteToDisk.add(chunk);
					currentChunkOrder++;
				}
			}

			chunkBuffer.removeAll(wroteToDisk);
		} while (!wroteToDisk.isEmpty());
	}
}
