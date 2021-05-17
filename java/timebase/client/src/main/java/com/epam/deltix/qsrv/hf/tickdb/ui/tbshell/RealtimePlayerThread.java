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
package com.epam.deltix.qsrv.hf.tickdb.ui.tbshell;

import com.epam.deltix.streaming.MessageChannel;
import com.epam.deltix.streaming.MessageSource;
import com.epam.deltix.timebase.messages.InstrumentMessage;
import com.epam.deltix.qsrv.hf.pub.RawMessage;
import com.epam.deltix.timebase.messages.TimeStamp;
import com.epam.deltix.qsrv.hf.tickdb.schema.SchemaConverter;
import com.epam.deltix.util.time.TimeKeeper;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/**
* Replays messages from specific streams in real-time.
*/
@ParametersAreNonnullByDefault
public class RealtimePlayerThread extends Thread {

    public enum PlayMode {
        STOP,
        PAUSED,
        PLAY,
        SKIP
    }

    private volatile PlayMode playMode = PlayMode.PLAY;
    private final MessageSource<InstrumentMessage> src;
    private final MessageChannel<InstrumentMessage> dest;
    private final SchemaConverter converter;
    private final Runnable streamRestarter;
    private volatile long timeOffset = Long.MIN_VALUE;
    private volatile long endTimeNano = Long.MAX_VALUE;
    protected long count = 1;

    /**
     * @param streamRestarter will be executed (if not null) when source stream depletes (ends) to restart (cycle) it
     */
    public RealtimePlayerThread(
            MessageSource<InstrumentMessage> src,
            MessageChannel<InstrumentMessage> dest,
            SchemaConverter converter,
            @Nullable
            Runnable streamRestarter) {
        super("Player Thread");

        this.src = src;
        this.dest = dest;
        this.converter = converter;
        this.streamRestarter = streamRestarter;
        
        this.setDaemon(false);
    }

    public void setMode(PlayMode mode) {
        if (mode == PlayMode.PAUSED || mode == PlayMode.SKIP)
            timeOffset = Long.MIN_VALUE;

        playMode = mode;
        interrupt();
    }

    public void setEndTimeNano(long endTimeNano) {
        this.endTimeNano = endTimeNano;
    }

    @Override
    public void run() {

        for (; ; ) {
            try {
                switch (playMode) {
                    case PAUSED:
                        Thread.sleep(5000);
                        continue;

                    case STOP:
                        return;
                }

                if (!src.next()) {
                    // Source depleted
                    if (streamRestarter == null) {
                        return;
                    } else {
                        // Cyclic mode: restart stream
                        streamRestarter.run();
                        timeOffset = Long.MIN_VALUE;
                        if (!src.next()) {
                            // Even after reset we don't have any messages. So stream is empty. Exit.
                            return;
                        }
                    }

                }

                RawMessage inMsg = (RawMessage) src.getMessage();
                long mt = inMsg.getNanoTime();
                if (mt >= endTimeNano) {
                    playMode = PlayMode.PAUSED;
                    continue;
                }
                long now = TimeKeeper.currentTimeNanos;

                if (timeOffset != Long.MIN_VALUE) {
                    long time = mt + timeOffset;

                    if (playMode == PlayMode.SKIP)
                        playMode = PlayMode.PLAY;
                    else {
                        int wait = (int) TimeStamp.getMilliseconds(time - now);

                        if (wait > 2)
                            Thread.sleep(wait);
                    }
                } else
                    timeOffset = now - mt;

                RawMessage outMsg = converter.convert(inMsg);

                if (outMsg == null) {
                    onMessageConversionError(inMsg);
                    continue;
                }

                now = TimeKeeper.currentTimeNanos;

                outMsg.setNanoTime(now);

                log(mt, now, outMsg);

                dest.send(outMsg);
                count++;
            } catch (InterruptedException x) {
                return;
            }
        }
    }

    protected void log(long mt, long now, RawMessage outMsg) {
        // do nothing by default
    }

    protected void onMessageConversionError (RawMessage msg) {
        System.err.println("Cannot convert message:" + msg);
    }
}
