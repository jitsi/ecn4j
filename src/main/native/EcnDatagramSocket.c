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

/* Request IPV6_RECVTCLASS and friends (RFC 3542) on macOS. */
#define __APPLE_USE_RFC_3542 1
#define _GNU_SOURCE 1

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <netinet/in.h>

#include "org_jitsi_ecn4j_EcnDatagramSocket.h"

/* The largest datagram which will be received. A UDP payload can not be longer than this. */
#define MAX_DATAGRAM 65536

/* How long a receive blocks before returning to Java, so that a closed socket is noticed even on a system where
 * shutting a socket down does not wake a blocked receive. */
#define RECV_TIMEOUT_MS 250

/* Return values of recv, matching the constants in EcnDatagramSocket.java. */
#define RECV_TIMEOUT (-1)
#define RECV_SHUTDOWN (-2)

/* The layout of the metadata array, matching the constants in EcnDatagramSocket.java. */
#define META_TC_VALID 0
#define META_TC 1
#define META_ADDR_LEN 2
#define META_PORT 4
#define META_ADDR 6
#define META_SCOPE 22
#define META_SIZE 26

static void throw_errno(JNIEnv *env, const char *exception, const char *what, int err)
{
    char message[256];
    jclass cls;

    snprintf(message, sizeof(message), "%s: %s", what, strerror(err));
    cls = (*env)->FindClass(env, exception);
    if (cls != NULL)
    {
        (*env)->ThrowNew(env, cls, message);
    }
}

static void throw_socket_exception(JNIEnv *env, const char *what, int err)
{
    throw_errno(env, "java/net/SocketException", what, err);
}

/* Describes an address in the metadata layout. An IPv4-mapped IPv6 address is described as the IPv4 address. */
static void address_to_meta(const struct sockaddr_storage *ss, jbyte *meta)
{
    if (ss->ss_family == AF_INET)
    {
        const struct sockaddr_in *sin = (const struct sockaddr_in *) ss;

        meta[META_ADDR_LEN] = 4;
        memcpy(&meta[META_PORT], &sin->sin_port, 2);
        memcpy(&meta[META_ADDR], &sin->sin_addr, 4);
    }
    else if (ss->ss_family == AF_INET6)
    {
        const struct sockaddr_in6 *sin6 = (const struct sockaddr_in6 *) ss;

        memcpy(&meta[META_PORT], &sin6->sin6_port, 2);
        if (IN6_IS_ADDR_V4MAPPED(&sin6->sin6_addr))
        {
            meta[META_ADDR_LEN] = 4;
            memcpy(&meta[META_ADDR], &sin6->sin6_addr.s6_addr[12], 4);
        }
        else
        {
            uint32_t scope = htonl(sin6->sin6_scope_id);

            meta[META_ADDR_LEN] = 16;
            memcpy(&meta[META_ADDR], &sin6->sin6_addr, 16);
            memcpy(&meta[META_SCOPE], &scope, 4);
        }
    }
}

JNIEXPORT jint JNICALL
Java_org_jitsi_ecn4j_EcnDatagramSocket_open(
    JNIEnv *env, jclass clazz, jbyteArray address, jint port, jint scope_id, jboolean reuse_address)
{
    struct sockaddr_storage ss;
    socklen_t ss_len;
    int family;
    int fd;
    int on = 1;
    int off = 0;
    struct timeval timeout = { RECV_TIMEOUT_MS / 1000, (RECV_TIMEOUT_MS % 1000) * 1000 };
    jsize address_len = address == NULL ? 0 : (*env)->GetArrayLength(env, address);

    memset(&ss, 0, sizeof(ss));
    if (address_len == 4)
    {
        struct sockaddr_in *sin = (struct sockaddr_in *) &ss;

        family = AF_INET;
        ss_len = sizeof(*sin);
        sin->sin_family = AF_INET;
        sin->sin_port = htons((uint16_t) port);
        (*env)->GetByteArrayRegion(env, address, 0, 4, (jbyte *) &sin->sin_addr);
    }
    else if (address_len == 16 || address_len == 0)
    {
        struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *) &ss;

        family = AF_INET6;
        ss_len = sizeof(*sin6);
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port = htons((uint16_t) port);
        sin6->sin6_scope_id = (uint32_t) scope_id;
        if (address_len == 16)
        {
            (*env)->GetByteArrayRegion(env, address, 0, 16, (jbyte *) &sin6->sin6_addr);
        }
        /* Otherwise the address stays all zeroes: the wildcard. */
    }
    else
    {
        throw_socket_exception(env, "Invalid address length", EINVAL);
        return -1;
    }

    fd = socket(family, SOCK_DGRAM, IPPROTO_UDP);
    if (fd < 0 && family == AF_INET6 && address_len == 0 && errno == EAFNOSUPPORT)
    {
        /* No IPv6 on this system: bind the IPv4 wildcard instead. */
        struct sockaddr_in *sin = (struct sockaddr_in *) &ss;

        memset(&ss, 0, sizeof(ss));
        family = AF_INET;
        ss_len = sizeof(*sin);
        sin->sin_family = AF_INET;
        sin->sin_port = htons((uint16_t) port);
        fd = socket(family, SOCK_DGRAM, IPPROTO_UDP);
    }
    if (fd < 0)
    {
        throw_socket_exception(env, "socket", errno);
        return -1;
    }

    fcntl(fd, F_SETFD, FD_CLOEXEC);

    if (family == AF_INET6)
    {
        /* A wildcard socket receives IPv4 as well; a socket for one IPv6 address has no use for it. Failure is not
         * fatal: the socket then has the system's default behavior. */
        setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, address_len == 0 ? &off : &on, sizeof(int));
    }

    if (reuse_address)
    {
        if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on)) != 0)
        {
            int err = errno;

            close(fd);
            throw_socket_exception(env, "setsockopt(SO_REUSEADDR)", err);
            return -1;
        }
#if defined(SO_REUSEPORT) && !defined(__linux__)
        /* The BSDs and macOS only let UDP sockets share an address and port with SO_REUSEPORT, which is why the JDK
         * sets it as well when address reuse is enabled on a datagram socket there. On Linux SO_REUSEADDR is enough,
         * and SO_REUSEPORT means something else (spreading datagrams over the sockets). */
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &on, sizeof(on));
#endif
    }

    /* Ask for the traffic class of received datagrams, as described in RFC 10055 section 3.1.1: IP_RECVTOS on an
     * IPv4 socket, IPV6_RECVTCLASS on an IPv6 one. On Linux a dual-stack socket needs both to cover IPv4 datagrams;
     * the BSDs and macOS do not need the IPv4 option there, and setting it does no harm. Where an option is not
     * supported the traffic class is simply reported as unknown, so failures are ignored. */
    if (family == AF_INET6)
    {
        setsockopt(fd, IPPROTO_IPV6, IPV6_RECVTCLASS, &on, sizeof(on));
    }
    if (family == AF_INET || address_len == 0)
    {
        setsockopt(fd, IPPROTO_IP, IP_RECVTOS, &on, sizeof(on));
    }

    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    if (bind(fd, (struct sockaddr *) &ss, ss_len) != 0)
    {
        int err = errno;

        close(fd);
        throw_errno(env, "java/net/BindException", "bind", err);
        return -1;
    }

    return fd;
}

JNIEXPORT jint JNICALL
Java_org_jitsi_ecn4j_EcnDatagramSocket_recv(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint offset, jint length, jbyteArray meta_array)
{
    char data[MAX_DATAGRAM];
    /* Room for the traffic class of both address families, with space to spare for alignment. */
    char control[128];
    jbyte meta[META_SIZE];
    struct sockaddr_storage from;
    struct iovec iov;
    struct msghdr msg;
    struct cmsghdr *cmsg;
    ssize_t n;

    if (offset < 0 || length < 0 || length > (*env)->GetArrayLength(env, buf) - offset)
    {
        throw_socket_exception(env, "Invalid offset or length", EINVAL);
        return 0;
    }

    iov.iov_base = data;
    iov.iov_len = length < MAX_DATAGRAM ? (size_t) length : MAX_DATAGRAM;

    for (;;)
    {
        memset(&msg, 0, sizeof(msg));
        msg.msg_name = &from;
        msg.msg_namelen = sizeof(from);
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = control;
        msg.msg_controllen = sizeof(control);

        n = recvmsg(fd, &msg, 0);
        if (n >= 0)
        {
            break;
        }
        if (errno == EINTR)
        {
            continue;
        }
        if (errno == EAGAIN || errno == EWOULDBLOCK)
        {
            return RECV_TIMEOUT;
        }
        throw_socket_exception(env, "recvmsg", errno);
        return 0;
    }

    if (n == 0 && msg.msg_namelen == 0)
    {
        /* Not an empty datagram, which would have a source address: the socket was shut down. */
        return RECV_SHUTDOWN;
    }

    memset(meta, 0, sizeof(meta));
    address_to_meta(&from, meta);

    /* The traffic class arrives in a control message whose type and size depend on the platform and on the address
     * family of the datagram and of the socket (RFC 10055 section 3.2):
     *
     *                                   Linux                        BSDs and macOS
     *   IPv4 on an IPv4 socket          IPPROTO_IP, IP_TOS, 1 byte   IPPROTO_IP, IP_RECVTOS, 1 byte
     *   IPv4 on a dual-stack socket     IPPROTO_IP, IP_TOS, 1 byte   IPPROTO_IPV6, IPV6_TCLASS, int
     *   IPv6                            IPPROTO_IPV6, IPV6_TCLASS, int (both)
     *
     * In each case the value is the whole octet, with the DSCP in its upper six bits. */
    for (cmsg = CMSG_FIRSTHDR(&msg); cmsg != NULL; cmsg = CMSG_NXTHDR(&msg, cmsg))
    {
#if defined(__linux__)
        if (cmsg->cmsg_level == IPPROTO_IP && cmsg->cmsg_type == IP_TOS)
#else
        if (cmsg->cmsg_level == IPPROTO_IP && cmsg->cmsg_type == IP_RECVTOS)
#endif
        {
            meta[META_TC_VALID] = 1;
            meta[META_TC] = (jbyte) *(unsigned char *) CMSG_DATA(cmsg);
        }
        else if (cmsg->cmsg_level == IPPROTO_IPV6 && cmsg->cmsg_type == IPV6_TCLASS)
        {
            int tclass;

            memcpy(&tclass, CMSG_DATA(cmsg), sizeof(tclass));
            meta[META_TC_VALID] = 1;
            meta[META_TC] = (jbyte) tclass;
        }
    }

    (*env)->SetByteArrayRegion(env, buf, offset, (jsize) n, (const jbyte *) data);
    (*env)->SetByteArrayRegion(env, meta_array, 0, META_SIZE, meta);

    return (jint) n;
}

JNIEXPORT void JNICALL
Java_org_jitsi_ecn4j_EcnDatagramSocket_localAddress(JNIEnv *env, jclass clazz, jint fd, jbyteArray meta_array)
{
    struct sockaddr_storage ss;
    socklen_t ss_len = sizeof(ss);
    jbyte meta[META_SIZE];

    memset(&ss, 0, sizeof(ss));
    if (getsockname(fd, (struct sockaddr *) &ss, &ss_len) != 0)
    {
        throw_socket_exception(env, "getsockname", errno);
        return;
    }
    memset(meta, 0, sizeof(meta));
    address_to_meta(&ss, meta);
    (*env)->SetByteArrayRegion(env, meta_array, 0, META_SIZE, meta);
}

JNIEXPORT jint JNICALL
Java_org_jitsi_ecn4j_EcnDatagramSocket_getReceiveBufferSize(JNIEnv *env, jclass clazz, jint fd)
{
    int size = 0;
    socklen_t len = sizeof(size);

    if (getsockopt(fd, SOL_SOCKET, SO_RCVBUF, &size, &len) != 0)
    {
        throw_socket_exception(env, "getsockopt(SO_RCVBUF)", errno);
        return 0;
    }
    return size;
}

JNIEXPORT void JNICALL
Java_org_jitsi_ecn4j_EcnDatagramSocket_setReceiveBufferSize(JNIEnv *env, jclass clazz, jint fd, jint size)
{
    int value = size;

    if (setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &value, sizeof(value)) != 0)
    {
        throw_socket_exception(env, "setsockopt(SO_RCVBUF)", errno);
    }
}

JNIEXPORT void JNICALL
Java_org_jitsi_ecn4j_EcnDatagramSocket_shutdown0(JNIEnv *env, jclass clazz, jint fd)
{
    /* On Linux this wakes a thread blocked in recvmsg, and reports ENOTCONN for an unconnected socket. */
    shutdown(fd, SHUT_RD);
}

JNIEXPORT void JNICALL
Java_org_jitsi_ecn4j_EcnDatagramSocket_close0(JNIEnv *env, jclass clazz, jint fd)
{
    close(fd);
}
