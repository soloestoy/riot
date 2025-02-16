/*
 * Copyright 2006-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redis.riot.core.resource;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

import org.springframework.batch.item.WriteFailedException;
import org.springframework.batch.support.transaction.FlushFailedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Wrapper for a {@link WritableByteChannel} that delays actually writing to or
 * closing the buffer if a transaction is active. If a transaction is detected
 * on the call to {@link #write(String)} the parameter is buffered and passed on
 * to the underlying writer only when the transaction is committed.
 *
 * @author Dave Syer
 * @author Michael Minella
 *
 */
public class TransactionAwareBufferedWriter extends Writer {

	private final Object bufferKey;

	private final Object closeKey;

	private WritableByteChannel channel;

	private final Runnable closeCallback;

	// default encoding for writing to output - set to UTF-8.
	private static final String DEFAULT_CHARSET = "UTF-8";

	private String encoding = DEFAULT_CHARSET;

	/**
	 * Create a new instance with the underlying byte channel provided, and a
	 * callback to execute on close. The callback should clean up related resources
	 * like output streams or channels.
	 *
	 * @param channel       channel used to do the actual IO
	 * @param closeCallback callback to execute on close
	 */
	public TransactionAwareBufferedWriter(WritableByteChannel channel, Runnable closeCallback) {
		super();
		this.channel = channel;
		this.closeCallback = closeCallback;
		this.bufferKey = new Object();
		this.closeKey = new Object();
	}

	public void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	/**
	 * @return
	 */
	private StringBuilder getCurrentBuffer() {

		if (!TransactionSynchronizationManager.hasResource(bufferKey)) {

			TransactionSynchronizationManager.bindResource(bufferKey, new StringBuilder());

			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					clear();
				}

				@Override
				public void beforeCommit(boolean readOnly) {
					try {
						if (!readOnly) {
							complete();
						}
					} catch (IOException e) {
						throw new FlushFailedException("Could not write to output buffer", e);
					}
				}

				private void complete() throws IOException {
					StringBuilder buffer = (StringBuilder) TransactionSynchronizationManager.getResource(bufferKey);
					if (buffer != null) {
						String string = buffer.toString();
						byte[] bytes = string.getBytes(encoding);
						int bufferLength = bytes.length;
						ByteBuffer bb = ByteBuffer.wrap(bytes);
						int bytesWritten = channel.write(bb);
						if (bytesWritten != bufferLength) {
							throw new IOException("All bytes to be written were not successfully written");
						}
						if (TransactionSynchronizationManager.hasResource(closeKey)) {
							closeCallback.run();
						}
					}
				}

				private void clear() {
					if (TransactionSynchronizationManager.hasResource(bufferKey)) {
						TransactionSynchronizationManager.unbindResource(bufferKey);
					}
					if (TransactionSynchronizationManager.hasResource(closeKey)) {
						TransactionSynchronizationManager.unbindResource(closeKey);
					}
				}

			});

		}

		return (StringBuilder) TransactionSynchronizationManager.getResource(bufferKey);

	}

	/**
	 * Convenience method for clients to determine if there is any unflushed data.
	 *
	 * @return the current size (in bytes) of unflushed buffered data
	 */
	public long getBufferSize() {
		if (!transactionActive()) {
			return 0L;
		}
		StringBuilder buffer = getCurrentBuffer();
		if (buffer == null) {
			return 0;
		}
		try {
			return buffer.toString().getBytes(encoding).length;
		} catch (UnsupportedEncodingException e) {
			throw new WriteFailedException(
					"Could not determine buffer size because of unsupported encoding: " + encoding, e);
		}
	}

	/**
	 * @return
	 */
	private boolean transactionActive() {
		return TransactionSynchronizationManager.isActualTransactionActive();
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.io.Writer#close()
	 */
	@Override
	public void close() throws IOException {
		if (transactionActive()) {
			StringBuilder buffer = getCurrentBuffer();
			if (buffer != null && buffer.length() > 0) {
				TransactionSynchronizationManager.bindResource(closeKey, Boolean.TRUE);
			}
			return;
		}
		closeCallback.run();
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.io.Writer#flush()
	 */
	@Override
	public void flush() throws IOException {
		// do nothing
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.io.Writer#write(char[], int, int)
	 */
	@Override
	public void write(char[] cbuf, int off, int len) throws IOException {

		if (!transactionActive()) {
			char[] subArray = new char[len];
			System.arraycopy(cbuf, off, subArray, 0, len);
			byte[] bytes = new String(subArray).getBytes(encoding);
			int length = bytes.length;
			ByteBuffer bb = ByteBuffer.wrap(bytes);
			int bytesWritten = channel.write(bb);
			if (bytesWritten != length) {
				throw new IOException(
						"Unable to write all data.  Bytes to write: " + len + ".  Bytes written: " + bytesWritten);
			}
			return;
		}

		StringBuilder buffer = getCurrentBuffer();
		if (buffer != null) {
			buffer.append(cbuf, off, len);
		}
	}
}
