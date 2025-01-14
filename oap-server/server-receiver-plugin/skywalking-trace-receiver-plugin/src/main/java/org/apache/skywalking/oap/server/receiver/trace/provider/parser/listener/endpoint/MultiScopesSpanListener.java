/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.receiver.trace.provider.parser.listener.endpoint;

import java.util.*;
import org.apache.skywalking.apm.network.common.KeyStringValuePair;
import org.apache.skywalking.apm.network.language.agent.*;
import org.apache.skywalking.apm.util.StringUtil;
import org.apache.skywalking.oap.server.core.*;
import org.apache.skywalking.oap.server.core.cache.*;
import org.apache.skywalking.oap.server.core.source.*;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.core.analysis.TimeBucket;
import org.apache.skywalking.oap.server.receiver.trace.provider.*;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.SpanTags;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.decorator.*;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.listener.*;
import org.slf4j.*;

import static java.util.Objects.nonNull;

/**
 * Notice, in here, there are following concepts match
 *
 * v5        |   v6
 *
 * 1. Application == Service 2. Server == Service Instance 3. Service == Endpoint
 *
 * @author peng-yongsheng, wusheng
 */
public class MultiScopesSpanListener implements EntrySpanListener, ExitSpanListener, GlobalTraceIdsListener {

    private static final Logger logger = LoggerFactory.getLogger(MultiScopesSpanListener.class);

    private final SourceReceiver sourceReceiver;
    private final ServiceInstanceInventoryCache instanceInventoryCache;
    private final ServiceInventoryCache serviceInventoryCache;
    private final EndpointInventoryCache endpointInventoryCache;

    private final List<SourceBuilder> entrySourceBuilders;
    private final List<SourceBuilder> exitSourceBuilders;
    private final List<DatabaseSlowStatement> slowDatabaseAccesses;
    private final TraceServiceModuleConfig config;
    private SpanDecorator entrySpanDecorator;
    private long minuteTimeBucket;
    private String traceId;

    private MultiScopesSpanListener(ModuleManager moduleManager, TraceServiceModuleConfig config) {
        this.sourceReceiver = moduleManager.find(CoreModule.NAME).provider().getService(SourceReceiver.class);
        this.entrySourceBuilders = new LinkedList<>();
        this.exitSourceBuilders = new LinkedList<>();
        this.slowDatabaseAccesses = new ArrayList<>(10);
        this.instanceInventoryCache = moduleManager.find(CoreModule.NAME).provider().getService(ServiceInstanceInventoryCache.class);
        this.serviceInventoryCache = moduleManager.find(CoreModule.NAME).provider().getService(ServiceInventoryCache.class);
        this.endpointInventoryCache = moduleManager.find(CoreModule.NAME).provider().getService(EndpointInventoryCache.class);
        this.config = config;
        this.traceId = null;
    }

    @Override public boolean containsPoint(Point point) {
        return Point.Entry.equals(point) || Point.Exit.equals(point) || Point.TraceIds.equals(point);
    }

    @Override
    public void parseEntry(SpanDecorator spanDecorator, SegmentCoreInfo segmentCoreInfo) {
        this.minuteTimeBucket = segmentCoreInfo.getMinuteTimeBucket();

        if (spanDecorator.getRefsCount() > 0) {
            for (int i = 0; i < spanDecorator.getRefsCount(); i++) {
                ReferenceDecorator reference = spanDecorator.getRefs(i);
                SourceBuilder sourceBuilder = new SourceBuilder();
                sourceBuilder.setSourceEndpointId(reference.getParentEndpointId());

                if (spanDecorator.getSpanLayer().equals(SpanLayer.MQ)) {
                    int serviceIdByPeerId = serviceInventoryCache.getServiceId(reference.getNetworkAddressId());
                    int instanceIdByPeerId = instanceInventoryCache.getServiceInstanceId(serviceIdByPeerId, reference.getNetworkAddressId());
                    sourceBuilder.setSourceServiceInstanceId(instanceIdByPeerId);
                    sourceBuilder.setSourceServiceId(serviceIdByPeerId);
                } else {
                    sourceBuilder.setSourceServiceInstanceId(reference.getParentServiceInstanceId());
                    sourceBuilder.setSourceServiceId(instanceInventoryCache.get(reference.getParentServiceInstanceId()).getServiceId());
                }
                sourceBuilder.setDestEndpointId(spanDecorator.getOperationNameId());
                sourceBuilder.setDestServiceInstanceId(segmentCoreInfo.getServiceInstanceId());
                sourceBuilder.setDestServiceId(segmentCoreInfo.getServiceId());
                sourceBuilder.setDetectPoint(DetectPoint.SERVER);
                sourceBuilder.setComponentId(spanDecorator.getComponentId());
                setPublicAttrs(sourceBuilder, spanDecorator);
                entrySourceBuilders.add(sourceBuilder);
            }
        } else {
            SourceBuilder sourceBuilder = new SourceBuilder();
            sourceBuilder.setSourceEndpointId(Const.USER_ENDPOINT_ID);
            sourceBuilder.setSourceServiceInstanceId(Const.USER_INSTANCE_ID);
            sourceBuilder.setSourceServiceId(Const.USER_SERVICE_ID);
            sourceBuilder.setDestEndpointId(spanDecorator.getOperationNameId());
            sourceBuilder.setDestServiceInstanceId(segmentCoreInfo.getServiceInstanceId());
            sourceBuilder.setDestServiceId(segmentCoreInfo.getServiceId());
            sourceBuilder.setDetectPoint(DetectPoint.SERVER);
            sourceBuilder.setComponentId(spanDecorator.getComponentId());

            setPublicAttrs(sourceBuilder, spanDecorator);
            entrySourceBuilders.add(sourceBuilder);
        }
        this.entrySpanDecorator = spanDecorator;
    }

    @Override public void parseExit(SpanDecorator spanDecorator, SegmentCoreInfo segmentCoreInfo) {
        if (this.minuteTimeBucket == 0) {
            this.minuteTimeBucket = segmentCoreInfo.getMinuteTimeBucket();
        }

        SourceBuilder sourceBuilder = new SourceBuilder();

        int peerId = spanDecorator.getPeerId();
        if (peerId == 0) {
            return;
        }
        int destServiceId = serviceInventoryCache.getServiceId(peerId);
        int mappingServiceId = serviceInventoryCache.get(destServiceId).getMappingServiceId();
        int destInstanceId = instanceInventoryCache.getServiceInstanceId(destServiceId, peerId);

        sourceBuilder.setSourceEndpointId(Const.USER_ENDPOINT_ID);
        sourceBuilder.setSourceServiceInstanceId(segmentCoreInfo.getServiceInstanceId());
        sourceBuilder.setSourceServiceId(segmentCoreInfo.getServiceId());
        sourceBuilder.setDestEndpointId(spanDecorator.getOperationNameId());
        sourceBuilder.setDestServiceInstanceId(destInstanceId);
        if (Const.NONE == mappingServiceId) {
            sourceBuilder.setDestServiceId(destServiceId);
        } else {
            sourceBuilder.setDestServiceId(mappingServiceId);
        }
        sourceBuilder.setDetectPoint(DetectPoint.CLIENT);
        sourceBuilder.setComponentId(spanDecorator.getComponentId());
        setPublicAttrs(sourceBuilder, spanDecorator);
        exitSourceBuilders.add(sourceBuilder);

        if (sourceBuilder.getType().equals(RequestType.DATABASE)) {
            boolean isSlowDBAccess = false;

            DatabaseSlowStatement statement = new DatabaseSlowStatement();
            statement.setId(segmentCoreInfo.getSegmentId() + "-" + spanDecorator.getSpanId());
            statement.setDatabaseServiceId(sourceBuilder.getDestServiceId());
            statement.setLatency(sourceBuilder.getLatency());
            statement.setTimeBucket(TimeBucket.getRecordTimeBucket(segmentCoreInfo.getStartTime()));
            statement.setTraceId(traceId);
            for (KeyStringValuePair tag : spanDecorator.getAllTags()) {
                if (SpanTags.DB_STATEMENT.equals(tag.getKey())) {
                    String sqlStatement = tag.getValue();
                    if (!StringUtil.isEmpty(sqlStatement) && sqlStatement.length() > config.getMaxSlowSQLLength()) {
                        statement.setStatement(sqlStatement.substring(0,config.getMaxSlowSQLLength()));
                    }
                    else {
                        statement.setStatement(sqlStatement);
                    }

                } else if (SpanTags.DB_TYPE.equals(tag.getKey())) {
                    String dbType = tag.getValue();
                    DBLatencyThresholdsAndWatcher thresholds = config.getDbLatencyThresholdsAndWatcher();
                    int threshold = thresholds.getThreshold(dbType);
                    if (sourceBuilder.getLatency() > threshold) {
                        isSlowDBAccess = true;
                    }
                }
            }

            if (isSlowDBAccess) {
                slowDatabaseAccesses.add(statement);
            }
        }
    }

    private void setPublicAttrs(SourceBuilder sourceBuilder, SpanDecorator spanDecorator) {
        long latency = spanDecorator.getEndTime() - spanDecorator.getStartTime();
        sourceBuilder.setLatency((int)latency);
        sourceBuilder.setResponseCode(Const.NONE);
        sourceBuilder.setStatus(!spanDecorator.getIsError());

        switch (spanDecorator.getSpanLayer()) {
            case Http:
                sourceBuilder.setType(RequestType.HTTP);
                break;
            case Database:
                sourceBuilder.setType(RequestType.DATABASE);
                break;
            default:
                sourceBuilder.setType(RequestType.RPC);
                break;
        }

        sourceBuilder.setSourceServiceName(serviceInventoryCache.get(sourceBuilder.getSourceServiceId()).getName());
        sourceBuilder.setSourceServiceInstanceName(instanceInventoryCache.get(sourceBuilder.getSourceServiceInstanceId()).getName());
        sourceBuilder.setSourceEndpointName(endpointInventoryCache.get(sourceBuilder.getSourceEndpointId()).getName());
        sourceBuilder.setDestServiceName(serviceInventoryCache.get(sourceBuilder.getDestServiceId()).getName());
        sourceBuilder.setDestServiceInstanceName(instanceInventoryCache.get(sourceBuilder.getDestServiceInstanceId()).getName());
        sourceBuilder.setDestEndpointName(endpointInventoryCache.get(sourceBuilder.getDestEndpointId()).getName());
    }

    @Override public void build() {
        entrySourceBuilders.forEach(entrySourceBuilder -> {
            entrySourceBuilder.setTimeBucket(minuteTimeBucket);
            sourceReceiver.receive(entrySourceBuilder.toAll());
            sourceReceiver.receive(entrySourceBuilder.toService());
            sourceReceiver.receive(entrySourceBuilder.toServiceInstance());
            sourceReceiver.receive(entrySourceBuilder.toEndpoint());
            sourceReceiver.receive(entrySourceBuilder.toServiceRelation());
            sourceReceiver.receive(entrySourceBuilder.toServiceInstanceRelation());
            EndpointRelation endpointRelation = entrySourceBuilder.toEndpointRelation();
            /**
             * Parent endpoint could be none, because in SkyWalking Cross Process Propagation Headers Protocol v2,
             * endpoint in ref could be empty, based on that, endpoint relation maybe can't be established.
             * So, I am making this source as optional.
             */
            if (endpointRelation != null) {
                sourceReceiver.receive(endpointRelation);
            }
        });

        exitSourceBuilders.forEach(exitSourceBuilder -> {
            if (nonNull(entrySpanDecorator)) {
                exitSourceBuilder.setSourceEndpointId(entrySpanDecorator.getOperationNameId());
            } else {
                exitSourceBuilder.setSourceEndpointId(Const.USER_ENDPOINT_ID);
            }
            exitSourceBuilder.setSourceEndpointName(endpointInventoryCache.get(exitSourceBuilder.getSourceEndpointId()).getName());

            exitSourceBuilder.setTimeBucket(minuteTimeBucket);
            sourceReceiver.receive(exitSourceBuilder.toServiceRelation());
            sourceReceiver.receive(exitSourceBuilder.toServiceInstanceRelation());
            if (RequestType.DATABASE.equals(exitSourceBuilder.getType())) {
                sourceReceiver.receive(exitSourceBuilder.toDatabaseAccess());
            }
        });

        slowDatabaseAccesses.forEach(sourceReceiver::receive);
    }

    @Override public void parseGlobalTraceId(UniqueId uniqueId, SegmentCoreInfo segmentCoreInfo) {
        if (traceId == null) {
            StringBuilder traceIdBuilder = new StringBuilder();
            for (int i = 0; i < uniqueId.getIdPartsList().size(); i++) {
                if (i == 0) {
                    traceIdBuilder.append(uniqueId.getIdPartsList().get(i));
                } else {
                    traceIdBuilder.append(".").append(uniqueId.getIdPartsList().get(i));
                }
            }
            traceId = traceIdBuilder.toString();
        }
    }

    public static class Factory implements SpanListenerFactory {

        @Override
        public SpanListener create(ModuleManager moduleManager, TraceServiceModuleConfig config) {
            return new MultiScopesSpanListener(moduleManager, config);
        }
    }
}
