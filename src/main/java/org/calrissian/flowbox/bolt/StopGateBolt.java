package org.calrissian.flowbox.bolt;


import backtype.storm.Config;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.calrissian.flowbox.Constants;
import org.calrissian.flowbox.FlowboxTopology;
import org.calrissian.flowbox.model.*;
import org.calrissian.flowbox.support.Policy;
import org.calrissian.flowbox.support.StopGateWindow;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.calrissian.flowbox.Constants.*;
import static org.calrissian.flowbox.FlowboxTopology.declareOutputStreams;
import static org.calrissian.flowbox.model.StopGateOp.STOP_GATE;
import static org.calrissian.flowbox.spout.MockFlowLoaderSpout.FLOW_LOADER_STREAM;

/**
 * Uses a tumbling window to stop execution after an activation policy is met.
 */
public class StopGateBolt extends BaseRichBolt {

    Map<String, Flow> flowMap;
    Map<String, Cache<String, StopGateWindow>> windows;

    OutputCollector collector;


    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        this.collector = outputCollector;
        flowMap = new HashMap<String, Flow>();
        windows = new HashMap<String, Cache<String, StopGateWindow>>();
    }

    @Override
    public void execute(Tuple tuple) {

        collector.ack(tuple);

        /**
         * Update rules if necessary
         */
        if(FLOW_LOADER_STREAM.equals(tuple.getSourceStreamId())) {

            Collection<Flow> flows = (Collection<Flow>) tuple.getValue(0);
            Set<String> rulesToRemove = new HashSet<String>();

            // find deleted rules and remove them
            for(Flow flow : flowMap.values()) {
                if(!flows.contains(flow))
                    rulesToRemove.add(flow.getId());
            }

            /**
             * Remove any deleted rules
             */
            for(String flowId : rulesToRemove) {
                flowMap.remove(flowId);
                windows.remove(flowId);
            }

            for(Flow flow : flows) {
                /**
                 * If a rule has been updated, let's drop the window windows and start out fresh.
                 */
                if(flowMap.get(flow.getId()) != null && !flowMap.get(flow.getId()).equals(flow) ||
                        !flowMap.containsKey(flow.getId())) {
                    flowMap.put(flow.getId(), flow);
                    windows.remove(flow.getId());
                }
            }

        } else if("__system".equals(tuple.getSourceComponent()) &&
                "__tick".equals(tuple.getSourceStreamId())) {

            /**
             * Don't bother evaluating if we don't even have any rules
             */
            if(flowMap.size() > 0) {

                for(Flow flow : flowMap.values()) {

                    int idx = 0;
                    for(FlowOp curOp : flow.getFlowOps()) {

                        if(curOp instanceof StopGateOp) {
                            StopGateOp op = (StopGateOp)curOp;
                            /**
                             * If we need to trigger any time-based policies, let's do that here.
                             */
                            if(op.getActivationPolicy() == Policy.TIME || op.getOpenPolicy() == Policy.TIME) {
                                Cache<String, StopGateWindow> buffersForRule = windows.get(flow.getId() + "\0" + idx);
                                if(buffersForRule != null) {
                                    for (StopGateWindow buffer : buffersForRule.asMap().values()) {
                                        if(op.getActivationPolicy() == Policy.TIME && !buffer.isStopped()) {
                                            if (buffer.getTriggerTicks() == op.getActivationThreshold()) {
                                                buffer.setStopped(true);
                                                buffer.clear();
                                            } else {
                                                buffer.incrTriggerTicks();
                                            }
                                        }

                                        else if(op.getOpenPolicy() == Policy.TIME && buffer.isStopped()) {
                                            if(buffer.getStopTicks() == op.getOpenThreshold()) {
                                                buffer.setStopped(false);
                                                buffer.resetStopTicks();
                                            } else {
                                                buffer.incrementStopTicks();
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        idx++;
                    }
                }
            }

        } else {

            /**
             * Short circuit if we don't have any rules.
             */
            if (flowMap.size() > 0) {

                /**
                 * If we've received an event for an flowbox rule, we need to act on it here. Purposefully, the groupBy
                 * fields have been hashed so that we know the buffer exists on this current bolt for the given rule.
                 *
                 * The hashKey was added to the "fieldsGrouping" in an attempt to share pointers where possible. Different
                 * rules with like fields groupings can store the items in their windows on the same node.
                 */

                String flowId = tuple.getStringByField(FLOW_ID);
                String hash = tuple.getStringByField(PARTITION);
                Event event = (Event) tuple.getValueByField(EVENT);
                int idx = tuple.getIntegerByField(FLOW_OP_IDX);
                idx++;

                Flow flow = flowMap.get(flowId);

                StopGateOp op = (StopGateOp) flow.getFlowOps().get(idx);

                Cache<String, StopGateWindow> buffersForRule = windows.get(flow.getId() + "\0" + idx);
                StopGateWindow buffer;
                if (buffersForRule != null) {
                    buffer = buffersForRule.getIfPresent(hash);

                    if (buffer != null) {    // if we have a buffer already, process it

                        if(!buffer.isStopped()) {
                            /**
                             * If we need to evict any buffered items, let's do it here
                             */
                            if(op.getEvictionPolicy() == Policy.TIME)
                                buffer.timeEvict(op.getEvictionThreshold());
                            /**
                             * Perform count-based eviction if necessary
                             */
                            else if (op.getEvictionPolicy() == Policy.COUNT) {
                                if (buffer.size() == op.getEvictionThreshold())
                                    buffer.expire();
                            }
                        }
                    }

                } else {
                    buffersForRule = CacheBuilder.newBuilder().expireAfterAccess(60, TimeUnit.MINUTES).build(); // just in case we get some rogue data, we don't wan ti to sit for too long.
                    buffer = op.getEvictionPolicy() == Policy.TIME ? new StopGateWindow(hash) :
                            new StopGateWindow(hash, op.getEvictionThreshold());
                    buffersForRule.put(hash, buffer);
                    windows.put(flow.getId() + "\0" + idx, buffersForRule);
                }

                if(buffer.isStopped()) {
                    if(op.getOpenPolicy() == Policy.COUNT ) {
                        if(buffer.getStopTicks() == op.getOpenThreshold()) {
                            buffer.setStopped(false);
                            buffer.resetStopTicks();
                        } else {
                            buffer.incrementStopTicks();
                        }
                    }
                }

                /**
                 * Perform count-based trigger if necessary
                 */
                if(!buffer.isStopped()) {

                    if (op.getActivationPolicy() == Policy.COUNT)
                        buffer.incrTriggerTicks();

                    if(buffer.getTriggerTicks() == op.getActivationThreshold()) {
                        buffer.setStopped(true);
                        buffer.resetTriggerTicks();
                        buffer.clear();
                    }

                    long timeRange = buffer.timeRange();
                    if(op.getActivationPolicy() == Policy.TIME_DELTA_LT && buffer.timeRange() > -1 && buffer.timeRange() <= op.getActivationThreshold() * 1000) {

                        if(op.getEvictionPolicy() == Policy.COUNT && buffer.size() == op.getEvictionThreshold() ||
                                op.getEvictionPolicy() != Policy.COUNT) {
                            buffer.setStopped(true);
                            buffer.clear();
                        }
                    }



                }
                if(!buffer.isStopped()) {
                    buffer.add(event);
                    collector.emit(new Values(flow.getId(), event));
                    System.out.println("EMITTING: " + event);
                }
            }
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        declareOutputStreams(outputFieldsDeclarer);
    }

    @Override
    public Map<String, Object> getComponentConfiguration() {
        Map<String,Object> config = new HashMap<String, Object>();
        config.put(Config.TOPOLOGY_TICK_TUPLE_FREQ_SECS, 1);
        return config;
    }
}
