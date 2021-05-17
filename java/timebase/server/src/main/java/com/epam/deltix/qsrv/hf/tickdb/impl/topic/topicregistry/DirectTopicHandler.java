/*
 * Copyright 2021 EPAM Systems, Inc
 *
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. Licensed under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.epam.deltix.qsrv.hf.tickdb.impl.topic.topicregistry;

import com.lmax.disruptor.util.Util;
import com.epam.deltix.timebase.messages.IdentityKey;
import com.epam.deltix.qsrv.hf.topic.DirectProtocol;
import com.epam.deltix.qsrv.hf.blocks.InstrumentKeyToIntegerHashMap;
import com.epam.deltix.qsrv.hf.pub.ChannelPerformance;
import com.epam.deltix.timebase.messages.ConstantIdentityKey;
import com.epam.deltix.qsrv.hf.tickdb.impl.IdleStrategyProvider;
import com.epam.deltix.util.collections.ElementsEnumeration;
import com.epam.deltix.util.collections.generated.IntegerEntry;
import com.epam.deltix.util.collections.generated.IntegerToObjectHashMap;
import com.epam.deltix.util.concurrent.QuickExecutor;
import com.epam.deltix.util.io.EOQException;
import com.epam.deltix.util.io.IOUtil;
import com.epam.deltix.util.io.aeron.AeronPublicationMDOAdapter;
import com.epam.deltix.util.memory.MemoryDataInput;
import com.epam.deltix.util.memory.MemoryDataOutput;
import io.aeron.ExclusivePublication;
import net.jcip.annotations.GuardedBy;
import org.jetbrains.annotations.NotNull;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Alexei Osipov
 */
@ParametersAreNonnullByDefault
public class DirectTopicHandler extends QuickExecutor.QuickTask {

    private static final int NOT_FOUND_VALUE = -1;
    private static final int INITIAL_BUFFER_SIZE = 64;
    private static final long TEMP_INDEX_LIFETIME = TimeUnit.SECONDS.toMillis(100);

    // How many temporary indexes we may want to send at once
    private static final int MAX_TEMP_INDEX_COUNT_TO_SEND = 64;

    private final List<PublisherEntry> publishers = new ArrayList<>();
    private final MemoryDataInput mdi = new MemoryDataInput(INITIAL_BUFFER_SIZE);

    private final AeronPublicationMDOAdapter publicationAdapter;

    @GuardedBy("entities")
    private final InstrumentKeyToIntegerHashMap entities;

    // Temporary mapping is a mapping individually generated by each loader and used only by specific loader till it gets a permanent mapping from TB server.
    @GuardedBy("entities")
    private final IntegerToObjectHashMap<ConstantIdentityKey> tempIndexes = new IntegerToObjectHashMap<>();

    private long lastTempIndexAdded = Long.MIN_VALUE;

    // This Runnable should be executed whenever there are new data in any of Loader's streams OR Loader was disconnected
    private final Runnable loaderDataAvailabilityCallback = DirectTopicHandler.this::submit;



    public DirectTopicHandler(ExclusivePublication serverMetadataStream, QuickExecutor executor, InstrumentKeyToIntegerHashMap entities) {
        super(executor);
        this.entities = entities;
        ChannelPerformance highThroughput = ChannelPerformance.HIGH_THROUGHPUT; // TODO: Impl
        this.publicationAdapter = new AeronPublicationMDOAdapter(serverMetadataStream, IdleStrategyProvider.getIdleStrategy(highThroughput));
    }

    public void addLoader(List<? extends IdentityKey> keys, InputStream is, Runnable closeCallback) {
        boolean isFirstPublisher;
        synchronized (publishers) {
            isFirstPublisher = publishers.isEmpty();
            BufferedInputStream bis = is instanceof BufferedInputStream ? (BufferedInputStream) is : new BufferedInputStream(is, INITIAL_BUFFER_SIZE);
            publishers.add(new PublisherEntry(bis, closeCallback));
        }

        // We don't send metadata if we just added the first publisher because there is nobody to read data.
        // In case of Aeron no readers causes blocked publish.
        addKeys(keys, !isFirstPublisher);
    }

    // Note: allocates objects
    private void addKeys(List<? extends IdentityKey> keys, boolean sendAddedMappings) {
        InstrumentKeyToIntegerHashMap addedEntries = null;

        synchronized (entities) {
            for (IdentityKey originalKey : keys) {
                ConstantIdentityKey key = ConstantIdentityKey.makeImmutable(originalKey);
                int entityIndex = entities.get(key, NOT_FOUND_VALUE);
                if (entityIndex == NOT_FOUND_VALUE) {
                    entityIndex = entities.size();
                    entities.put(key, entityIndex);

                    if (addedEntries == null) {
                        addedEntries = new InstrumentKeyToIntegerHashMap();
                    }
                    addedEntries.put(key, entityIndex);
                }
            }
        }

        if (sendAddedMappings && addedEntries != null) {
            // Sends entity id mappings for entities added by new loader.
            // The the only reason for that is let other publishers know right mapping even before they need it.
            // In general, it's not strictly necessary. So this send operation can be considered optional
            // because other producers may request data themselves when they actually need it.
            // So it's permitted to skip this call entirely (with possible minor performance hit).
            // Please note that in some specific cases *executing* this call may cause performance hit
            // (due to little higher memory footprint).
            // TODO: Make it possible to turn off this call by a configuration option.
            sendMetadata(addedEntries);
        }
    }

    private void sendMetadata(InstrumentKeyToIntegerHashMap addedEntries) {
        synchronized (publicationAdapter) {
            MemoryDataOutput mdo = publicationAdapter.getMemoryDataOutput();
            mdo.writeByte(DirectProtocol.CODE_METADATA);
            mdo.writeInt(addedEntries.size()); // Record count
            Iterator<ConstantIdentityKey> keyIterator = addedEntries.keyIterator();
            while (keyIterator.hasNext()) {
                ConstantIdentityKey key = keyIterator.next();
                int index = addedEntries.get(key, NOT_FOUND_VALUE);
                assert index != NOT_FOUND_VALUE;
                mdo.writeInt(index);
                mdo.writeStringNonNull(key.getSymbol());
            }
            publicationAdapter.sendBufferIfConnected();
        }
    }

    private void sendMetadata(CharSequence symbol, int index) {
        synchronized (publicationAdapter) {
            MemoryDataOutput mdo = publicationAdapter.getMemoryDataOutput();
            mdo.writeByte(DirectProtocol.CODE_METADATA);
            mdo.writeInt(1); // Record count
            mdo.writeInt(index);
            mdo.writeStringNonNull(symbol);
            publicationAdapter.sendBufferIfConnected();
        }
    }

    @Override
    public void run() {
        //noinspection StatementWithEmptyBody
        while (checkMessagesFromLoaders()) {
        }
    }

    private boolean checkMessagesFromLoaders() {
        boolean someDataPending = false;
        synchronized (publishers) {
            for (PublisherEntry entry : publishers) {
                BufferedInputStream inputStream = entry.is;
                try {
                    int available = inputStream.available();
                    if (available > 0) {
                        someDataPending = true;
                    }
                    int lengthFieldBytes = Integer.BYTES;
                    if (available > lengthFieldBytes) {
                        inputStream.mark(lengthFieldBytes);
                        // Note: We expect BigEndian encoding for length
                        int b3 = inputStream.read();
                        int b2 = inputStream.read();
                        int b1 = inputStream.read();
                        int b0 = inputStream.read();
                        int messageLength = makeInt(b3, b2, b1, b0);
                        if (messageLength + lengthFieldBytes <= available) {
                            // We should have full message
                            readMessageFromLoaderStream(inputStream, messageLength);
                        } else {
                            // We need more data
                            inputStream.reset();
                        }
                    }
                } catch (EOQException eoq) {
                    // This means that loader disconnected.
                    publishers.remove(entry);
                    entry.closeCallback.run(); // TODO: Double check if deadlock possible.

                    // We stop the processing here and restart it from the beginning to avoid breaking list iterator. TODO: Rewrite?
                    return true;
                } catch (IOException e) {
                    // TODO: Handle exception. We should close this loader.
                    throw new UncheckedIOException(e);
                }
            }
        }
        return someDataPending;
    }

    private void readMessageFromLoaderStream(BufferedInputStream inputStream, int messageLength) throws IOException {
        if (mdi.getBytes().length < messageLength) {
            mdi.setBytes(new byte[Util.ceilingNextPowerOfTwo(messageLength)]);
        }
        mdi.reset(messageLength);
        IOUtil.readFully(inputStream, mdi.getBytes(), 0, messageLength);
        readMessageFromMdi();
    }

    private void readMessageFromMdi() {
        byte code = mdi.readByte();

        switch (code) {
            case DirectProtocol.CODE_METADATA:
                processMetadataFromLoader();
                break;

            case DirectProtocol.CODE_TEMP_INDEX_REMOVED:
                // TODO: Impl
                break;

            case DirectProtocol.CODE_END_OF_STREAM:
                // Do nothing
                // TODO: We should track connection loss too
                break;

            default:
                throw new IllegalArgumentException("Unknown code: " + code);
        }
    }

    private void processMetadataFromLoader() {
        // Implementation note: existing loaders will send only one temp index at a time,
        // i.e. recordCount==1.
        // So there is no reason to implement batched message send.

        int recordCount = mdi.readInt();

        long currentTime = System.currentTimeMillis();
        synchronized (entities) {
            clearOldTempIndexes(currentTime);

            for (int i = 0; i < recordCount; i++) {
                int tempIndex = mdi.readInt();
                assert DirectProtocol.isValidTempIndex(tempIndex);
                String symbol = mdi.readCharSequence().toString().intern();

                ConstantIdentityKey key;
                int entityIndex = entities.get(symbol, NOT_FOUND_VALUE);
                if (entityIndex == NOT_FOUND_VALUE) {
                    entityIndex = entities.size();
                    key = new ConstantIdentityKey(symbol);
                    entities.put(key, entityIndex);
                } else {
                    key = entities.getKeyObject(symbol);
                }

                // Note: key is guarantied to be same object as in "entities" field
                tempIndexes.put(tempIndex, key);

                sendMetadata(symbol, entityIndex);
            }
            if (recordCount > 0) {
                lastTempIndexAdded = currentTime;
            }
        }
    }

    private void clearOldTempIndexes(long currentTime) {
        if (lastTempIndexAdded != Long.MIN_VALUE && (currentTime - lastTempIndexAdded > TEMP_INDEX_LIFETIME)) {
            // All previously added temp indexes are out of date. Discard them.
            tempIndexes.clear();
        }
    }

    /**
     * This Runnable should be executed whenever there are new data in any of Loader's streams OR Loader was disconnected.
     */
    public Runnable getLoaderDataAvailabilityCallback() {
        return loaderDataAvailabilityCallback;
    }

    // TODO: Check for existing method
    static private int makeInt(int b3, int b2, int b1, int b0) {
        return (((b3       ) << 24) |
                ((b2 & 0xff) << 16) |
                ((b1 & 0xff) <<  8) |
                ((b0 & 0xff)      ));
    }

    public IntegerToObjectHashMap<ConstantIdentityKey> getTemporaryMappingSnapshot(int requestedTempEntityIndex) {
        synchronized (entities) {
            clearOldTempIndexes(System.currentTimeMillis());
            int size = tempIndexes.size();
            if (size <= MAX_TEMP_INDEX_COUNT_TO_SEND) {
                // We have low amount of temporary indexes so we can send all of them to the client at once
                return cloneIntHashMap(tempIndexes);
            } else {
                // We can't send all. Fallback to sending only what was requested
                ConstantIdentityKey instrumentKey = tempIndexes.get(requestedTempEntityIndex, null);
                if (instrumentKey == null) {
                    // The key was not found => empty result
                    return new IntegerToObjectHashMap<>();
                } else {
                    // Send only the requested key
                    IntegerToObjectHashMap<ConstantIdentityKey> result = new IntegerToObjectHashMap<>();
                    result.put(requestedTempEntityIndex, instrumentKey);
                    return result;
                }
            }
        }
    }

    @NotNull
    private IntegerToObjectHashMap<ConstantIdentityKey> cloneIntHashMap(IntegerToObjectHashMap<ConstantIdentityKey> tempIndexes) {
        IntegerToObjectHashMap<ConstantIdentityKey> result = new IntegerToObjectHashMap<>();
        ElementsEnumeration<ConstantIdentityKey> elements = this.tempIndexes.elements();
        IntegerEntry entry = (IntegerEntry) elements;
        while (elements.hasMoreElements()) {
            int index = entry.keyInteger(); // Note: we have to take key before iterating to the next
            ConstantIdentityKey instrumentKey = elements.nextElement();
            result.put(index, instrumentKey);
        }
        return result;
    }

    private static final class PublisherEntry {
        final BufferedInputStream is;
        final Runnable closeCallback;

        private PublisherEntry(BufferedInputStream is, Runnable closeCallback) {
            this.is = is;
            this.closeCallback = closeCallback;
        }
    }
}
