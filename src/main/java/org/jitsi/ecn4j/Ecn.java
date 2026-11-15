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

/**
 * The values of the Explicit Congestion Notification field, the two least significant bits of the IP traffic class
 * octet (RFC 3168, section 5).
 */
public final class Ecn
{
    /** Not ECN-capable transport. */
    public static final int NOT_ECT = 0;

    /** ECN-capable transport, codepoint 1. Used by L4S (RFC 9331). */
    public static final int ECT_1 = 1;

    /** ECN-capable transport, codepoint 0. */
    public static final int ECT_0 = 2;

    /** Congestion experienced. */
    public static final int CE = 3;

    /** The bits of the traffic class octet which hold the ECN field. */
    public static final int MASK = 0x3;

    /**
     * Extracts the ECN field from a traffic class as returned by
     * {@link EcnDatagramSocket#receive(java.net.DatagramPacket)}.
     *
     * @param trafficClass the traffic class octet, or {@link EcnDatagramSocket#TRAFFIC_CLASS_UNKNOWN}.
     * @return the ECN field: {@link #NOT_ECT} (also when the traffic class is unknown), {@link #ECT_1},
     * {@link #ECT_0} or {@link #CE}.
     */
    public static int fromTrafficClass(int trafficClass)
    {
        return trafficClass < 0 ? NOT_ECT : trafficClass & MASK;
    }

    private Ecn()
    {
    }
}
