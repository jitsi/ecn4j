# ecn4j

A UDP socket for Java which reports the ECN field of received datagrams.

`java.net.DatagramSocket` and `java.nio.channels.DatagramChannel` have no way
to tell an application which
[Explicit Congestion Notification](https://www.rfc-editor.org/rfc/rfc3168)
marking a datagram arrived with. A receiver needs it in order to report
congestion marks back to the sender, for example in
[RFC 8888](https://www.rfc-editor.org/rfc/rfc8888) feedback for
[L4S](https://www.rfc-editor.org/rfc/rfc9330).

`EcnDatagramSocket` opens its own socket through JNI and returns the IP traffic
class octet of each datagram from `receive`:

```java
try (EcnDatagramSocket socket = new EcnDatagramSocket(new InetSocketAddress(10000), false))
{
    DatagramPacket packet = new DatagramPacket(new byte[1500], 1500);
    int trafficClass = socket.receive(packet);
    if (Ecn.fromTrafficClass(trafficClass) == Ecn.CE)
    {
        // The datagram was marked "congestion experienced".
    }
}
```

It uses only the public Java API and POSIX sockets, and does not depend on how
the JDK implements its own sockets.

## Sending

`EcnDatagramSocket` only receives. Sending needs no native code: the ECN field
of outgoing datagrams can be set with `DatagramSocket.setTrafficClass`.

To send and receive on one address and port, bind ordinary `DatagramSocket`s
to it with `setReuseAddress(true)`, then create the `EcnDatagramSocket` on the
same address with `reuseAddress` set. Incoming datagrams are delivered to the
`EcnDatagramSocket`. (On macOS this works for a specific address but not for
the wildcard address.)

## Platforms

Native libraries are built for Linux (x86-64, aarch64, ppc64le) and macOS
(x86-64, aarch64). Elsewhere `EcnDatagramSocket.isSupported()` returns
`false`, and an application should fall back to `DatagramSocket` and treat
every datagram as not ECN-capable.

The native code follows [RFC 10055](https://www.rfc-editor.org/rfc/rfc10055),
which describes how each platform reports the ECN field of a received UDP
datagram. Of the platforms covered there, FreeBSD behaves like macOS and
should need nothing beyond a build, and Windows has a different API and is not
implemented.

## Building with Java changes only

To avoid having to build the native libraries, execute
`resources/fetch-maven.sh` to download and extract the native binaries from the
latest release on Maven Central.

## Building the native libraries

The JNI headers are generated when the Java code is compiled, so run
`mvn compile` first.

### Ubuntu

Prerequisites:

- Docker

Run `resources/ubuntu-build-all.sh` to cross-compile the library for all
supported Linux architectures, or
`resources/ubuntu-build-docker.sh <arch> <dist> <java-version>` for one
(`arch` is `amd64`, `arm64` or `ppc64el`).

### Mac

Prerequisites:

- XCode
- CMake
- A JDK

Run `resources/mac-cmake.sh <java-home> <arch>` (`arch` is `x86_64` or
`arm64`).

The libraries are installed into `src/main/resources/<os>-<arch>` and packaged
into the jar. `mvn test` runs the tests against the library for the current
platform; pass `-DskipNativeTests=true` to skip them when it has not been
built.
