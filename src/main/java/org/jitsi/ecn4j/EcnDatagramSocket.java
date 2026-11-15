/*
 * Copyright @ 2026 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.ecn4j;

import java.io.*;
import java.lang.ref.*;
import java.net.*;
import java.util.*;

import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;

/**
 * A UDP socket for receiving datagrams together with the IP traffic class octet they arrived with, which holds the
 * ECN field. {@link java.net.DatagramSocket} has no way to report it.
 * <p>
 * The socket is opened, bound, read and closed by native code through JNI. It is independent of the JDK's own socket
 * implementation and uses nothing beyond the public Java API.
 * <p>
 * This class only receives. To send from the same address and port, bind a {@link java.net.DatagramSocket} to it with
 * {@link java.net.DatagramSocket#setReuseAddress(boolean) address reuse} enabled, and create this socket last with
 * {@code reuseAddress} set: an operating system which allows several UDP sockets on one address delivers incoming
 * unicast datagrams to only one of them, and on Linux that is this socket when it is bound last. So it is on macOS
 * for a specific address, but not for the wildcard address, which can not be shared this way there.
 * <p>
 * {@link #receive(DatagramPacket)} may be called by one thread at a time, and {@link #close()} by any thread.
 */
public final class EcnDatagramSocket
    implements Closeable
{
    /**
     * The value returned by {@link #receive(DatagramPacket)} when the operating system did not report the traffic
     * class of the received datagram.
     */
    public static final int TRAFFIC_CLASS_UNKNOWN = -1;

    /** Returned by {@link #recv} when nothing arrived within the receive timeout. */
    private static final int RECV_TIMEOUT = -1;

    /** Returned by {@link #recv} when the socket has been shut down for reading. */
    private static final int RECV_SHUTDOWN = -2;

    /**
     * The layout of the metadata written by {@link #recv} and {@link #localAddress}: whether the traffic class is
     * known, the traffic class, the length of the address (4 or 16), the port (big-endian), the address, and the IPv6
     * scope ID (big-endian).
     */
    private static final int META_TC_VALID = 0;
    private static final int META_TC = 1;
    private static final int META_ADDR_LEN = 2;
    private static final int META_PORT = 4;
    private static final int META_ADDR = 6;
    private static final int META_SCOPE = 22;
    private static final int META_SIZE = 26;

    private static final Logger logger = new LoggerImpl(EcnDatagramSocket.class.getName());

    private static final boolean supported;

    private static final Cleaner cleaner;

    static
    {
        boolean loaded = false;
        try
        {
            JNIUtils.loadLibrary("ecn4j", EcnDatagramSocket.class.getClassLoader());
            loaded = true;
        }
        catch (UnsatisfiedLinkError e)
        {
            logger.info("The ecn4j native library is not available on this platform: " + e.getMessage());
        }
        supported = loaded;
        cleaner = loaded ? Cleaner.create() : null;
    }

    /**
     * Whether the native library is available. If not, the constructor throws
     * {@link UnsupportedOperationException}.
     *
     * @return {@code true} if instances of this class can be created.
     */
    public static boolean isSupported()
    {
        return supported;
    }

    /** Holds the file descriptor, so that it can be closed when a socket becomes unreachable without being closed. */
    private static final class Descriptor
        implements Runnable
    {
        private int fd;

        private Descriptor(int fd)
        {
            this.fd = fd;
        }

        @Override
        public synchronized void run()
        {
            if (fd >= 0)
            {
                close0(fd);
                fd = -1;
            }
        }
    }

    private final Descriptor descriptor;

    private final Cleaner.Cleanable cleanable;

    private final int fd;

    private final InetSocketAddress localSocketAddress;

    /** Held while a thread is in {@link #receive(DatagramPacket)}; {@link #close()} takes it before closing. */
    private final Object receiveLock = new Object();

    private volatile boolean closed = false;

    private final byte[] meta = new byte[META_SIZE];

    /** The source of the previous datagram, kept to avoid creating an address object for every datagram. */
    private final byte[] lastSource = new byte[META_SIZE];

    private InetSocketAddress lastSourceAddress = null;

    /**
     * Opens a socket and binds it.
     *
     * @param bindAddress the local address and port to bind to. A wildcard address binds a socket which receives both
     * IPv4 and IPv6 where the system supports that. Port 0 lets the system choose a port.
     * @param reuseAddress whether to allow other sockets to be bound to the same address and port (SO_REUSEADDR).
     * @throws UnsupportedOperationException if {@link #isSupported()} is {@code false}.
     * @throws IOException if the socket can not be opened or bound.
     */
    public EcnDatagramSocket(InetSocketAddress bindAddress, boolean reuseAddress)
        throws IOException
    {
        if (!supported)
        {
            throw new UnsupportedOperationException("The ecn4j native library is not available");
        }
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (bindAddress.isUnresolved())
        {
            throw new SocketException("Unresolved address: " + bindAddress);
        }
        InetAddress address = bindAddress.getAddress();
        int scopeId = address instanceof Inet6Address ? ((Inet6Address) address).getScopeId() : 0;

        fd = open(
            address.isAnyLocalAddress() ? null : address.getAddress(),
            bindAddress.getPort(),
            scopeId,
            reuseAddress);
        descriptor = new Descriptor(fd);
        cleanable = cleaner.register(this, descriptor);

        try
        {
            localAddress(fd, meta);
            localSocketAddress = addressFromMeta();
        }
        catch (IOException | RuntimeException e)
        {
            cleanable.clean();
            throw e;
        }
    }

    /**
     * Receives a datagram, blocking until one arrives or the socket is closed.
     * <p>
     * As with {@link java.net.DatagramSocket#receive(DatagramPacket)}, the data is placed in the packet's buffer
     * starting at its offset, at most {@link DatagramPacket#getLength()} bytes of it (a longer datagram is truncated),
     * and the packet's length and address are set to those of the datagram. A packet which is reused therefore needs
     * its length set again before each call.
     *
     * @param packet the packet to receive into.
     * @return the IP traffic class octet (IPv4 type of service) of the datagram, between 0 and 255, or
     * {@link #TRAFFIC_CLASS_UNKNOWN} if the system did not report it. {@link Ecn#fromTrafficClass(int)} extracts the
     * ECN field from it.
     * @throws SocketException if the socket is closed, including while this call is blocked.
     * @throws IOException if receiving fails.
     */
    public int receive(DatagramPacket packet)
        throws IOException
    {
        synchronized (receiveLock)
        {
            int n;
            do
            {
                if (closed)
                {
                    throw new SocketException("Socket closed");
                }
                n = recv(fd, packet.getData(), packet.getOffset(), packet.getLength(), meta);
                if (n == RECV_SHUTDOWN)
                {
                    throw new SocketException("Socket closed");
                }
            }
            while (n == RECV_TIMEOUT);

            packet.setLength(n);
            if (lastSourceAddress == null
                || !Arrays.equals(meta, META_ADDR_LEN, META_SIZE, lastSource, META_ADDR_LEN, META_SIZE))
            {
                lastSourceAddress = addressFromMeta();
                System.arraycopy(meta, 0, lastSource, 0, META_SIZE);
            }
            packet.setSocketAddress(lastSourceAddress);

            return meta[META_TC_VALID] != 0 ? meta[META_TC] & 0xff : TRAFFIC_CLASS_UNKNOWN;
        }
    }

    /**
     * Returns the address and port this socket is bound to.
     *
     * @return the local address, with the port the system chose if the socket was bound to port 0.
     */
    public InetSocketAddress getLocalSocketAddress()
    {
        return localSocketAddress;
    }

    /**
     * Returns the size of the socket's receive buffer (SO_RCVBUF), as the operating system reports it.
     *
     * @return the size in bytes.
     * @throws IOException if the socket is closed or the option can not be read.
     */
    public int getReceiveBufferSize()
        throws IOException
    {
        synchronized (descriptor)
        {
            checkOpen();
            return getReceiveBufferSize(fd);
        }
    }

    /**
     * Requests a size for the socket's receive buffer (SO_RCVBUF). The system may use a different size; see
     * {@link #getReceiveBufferSize()}.
     *
     * @param size the requested size in bytes.
     * @throws IOException if the socket is closed or the option can not be set.
     */
    public void setReceiveBufferSize(int size)
        throws IOException
    {
        if (size <= 0)
        {
            throw new IllegalArgumentException("Invalid receive buffer size: " + size);
        }
        synchronized (descriptor)
        {
            checkOpen();
            setReceiveBufferSize(fd, size);
        }
    }

    /**
     * Whether this socket has been closed.
     *
     * @return {@code true} once {@link #close()} has been called.
     */
    public boolean isClosed()
    {
        return closed;
    }

    /**
     * Closes the socket. A thread blocked in {@link #receive(DatagramPacket)} gets a {@link SocketException}. This
     * waits for that thread to leave the native call, which takes at most the native receive timeout (a fraction of a
     * second) and normally no time at all.
     */
    @Override
    public void close()
    {
        synchronized (descriptor)
        {
            if (closed)
            {
                return;
            }
            closed = true;
            // Wake the receiving thread, where the system supports it. Otherwise it notices within the timeout.
            shutdown0(fd);
        }
        // The descriptor must not be closed while a thread is still using it: its number could be given to another
        // file in the meantime.
        synchronized (receiveLock)
        {
            cleanable.clean();
        }
    }

    private void checkOpen()
        throws SocketException
    {
        if (closed)
        {
            throw new SocketException("Socket closed");
        }
    }

    /** Creates an address from the metadata written by the native code. */
    private InetSocketAddress addressFromMeta()
        throws UnknownHostException
    {
        int addrLen = meta[META_ADDR_LEN];
        int port = ((meta[META_PORT] & 0xff) << 8) | (meta[META_PORT + 1] & 0xff);
        byte[] addr = Arrays.copyOfRange(meta, META_ADDR, META_ADDR + addrLen);
        int scope = ((meta[META_SCOPE] & 0xff) << 24) | ((meta[META_SCOPE + 1] & 0xff) << 16)
            | ((meta[META_SCOPE + 2] & 0xff) << 8) | (meta[META_SCOPE + 3] & 0xff);
        InetAddress address = addrLen == 16 && scope != 0
            ? Inet6Address.getByAddress(null, addr, scope)
            : InetAddress.getByAddress(addr);
        return new InetSocketAddress(address, port);
    }

    /**
     * Opens and binds a socket.
     *
     * @param address the 4 or 16 bytes of the address, or {@code null} for the wildcard address.
     * @return the file descriptor.
     */
    private static native int open(byte[] address, int port, int scopeId, boolean reuseAddress)
        throws IOException;

    /**
     * Receives a datagram into {@code buf} and describes it in {@code meta}.
     *
     * @return the length of the data, {@link #RECV_TIMEOUT} or {@link #RECV_SHUTDOWN}.
     */
    private static native int recv(int fd, byte[] buf, int offset, int length, byte[] meta)
        throws IOException;

    /** Writes the address the socket is bound to into {@code meta}. */
    private static native void localAddress(int fd, byte[] meta)
        throws IOException;

    private static native int getReceiveBufferSize(int fd)
        throws IOException;

    private static native void setReceiveBufferSize(int fd, int size)
        throws IOException;

    /** Shuts the socket down for reading, so that a blocked {@link #recv} returns. */
    private static native void shutdown0(int fd);

    private static native void close0(int fd);
}
