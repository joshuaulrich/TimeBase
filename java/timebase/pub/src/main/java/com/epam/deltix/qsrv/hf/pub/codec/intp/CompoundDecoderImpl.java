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
package com.epam.deltix.qsrv.hf.pub.codec.intp;

import com.epam.deltix.qsrv.hf.codec.cg.ObjectManager;
import com.epam.deltix.qsrv.hf.pub.codec.BoundDecoder;
import com.epam.deltix.qsrv.hf.pub.codec.FixedExternalDecoder;
import com.epam.deltix.util.memory.MemoryDataInput;

public class CompoundDecoderImpl implements BoundDecoder {

    private final FixedExternalDecoder[]    decoders;
    private final ObjectManager             manager;
    private final boolean                   polymorphic;

    public CompoundDecoderImpl(FixedExternalDecoder[] decoders, boolean polymorphic, ObjectManager manager) {
        this.decoders = decoders;
        this.polymorphic = polymorphic;
        this.manager = manager;
    }

    protected final FixedExternalDecoder    getDecoder (int code) {
        FixedExternalDecoder    decoder = decoders [code];

        if (decoder == null)
            throw new RuntimeException (
                    "Decoder for class #" + code +
                            " was not created (probably due to unloadable class)"
            );

        return (decoder);
    }

    private Object createInstance(int code) {
        FixedExternalDecoder decoder = getDecoder(code);
        return manager.useObject(decoder.getClassInfo().getTargetClass());
    }

    @Override
    public Object decode(MemoryDataInput in) {
        int code = polymorphic ? in.readUnsignedByte () : 0;

        Object msg = createInstance(code);
        FixedExternalDecoder decoder = getDecoder(code);
        decoder.setStaticFields(msg);
        decoder.decode(in, msg);

        // contract: we have limit for reading data and we should skip all unread content
        int available = in.getAvail();
        if (available > 0)
            in.skipBytes(available);

        return msg;
    }

    @Override
    public void         setStaticFields(Object message) {
    }

    @Override
    public void         decode(MemoryDataInput in, Object message) {
        throw new IllegalStateException("Not allowed");
    }

    public void             reset() {
    }
}
