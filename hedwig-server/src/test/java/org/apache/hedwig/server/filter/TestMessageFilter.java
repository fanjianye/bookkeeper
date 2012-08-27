/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hedwig.server.filter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.hedwig.client.HedwigClient;
import org.apache.hedwig.client.api.MessageHandler;
import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.MessageHeader;
import org.apache.hedwig.protocol.PubSubProtocol.SubscribeRequest.CreateOrAttach;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionOptions;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionPreferences;
import org.apache.hedwig.protoextensions.MapUtils;
import org.apache.hedwig.protoextensions.SubscriptionStateUtils;

import org.apache.hedwig.client.api.Client;
import org.apache.hedwig.client.api.Subscriber;
import org.apache.hedwig.client.api.Publisher;
import org.apache.hedwig.client.conf.ClientConfiguration;
import org.apache.hedwig.filter.MessageFilter;
import org.apache.hedwig.util.Callback;

import org.apache.hedwig.server.HedwigHubTestBase;

public class TestMessageFilter extends HedwigHubTestBase {

    // Client side variables
    protected HedwigClient client;
    protected Publisher publisher;
    protected Subscriber subscriber;

    static final String OPT_MOD = "MOD";

    static class ModMessageFilter implements MessageFilter {

        int mod;

        @Override
        public MessageFilter initialize(Configuration conf) {
            // do nothing
            return this;
        }

        @Override
        public void uninitialize() {
            // do nothing;
        }

        @Override
        public MessageFilter setSubscriptionPreferences(ByteString topic, ByteString subscriberId,
                                                        SubscriptionPreferences preferences) {
            Map<String, ByteString> userOptions = SubscriptionStateUtils.buildUserOptions(preferences);
            ByteString modValue = userOptions.get(OPT_MOD);
            if (null == modValue) {
                mod = 0;
            } else {
                mod = Integer.valueOf(modValue.toStringUtf8());
            }
            return this;
        }

        @Override
        public boolean testMessage(Message msg) {
            int value = Integer.valueOf(msg.getBody().toStringUtf8());
            return 0 == value % mod;
        }
    }

    static class HeaderMessageFilter implements MessageFilter {
        int mod;
        @Override
        public MessageFilter initialize(Configuration conf) {
            // do nothing
            return this;
        }

        @Override
        public void uninitialize() {
            // do nothing
        }

        @Override
        public MessageFilter setSubscriptionPreferences(ByteString topic, ByteString subscriberId,
                                                        SubscriptionPreferences preferences) {
            // do nothing now
            return this;
        }

        @Override
        public boolean testMessage(Message msg) {
            if (msg.hasHeader()) {
                MessageHeader header = msg.getHeader();
                if (header.hasProperties()) {
                    Map<String, ByteString> props = MapUtils.buildMap(header.getProperties());
                    ByteString value = props.get(OPT_MOD);
                    if (null == value) {
                        return false;
                    }
                    int intValue = Integer.valueOf(value.toStringUtf8());
                    if (0 != intValue) {
                        return false;
                    }
                    return true;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        }
    }

    @Override
    @Before
    public void setUp() throws Exception {
        numServers = 1;
        super.setUp();

        client = new HedwigClient(new ClientConfiguration() {
            @Override
            public boolean isAutoSendConsumeMessageEnabled() {
                return false;
            }
        });
        publisher = client.getPublisher();
        subscriber = client.getSubscriber();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        client.close();
        super.tearDown();
    }

    private void publishNums(ByteString topic, int start, int num, int M) throws Exception {
        for (int i=1; i<=num; i++) {
            PubSubProtocol.Map.Builder propsBuilder = PubSubProtocol.Map.newBuilder()
                .addEntries(PubSubProtocol.Map.Entry.newBuilder().setKey(OPT_MOD)
                            .setValue(ByteString.copyFromUtf8(String.valueOf((start + i) % M))));
            MessageHeader.Builder headerBuilder = MessageHeader.newBuilder().setProperties(propsBuilder);
            Message msg = Message.newBuilder().setBody(
                          ByteString.copyFromUtf8(String.valueOf((start + i))))
                          .setHeader(headerBuilder).build();
            publisher.publish(topic, msg);
        }
    }

    private void receiveNumModM(final ByteString topic, final ByteString subid,
                                final String filterClassName,
                                final int start, final int num, final int M,
                                final boolean consume)
    throws Exception {
        PubSubProtocol.Map userOptions = PubSubProtocol.Map.newBuilder()
            .addEntries(PubSubProtocol.Map.Entry.newBuilder().setKey(OPT_MOD)
                        .setValue(ByteString.copyFromUtf8(String.valueOf(M)))).build();
        SubscriptionOptions options = SubscriptionOptions.newBuilder()
            .setCreateOrAttach(CreateOrAttach.ATTACH)
            .setMessageFilter(filterClassName)
            .setOptions(userOptions).build();
        subscriber.subscribe(topic, subid, options);

        final int base = start + M - start % M;

        final AtomicInteger expected = new AtomicInteger(base);
        final CountDownLatch latch = new CountDownLatch(1);
        subscriber.startDelivery(topic, subid, new MessageHandler() {
            synchronized public void deliver(ByteString topic, ByteString subscriberId,
                                             Message msg, Callback<Void> callback, Object context) {
                try {
                    int value = Integer.valueOf(msg.getBody().toStringUtf8());
                    // duplicated messages received, ignore them
                    if (value > start) {
                        if (value == expected.get()) {
                            expected.addAndGet(M);
                        } else {
                            logger.error("Did not receive expected value, expected {}, got {}",
                                         expected.get(), value);
                            expected.set(0);
                            latch.countDown();
                        }
                        if (expected.get() == (base + num * M)) {
                            latch.countDown();
                        }
                    }
                    callback.operationFinished(context, null);
                    if (consume) {
                        subscriber.consume(topic, subid, msg.getMsgId());
                    }
                } catch (Exception e) {
                    logger.error("Received bad message", e);
                    latch.countDown();
                }
            }
        });
        assertTrue("Timed out waiting for messages mod " + M + " expected is " + expected.get(),
                   latch.await(10, TimeUnit.SECONDS));
        assertEquals("Should be expected message with " + (base + num * M), (base + num*M), expected.get());
        subscriber.stopDelivery(topic, subid);
        subscriber.closeSubscription(topic, subid);
    }

    @Test
    public void testMessageFilter() throws Exception {
        ByteString topic = ByteString.copyFromUtf8("TestMessageFilter");
        ByteString subid = ByteString.copyFromUtf8("mysub");

        subscriber.subscribe(topic, subid, CreateOrAttach.CREATE_OR_ATTACH);
        subscriber.closeSubscription(topic, subid);
        publishNums(topic, 0, 100, 2);
        receiveNumModM(topic, subid, ModMessageFilter.class.getName(), 0, 50, 2, true);
    }

    @Test
    public void testInvalidMessageFilter() throws Exception {
        ByteString topic = ByteString.copyFromUtf8("TestInvalidMessageFilter");
        ByteString subid = ByteString.copyFromUtf8("mysub");

        SubscriptionOptions options = SubscriptionOptions.newBuilder()
            .setCreateOrAttach(CreateOrAttach.CREATE_OR_ATTACH)
            .setMessageFilter("Invalid_Message_Filter").build();
        try {
            subscriber.subscribe(topic, subid, options);
            // coun't reach here
            fail("Should fail subscribe with invalid message filter");
        } catch (PubSubException pse) {
            assertTrue("Should respond with INVALID_MESSAGE_FILTER",
                       pse.getMessage().contains("INVALID_MESSAGE_FILTER"));
        }
    }

    @Test
    public void testChangeSubscriptionPreferences() throws Exception {
        ByteString topic = ByteString.copyFromUtf8("TestChangeSubscriptionPreferences");
        ByteString subid = ByteString.copyFromUtf8("mysub");

        subscriber.subscribe(topic, subid, CreateOrAttach.CREATE_OR_ATTACH);
        subscriber.closeSubscription(topic, subid);

        publishNums(topic, 0, 100, 2);
        receiveNumModM(topic, subid, ModMessageFilter.class.getName(), 0, 50, 2, false);
        receiveNumModM(topic, subid, ModMessageFilter.class.getName(), 0, 25, 4, false);
        receiveNumModM(topic, subid, ModMessageFilter.class.getName(), 0, 33, 3, true);

        // change mod to receive numbers mod 5
        publishNums(topic, 100, 100, 5);
        receiveNumModM(topic, subid, ModMessageFilter.class.getName(), 100, 20, 5, true);

        // change mod to receive numbers mod 7
        publishNums(topic, 200, 100, 7);
        receiveNumModM(topic, subid, ModMessageFilter.class.getName(), 200, 14, 7, true);
    }

    @Test
    public void testChangeMessageFilter() throws Exception {
        ByteString topic = ByteString.copyFromUtf8("TestChangeMessageFilter");
        ByteString subid = ByteString.copyFromUtf8("mysub");

        subscriber.subscribe(topic, subid, CreateOrAttach.CREATE_OR_ATTACH);
        subscriber.closeSubscription(topic, subid);

        publishNums(topic, 0, 100, 3);
        receiveNumModM(topic, subid, ModMessageFilter.class.getName(), 0, 50, 2, false);
        receiveNumModM(topic, subid, ModMessageFilter.class.getName(), 0, 25, 4, false);
        receiveNumModM(topic, subid, HeaderMessageFilter.class.getName(), 0, 33, 3, true);

        publishNums(topic, 200, 100, 7);
        receiveNumModM(topic, subid, HeaderMessageFilter.class.getName(), 200, 14, 7, true);
    }

    @Test
    public void testFixMessageFilter() throws Exception {
        ByteString topic = ByteString.copyFromUtf8("TestFixMessageFilter");
        ByteString subid = ByteString.copyFromUtf8("mysub");

        subscriber.subscribe(topic, subid, CreateOrAttach.CREATE_OR_ATTACH);
        subscriber.closeSubscription(topic, subid);

        publishNums(topic, 0, 100, 3);
        try {
            receiveNumModM(topic, subid, "Invalid_Message_Filter", 0, 33, 3, true);
            // coun't reach here
            fail("Should fail subscribe with invalid message filter");
        } catch (Exception pse) {
            assertTrue("Should respond with INVALID_MESSAGE_FILTER",
                       pse.getMessage().contains("INVALID_MESSAGE_FILTER"));
        }
        receiveNumModM(topic, subid, HeaderMessageFilter.class.getName(), 0, 33, 3, true);
    }
}
