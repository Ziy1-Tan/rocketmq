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
 */
package org.apache.rocketmq.tieredstore;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Sets;
import io.opentelemetry.api.common.Attributes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.logging.org.slf4j.Logger;
import org.apache.rocketmq.logging.org.slf4j.LoggerFactory;
import org.apache.rocketmq.store.GetMessageResult;
import org.apache.rocketmq.store.GetMessageStatus;
import org.apache.rocketmq.store.MessageFilter;
import org.apache.rocketmq.store.QueryMessageResult;
import org.apache.rocketmq.store.SelectMappedBufferResult;
import org.apache.rocketmq.tieredstore.common.InFlightRequestFuture;
import org.apache.rocketmq.tieredstore.common.MessageCacheKey;
import org.apache.rocketmq.tieredstore.common.SelectMappedBufferResultWrapper;
import org.apache.rocketmq.tieredstore.common.TieredMessageStoreConfig;
import org.apache.rocketmq.tieredstore.common.TieredStoreExecutor;
import org.apache.rocketmq.tieredstore.exception.TieredStoreErrorCode;
import org.apache.rocketmq.tieredstore.exception.TieredStoreException;
import org.apache.rocketmq.tieredstore.file.CompositeFlatFile;
import org.apache.rocketmq.tieredstore.file.CompositeQueueFlatFile;
import org.apache.rocketmq.tieredstore.file.TieredConsumeQueue;
import org.apache.rocketmq.tieredstore.file.TieredFlatFileManager;
import org.apache.rocketmq.tieredstore.file.TieredIndexFile;
import org.apache.rocketmq.tieredstore.metadata.TieredMetadataStore;
import org.apache.rocketmq.tieredstore.metadata.TopicMetadata;
import org.apache.rocketmq.tieredstore.metrics.TieredStoreMetricsConstant;
import org.apache.rocketmq.tieredstore.metrics.TieredStoreMetricsManager;
import org.apache.rocketmq.tieredstore.util.CQItemBufferUtil;
import org.apache.rocketmq.tieredstore.util.MessageBufferUtil;
import org.apache.rocketmq.tieredstore.util.TieredStoreUtil;
import org.apache.rocketmq.common.BoundaryType;

public class TieredMessageFetcher implements MessageStoreFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(TieredStoreUtil.TIERED_STORE_LOGGER_NAME);

    private final String brokerName;
    private final TieredMessageStoreConfig storeConfig;
    private final TieredMetadataStore metadataStore;
    private final TieredFlatFileManager flatFileManager;
    private final Cache<MessageCacheKey, SelectMappedBufferResultWrapper> readAheadCache;

    public TieredMessageFetcher(TieredMessageStoreConfig storeConfig) {
        this.storeConfig = storeConfig;
        this.brokerName = storeConfig.getBrokerName();
        this.metadataStore = TieredStoreUtil.getMetadataStore(storeConfig);
        this.flatFileManager = TieredFlatFileManager.getInstance(storeConfig);
        this.readAheadCache = this.initCache(storeConfig);
    }

    private Cache<MessageCacheKey, SelectMappedBufferResultWrapper> initCache(TieredMessageStoreConfig storeConfig) {
        long memoryMaxSize =
            (long) (Runtime.getRuntime().maxMemory() * storeConfig.getReadAheadCacheSizeThresholdRate());

        return Caffeine.newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(storeConfig.getReadAheadCacheExpireDuration(), TimeUnit.MILLISECONDS)
            .maximumWeight(memoryMaxSize)
            // Using the buffer size of messages to calculate memory usage
            .weigher((MessageCacheKey key, SelectMappedBufferResultWrapper msg) -> msg.getDuplicateResult().getSize())
            .recordStats()
            .build();
    }

    protected SelectMappedBufferResultWrapper putMessageToCache(CompositeFlatFile flatFile,
        long queueOffset, SelectMappedBufferResult result, long minOffset, long maxOffset, int size) {

        return putMessageToCache(flatFile, queueOffset, result, minOffset, maxOffset, size, false);
    }

    protected SelectMappedBufferResultWrapper putMessageToCache(CompositeFlatFile flatFile,
        long queueOffset, SelectMappedBufferResult result, long minOffset, long maxOffset, int size, boolean used) {

        SelectMappedBufferResultWrapper wrapper =
            new SelectMappedBufferResultWrapper(result, queueOffset, minOffset, maxOffset, size);
        if (used) {
            wrapper.addAccessCount();
        }
        readAheadCache.put(new MessageCacheKey(flatFile, queueOffset), wrapper);
        return wrapper;
    }

    // Visible for metrics monitor
    public Cache<MessageCacheKey, SelectMappedBufferResultWrapper> getMessageCache() {
        return readAheadCache;
    }

    // Waiting for the request in transit to complete
    protected CompletableFuture<GetMessageResult> getMessageFromCacheAsync(
        CompositeQueueFlatFile flatFile, String group, long queueOffset, int maxCount) {

        return getMessageFromCacheAsync(flatFile, group, queueOffset, maxCount, true);
    }

    @Nullable
    protected SelectMappedBufferResultWrapper getMessageFromCache(CompositeFlatFile flatFile, long queueOffset) {
        MessageCacheKey cacheKey = new MessageCacheKey(flatFile, queueOffset);
        return readAheadCache.getIfPresent(cacheKey);
    }

    protected void recordCacheAccess(CompositeFlatFile flatFile, String group, long queueOffset,
        List<SelectMappedBufferResultWrapper> resultWrapperList) {
        if (resultWrapperList.size() > 0) {
            queueOffset = resultWrapperList.get(resultWrapperList.size() - 1).getCurOffset();
        }
        flatFile.recordGroupAccess(group, queueOffset);
        for (SelectMappedBufferResultWrapper wrapper : resultWrapperList) {
            wrapper.addAccessCount();
            if (wrapper.getAccessCount() >= flatFile.getActiveGroupCount()) {
                MessageCacheKey cacheKey = new MessageCacheKey(flatFile, wrapper.getCurOffset());
                readAheadCache.invalidate(cacheKey);
            }
        }
    }

    private void prefetchMessage(CompositeQueueFlatFile flatFile, String group, int maxCount, long nextBeginOffset) {
        if (maxCount == 1 || flatFile.getReadAheadFactor() == 1) {
            return;
        }

        MessageQueue mq = flatFile.getMessageQueue();
        // make sure there is only one request per group and request range
        int prefetchBatchSize = Math.min(maxCount * flatFile.getReadAheadFactor(), storeConfig.getReadAheadMessageCountThreshold());
        InFlightRequestFuture inflightRequest = flatFile.getInflightRequest(group, nextBeginOffset, prefetchBatchSize);
        if (!inflightRequest.isAllDone()) {
            return;
        }

        synchronized (flatFile) {
            inflightRequest = flatFile.getInflightRequest(nextBeginOffset, maxCount);
            if (!inflightRequest.isAllDone()) {
                return;
            }

            long maxOffsetOfLastRequest = inflightRequest.getLastFuture().join();
            boolean lastRequestIsExpired = getMessageFromCache(flatFile, nextBeginOffset) == null;

            // if message fetch by last request is expired, we need to prefetch the message from tiered store
            int cacheRemainCount = (int) (maxOffsetOfLastRequest - nextBeginOffset);
            LOGGER.debug("TieredMessageFetcher#preFetchMessage: group={}, nextBeginOffset={}, maxOffsetOfLastRequest={}, lastRequestIsExpired={}, cacheRemainCount={}",
                group, nextBeginOffset, maxOffsetOfLastRequest, lastRequestIsExpired, cacheRemainCount);

            if (lastRequestIsExpired
                || maxOffsetOfLastRequest != -1L && nextBeginOffset >= inflightRequest.getStartOffset()) {

                long queueOffset;
                if (lastRequestIsExpired) {
                    queueOffset = nextBeginOffset;
                    flatFile.decreaseReadAheadFactor();
                } else {
                    queueOffset = maxOffsetOfLastRequest + 1;
                    flatFile.increaseReadAheadFactor();
                }

                int factor = Math.min(flatFile.getReadAheadFactor(), storeConfig.getReadAheadMessageCountThreshold() / maxCount);
                int flag = 0;
                int concurrency = 1;
                if (factor > storeConfig.getReadAheadBatchSizeFactorThreshold()) {
                    flag = factor % storeConfig.getReadAheadBatchSizeFactorThreshold() == 0 ? 0 : 1;
                    concurrency = factor / storeConfig.getReadAheadBatchSizeFactorThreshold() + flag;
                }
                int requestBatchSize = maxCount * Math.min(factor, storeConfig.getReadAheadBatchSizeFactorThreshold());

                List<Pair<Integer, CompletableFuture<Long>>> futureList = new ArrayList<>();
                long nextQueueOffset = queueOffset;
                if (flag == 1) {
                    int firstBatchSize = factor % storeConfig.getReadAheadBatchSizeFactorThreshold() * maxCount;
                    CompletableFuture<Long> future = prefetchMessageThenPutToCache(flatFile, mq, nextQueueOffset, firstBatchSize);
                    futureList.add(Pair.of(firstBatchSize, future));
                    nextQueueOffset += firstBatchSize;
                }
                for (long i = 0; i < concurrency - flag; i++) {
                    CompletableFuture<Long> future = prefetchMessageThenPutToCache(flatFile, mq, nextQueueOffset + i * requestBatchSize, requestBatchSize);
                    futureList.add(Pair.of(requestBatchSize, future));
                }
                flatFile.putInflightRequest(group, queueOffset, maxCount * factor, futureList);
                LOGGER.debug("TieredMessageFetcher#preFetchMessage: try to prefetch messages for later requests: next begin offset: {}, request offset: {}, factor: {}, flag: {}, request batch: {}, concurrency: {}",
                    nextBeginOffset, queueOffset, factor, flag, requestBatchSize, concurrency);
            }
        }
    }

    private CompletableFuture<Long> prefetchMessageThenPutToCache(CompositeQueueFlatFile flatFile, MessageQueue mq,
        long queueOffset, int batchSize) {
        return getMessageFromTieredStoreAsync(flatFile, queueOffset, batchSize)
            .thenApplyAsync(result -> {
                if (result.getStatus() != GetMessageStatus.FOUND) {
                    LOGGER.warn("TieredMessageFetcher#prefetchAndPutMsgToCache: read ahead failed: topic: {}, queue: {}, queue offset: {}, batch size: {}, result: {}",
                        mq.getTopic(), mq.getQueueId(), queueOffset, batchSize, result.getStatus());
                    return -1L;
                }
                // put message into cache
                List<Long> offsetList = result.getMessageQueueOffset();
                List<SelectMappedBufferResult> msgList = result.getMessageMapedList();
                if (offsetList.size() != msgList.size()) {
                    LOGGER.error("TieredMessageFetcher#prefetchAndPutMsgToCache: read ahead failed, result is illegal: topic: {}, queue: {}, queue offset: {}, batch size: {}, offsetList size: {}, msgList size: {}",
                        mq.getTopic(), mq.getQueueId(), queueOffset, batchSize, offsetList.size(), msgList.size());
                    return -1L;
                }
                if (offsetList.isEmpty()) {
                    LOGGER.error("TieredMessageFetcher#prefetchAndPutMsgToCache: read ahead failed, result is FOUND but msgList is empty: topic: {}, queue: {}, queue offset: {}, batch size: {}",
                        mq.getTopic(), mq.getQueueId(), queueOffset, batchSize);
                    return -1L;
                }
                Long minOffset = offsetList.get(0);
                Long maxOffset = offsetList.get(offsetList.size() - 1);
                int size = offsetList.size();
                for (int n = 0; n < offsetList.size(); n++) {
                    putMessageToCache(flatFile, offsetList.get(n), msgList.get(n), minOffset, maxOffset, size);
                }
                if (size != batchSize || maxOffset != queueOffset + batchSize - 1) {
                    LOGGER.warn("TieredMessageFetcher#prefetchAndPutMsgToCache: size not match: except: {}, actual: {}, queue offset: {}, min offset: {}, except offset: {}, max offset: {}",
                        batchSize, size, queueOffset, minOffset, queueOffset + batchSize - 1, maxOffset);
                }
                return maxOffset;
            }, TieredStoreExecutor.fetchDataExecutor);
    }

    public CompletableFuture<GetMessageResult> getMessageFromCacheAsync(CompositeQueueFlatFile flatFile,
        String group, long queueOffset, int maxCount, boolean waitInflightRequest) {

        MessageQueue mq = flatFile.getMessageQueue();

        long lastGetOffset = queueOffset - 1;
        List<SelectMappedBufferResultWrapper> resultWrapperList = new ArrayList<>(maxCount);
        for (int i = 0; i < maxCount; i++) {
            lastGetOffset++;
            SelectMappedBufferResultWrapper wrapper = getMessageFromCache(flatFile, lastGetOffset);
            if (wrapper == null) {
                lastGetOffset--;
                break;
            }
            resultWrapperList.add(wrapper);
        }

        // only record cache access count once
        if (waitInflightRequest) {
            Attributes attributes = TieredStoreMetricsManager.newAttributesBuilder()
                .put(TieredStoreMetricsConstant.LABEL_TOPIC, mq.getTopic())
                .put(TieredStoreMetricsConstant.LABEL_GROUP, group)
                .build();
            TieredStoreMetricsManager.cacheAccess.add(maxCount, attributes);
            TieredStoreMetricsManager.cacheHit.add(resultWrapperList.size(), attributes);
        }

        // If there are no messages in the cache and there are currently requests being pulled.
        // We need to wait for the request to return before continuing.
        if (resultWrapperList.isEmpty() && waitInflightRequest) {
            CompletableFuture<Long> future =
                flatFile.getInflightRequest(group, queueOffset, maxCount).getFuture(queueOffset);
            if (!future.isDone()) {
                Stopwatch stopwatch = Stopwatch.createStarted();
                // to prevent starvation issues, only allow waiting for inflight request once
                return future.thenCompose(v -> {
                    LOGGER.debug("MessageFetcher#getMessageFromCacheAsync: wait for response cost: {}ms",
                        stopwatch.elapsed(TimeUnit.MILLISECONDS));
                    return getMessageFromCacheAsync(flatFile, group, queueOffset, maxCount, false);
                });
            }
        }

        // try to get message from cache again when prefetch request is done
        for (int i = 0; i < maxCount - resultWrapperList.size(); i++) {
            lastGetOffset++;
            SelectMappedBufferResultWrapper wrapper = getMessageFromCache(flatFile, lastGetOffset);
            if (wrapper == null) {
                lastGetOffset--;
                break;
            }
            resultWrapperList.add(wrapper);
        }

        recordCacheAccess(flatFile, group, queueOffset, resultWrapperList);

        // if cache hit, result will be returned immediately and asynchronously prefetch messages for later requests
        if (!resultWrapperList.isEmpty()) {
            LOGGER.debug("MessageFetcher#getMessageFromCacheAsync: cache hit: " +
                    "topic: {}, queue: {}, queue offset: {}, max message num: {}, cache hit num: {}",
                mq.getTopic(), mq.getQueueId(), queueOffset, maxCount, resultWrapperList.size());
            prefetchMessage(flatFile, group, maxCount, lastGetOffset + 1);

            GetMessageResult result = new GetMessageResult();
            result.setStatus(GetMessageStatus.FOUND);
            result.setMinOffset(flatFile.getConsumeQueueMinOffset());
            result.setMaxOffset(flatFile.getConsumeQueueCommitOffset());
            result.setNextBeginOffset(queueOffset + resultWrapperList.size());
            resultWrapperList.forEach(wrapper -> result.addMessage(wrapper.getDuplicateResult(), wrapper.getCurOffset()));
            return CompletableFuture.completedFuture(result);
        }

        // if cache is miss, immediately pull messages
        LOGGER.warn("TieredMessageFetcher#getMessageFromCacheAsync: cache miss: " +
                "topic: {}, queue: {}, queue offset: {}, max message num: {}",
            mq.getTopic(), mq.getQueueId(), queueOffset, maxCount);

        CompletableFuture<GetMessageResult> resultFuture;
        synchronized (flatFile) {
            int batchSize = maxCount * storeConfig.getReadAheadMinFactor();
            resultFuture = getMessageFromTieredStoreAsync(flatFile, queueOffset, batchSize)
                .thenApplyAsync(result -> {
                    if (result.getStatus() != GetMessageStatus.FOUND) {
                        return result;
                    }
                    GetMessageResult newResult = new GetMessageResult();
                    newResult.setStatus(GetMessageStatus.FOUND);
                    newResult.setMinOffset(flatFile.getConsumeQueueMinOffset());
                    newResult.setMaxOffset(flatFile.getConsumeQueueCommitOffset());

                    List<Long> offsetList = result.getMessageQueueOffset();
                    List<SelectMappedBufferResult> msgList = result.getMessageMapedList();
                    Long minOffset = offsetList.get(0);
                    Long maxOffset = offsetList.get(offsetList.size() - 1);
                    int size = offsetList.size();
                    for (int i = 0; i < offsetList.size(); i++) {
                        Long offset = offsetList.get(i);
                        SelectMappedBufferResult msg = msgList.get(i);
                        // put message into cache
                        SelectMappedBufferResultWrapper resultWrapper = putMessageToCache(flatFile, offset, msg, minOffset, maxOffset, size, true);
                        // try to meet maxCount
                        if (newResult.getMessageMapedList().size() < maxCount) {
                            newResult.addMessage(resultWrapper.getDuplicateResult(), offset);
                        }
                    }
                    newResult.setNextBeginOffset(queueOffset + newResult.getMessageMapedList().size());
                    return newResult;
                }, TieredStoreExecutor.fetchDataExecutor);

            List<Pair<Integer, CompletableFuture<Long>>> futureList = new ArrayList<>();
            CompletableFuture<Long> inflightRequestFuture = resultFuture.thenApply(result ->
                result.getStatus() == GetMessageStatus.FOUND ? result.getMessageQueueOffset().get(result.getMessageQueueOffset().size() - 1) : -1L);
            futureList.add(Pair.of(batchSize, inflightRequestFuture));
            flatFile.putInflightRequest(group, queueOffset, batchSize, futureList);
        }
        return resultFuture;
    }

    public CompletableFuture<GetMessageResult> getMessageFromTieredStoreAsync(CompositeQueueFlatFile flatFile,
        long queueOffset, int batchSize) {

        GetMessageResult result = new GetMessageResult();
        result.setMinOffset(flatFile.getConsumeQueueMinOffset());
        result.setMaxOffset(flatFile.getConsumeQueueCommitOffset());
        CompletableFuture<ByteBuffer> readConsumeQueueFuture;
        try {
            readConsumeQueueFuture = flatFile.getConsumeQueueAsync(queueOffset, batchSize);
        } catch (TieredStoreException e) {
            switch (e.getErrorCode()) {
                case NO_NEW_DATA:
                    result.setStatus(GetMessageStatus.OFFSET_OVERFLOW_ONE);
                    result.setNextBeginOffset(queueOffset);
                    return CompletableFuture.completedFuture(result);
                case ILLEGAL_PARAM:
                case ILLEGAL_OFFSET:
                default:
                    result.setStatus(GetMessageStatus.OFFSET_FOUND_NULL);
                    result.setNextBeginOffset(queueOffset);
                    return CompletableFuture.completedFuture(result);
            }
        }

        CompletableFuture<ByteBuffer> readCommitLogFuture = readConsumeQueueFuture.thenComposeAsync(cqBuffer -> {
            long firstCommitLogOffset = CQItemBufferUtil.getCommitLogOffset(cqBuffer);
            cqBuffer.position(cqBuffer.remaining() - TieredConsumeQueue.CONSUME_QUEUE_STORE_UNIT_SIZE);
            long lastCommitLogOffset = CQItemBufferUtil.getCommitLogOffset(cqBuffer);
            if (lastCommitLogOffset < firstCommitLogOffset) {
                MessageQueue mq = flatFile.getMessageQueue();
                LOGGER.error("TieredMessageFetcher#getMessageFromTieredStoreAsync: message is not in order, try to fetch data in next store, topic: {}, queueId: {}, batch size: {}, queue offset {}",
                    mq.getTopic(), mq.getQueueId(), batchSize, queueOffset);
                throw new TieredStoreException(TieredStoreErrorCode.ILLEGAL_OFFSET, "message is not in order");
            }
            long length = lastCommitLogOffset - firstCommitLogOffset + CQItemBufferUtil.getSize(cqBuffer);

            // prevent OOM
            long originLength = length;
            while (cqBuffer.limit() > TieredConsumeQueue.CONSUME_QUEUE_STORE_UNIT_SIZE && length > storeConfig.getReadAheadMessageSizeThreshold()) {
                cqBuffer.limit(cqBuffer.position());
                cqBuffer.position(cqBuffer.limit() - TieredConsumeQueue.CONSUME_QUEUE_STORE_UNIT_SIZE);
                length = CQItemBufferUtil.getCommitLogOffset(cqBuffer) - firstCommitLogOffset + CQItemBufferUtil.getSize(cqBuffer);
            }

            if (originLength != length) {
                MessageQueue mq = flatFile.getMessageQueue();
                LOGGER.info("TieredMessageFetcher#getMessageFromTieredStoreAsync: msg data is too large, topic: {}, queueId: {}, batch size: {}, fix it from {} to {}",
                    mq.getTopic(), mq.getQueueId(), batchSize, originLength, length);
            }

            return flatFile.getCommitLogAsync(firstCommitLogOffset, (int) length);
        }, TieredStoreExecutor.fetchDataExecutor);

        return readConsumeQueueFuture.thenCombineAsync(readCommitLogFuture, (cqBuffer, msgBuffer) -> {
            List<Pair<Integer, Integer>> msgList = MessageBufferUtil.splitMessageBuffer(cqBuffer, msgBuffer);
            if (!msgList.isEmpty()) {
                int requestSize = cqBuffer.remaining() / TieredConsumeQueue.CONSUME_QUEUE_STORE_UNIT_SIZE;
                result.setStatus(GetMessageStatus.FOUND);
                result.setNextBeginOffset(queueOffset + msgList.size());
                msgList.forEach(pair -> {
                    msgBuffer.position(pair.getLeft());
                    ByteBuffer slice = msgBuffer.slice();
                    slice.limit(pair.getRight());
                    result.addMessage(new SelectMappedBufferResult(pair.getLeft(), slice, pair.getRight(), null), MessageBufferUtil.getQueueOffset(slice));
                });
                if (requestSize != msgList.size()) {
                    Set<Long> requestOffsetSet = new HashSet<>();
                    for (int i = 0; i < requestSize; i++) {
                        requestOffsetSet.add(queueOffset + i);
                    }
                    LOGGER.error("TieredMessageFetcher#getMessageFromTieredStoreAsync: split message buffer failed, batch size: {}, request message count: {}, actual message count: {}, these messages may lost: {}", batchSize, requestSize, msgList.size(), Sets.difference(requestOffsetSet, Sets.newHashSet(result.getMessageQueueOffset())));
                } else if (requestSize != batchSize) {
                    LOGGER.debug("TieredMessageFetcher#getMessageFromTieredStoreAsync: message count does not meet batch size, maybe dispatch delay: batch size: {}, request message count: {}", batchSize, requestSize);
                }
                return result;
            }
            long nextBeginOffset = queueOffset + cqBuffer.remaining() / TieredConsumeQueue.CONSUME_QUEUE_STORE_UNIT_SIZE;
            LOGGER.error("TieredMessageFetcher#getMessageFromTieredStoreAsync: split message buffer failed, consume queue buffer size: {}, message buffer size: {}, change offset from {} to {}", cqBuffer.remaining(), msgBuffer.remaining(), queueOffset, nextBeginOffset);
            result.setStatus(GetMessageStatus.MESSAGE_WAS_REMOVING);
            result.setNextBeginOffset(nextBeginOffset);
            return result;
        }, TieredStoreExecutor.fetchDataExecutor).exceptionally(e -> {
            MessageQueue mq = flatFile.getMessageQueue();
            LOGGER.warn("TieredMessageFetcher#getMessageFromTieredStoreAsync: get message failed: topic: {} queueId: {}", mq.getTopic(), mq.getQueueId(), e);
            result.setStatus(GetMessageStatus.OFFSET_FOUND_NULL);
            result.setNextBeginOffset(queueOffset);
            return result;
        });
    }

    @Override
    public CompletableFuture<GetMessageResult> getMessageAsync(
        String group, String topic, int queueId, long queueOffset, int maxCount, final MessageFilter messageFilter) {

        GetMessageResult result = new GetMessageResult();
        CompositeQueueFlatFile flatFile = flatFileManager.getFlatFile(new MessageQueue(topic, brokerName, queueId));

        if (flatFile == null) {
            result.setNextBeginOffset(queueOffset);
            result.setStatus(GetMessageStatus.NO_MATCHED_LOGIC_QUEUE);
            return CompletableFuture.completedFuture(result);
        }

        // Max queue offset means next message put position
        result.setMinOffset(flatFile.getConsumeQueueMinOffset());
        result.setMaxOffset(flatFile.getConsumeQueueCommitOffset());

        // Fill result according file offset.
        // Offset range  | Result           | Fix to
        // (-oo, 0]      | no message       | current offset
        // (0, min)      | too small        | min offset
        // [min, max)    | correct          |
        // [max, max]    | overflow one     | max offset
        // (max, +oo)    | overflow badly   | max offset

        if (result.getMaxOffset() <= 0) {
            result.setStatus(GetMessageStatus.NO_MESSAGE_IN_QUEUE);
            result.setNextBeginOffset(queueOffset);
            return CompletableFuture.completedFuture(result);
        } else if (queueOffset < result.getMinOffset()) {
            result.setStatus(GetMessageStatus.OFFSET_TOO_SMALL);
            result.setNextBeginOffset(result.getMinOffset());
            return CompletableFuture.completedFuture(result);
        } else if (queueOffset == result.getMaxOffset()) {
            result.setStatus(GetMessageStatus.OFFSET_OVERFLOW_ONE);
            result.setNextBeginOffset(result.getMaxOffset());
            return CompletableFuture.completedFuture(result);
        } else if (queueOffset > result.getMaxOffset()) {
            result.setStatus(GetMessageStatus.OFFSET_OVERFLOW_BADLY);
            result.setNextBeginOffset(result.getMaxOffset());
            return CompletableFuture.completedFuture(result);
        }

        return getMessageFromCacheAsync(flatFile, group, queueOffset, maxCount);
    }

    @Override
    public CompletableFuture<Long> getEarliestMessageTimeAsync(String topic, int queueId) {
        CompositeFlatFile flatFile = flatFileManager.getFlatFile(new MessageQueue(topic, brokerName, queueId));
        if (flatFile == null) {
            return CompletableFuture.completedFuture(-1L);
        }

        // read from timestamp to timestamp + length
        int length = MessageBufferUtil.STORE_TIMESTAMP_POSITION + 8;
        return flatFile.getCommitLogAsync(flatFile.getCommitLogMinOffset(), length)
            .thenApply(MessageBufferUtil::getStoreTimeStamp);
    }

    @Override
    public CompletableFuture<Long> getMessageStoreTimeStampAsync(String topic, int queueId, long queueOffset) {
        CompositeFlatFile flatFile = flatFileManager.getFlatFile(new MessageQueue(topic, brokerName, queueId));
        if (flatFile == null) {
            return CompletableFuture.completedFuture(-1L);
        }

        return flatFile.getConsumeQueueAsync(queueOffset)
            .thenComposeAsync(cqItem -> {
                long commitLogOffset = CQItemBufferUtil.getCommitLogOffset(cqItem);
                int size = CQItemBufferUtil.getSize(cqItem);
                return flatFile.getCommitLogAsync(commitLogOffset, size);
            }, TieredStoreExecutor.fetchDataExecutor)
            .thenApply(MessageBufferUtil::getStoreTimeStamp)
            .exceptionally(e -> {
                LOGGER.error("TieredMessageFetcher#getMessageStoreTimeStampAsync: " +
                    "get or decode message failed: topic: {}, queue: {}, offset: {}", topic, queueId, queueOffset, e);
                return -1L;
            });
    }

    @Override
    public long getOffsetInQueueByTime(String topic, int queueId, long timestamp, BoundaryType type) {
        CompositeFlatFile flatFile = flatFileManager.getFlatFile(new MessageQueue(topic, brokerName, queueId));
        if (flatFile == null) {
            return -1L;
        }

        try {
            return flatFile.getOffsetInConsumeQueueByTime(timestamp, type);
        } catch (Exception e) {
            LOGGER.error("TieredMessageFetcher#getOffsetInQueueByTime: " +
                "get offset in queue by time failed: topic: {}, queue: {}, timestamp: {}, type: {}",
                topic, queueId, timestamp, type, e);
        }
        return -1L;
    }

    @Override
    public CompletableFuture<QueryMessageResult> queryMessageAsync(
        String topic, String key, int maxCount, long begin, long end) {

        TieredIndexFile indexFile = TieredFlatFileManager.getIndexFile(storeConfig);

        int hashCode = TieredIndexFile.indexKeyHashMethod(TieredIndexFile.buildKey(topic, key));
        long topicId;
        try {
            TopicMetadata topicMetadata = metadataStore.getTopic(topic);
            if (topicMetadata == null) {
                LOGGER.info("TieredMessageFetcher#queryMessageAsync, topic metadata not found, topic: {}", topic);
                return CompletableFuture.completedFuture(new QueryMessageResult());
            }
            topicId = topicMetadata.getTopicId();
        } catch (Exception e) {
            LOGGER.error("TieredMessageFetcher#queryMessageAsync, get topic id failed, topic: {}", topic, e);
            return CompletableFuture.completedFuture(new QueryMessageResult());
        }

        return indexFile.queryAsync(topic, key, begin, end)
            .thenCompose(indexBufferList -> {
                QueryMessageResult result = new QueryMessageResult();
                int resultCount = 0;
                List<CompletableFuture<Void>> futureList = new ArrayList<>(maxCount);
                for (Pair<Long, ByteBuffer> pair : indexBufferList) {
                    Long fileBeginTimestamp = pair.getKey();
                    ByteBuffer indexBuffer = pair.getValue();

                    if (indexBuffer.remaining() % TieredIndexFile.INDEX_FILE_HASH_COMPACT_INDEX_SIZE != 0) {
                        LOGGER.error("[Bug] TieredMessageFetcher#queryMessageAsync: " +
                            "index buffer size {} is not multiple of index item size {}",
                            indexBuffer.remaining(), TieredIndexFile.INDEX_FILE_HASH_COMPACT_INDEX_SIZE);
                        continue;
                    }

                    for (int indexOffset = indexBuffer.position();
                        indexOffset < indexBuffer.limit();
                        indexOffset += TieredIndexFile.INDEX_FILE_HASH_COMPACT_INDEX_SIZE) {

                        int indexItemHashCode = indexBuffer.getInt(indexOffset);
                        if (indexItemHashCode != hashCode) {
                            continue;
                        }

                        int indexItemTopicId = indexBuffer.getInt(indexOffset + 4);
                        if (indexItemTopicId != topicId) {
                            continue;
                        }

                        int queueId = indexBuffer.getInt(indexOffset + 4 + 4);
                        CompositeFlatFile flatFile =
                            flatFileManager.getFlatFile(new MessageQueue(topic, brokerName, queueId));
                        if (flatFile == null) {
                            continue;
                        }

                        // decode index item
                        long offset = indexBuffer.getLong(indexOffset + 4 + 4 + 4);
                        int size = indexBuffer.getInt(indexOffset + 4 + 4 + 4 + 8);
                        int timeDiff = indexBuffer.getInt(indexOffset + 4 + 4 + 4 + 8 + 4);
                        long indexTimestamp = fileBeginTimestamp + timeDiff;
                        if (indexTimestamp < begin || indexTimestamp > end) {
                            continue;
                        }

                        CompletableFuture<Void> getMessageFuture = flatFile.getCommitLogAsync(offset, size)
                            .thenAccept(messageBuffer -> result.addMessage(
                                new SelectMappedBufferResult(0, messageBuffer, size, null)));
                        futureList.add(getMessageFuture);

                        resultCount++;
                        if (resultCount >= maxCount) {
                            break;
                        }
                    }

                    if (resultCount >= maxCount) {
                        break;
                    }
                }
                return CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0]))
                    .thenApply(v -> result);
            });
    }
}
