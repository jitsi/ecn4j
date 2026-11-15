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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.net.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.jupiter.api.*;

public class EcnDatagramSocketTest
{
    private static final InetAddress LOOPBACK_V4 = address("127.0.0.1");

    private static final InetAddress LOOPBACK_V6 = address("::1");

    private static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase().contains("linux");

    /** The traffic classes to send: each ECN codepoint, and ECT(1) combined with a DSCP. */
    private static final int[] TRAFFIC_CLASSES = { Ecn.NOT_ECT, Ecn.ECT_1, Ecn.ECT_0, Ecn.CE, 0xb8 | Ecn.ECT_1 };

    private static InetAddress address(String s)
    {
        try
        {
            return InetAddress.getByName(s);
        }
        catch (UnknownHostException e)
        {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    public void requireNativeLibrary()
    {
        // The library has to be built for this platform, see the README. CI sets skipNativeTests where it is not.
        if (Boolean.getBoolean("skipNativeTests"))
        {
            assumeTrue(EcnDatagramSocket.isSupported(), "The native library is not available");
        }
        else
        {
            assertTrue(EcnDatagramSocket.isSupported(), "The native library is not available");
        }
    }

    private static void assumeIpv6Loopback()
    {
        try (DatagramSocket s = new DatagramSocket(new InetSocketAddress(LOOPBACK_V6, 0)))
        {
            assumeTrue(s.isBound());
        }
        catch (Exception e)
        {
            assumeTrue(false, "No IPv6 loopback: " + e);
        }
    }

    /**
     * Receives a datagram, failing the test instead of blocking forever if none arrives: the socket is closed after
     * a few seconds, which makes the receive throw.
     */
    private static int receive(EcnDatagramSocket receiver, DatagramPacket packet)
        throws Exception
    {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        try
        {
            watchdog.schedule(receiver::close, 3, TimeUnit.SECONDS);
            return receiver.receive(packet);
        }
        catch (SocketException e)
        {
            throw new AssertionError("No datagram was received on " + receiver.getLocalSocketAddress(), e);
        }
        finally
        {
            watchdog.shutdownNow();
        }
    }

    /**
     * Opens a socket bound to {@code source} that sends with the traffic class {@code trafficClass}.
     *
     * It is the socket of a {@link DatagramChannel}, because the old plain implementation of
     * {@link DatagramSocket} clears the lowest bit of the traffic class for IPv4 on Linux, in
     * {@link DatagramSocket#setTrafficClass}. That bit is part of the ECN field. The plain implementation is the
     * default before Java 15, and can be selected with the system property
     * {@code jdk.net.usePlainDatagramSocketImpl} in Java 15 through 17.
     */
    private static DatagramSocket openSender(InetAddress source, int trafficClass)
        throws Exception
    {
        DatagramChannel channel = DatagramChannel.open(
            source instanceof Inet4Address ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6);
        try
        {
            channel.bind(new InetSocketAddress(source, 0));
            channel.setOption(StandardSocketOptions.IP_TOS, trafficClass);
            assertEquals(trafficClass, channel.getOption(StandardSocketOptions.IP_TOS));
        }
        catch (Exception e)
        {
            channel.close();
            throw e;
        }
        return channel.socket();
    }

    private static void send(DatagramSocket sender, String text, SocketAddress to)
        throws Exception
    {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        sender.send(new DatagramPacket(data, data.length, to));
    }

    private static String text(DatagramPacket p)
    {
        return new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
    }

    /** Sends one datagram with each traffic class from {@code source} and checks how each is received. */
    private static void checkTrafficClasses(EcnDatagramSocket receiver, InetAddress source, InetAddress destination)
        throws Exception
    {
        SocketAddress to = new InetSocketAddress(destination, receiver.getLocalSocketAddress().getPort());
        byte[] buf = new byte[1500];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        for (int tc : TRAFFIC_CLASSES)
        {
            try (DatagramSocket sender = openSender(source, tc))
            {
                send(sender, "tc " + tc, to);

                packet.setLength(buf.length);
                int received = receive(receiver, packet);

                assertEquals("tc " + tc, text(packet));
                assertEquals(source, packet.getAddress());
                assertEquals(sender.getLocalPort(), packet.getPort());
                assertNotEquals(EcnDatagramSocket.TRAFFIC_CLASS_UNKNOWN, received, "traffic class not reported");
                assertEquals(tc & Ecn.MASK, Ecn.fromTrafficClass(received), "ECN field of traffic class " + tc);
                if (IS_LINUX)
                {
                    // Linux carries the whole octet over the loopback interface.
                    assertEquals(tc, received);
                }
            }
        }
    }

    @Test
    public void reportsTrafficClassOverIpv4()
        throws Exception
    {
        try (EcnDatagramSocket receiver = new EcnDatagramSocket(new InetSocketAddress(LOOPBACK_V4, 0), false))
        {
            assertEquals(LOOPBACK_V4, receiver.getLocalSocketAddress().getAddress());
            assertNotEquals(0, receiver.getLocalSocketAddress().getPort());
            checkTrafficClasses(receiver, LOOPBACK_V4, LOOPBACK_V4);
        }
    }

    @Test
    public void reportsTrafficClassOverIpv6()
        throws Exception
    {
        assumeIpv6Loopback();
        try (EcnDatagramSocket receiver = new EcnDatagramSocket(new InetSocketAddress(LOOPBACK_V6, 0), false))
        {
            assertEquals(LOOPBACK_V6, receiver.getLocalSocketAddress().getAddress());
            checkTrafficClasses(receiver, LOOPBACK_V6, LOOPBACK_V6);
        }
    }

    @Test
    public void wildcardSocketReportsTrafficClassOfBothFamilies()
        throws Exception
    {
        try (EcnDatagramSocket receiver = new EcnDatagramSocket(new InetSocketAddress(0), false))
        {
            assertTrue(receiver.getLocalSocketAddress().getAddress().isAnyLocalAddress());
            // IPv4 senders must be seen with their IPv4 address, not an IPv4-mapped IPv6 one.
            checkTrafficClasses(receiver, LOOPBACK_V4, LOOPBACK_V4);
            assumeIpv6Loopback();
            checkTrafficClasses(receiver, LOOPBACK_V6, LOOPBACK_V6);
        }
    }

    /**
     * The intended use: ordinary sockets bound to an address for sending, and an {@link EcnDatagramSocket} bound to
     * the same address last, which is the one that receives.
     */
    @Test
    public void receivesInsteadOfJavaSocketsSharingAnIpv4Address()
        throws Exception
    {
        checkSharing(new InetSocketAddress(LOOPBACK_V4, 0), LOOPBACK_V4);
    }

    @Test
    public void receivesInsteadOfJavaSocketsSharingAnIpv6Address()
        throws Exception
    {
        assumeIpv6Loopback();
        checkSharing(new InetSocketAddress(LOOPBACK_V6, 0), LOOPBACK_V6);
    }

    @Test
    public void receivesInsteadOfJavaSocketsSharingTheWildcardAddress()
        throws Exception
    {
        // macOS does not deliver to the socket bound last when the sockets are bound to the wildcard address.
        assumeTrue(IS_LINUX, "Sharing the wildcard address is only supported on Linux");
        checkSharing(new InetSocketAddress(0), LOOPBACK_V4);
        assumeIpv6Loopback();
        checkSharing(new InetSocketAddress(0), LOOPBACK_V6);
    }

    /**
     * Binds four ordinary sockets and then an {@link EcnDatagramSocket} to {@code bindTo}, and exchanges datagrams
     * with a peer on the loopback address {@code loopback}.
     */
    private static void checkSharing(InetSocketAddress bindTo, InetAddress loopback)
        throws Exception
    {
        List<DatagramSocket> pool = new ArrayList<>();
        try
        {
            SocketAddress bindAddress = bindTo;
            for (int i = 0; i < 4; i++)
            {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.bind(bindAddress);
                s.setSoTimeout(200);
                bindAddress = s.getLocalSocketAddress();
                pool.add(s);
            }
            int port = ((InetSocketAddress) bindAddress).getPort();
            // Where the peer sends to, and where it sees the pool's datagrams coming from.
            SocketAddress shared = new InetSocketAddress(loopback, port);

            try (EcnDatagramSocket receiver = new EcnDatagramSocket((InetSocketAddress) bindAddress, true);
                DatagramSocket peer = openSender(loopback, Ecn.ECT_1))
            {
                assertEquals(port, receiver.getLocalSocketAddress().getPort());
                assertEquals(bindTo.getAddress().isAnyLocalAddress(),
                    receiver.getLocalSocketAddress().getAddress().isAnyLocalAddress());
                byte[] buf = new byte[1500];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);

                for (int i = 0; i < 20; i++)
                {
                    send(peer, "to receiver " + i, shared);
                    packet.setLength(buf.length);
                    int tc = receive(receiver, packet);
                    assertEquals("to receiver " + i, text(packet));
                    assertEquals(peer.getLocalSocketAddress(), packet.getSocketAddress());
                    assertEquals(Ecn.ECT_1, Ecn.fromTrafficClass(tc));

                    // The pool can still send from the shared address, and the peer sees that address as the source.
                    send(pool.get(i % pool.size()), "from pool " + i, peer.getLocalSocketAddress());
                    packet.setLength(buf.length);
                    peer.receive(packet);
                    assertEquals("from pool " + i, text(packet));
                    assertEquals(shared, packet.getSocketAddress());
                }

                // None of the datagrams went to the pool's sockets.
                for (DatagramSocket s : pool)
                {
                    packet.setLength(buf.length);
                    assertThrows(SocketTimeoutException.class, () -> s.receive(packet));
                }
            }
        }
        finally
        {
            pool.forEach(DatagramSocket::close);
        }
    }

    @Test
    public void receivesIntoThePacketsOffsetAndTruncates()
        throws Exception
    {
        try (EcnDatagramSocket receiver = new EcnDatagramSocket(new InetSocketAddress(LOOPBACK_V4, 0), false);
            DatagramSocket sender = new DatagramSocket(new InetSocketAddress(LOOPBACK_V4, 0)))
        {
            byte[] buf = new byte[32];
            Arrays.fill(buf, (byte) '.');
            DatagramPacket packet = new DatagramPacket(buf, 4, 8);

            send(sender, "0123456789abcdef", receiver.getLocalSocketAddress());
            receive(receiver, packet);
            assertEquals(4, packet.getOffset());
            assertEquals(8, packet.getLength());
            assertEquals("....01234567....................", new String(buf, StandardCharsets.UTF_8));

            // An empty datagram is a datagram, not the end of the socket.
            sender.send(new DatagramPacket(new byte[0], 0, receiver.getLocalSocketAddress()));
            packet.setLength(8);
            receive(receiver, packet);
            assertEquals(0, packet.getLength());
            assertEquals(sender.getLocalPort(), packet.getPort());

            send(sender, "xy", receiver.getLocalSocketAddress());
            packet.setLength(8);
            receive(receiver, packet);
            assertEquals("xy", text(packet));
        }
    }

    @Test
    public void closeWakesABlockedReceive()
        throws Exception
    {
        EcnDatagramSocket receiver = new EcnDatagramSocket(new InetSocketAddress(LOOPBACK_V4, 0), false);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() ->
        {
            try
            {
                receiver.receive(new DatagramPacket(new byte[1500], 1500));
            }
            catch (Throwable e)
            {
                thrown.set(e);
            }
            done.countDown();
        });
        t.setDaemon(true);
        t.start();
        // Let it block, across at least one native receive timeout.
        Thread.sleep(600);
        assertEquals(1, done.getCount(), "receive returned without a datagram");

        Instant start = Instant.now();
        receiver.close();
        assertTrue(done.await(5, TimeUnit.SECONDS), "receive did not return after close");
        Duration took = Duration.between(start, Instant.now());

        assertTrue(thrown.get() instanceof SocketException, "expected a SocketException, got " + thrown.get());
        assertTrue(receiver.isClosed());
        assertTrue(took.toMillis() < 1000, "close took " + took);
        if (IS_LINUX)
        {
            // Shutting the socket down wakes the receiver at once; elsewhere it may take a receive timeout.
            assertTrue(took.toMillis() < 150, "close took " + took);
        }

        // Further use fails cleanly, and closing again is harmless.
        assertThrows(SocketException.class, () -> receiver.receive(new DatagramPacket(new byte[10], 10)));
        assertThrows(SocketException.class, receiver::getReceiveBufferSize);
        receiver.close();
    }

    @Test
    public void receiveBufferSizeCanBeSet()
        throws Exception
    {
        try (EcnDatagramSocket receiver = new EcnDatagramSocket(new InetSocketAddress(LOOPBACK_V4, 0), false))
        {
            int initial = receiver.getReceiveBufferSize();
            assertTrue(initial > 0);
            receiver.setReceiveBufferSize(32 * 1024);
            int small = receiver.getReceiveBufferSize();
            receiver.setReceiveBufferSize(128 * 1024);
            int large = receiver.getReceiveBufferSize();
            assertTrue(large > small, "expected " + large + " > " + small);
            assertThrows(IllegalArgumentException.class, () -> receiver.setReceiveBufferSize(0));
        }
    }

    @Test
    public void bindingAnAddressInUseFails()
        throws Exception
    {
        try (EcnDatagramSocket first = new EcnDatagramSocket(new InetSocketAddress(LOOPBACK_V4, 0), false))
        {
            assertThrows(BindException.class, () -> new EcnDatagramSocket(first.getLocalSocketAddress(), false).close());
        }
    }
}
