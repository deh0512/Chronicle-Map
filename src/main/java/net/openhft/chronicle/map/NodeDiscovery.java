package net.openhft.chronicle.map;

import net.openhft.lang.collection.ATSDirectBitSet;
import net.openhft.lang.collection.DirectBitSet;
import net.openhft.lang.io.ByteBufferBytes;
import net.openhft.lang.io.Bytes;
import net.openhft.lang.io.serialization.BytesMarshallable;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.ByteBuffer.wrap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.openhft.chronicle.map.NodeDiscoveryBroadcaster.BOOTSTRAP_BYTES;
import static net.openhft.chronicle.map.NodeDiscoveryBroadcaster.LOG;
import static net.openhft.chronicle.map.Replicators.tcp;

/**
 * @author Rob Austin.
 */
public class NodeDiscovery {


    public ChronicleMap<Integer, CharSequence> discoverMap(int udpBroadcastPort, final int tcpPort) throws IOException, InterruptedException {

        final AtomicInteger proposedIdentifier = new AtomicInteger();
        final AtomicBoolean useAnotherIdentifier = new AtomicBoolean();

        final UdpReplicationConfig udpConfig = UdpReplicationConfig
                .simple(Inet4Address.getByName("255.255.255.255"), udpBroadcastPort);

        final KnownNodes knownNodes = new KnownNodes();
        final DirectBitSet knownIdentifiers = new ATSDirectBitSet(new ByteBufferBytes(ByteBuffer.allocate
                (128 / 8)));

        final AddressAndPort ourAddressAndPort = new AddressAndPort(InetAddress.getLocalHost()
                .getAddress(),
                (short) tcpPort);

        final Set<AddressAndPort> knownHostPorts = new ConcurrentSkipListSet<AddressAndPort>();

        final UDPEventListener udpEventListener = new UDPEventListener() {

            @Override
            public void onRemoteNodeEvent(KnownNodes remoteNode, ConcurrentExpiryMap<AddressAndPort, DiscoveryNodeBytesMarshallable.ProposedNodes> proposedIdentifiersWithHost) {

                knownHostPorts.addAll(remoteNode.addressAndPorts());

                orBitSets(remoteNode.activeIdentifierBitSet(), knownIdentifiers);

                for (DiscoveryNodeBytesMarshallable.ProposedNodes proposedIdentifierWithHost :
                        proposedIdentifiersWithHost.values()) {
                    if (!proposedIdentifierWithHost.addressAndPort().equals(ourAddressAndPort)) {

                        int remoteIdentifier = proposedIdentifierWithHost.identifier();
                        knownIdentifiers.set(remoteIdentifier, true);
                        knownHostPorts.add(proposedIdentifierWithHost.addressAndPort());

                        if (remoteIdentifier == proposedIdentifier.get())
                            useAnotherIdentifier.set(true);
                    }
                }
            }


        };

        final DiscoveryNodeBytesMarshallable externalizable = new DiscoveryNodeBytesMarshallable(knownNodes, udpEventListener);

        final DiscoveryNodeBytesMarshallable.ProposedNodes ourHostPort = new
                DiscoveryNodeBytesMarshallable.ProposedNodes(ourAddressAndPort, (byte) -1);

        // to start with we will send a bootstrap that just contains our hostname without and identifier

        externalizable.sendBootStrap(ourHostPort);
        Thread.sleep(10);

        // we should not get back some identifiers
        // the identifiers will come back to the callback on the nio thread, the update arrives at the
        // onRemoteNodeEvent

        externalizable.sendBootStrap(ourHostPort);
        Thread.sleep(10);
        byte identifier;


        boolean isFistTime = true;

        for (; ; ) {

            identifier = proposeRandomUnusedIdentifier(knownIdentifiers, isFistTime);
            proposedIdentifier.set(identifier);

            isFistTime = false;

            final DiscoveryNodeBytesMarshallable.ProposedNodes proposedNodes = new
                    DiscoveryNodeBytesMarshallable.ProposedNodes(ourAddressAndPort, identifier);

            externalizable.sendBootStrap(proposedNodes);

            Thread.sleep(10);

            for (int j = 0; j < 3; j++) {

                externalizable.sendBootStrap(proposedNodes);
                Thread.sleep(10);

                if (useAnotherIdentifier.get()) {
                    // given that another node host proposed the same identifier, we will choose a different one.
                    continue;
                }
            }

            break;
        }


        final NodeDiscoveryBroadcaster nodeDiscoveryBroadcaster
                = new NodeDiscoveryBroadcaster(udpConfig, 1024, externalizable);

        externalizable.setModificationNotifier(nodeDiscoveryBroadcaster);

        // we should make a local copy as this may change

        final IdentifierListener identifierListener = new IdentifierListener() {

            final ConcurrentMap<Byte, SocketAddress> identifiers = new ConcurrentHashMap<Byte,
                    SocketAddress>();

            @Override
            public boolean isIdentifierUnique(byte remoteIdentifier, SocketAddress remoteAddress) {
                final SocketAddress socketAddress = identifiers.putIfAbsent(remoteIdentifier, remoteAddress);
                knownNodes.activeIdentifierBitSet().set(remoteIdentifier);
                return socketAddress == null;
            }
        };

        // add our identifier and host:port to the list of known identifiers
        knownNodes.add(ourAddressAndPort, identifier);

        final TcpReplicationConfig tcpConfig = TcpReplicationConfig
                .of(tcpPort, toInetSocketArray(knownHostPorts))
                .heartBeatInterval(1, SECONDS).nonUniqueIdentifierListener(identifierListener);

        return ChronicleMapBuilder.of(Integer.class,
                CharSequence.class)
                .entries(20000L)
                .addReplicator(tcp(identifier, tcpConfig)).create();

    }

    private InetSocketAddress[] toInetSocketArray(Set<AddressAndPort> source) throws
            UnknownHostException {

        // make a safe copy
        final HashSet<AddressAndPort> addressAndPorts = new HashSet<AddressAndPort>(source);

        if (addressAndPorts.isEmpty())
            return new InetSocketAddress[0];

        final InetSocketAddress[] addresses = new InetSocketAddress[addressAndPorts.size()];

        int i = 0;

        for (final AddressAndPort addressAndPort : addressAndPorts) {
            addresses[i++] = new InetSocketAddress(InetAddress.getByAddress(addressAndPort.address())
                    .getHostAddress(), addressAndPort.port());
        }
        return addresses;
    }


    /**
     * bitwise OR's the two bit sets or put another way, merges the source bitset into the destination bitset
     * and returns the destination
     *
     * @param source
     * @param destination
     * @return
     */
    private DirectBitSet orBitSets(@NotNull final DirectBitSet source,
                                   @NotNull final DirectBitSet destination) {

        // merges the two bit-sets together
        for (int i = (int) source.nextSetBit(0); i > 0;
             i = (int) source.nextSetBit(i + 1)) {
            try {
                destination.set(i, true);
            } catch (IndexOutOfBoundsException e) {
                LOG.error("", e);
            }
        }

        return destination;
    }


    static byte proposeRandomUnusedIdentifier(final DirectBitSet knownIdentifiers,
                                              boolean isFirstTime) throws UnknownHostException {
        byte possible;


        // the first time, rather than choosing a random number, we will choose the last value of the IP
        // address as our random number, ( or at least something that is based upon it)
        if (isFirstTime) {
            byte[] address = InetAddress.getLocalHost().getAddress();
            int lastAddress = address[address.length - 1];
            if (lastAddress > 127)
                lastAddress = lastAddress - 127;
            if (lastAddress > 127)
                lastAddress = lastAddress - 127;

            possible = (byte) lastAddress;
        } else
            possible = (byte) (Math.random() * 128);

        int count = 0;
        for (; ; ) {

            if (knownIdentifiers.setIfClear(possible)) {
                return possible;
            }

            count++;

            if (count == 128) {
                throw new IllegalStateException("The grid is full, its not possible for any more nodes to " +
                        "going the grid.");
            }

            if (possible == 128)
                possible = 0;
            else
                possible++;
        }
    }

    /**
     * creates a bit set based on a number of bits
     *
     * @param numberOfBits the number of bits the bit set should include
     * @return a new DirectBitSet backed by a byteBuffer
     */
    private static DirectBitSet newBitSet(int numberOfBits) {
        final ByteBufferBytes bytes = new ByteBufferBytes(wrap(new byte[(numberOfBits + 7) / 8]));
        return new ATSDirectBitSet(bytes);
    }

}

/**
 * Broadcast the nodes host ports and identifiers over UDP, to make it easy to join a grid of remote nodes
 * just by name, this functionality requires UDP
 *
 * @author Rob Austin.
 */
class NodeDiscoveryBroadcaster extends UdpChannelReplicator {

    public static final Logger LOG = LoggerFactory.getLogger(NodeDiscoveryBroadcaster.class.getName());

    private static final byte UNUSED = (byte) -1;

    static final ByteBufferBytes BOOTSTRAP_BYTES;

    static {
        final String BOOTSTRAP = "BOOTSTRAP";
        BOOTSTRAP_BYTES = new ByteBufferBytes(ByteBuffer.allocate(2 + BOOTSTRAP.length()));
        BOOTSTRAP_BYTES.write((short) BOOTSTRAP.length());
        BOOTSTRAP_BYTES.append(BOOTSTRAP);
    }

    static String toString(DirectBitSet bitSet) {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < bitSet.size(); i++) {
            builder.append(bitSet.get(i) ? '1' : '0');
        }
        return builder.toString();

        // LOG.debug(type + "=" + "bitset =" + builder);
    }

    /**
     * @param replicationConfig
     * @param externalizable
     * @throws java.io.IOException
     */
    NodeDiscoveryBroadcaster(
            @NotNull final UdpReplicationConfig replicationConfig,
            final int serializedEntrySize,
            final BytesMarshallable externalizable)
            throws IOException {

        super(replicationConfig, serializedEntrySize, UNUSED);

        final UdpSocketChannelEntryWriter writer = new UdpSocketChannelEntryWriter(1024, externalizable, this);
        final UdpSocketChannelEntryReader reader = new UdpSocketChannelEntryReader(1024, externalizable);

        setReader(reader);
        setWriter(writer);

        start();
    }

    static class UdpSocketChannelEntryReader implements EntryReader {

        private final ByteBuffer in;
        private final ByteBufferBytes out;
        private final BytesMarshallable externalizable;

        /**
         * @param serializedEntrySize the maximum size of an entry include the meta data
         * @param externalizable      supports reading and writing serialize entries
         */
        UdpSocketChannelEntryReader(final int serializedEntrySize,
                                    @NotNull final BytesMarshallable externalizable) {

            // we make the buffer twice as large just to give ourselves headroom
            in = allocateDirect(serializedEntrySize * 2);

            out = new ByteBufferBytes(in);
            out.limit(0);
            in.clear();
            this.externalizable = externalizable;
        }

        /**
         * reads entries from the socket till it is empty
         *
         * @param socketChannel the socketChannel that we will read from
         * @throws IOException
         * @throws InterruptedException
         */
        public void readAll(@NotNull final DatagramChannel socketChannel) throws IOException,
                InterruptedException {

            out.clear();
            in.clear();

            socketChannel.receive(in);

            final int bytesRead = in.position();

            if (bytesRead < SIZE_OF_SHORT + SIZE_OF_SHORT)
                return;

            out.limit(in.position());

            final short invertedSize = out.readShort();
            final int size = out.readUnsignedShort();

            // check the the first 4 bytes are the inverted len followed by the len
            // we do this to check that this is a valid start of entry, otherwise we throw it away
            if (((short) ~size) != invertedSize)
                return;

            if (out.remaining() != size)
                return;

            externalizable.readMarshallable(out);
        }


    }

    static class UdpSocketChannelEntryWriter implements EntryWriter {

        private final ByteBuffer out;
        private final ByteBufferBytes in;

        @NotNull
        private final BytesMarshallable externalizable;
        private UdpChannelReplicator udpReplicator;

        UdpSocketChannelEntryWriter(final int serializedEntrySize,
                                    @NotNull final BytesMarshallable externalizable,
                                    @NotNull final UdpChannelReplicator udpReplicator) {

            this.externalizable = externalizable;
            this.udpReplicator = udpReplicator;

            // we make the buffer twice as large just to give ourselves headroom
            out = allocateDirect(serializedEntrySize * 2);
            in = new ByteBufferBytes(out);
        }


        /**
         * @param socketChannel the socketChannel that we will write to
         * @throws InterruptedException
         * @throws IOException
         */
        public int writeAll(@NotNull final DatagramChannel socketChannel)
                throws InterruptedException, IOException {

            out.clear();
            in.clear();

            // skip the size inverted
            in.skip(SIZE_OF_SHORT);

            // skip the size
            in.skip(SIZE_OF_SHORT);

            long start = in.position();

            // writes the contents of
            externalizable.writeMarshallable(in);

            long size = in.position() - start;

            // we'll write the size inverted at the start
            in.writeShort(0, ~((int) size));
            in.writeUnsignedShort(SIZE_OF_SHORT, (int) size);

            out.limit((int) in.position());

            udpReplicator.disableWrites();

            return socketChannel.write(out);
        }
    }

}


class KnownNodes implements BytesMarshallable {

    private Bytes activeIdentifiersBitSetBytes;
    private ConcurrentSkipListSet<AddressAndPort> addressAndPorts = new ConcurrentSkipListSet<AddressAndPort>();
    private ATSDirectBitSet atsDirectBitSet;

    KnownNodes() {

        this.activeIdentifiersBitSetBytes = new ByteBufferBytes(ByteBuffer.allocate(128 / 8));
        this.addressAndPorts = new ConcurrentSkipListSet<AddressAndPort>();
        this.atsDirectBitSet = new ATSDirectBitSet(this.activeIdentifiersBitSetBytes);
    }


    public Set<AddressAndPort> addressAndPorts() {
        return addressAndPorts;
    }

    public void add(AddressAndPort inetSocketAddress, byte identifier) {
        activeIdentifierBitSet().set(identifier);
        addressAndPorts.add(inetSocketAddress);
    }

    public DirectBitSet activeIdentifierBitSet() {
        return atsDirectBitSet;
    }


    @Override
    public void readMarshallable(@net.openhft.lang.model.constraints.NotNull Bytes in) throws IllegalStateException {
        short size = in.readShort();

        for (int i = 0; i < size; i++) {
            final AddressAndPort addressAndPort = new AddressAndPort();
            addressAndPort.readMarshallable(in);
            addressAndPorts.add(addressAndPort);
        }

        final ByteBufferBytes activeIdentifiersBitSetBytes = new ByteBufferBytes(ByteBuffer.allocate(128 / 8));
        activeIdentifiersBitSetBytes.readMarshallable(in);

        final ATSDirectBitSet bitset = new ATSDirectBitSet(activeIdentifiersBitSetBytes);
        for (long next = bitset.nextSetBit(0); next > 0; next = bitset.nextSetBit(next + 1)) {
            atsDirectBitSet.set(next);
        }
    }

    @Override
    public void writeMarshallable(@net.openhft.lang.model.constraints.NotNull Bytes out) {

        // make a safe copy
        final Set<AddressAndPort> addressAndPorts = new HashSet<AddressAndPort>(this.addressAndPorts);

        // write the size
        out.writeShort(addressAndPorts.size());

        for (AddressAndPort bytesMarshallable : addressAndPorts) {
            bytesMarshallable.writeMarshallable(out);
        }

        activeIdentifiersBitSetBytes.clear();
        activeIdentifiersBitSetBytes.writeMarshallable(out);

    }

    @Override
    public String toString() {
        return "RemoteNodes{" +
                " addressAndPorts=" + addressAndPorts +
                ", bitSet=" + NodeDiscoveryBroadcaster.toString(atsDirectBitSet) +
                '}';
    }
}


class AddressAndPort implements Comparable<AddressAndPort>, BytesMarshallable {
    private byte[] address;
    private short port;

    AddressAndPort(byte[] address, short port) {
        this.address = address;
        this.port = port;
    }

    public AddressAndPort() {

    }

    public byte[] getAddress() {
        return address;
    }

    public short getPort() {
        return port;
    }

    public byte[] address() {
        return address;
    }

    public short port() {
        return port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AddressAndPort that = (AddressAndPort) o;

        if (port != that.port) return false;
        if (!Arrays.equals(address, that.address)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(address);
        result = 31 * result + (int) port;
        return result;
    }


    @Override
    public int compareTo(AddressAndPort o) {
        int i = 0;
        for (byte b : address) {
            int compare = Byte.compare(b, o.address[i++]);
            if (compare != 0)
                return compare;
        }
        return Short.compare(port, o.port);
    }


    @Override
    public String toString() {
        return "AddressAndPort{" +
                "address=" + numericToTextFormat(address) +
                ", port=" + port +
                '}';
    }

    static String numericToTextFormat(byte[] src) {
        if (src.length == 4) {
            return (src[0] & 0xff) + "." + (src[1] & 0xff) + "." + (src[2] & 0xff) + "." + (src[3] & 0xff);
        }
        throw new UnsupportedOperationException();
    }


    @Override
    public void readMarshallable(@net.openhft.lang.model.constraints.NotNull Bytes in) throws IllegalStateException {
        short len = in.readShort();
        address = new byte[len];

        for (int i = 0; i < len; i++) {
            address[i] = in.readByte();
        }
        port = in.readShort();

    }

    @Override
    public void writeMarshallable(@net.openhft.lang.model.constraints.NotNull Bytes out) {
        out.writeShort(getAddress().length);
        for (byte address : getAddress()) {
            out.write(address);
        }
        out.writeShort(port);
    }
}


class DiscoveryNodeBytesMarshallable implements BytesMarshallable {

    private final KnownNodes remoteNode;

    private final AtomicBoolean bootstrapRequired = new AtomicBoolean();
    private final UDPEventListener udpEventListener;

    private ProposedNodes ourProposedIdentifier;

    final ConcurrentExpiryMap<AddressAndPort, ProposedNodes> proposedIdentifiersWithHost = new
            ConcurrentExpiryMap<AddressAndPort, ProposedNodes>(AddressAndPort.class,
            ProposedNodes.class);

    public ProposedNodes getOurProposedIdentifier() {
        return ourProposedIdentifier;
    }

    private void setOurProposedIdentifier(ProposedNodes ourProposedIdentifier) {
        this.ourProposedIdentifier = ourProposedIdentifier;
    }

    public void setModificationNotifier(Replica.ModificationNotifier modificationNotifier) {
        this.modificationNotifier = modificationNotifier;
    }

    Replica.ModificationNotifier modificationNotifier;

    public DiscoveryNodeBytesMarshallable(final KnownNodes remoteNode, UDPEventListener udpEventListener) {
        this.remoteNode = remoteNode;
        this.udpEventListener = udpEventListener;
    }

    public KnownNodes getRemoteNodes() {
        return remoteNode;
    }

    /**
     * this is used to tell nodes that are connecting to us which host and ports are in our grid, along with
     * all the identifiers.
     */
    @Override
    public void writeMarshallable(@net.openhft.lang.model.constraints.NotNull Bytes out) {

        if (bootstrapRequired.getAndSet(false)) {
            writeBootstrap(out);
            return;
        }

        remoteNode.writeMarshallable(out);

        // we are no going to broadcast all the nodes that have been bootstaping in the last second
        proposedIdentifiersWithHost.expireEntries(System.currentTimeMillis() - SECONDS.toMillis(1));

        proposedIdentifiersWithHost.writeMarshallable(out);


    }

    private boolean writeBootstrap(Bytes out) {
        final ProposedNodes ourProposedIdentifier = getOurProposedIdentifier();
        if (ourProposedIdentifier == null || ourProposedIdentifier.addressAndPort == null)
            return false;

        BOOTSTRAP_BYTES.clear();
        out.write(BOOTSTRAP_BYTES);

        ourProposedIdentifier.writeMarshallable(out);
        return true;
    }

    /**
     * @param in
     * @return returns true if the UDP message contains the text 'BOOTSTRAP'
     */
    private ProposedNodes readBootstrapMessage(Bytes in) {

        final long start = in.position();

        try {

            long size = BOOTSTRAP_BYTES.limit();

            if (size > in.remaining())
                return null;

            // reads the text bootstrap
            for (int i = 0; i < size; i++) {

                final byte byteRead = in.readByte();
                final byte expectedByte = BOOTSTRAP_BYTES.readByte(i);

                if (!(expectedByte == byteRead))
                    return null;
            }

            final ProposedNodes result = new ProposedNodes();
            result.readMarshallable(in);

            return result;

        } finally {
            in.position(start);
        }
    }


    @Override
    public void readMarshallable(@net.openhft.lang.model.constraints.NotNull Bytes in) throws IllegalStateException {

        final ProposedNodes bootstrap = readBootstrapMessage(in);
        if (bootstrap != null) {
            LOG.debug("received Bootstrap");

            proposedIdentifiersWithHost.put(bootstrap.addressAndPort, bootstrap);

            try {

                // we've received a bootstrap message so will will now rebroadcast what we know,
                // after a random delay
                Thread.sleep((int) (Math.random() * 9.0));

                // this is used to turn on the OP_WRITE, so that we can broadcast back the known host and
                // ports in the grid
                onChange();

            } catch (InterruptedException e) {
                LOG.error("", e);
            }

            return;
        }

        this.remoteNode.readMarshallable(in);
        this.proposedIdentifiersWithHost.readMarshallable(in);

        if (udpEventListener != null)
            udpEventListener.onRemoteNodeEvent(remoteNode, proposedIdentifiersWithHost);
    }


    public void onChange() {
        if (modificationNotifier != null)
            modificationNotifier.onChange();
    }

    static class ProposedNodes implements BytesMarshallable {

        private byte identifier;
        private long timestamp;
        private AddressAndPort addressAndPort;

        public ProposedNodes() {

        }

        public byte identifier() {
            return identifier;
        }

        ProposedNodes(@NotNull final AddressAndPort addressAndPort,
                      byte identifier) {
            this.addressAndPort = addressAndPort;
            this.identifier = identifier;
            this.timestamp = System.currentTimeMillis();
        }


        AddressAndPort addressAndPort() {
            return addressAndPort;
        }

        @Override
        public void readMarshallable(@net.openhft.lang.model.constraints.NotNull Bytes in) throws IllegalStateException {
            addressAndPort = new AddressAndPort();
            addressAndPort.readMarshallable(in);
            timestamp = in.readLong();
            identifier = in.readByte();

        }

        @Override
        public void writeMarshallable(@net.openhft.lang.model.constraints.NotNull Bytes out) {
            addressAndPort.writeMarshallable(out);
            out.writeLong(timestamp);
            out.writeByte(identifier);
        }

        @Override
        public String toString() {
            return "ProposedIdentifierWithHost{" +
                    "identifier=" + identifier +
                    ", timestamp=" + timestamp +
                    ", addressAndPort=" + addressAndPort +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ProposedNodes that = (ProposedNodes) o;

            if (identifier != that.identifier) return false;
            if (!addressAndPort.equals(that.addressAndPort)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = (int) identifier;
            result = 31 * result + addressAndPort.hashCode();
            return result;
        }
    }


    /**
     * sends a bootstrap message to the other nodes in the grid, the bootstrap message contains the host:port
     * and perhaps even proposed identifier of the node that sent it.
     *
     * @param proposedNodes
     */
    public void sendBootStrap(ProposedNodes proposedNodes) {
        setOurProposedIdentifier(proposedNodes);
        bootstrapRequired.set(true);
    }


}

interface UDPEventListener {
    /**
     * called when we have received a UDP message, this is called after the message has been parsed
     */
    void onRemoteNodeEvent(KnownNodes remoteNode, ConcurrentExpiryMap<AddressAndPort, DiscoveryNodeBytesMarshallable.ProposedNodes> proposedIdentifiersWithHost);
}

class ConcurrentExpiryMap<K extends BytesMarshallable, V extends BytesMarshallable> implements BytesMarshallable {

    final ConcurrentMap<K, V> map = new
            ConcurrentHashMap<K, V>();
    private final Class<K> kClass;
    private final Class<V> vClass;

    ConcurrentExpiryMap(Class<K> kClass, Class<V> vClass) {

        this.kClass = kClass;
        this.vClass = vClass;
    }

    @Override
    public void readMarshallable(@net.openhft.lang.model.constraints.NotNull Bytes in) throws IllegalStateException {

        short size = in.readShort();
        try {
            for (int i = 0; i < size; i++) {

                final K k = kClass.newInstance();
                k.readMarshallable(in);

                final V v = vClass.newInstance();
                v.readMarshallable(in);

                map.put(k, v);
            }
        } catch (Exception e) {
            LOG.error("", e);
        }

    }

    @Override
    public void writeMarshallable(@net.openhft.lang.model.constraints.NotNull Bytes out) {
        final Map<K, V> safeCopy = new HashMap<K, V>(map);
        out.writeShort(safeCopy.size());
        for (Map.Entry<K, V> entry : safeCopy.entrySet()) {
            entry.getKey().writeMarshallable(out);
            entry.getValue().writeMarshallable(out);
        }
    }

    class W<V> {
        final long timestamp;
        final V v;

        W(V v) {
            this.v = v;
            this.timestamp = System.currentTimeMillis();
        }
    }

    void put(final K k, final V v) {
        map.put(k, v);
        final W w = new W(v);
        queue.add(new Map.Entry<K, W<V>>() {

            @Override
            public K getKey() {
                return k;
            }

            @Override
            public W<V> getValue() {
                return w;
            }

            @Override
            public W<V> setValue(W<V> value) {
                throw new UnsupportedOperationException();
            }
        });
    }


    // this is used for expiry
    private final Queue<Map.Entry<K, W<V>>> queue = new ConcurrentLinkedQueue<Map.Entry<K, W<V>>>();

    java.util.Collection<V> values() {
        return map.values();
    }


    void expireEntries(long timeOlderThan) {
        for (; ; ) {

            final Map.Entry<K, W<V>> e = this.queue.peek();

            if (e == null)
                break;

            if (e.getValue().timestamp < timeOlderThan) {
                // only remote it if it has not changed
                map.remove(e.getKey(), e.getValue().v);
            }

            this.queue.poll();
        }
    }
}