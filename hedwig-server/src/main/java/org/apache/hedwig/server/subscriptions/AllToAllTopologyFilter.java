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
package org.apache.hedwig.server.subscriptions;

import java.io.IOException;

import com.google.protobuf.ByteString;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.hedwig.filter.MessageFilter;
import org.apache.hedwig.protocol.PubSubProtocol.Message;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionPreferences;
import org.apache.hedwig.protoextensions.SubscriptionStateUtils;
import org.apache.hedwig.server.common.ServerConfiguration;

public class AllToAllTopologyFilter implements MessageFilter {

    ByteString subscriberRegion;
    boolean isHubSubscriber;

    @Override
    public MessageFilter initialize(Configuration conf)
    throws ConfigurationException, IOException {
        String region = conf.getString(ServerConfiguration.REGION, "standalone");
        if (null == region) {
            throw new IOException("No region found to run " + getClass().getName());
        }
        subscriberRegion = ByteString.copyFromUtf8(region);
        return this;
    }

    @Override
    public void uninitialize() {
        // do nothing now
    }

    @Override
    public MessageFilter setSubscriptionPreferences(ByteString topic, ByteString subscriberId,
                                                    SubscriptionPreferences preferences) {
        isHubSubscriber = SubscriptionStateUtils.isHubSubscriber(subscriberId);
        return this;
    }

    @Override
    public boolean testMessage(Message message) {
        // We're using a simple all-to-all network topology, so no region
        // should ever need to forward messages to any other region.
        // Otherwise, with the current logic, messages will end up
        // ping-pong-ing back and forth between regions with subscriptions
        // to each other without termination (or in any other cyclic
        // configuration).
        if (isHubSubscriber && !message.getSrcRegion().equals(subscriberRegion)) {
            return false;
        } else {
            return true;
        }
    }

}
