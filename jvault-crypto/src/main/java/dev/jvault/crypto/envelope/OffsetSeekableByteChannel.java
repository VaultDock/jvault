package dev.jvault.crypto.envelope;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

/**
 * A read-only view of a channel that hides a fixed-length prefix.
 *
 * <p>The envelope header sits in front of the ciphertext in the same object, but Tink's seekable
 * decrypter expects position 0 to be the first ciphertext byte. Rather than store the header
 * elsewhere — which would cost the self-describing property that makes disaster recovery possible
 * — this shifts the coordinate system.
 */
final class OffsetSeekableByteChannel implements SeekableByteChannel {

    private final SeekableByteChannel delegate;
    private final long offset;

    OffsetSeekableByteChannel(SeekableByteChannel delegate, long offset) throws IOException {
        this.delegate = delegate;
        this.offset = offset;
        delegate.position(offset);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return delegate.read(dst);
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public long position() throws IOException {
        return delegate.position() - offset;
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        if (newPosition < 0) {
            throw new IllegalArgumentException("position must not be negative");
        }
        delegate.position(offset + newPosition);
        return this;
    }

    @Override
    public long size() throws IOException {
        return delegate.size() - offset;
    }

    @Override
    public SeekableByteChannel truncate(long size) {
        throw new NonWritableChannelException();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
