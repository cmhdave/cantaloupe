package edu.illinois.library.cantaloupe.source.stream;

import edu.illinois.library.cantaloupe.http.Range;
import edu.illinois.library.cantaloupe.http.Response;
import edu.illinois.library.cantaloupe.util.ObjectCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageInputStreamImpl;
import java.io.IOException;

/**
 * <p>Input stream that supports crude seeking over HTTP.</p>
 *
 * <p>The stream is divided conceptually into fixed-size windows a.k.a. chunks
 * which are fetched as needed using ranged HTTP requests. This may improve
 * efficiency when reading small portions of large images that are selectively
 * readable, like JPEG2000 or multiresolution/tiled TIFF. Conversely, it may
 * reduce efficiency when reading whole images.</p>
 *
 * <p>Downloaded chunks can be cached in memory by passing a positive value to
 * {@link #setMaxChunkCacheSize(long)}. The cache is per-instance.</p>
 *
 * <p>The HTTP client is abstracted into the exceedingly simple {@link
 * HTTPImageInputStreamClient} interface, so probably any HTTP client,
 * including many cloud storage clients, can be hooked up and used easily,
 * without this class needing to know about things like request signing
 * etc.</p>
 *
 * <p>This class works only with HTTP servers that support {@literal Range}
 * requests, as indicated by the presence of a {@literal Accept-Ranges: bytes}
 * header in a {@literal HEAD} response.</p>
 *
 * @author Alex Dolski UIUC
 * @since 4.1
 */
public class HTTPImageInputStream extends ImageInputStreamImpl
        implements ImageInputStream {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HTTPImageInputStream.class);

    /**
     * Can be overridden by {@link #setWindowSize(int)}.
     */
    private static final int DEFAULT_WINDOW_SIZE = (int) Math.pow(2, 19);

    private static final double MEGABYTE = Math.pow(2, 20);

    private HTTPImageInputStreamClient client;
    private int numChunkDownloads, numChunkCacheHits;
    private int windowSize = DEFAULT_WINDOW_SIZE;
    private long streamLength;
    private int windowIndex = -1, indexWithinBuffer;
    private byte[] windowBuffer = new byte[windowSize];
    private ObjectCache<Range,byte[]> chunkCache;

    /**
     * Variant that sends a preliminary {@literal HEAD} request to retrieve
     * some needed information. Use {@link #HTTPImageInputStream(
     * HTTPImageInputStreamClient, long)} instead if you already know the
     * resource length and that the server supports {@literal HEAD} requests.
     *
     * @param client Client to use to handle requests.
     * @throws RangesNotSupportedException if the server does not support
     *         ranged requests.
     * @throws IOException if something goes wrong when checking for range
     *         support.
     */
    public HTTPImageInputStream(HTTPImageInputStreamClient client)
            throws IOException {
        this.client       = client;
        sendHEADRequest();
    }

    /**
     * @param client         Client to use to handle requests.
     * @param resourceLength Resource length/size.
     */
    public HTTPImageInputStream(HTTPImageInputStreamClient client,
                                long resourceLength) {
        this.client       = client;
        this.streamLength = resourceLength;
    }

    public long getMaxChunkCacheSize() {
        if (chunkCache != null) {
            return chunkCache.maxSize();
        }
        return 0;
    }

    public int getWindowSize() {
        return windowSize;
    }

    /**
     * Must be called before any reading or seeking occurs, but
     * <strong>after</strong> {@link #setWindowSize(int)}.
     *
     * @param maxChunkCacheSize Maximum byte size of the shared chunk cache.
     *                          Supply {@literal 0} to disable the chunk cache.
     */
    public void setMaxChunkCacheSize(long maxChunkCacheSize) {
        long count = Math.round(maxChunkCacheSize / (double) getWindowSize());
        if (count > 0) {
            chunkCache = new ObjectCache<>(count);
        }
    }

    /**
     * Must be called before any reading or seeking occurs.
     *
     * @param windowSize Window/chunk size. In general, a smaller size means
     *                   more requests will be needed, and a larger size means
     *                   more irrelevant data will have to be read and
     *                   discarded. The optimal size will vary depending on the
     *                   source image, the amount of data needed from it, and
     *                   network transfer rate vs. latency.
     */
    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
        this.windowBuffer = new byte[windowSize];
    }

    /**
     * Checks whether the server supports the {@literal Range} header and reads
     * the resource length from the {@literal Content-Length} header.
     */
    private void sendHEADRequest() throws IOException {
        Response response = client.sendHEADRequest();

        if (!"bytes".equals(response.getHeaders().getFirstValue("Accept-Ranges"))) {
            throw new RangesNotSupportedException();
        }
        streamLength = Long.parseLong(response.getHeaders().getFirstValue("Content-Length"));
    }

    @Override
    public void close() throws IOException {
        LOGGER.debug("close(): {} chunks fetched ({}MB of {}MB); {} cache hits",
                numChunkDownloads,
                ((numChunkDownloads * windowSize) / MEGABYTE),
                String.format("%.2f", streamLength / MEGABYTE),
                numChunkCacheHits);
        try {
            super.close();
        } finally {
            client       = null;
            windowBuffer = null;
        }
    }

    @Override
    public long length() {
        return streamLength;
    }

    @Override
    public int read() throws IOException {
        if (streamPos >= streamLength) {
            return -1;
        }
        prepareWindowBuffer();
        bitOffset = 0;
        int b = windowBuffer[indexWithinBuffer] & 0xff;
        indexWithinBuffer++;
        streamPos++;
        return b;
    }

    @Override
    public int read(byte[] b,
                    int offset,
                    int requestedLength) throws IOException {
        if (streamPos >= streamLength) {
            return -1;
        } else if (offset < 0) {
            throw new IndexOutOfBoundsException("Negative offset");
        } else if (requestedLength < 0) {
            throw new IndexOutOfBoundsException("Negative length");
        } else if (offset + requestedLength > b.length) {
            throw new IndexOutOfBoundsException("offset + length > buffer length");
        }

        if (streamPos + requestedLength >= streamLength) {
            requestedLength = (int) (streamLength - streamPos);
        }

        prepareWindowBuffer();
        bitOffset = 0;

        final int fulfilledLength = Math.min(
                requestedLength,
                windowBuffer.length - indexWithinBuffer);
        System.arraycopy(
                windowBuffer, indexWithinBuffer, // from, from index
                b, offset,                       // to, to index
                fulfilledLength);                // length

        indexWithinBuffer += fulfilledLength;
        streamPos += fulfilledLength;
        return fulfilledLength;
    }

    @Override
    public void seek(long pos) throws IOException {
        super.seek(pos);
        indexWithinBuffer = (int) streamPos % windowSize;
    }

    /**
     * Checks that the {@link #windowBuffer window buffer} has some readable
     * bytes remaining, and fills it with more if not.
     */
    private void prepareWindowBuffer() throws IOException {
        final int streamWindowIndex = getStreamWindowIndex();
        if (streamWindowIndex != windowIndex) {
            Range range       = getRange(streamWindowIndex);
            windowBuffer      = fetchChunk(range);
            windowIndex       = streamWindowIndex;
            indexWithinBuffer = (int) streamPos % windowSize;
        }
    }

    /**
     * Fetches a chunk for the given range by either retrieving it from the
     * chunk cache or downloading it.
     */
    private byte[] fetchChunk(Range range) throws IOException {
        // If the chunk cache is available, try to fetch the needed chunk
        // from there. Otherwise, fetch it directly from the source.
        byte[] chunk;
        if (chunkCache != null) {
            chunk = chunkCache.get(range);
            if (chunk != null) {
                LOGGER.trace("Chunk cache hit for range: {}", range);
                numChunkCacheHits++;
            } else {
                chunk = downloadChunk(range);
                chunkCache.put(range, chunk);
            }
        } else {
            chunk = downloadChunk(range);
        }
        return chunk;
    }

    private byte[] downloadChunk(Range range) throws IOException {
        LOGGER.trace("Downloading range: {}", range);
        numChunkDownloads++;
        Response response = client.sendGETRequest(range);
        return response.getBody();
    }

    private Range getRange(int windowIndex) {
        final Range range = new Range();
        range.start       = windowIndex * windowSize;
        range.end         = Math.min(range.start + windowSize, streamLength) - 1;
        range.length      = streamLength;
        return range;
    }

    /**
     * Finds the current window index based on the {@link #streamPos stream
     * position}.
     */
    private int getStreamWindowIndex() {
        return (int) Math.floor(streamPos / (double) windowSize);
    }

}