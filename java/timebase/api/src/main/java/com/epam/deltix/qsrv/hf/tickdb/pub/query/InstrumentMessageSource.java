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
package com.epam.deltix.qsrv.hf.tickdb.pub.query;

import com.epam.deltix.data.stream.RealTimeMessageSource;
import com.epam.deltix.streaming.MessageSource;
import com.epam.deltix.timebase.messages.InstrumentMessage;
import com.epam.deltix.util.concurrent.IntermittentlyAvailableResource;

/**
 *  InstrumentMessageSource instances are subject to the following policies:
 *  <ol>
 *      <li>All <i>read methods</i> (those inherited from {@link StreamMessageSource} and
 *          {@link MessageSource}, except <code>close ()</code>) must be mutually
 *          synchronized by the caller, since it does not make sense to
 *          read a cursor from concurrent threads. This is called a
 *          <i>Single-Thread Read Policy</i></li>
 *      <li>The read thread will see a consistent picture between the
 *          invocations of <code>next ()</code>.</li>
 *      <li>All remaining methods, including <code>close ()</code>, are
 *          called <i>control methods</i>, and they may be
 *          invoked asynchronously.</li>
 *  </ol>
 */
public interface InstrumentMessageSource 
    extends
        MessageSource<InstrumentMessage>,
        SubscriptionController,
        StreamMessageSource<InstrumentMessage>,
        IntermittentlyAvailableResource,
        RealTimeMessageSource<InstrumentMessage>
{
    public InstrumentMessage    getMessage ();
    
    public boolean              isClosed ();
}
