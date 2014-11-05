/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.routing.allocation.allocator;

import com.google.common.base.Stopwatch;
import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.cluster.routing.MutableShardRouting;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.allocation.FailedRerouteAllocation;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.StartedRerouteAllocation;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;

public class RoundRobinUnassignedShardsAllocator extends AbstractComponent implements ShardsAllocator {

    private int roundRobinCounter;

    @Inject
    public RoundRobinUnassignedShardsAllocator(Settings settings) {
        super(settings);
    }

    @Override
    public void applyStartedShards(StartedRerouteAllocation allocation) { /* ONLY FOR GATEWAYS */ }

    @Override
    public void applyFailedShards(FailedRerouteAllocation allocation) { /* ONLY FOR GATEWAYS */ }

    @Override
    public boolean rebalance(RoutingAllocation routingAllocation) {

        if (logger.isDebugEnabled()) {
            logger.debug("{} does not support re-balancing", this.getClass().getSimpleName());
        }

        return false;
    }

    @Override
    public boolean move(MutableShardRouting mutableShardRouting, RoutingNode currentNode,
            RoutingAllocation routingAllocation) {

        boolean routingAllocationChanged = false;

        if (mutableShardRouting.started()) {

            if (logger.isTraceEnabled()) {
                logger.trace("Attempting to move {} shard [ID:{}] for index {}",
                        mutableShardRouting.primary() ? "primary" :
                                "replica", mutableShardRouting.getId(), mutableShardRouting.getIndex());
            }

            RoutingNode eligibleRoutingNode = findEligibleRoutingNode(routingAllocation, mutableShardRouting);

            while (eligibleRoutingNode != null && !routingAllocationChanged) {

                if (!eligibleRoutingNode.nodeId().equals(currentNode.nodeId())) {

                    if (logger.isTraceEnabled()) {
                        logger.trace("Attempting to move {} shard [ID:{}] for index {} to routing node {}",
                                mutableShardRouting.primary() ? "primary" :
                                        "replica", mutableShardRouting.getId(), mutableShardRouting.getIndex(),
                                eligibleRoutingNode.nodeId());
                    }

                    Decision decision = routingAllocation.deciders().canAllocate(mutableShardRouting,
                            eligibleRoutingNode, routingAllocation);

                    if (decision.type() == Decision.Type.YES) {

                        routingAllocation.routingNodes().assign(new MutableShardRouting(mutableShardRouting.index(),
                                        mutableShardRouting.id(),
                                        eligibleRoutingNode.nodeId(), mutableShardRouting.currentNodeId(),
                                        mutableShardRouting.restoreSource(),
                                        mutableShardRouting.primary(), INITIALIZING, mutableShardRouting.version() + 1),
                                eligibleRoutingNode.nodeId());

                        routingAllocation.routingNodes().relocate(mutableShardRouting, eligibleRoutingNode.nodeId());
                        routingAllocationChanged = true;

                        if (logger.isTraceEnabled()) {
                            logger.trace("Moved shard [ID:{}] for index {} to routing node {}",
                                    mutableShardRouting.primary() ? "primary" :
                                            "replica", mutableShardRouting.getId(), mutableShardRouting.getIndex(),
                                    eligibleRoutingNode.nodeId());
                        }
                    } else {
                        eligibleRoutingNode = findEligibleRoutingNode(routingAllocation, mutableShardRouting);
                    }
                }
            }
        }

        return routingAllocationChanged;
    }

    @Override
    public boolean allocateUnassigned(RoutingAllocation routingAllocation) {

        Stopwatch stopwatch = Stopwatch.createStarted();
        RoutingNodes routingNodes = routingAllocation.routingNodes();
        boolean routingAllocationChanged = false;
        int unassignedShardsProcessed = 0;

        Iterator<MutableShardRouting> mutableShardRoutingIterator = routingNodes.unassigned().iterator();

        while (mutableShardRoutingIterator.hasNext()) {

            unassignedShardsProcessed++;
            MutableShardRouting mutableShardRouting = mutableShardRoutingIterator.next();

            if (logger.isTraceEnabled()) {
                logger.trace("Attempting to assign {} shard [ID: {}] for index {}", mutableShardRouting.primary() ?
                        "primary" :
                        "replica", mutableShardRouting.getId(), mutableShardRouting.getIndex());
            }

            RoutingNode eligibleRoutingNode = findEligibleRoutingNode(routingAllocation, mutableShardRouting);

            if (eligibleRoutingNode != null) {

                routingAllocationChanged = true;

                if (logger.isTraceEnabled()) {
                    logger.trace("Attempting to assign {} shard [ID:{}] for index {} to routing node {}",
                            mutableShardRouting.primary() ? "primary" :
                                    "replica", mutableShardRouting.getId(), mutableShardRouting.getIndex(),
                            eligibleRoutingNode.nodeId());
                }

                routingNodes.assign(mutableShardRouting, eligibleRoutingNode.nodeId());
                mutableShardRoutingIterator.remove();

                if (logger.isTraceEnabled()) {
                    logger.trace("Successfully assigned {} shard [ID:{}] for index {} to routing node {}",
                            mutableShardRouting.primary() ? "primary" :
                                    "replica", mutableShardRouting.getId(), mutableShardRouting.getIndex(),
                            eligibleRoutingNode.nodeId());
                }
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace("No routing node was found for {} shard [ID{}] for index {}",
                            mutableShardRouting.primary() ? "primary" :
                                    "replica", mutableShardRouting.getId(), mutableShardRouting.getIndex());
                }
            }
        }

        if (logger.isTraceEnabled()) {
            logger.error("Processed {} unassigned shards in {} millis", unassignedShardsProcessed,
                    stopwatch.stop().elapsed(TimeUnit.MILLISECONDS));
        }

        return routingAllocationChanged;
    }

    private RoutingNode findEligibleRoutingNode(RoutingAllocation routingAllocation,
            MutableShardRouting mutableShardRouting) {

        RoutingNodes routingNodes = routingAllocation.routingNodes();
        Set<String> remainingRoutingNodeIds = new HashSet<>(getSortedRoutingNodeIds(routingNodes));
        RoutingNode eligibleRoutingNode = null;

        while (!remainingRoutingNodeIds.isEmpty() && eligibleRoutingNode == null) {

            RoutingNode nextRoutingNode = getNextRoutingNode(routingNodes);
            remainingRoutingNodeIds.remove(nextRoutingNode.nodeId());

            Decision decision = routingAllocation.deciders().canAllocate(mutableShardRouting, nextRoutingNode,
                    routingAllocation);

            if (decision.type() == Decision.Type.YES) {
                eligibleRoutingNode = nextRoutingNode;
            }
        }

        return eligibleRoutingNode;
    }

    private RoutingNode getNextRoutingNode(RoutingNodes routingNodes) {

        List<String> sortedRoutingNodeIds = getSortedRoutingNodeIds(routingNodes);
        int nextRoundRobinCounter = getNextRoundRobinCounter();

        int nextRoutingNodeIndex = nextRoundRobinCounter % sortedRoutingNodeIds.size();
        String nextRoutingNodeId = sortedRoutingNodeIds.get(nextRoutingNodeIndex);

        return routingNodes.node(nextRoutingNodeId);
    }

    private List<String> getSortedRoutingNodeIds(RoutingNodes routingNodes) {

        List<String> sortedRoutingNodeIds = new ArrayList<>();

        for (RoutingNode routingNode : routingNodes) {
            sortedRoutingNodeIds.add(routingNode.nodeId());
        }

        CollectionUtil.introSort(sortedRoutingNodeIds);

        return sortedRoutingNodeIds;
    }

    private synchronized int getNextRoundRobinCounter() {

        roundRobinCounter++;

        if (roundRobinCounter == Integer.MIN_VALUE) {
            roundRobinCounter = 0;
        }

        return roundRobinCounter;
    }
}
