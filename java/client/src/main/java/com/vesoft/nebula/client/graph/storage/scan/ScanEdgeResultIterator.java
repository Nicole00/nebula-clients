/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License,
 * attached with Common Clause Condition 1.0, found in the LICENSES directory.
 */

package com.vesoft.nebula.client.graph.storage.scan;

import com.facebook.thrift.TException;
import com.google.common.net.HostAndPort;
import com.vesoft.nebula.DataSet;
import com.vesoft.nebula.client.graph.exception.ExecuteFailedException;
import com.vesoft.nebula.client.graph.meta.MetaClient;
import com.vesoft.nebula.client.graph.storage.StorageConnPool;
import com.vesoft.nebula.client.graph.storage.StorageConnection;
import com.vesoft.nebula.client.graph.storage.data.ScanStatus;
import com.vesoft.nebula.client.graph.storage.processor.EdgeProcessor;
import com.vesoft.nebula.storage.ErrorCode;
import com.vesoft.nebula.storage.PartitionResult;
import com.vesoft.nebula.storage.ScanEdgeRequest;
import com.vesoft.nebula.storage.ScanEdgeResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScanEdgeResultIterator extends ScanResultIterator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanEdgeResultIterator.class);

    private final ScanEdgeRequest request;
    private final EdgeProcessor processor;

    private ScanEdgeResultIterator(MetaClient metaClient,
                                   StorageConnPool pool,
                                   Set<PartScanInfo> partScanInfoList,
                                   Set<HostAndPort> addresses,
                                   ScanEdgeRequest request,
                                   String spaceName,
                                   String labelName,
                                   boolean partSuccess) {
        super(metaClient, pool, new PartScanQueue(partScanInfoList), addresses, spaceName,
                labelName, partSuccess);
        this.request = request;
        this.processor = new EdgeProcessor(spaceName, metaClient.getMetaInfo());
    }


    /**
     * get the next edgeRow set
     *
     * @return ScanEdgeResult
     */
    public ScanEdgeResult next() throws IllegalAccessException, InterruptedException,
            ExecuteFailedException {
        if (!hasNext()) {
            throw new IllegalAccessException("iterator has no more data");
        }

        final List<DataSet> results =
                Collections.synchronizedList(new ArrayList<>(addresses.size()));
        List<Exception> exceptions =
                Collections.synchronizedList(new ArrayList<>(addresses.size()));
        CountDownLatch countDownLatch = new CountDownLatch(addresses.size());
        AtomicInteger existSuccess = new AtomicInteger(0);


        for (HostAndPort addr : addresses) {
            threadPool.submit(() -> {
                ScanEdgeRequest partRequest = new ScanEdgeRequest(request);
                ScanEdgeResponse response;
                PartScanInfo partInfo = partScanQueue.getPart(addr);
                // no part need to scan
                if (partInfo == null) {
                    countDownLatch.countDown();
                    existSuccess.addAndGet(1);
                    return;
                }

                StorageConnection connection;
                try {
                    connection = pool.getStorageConnection(addr);
                } catch (Exception e) {
                    LOGGER.error("get storage client error, ", e);
                    exceptions.add(e);
                    countDownLatch.countDown();
                    return;
                }

                partRequest.setPart_id(partInfo.getPart());
                partRequest.setCursor(partInfo.getCursor());
                try {
                    response = connection.scanEdge(partRequest);
                } catch (TException e) {
                    LOGGER.error(String.format("Scan edgeRow failed for %s", e.getMessage()), e);
                    exceptions.add(e);
                    partScanQueue.drop(partInfo);
                    countDownLatch.countDown();
                    return;
                }

                if (isSuccessful(response)) {
                    handleSucceedResult(existSuccess, response, partInfo);
                    results.add(response.getEdge_data());
                }

                if (response != null && response.getResult() != null) {
                    handleFailedResult(response, partInfo, exceptions);
                } else {
                    handleNullResult(partInfo, exceptions);
                }
                pool.release(addr, connection);
                countDownLatch.countDown();
            });

        }

        try {
            countDownLatch.await();
        } catch (InterruptedException interruptedE) {
            LOGGER.error("scan interrupted:", interruptedE);
            throw interruptedE;
        }

        if (partSuccess) {
            hasNext = partScanQueue.size() > 0;
            // no part succeed, throw ExecuteFailedException
            if (existSuccess.get() == 0) {
                throwExceptions(exceptions);
            }
            ScanStatus status = exceptions.size() > 0 ? ScanStatus.PART_SUCCESS :
                    ScanStatus.ALL_SUCCESS;
            return new ScanEdgeResult(results, request.getReturn_columns(), status, labelName,
                    processor);
        } else {
            hasNext = partScanQueue.size() > 0 && exceptions.isEmpty();
            // any part failed, throw ExecuteFailedException
            if (!exceptions.isEmpty()) {
                throwExceptions(exceptions);
            }
            boolean success = (existSuccess.get() == addresses.size());
            List<DataSet> finalResults = success ? results : null;
            return new ScanEdgeResult(finalResults, request.getReturn_columns(),
                    ScanStatus.ALL_SUCCESS, labelName, processor);
        }
    }


    private boolean isSuccessful(ScanEdgeResponse response) {
        return response.result.failed_parts.size() == 0;
    }

    private void handleSucceedResult(AtomicInteger existSuccess, ScanEdgeResponse response,
                                     PartScanInfo partInfo) {
        existSuccess.addAndGet(1);
        if (!response.has_next) {
            partScanQueue.drop(partInfo);
        } else {
            partInfo.setCursor(response.getNext_cursor());
        }
    }

    private void handleFailedResult(ScanEdgeResponse response, PartScanInfo partInfo,
                                    List<Exception> exceptions) {
        for (PartitionResult partResult : response.getResult().getFailed_parts()) {
            if (partResult.code == ErrorCode.E_LEADER_CHANGED) {
                freshLeader(spaceName, partInfo.getPart(), partResult.getLeader());
                partInfo.setLeader(getLeader(partResult.getLeader()));
            } else {
                int code = partResult.getCode();
                LOGGER.error(String.format("part scan failed, error code=%d", code));
                partScanQueue.drop(partInfo);
                exceptions.add(new Exception(String.format("part scan, error code=%d", code)));
            }
        }
    }


    /**
     * builder to build {@link ScanEdgeResult}
     */
    public static class ScanEdgeResultBuilder {

        MetaClient metaClient;
        StorageConnPool pool;
        Set<PartScanInfo> partScanInfoList;
        Set<HostAndPort> addresses;
        ScanEdgeRequest request;
        String spaceName;
        String edgeName;
        boolean partSuccess = false;

        public ScanEdgeResultBuilder withMetaClient(MetaClient metaClient) {
            this.metaClient = metaClient;
            return this;
        }

        public ScanEdgeResultBuilder withPool(StorageConnPool pool) {
            this.pool = pool;
            return this;
        }

        public ScanEdgeResultBuilder withPartScanInfo(Set<PartScanInfo> partScanInfoList) {
            this.partScanInfoList = partScanInfoList;
            return this;
        }

        public ScanEdgeResultBuilder withAddresses(Set<HostAndPort> addresses) {
            this.addresses = addresses;
            return this;
        }

        public ScanEdgeResultBuilder withRequest(ScanEdgeRequest request) {
            this.request = request;
            return this;
        }

        public ScanEdgeResultBuilder withSpaceName(String spaceName) {
            this.spaceName = spaceName;
            return this;
        }

        public ScanEdgeResultBuilder withEdgeName(String edgeName) {
            this.edgeName = edgeName;
            return this;
        }

        public ScanEdgeResultBuilder withPartSuccess(boolean partSuccess) {
            this.partSuccess = partSuccess;
            return this;
        }


        public ScanEdgeResultIterator build() {
            return new ScanEdgeResultIterator(
                    metaClient,
                    pool,
                    partScanInfoList,
                    addresses,
                    request,
                    spaceName,
                    edgeName,
                    partSuccess);
        }
    }
}
