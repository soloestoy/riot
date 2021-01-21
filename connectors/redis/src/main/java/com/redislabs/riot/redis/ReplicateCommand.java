package com.redislabs.riot.redis;

import com.redislabs.riot.*;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.redis.DataStructureItemReader;
import org.springframework.batch.item.redis.KeyDumpItemReader;
import org.springframework.batch.item.redis.KeyDumpItemWriter;
import org.springframework.batch.item.redis.support.*;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.util.ClassUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;

@Command(name = "replicate", description = "Replicate a source Redis database to a target Redis database")
public class ReplicateCommand extends AbstractTransferCommand<KeyValue<String, byte[]>, KeyValue<String, byte[]>> {

    @CommandLine.ArgGroup(exclusive = false, heading = "Target Redis connection options%n")
    private RedisOptions targetRedis = new RedisOptions();
    @CommandLine.ArgGroup(exclusive = false, heading = "Source Redis reader options%n")
    private RedisReaderOptions options = RedisReaderOptions.builder().build();
    @Option(names = "--live", description = "Enable live replication.")
    private boolean live;
    @CommandLine.Mixin
    private FlushingTransferOptions flushingOptions = FlushingTransferOptions.builder().build();
    @Option(names = "--event-queue", description = "Capacity of the keyspace notification event queue (default: ${DEFAULT-VALUE}).", paramLabel = "<size>")
    private int notificationQueueCapacity = LiveKeyValueItemReaderBuilder.DEFAULT_NOTIFICATION_QUEUE_CAPACITY;
    @Option(names = "--no-verify", description = "Verify target against source dataset after replication. True by default.", negatable = true)
    private boolean verify = true;
    @Option(names = "--ttl-tolerance", description = "Max TTL difference to use for dataset verification (default: ${DEFAULT-VALUE}).", paramLabel = "<sec>")
    private long ttlTolerance = 1;

    private AbstractRedisClient targetClient;
    private GenericObjectPool<? extends StatefulConnection<String, String>> targetPool;
    private StatefulConnection<String, String> targetConnection;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;

    @Override
    public void afterPropertiesSet() throws Exception {
        this.targetClient = client(targetRedis);
        this.targetPool = pool(targetRedis, targetClient);
        this.targetConnection = connection(targetClient);
        super.afterPropertiesSet();
        if (live) {
            this.pubSubConnection = pubSubConnection(client);
        }
    }

    private StatefulRedisPubSubConnection<String, String> pubSubConnection(AbstractRedisClient client) {
        if (client instanceof RedisClusterClient) {
            return ((RedisClusterClient) client).connectPubSub();
        }
        return ((RedisClient) client).connectPubSub();
    }

    @Override
    public void shutdown() {
        if (pubSubConnection != null) {
            pubSubConnection.close();
        }
        super.shutdown();
        if (targetConnection != null) {
            targetConnection.close();
        }
        if (targetPool != null) {
            targetPool.close();
        }
        if (targetClient != null) {
            targetClient.shutdown();
            targetClient.getResources().shutdown();
        }
    }

    @Override
    protected Flow flow() {
        String name = "Scanning";
        StepBuilder<KeyValue<String, byte[]>, KeyValue<String, byte[]>> replicationStep = stepBuilder(name);
        FlowBuilder<SimpleFlow> flow = flow(name).start(replicationStep.reader(sourceKeyDumpReader()).writer(targetKeyDumpWriter()).build().build());
        if (live) {
            String liveReplicationName = "Listening";
            StepBuilder<KeyValue<String, byte[]>, KeyValue<String, byte[]>> liveReplicationStep = stepBuilder(liveReplicationName);
            KeyDumpItemReader<String, String> liveReader = liveReader();
            liveReader.setName("Live" + ClassUtils.getShortName(liveReader.getClass()));
            SimpleFlow liveFlow = flow(liveReplicationName).start(flushingOptions.configure(liveReplicationStep.reader(liveReader).writer(targetKeyDumpWriter()).build()).build()).build();
            flow = flow(liveReplicationName).split(new SimpleAsyncTaskExecutor()).add(liveFlow, flow.build());
        }
        if (verify) {
            KeyComparisonItemWriter<String, String> writer = comparisonWriter();
            StepBuilder<DataStructure<String>,DataStructure<String>> verifyStep = stepBuilder("Verifying");
            flow = flow("Replication+Verification").start(flow.build()).next(verifyStep.reader(sourceDataStructureReader()).writer(writer).extraMessage(() -> message(writer)).build().build());
        }
        return flow.build();
    }

    private String message(KeyComparisonItemWriter<String, String> writer) {
        int v = writer.getDiffs().get(KeyComparisonItemWriter.DiffType.VALUE).size();
        int l = writer.getDiffs().get(KeyComparisonItemWriter.DiffType.LEFT_ONLY).size();
        int r = writer.getDiffs().get(KeyComparisonItemWriter.DiffType.LEFT_ONLY).size();
        int t = writer.getDiffs().get(KeyComparisonItemWriter.DiffType.TTL).size();
        return String.format(" OK:%s V:%s >:%s <:%s T:%s", writer.getOkCount(), v, l, r, t);
    }

    private KeyComparisonItemWriter<String, String> comparisonWriter() {
        Duration ttlToleranceDuration = Duration.ofSeconds(ttlTolerance);
        if (targetRedis.isCluster()) {
            DataStructureItemReader<String, String> targetReader = configureScanReader(DataStructureItemReader.builder((GenericObjectPool<StatefulRedisClusterConnection<String, String>>) targetPool, (StatefulRedisClusterConnection<String, String>) targetConnection)).build();
            return new KeyComparisonItemWriter<>(targetReader, ttlToleranceDuration);
        }
        DataStructureItemReader<String, String> targetReader = configureScanReader(DataStructureItemReader.builder((GenericObjectPool<StatefulRedisConnection<String, String>>) targetPool, (StatefulRedisConnection<String, String>) targetConnection)).build();
        return new KeyComparisonItemWriter<>(targetReader, ttlToleranceDuration);
    }

    private ItemReader<KeyValue<String, byte[]>> sourceKeyDumpReader() {
        if (isCluster()) {
            return configureScanReader(KeyDumpItemReader.builder((GenericObjectPool<StatefulRedisClusterConnection<String, String>>) pool, (StatefulRedisClusterConnection<String, String>) connection)).build();
        }
        return configureScanReader(KeyDumpItemReader.builder((GenericObjectPool<StatefulRedisConnection<String, String>>) pool, (StatefulRedisConnection<String, String>) connection)).build();
    }

    private ItemReader<DataStructure<String>> sourceDataStructureReader() {
        if (isCluster()) {
            return configureScanReader(DataStructureItemReader.builder((GenericObjectPool<StatefulRedisClusterConnection<String, String>>) pool, (StatefulRedisClusterConnection<String, String>) connection)).build();
        }
        return configureScanReader(DataStructureItemReader.builder((GenericObjectPool<StatefulRedisConnection<String, String>>) pool, (StatefulRedisConnection<String, String>) connection)).build();
    }

    private KeyDumpItemReader<String, String> liveReader() {
        if (isCluster()) {
            return configureLiveReader(KeyDumpItemReader.builder((GenericObjectPool<StatefulRedisClusterConnection<String, String>>) pool, (StatefulRedisClusterPubSubConnection<String, String>) pubSubConnection)).build();
        }
        return configureLiveReader(KeyDumpItemReader.builder((GenericObjectPool<StatefulRedisConnection<String, String>>) pool, pubSubConnection)).build();
    }

    private <B extends ScanKeyValueItemReaderBuilder<?>> B configureScanReader(B builder) {
        configureReader(builder.scanMatch(options.getScanMatch()).scanCount(options.getScanCount()).sampleSize(options.getSampleSize()));
        return builder;
    }

    private <B extends LiveKeyValueItemReaderBuilder<?>> B configureLiveReader(B builder) {
        configureReader(builder.keyPattern(options.getScanMatch()).notificationQueueCapacity(notificationQueueCapacity).database(getRedisURI().getDatabase()).flushingInterval(flushingOptions.getFlushIntervalDuration()).idleTimeout(flushingOptions.getIdleTimeoutDuration()));
        return builder;
    }

    private void configureReader(AbstractKeyValueItemReader.AbstractKeyValueItemReaderBuilder<?, ?> builder) {
        builder.threadCount(options.getThreads()).chunkSize(options.getBatchSize()).commandTimeout(getCommandTimeout()).queueCapacity(options.getQueueCapacity());
    }

    private ItemWriter<KeyValue<String, byte[]>> targetKeyDumpWriter() {
        if (targetRedis.isCluster()) {
            return KeyDumpItemWriter.clusterBuilder((GenericObjectPool<StatefulRedisClusterConnection<String, String>>) targetPool).replace(true).commandTimeout(getTargetCommandTimeout()).build();
        }
        return KeyDumpItemWriter.builder((GenericObjectPool<StatefulRedisConnection<String, String>>) targetPool).replace(true).commandTimeout(getTargetCommandTimeout()).build();
    }

    private Duration getTargetCommandTimeout() {
        return targetRedis.uris().get(0).getTimeout();
    }

}
