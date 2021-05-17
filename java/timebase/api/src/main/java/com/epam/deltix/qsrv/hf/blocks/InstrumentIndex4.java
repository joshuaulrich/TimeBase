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
package com.epam.deltix.qsrv.hf.blocks;

import com.epam.deltix.timebase.messages.IdentityKey;
import com.epam.deltix.util.collections.CharSequenceToIntegerMap;

/**
 *
 */
public final class InstrumentIndex4 {
    //private final InstrumentKey buffer = new InstrumentKey ();
    private final CharSequenceToIntegerMap map;

    public InstrumentIndex4(int initialCapacity) {
        map = new CharSequenceToIntegerMap(initialCapacity);
    }

    public InstrumentIndex4() {
        this (16);
    }

    public int getOrAdd(CharSequence symbol) {
        int idx = map.get(symbol, -1);

        if (idx == -1) {
            idx = map.size();
            map.put(symbol, idx);
        }

        return (idx);
    }

    public int          getOrAdd (IdentityKey id) {
        return (getOrAdd (id.getSymbol ()));
    }

    public int          size () {
        return (map.size ());
    }
}
