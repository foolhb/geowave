package org.locationtech.geowave.datastore.kudu.operations;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.apache.kudu.Schema;
import org.apache.kudu.client.AsyncKuduScanner;
import org.apache.kudu.client.AsyncKuduScanner.AsyncKuduScannerBuilder;
import org.apache.kudu.client.KuduPredicate;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.RowResult;
import org.apache.kudu.client.RowResultIterator;
import org.apache.kudu.client.KuduPredicate.ComparisonOp;
import org.locationtech.geowave.core.index.ByteArray;
import org.locationtech.geowave.core.index.ByteArrayRange;
import org.locationtech.geowave.core.index.SinglePartitionQueryRanges;
import org.locationtech.geowave.core.store.CloseableIterator;
import org.locationtech.geowave.core.store.CloseableIteratorWrapper;
import org.locationtech.geowave.core.store.base.dataidx.DataIndexUtils;
import org.locationtech.geowave.core.store.entities.GeoWaveRow;
import org.locationtech.geowave.core.store.entities.GeoWaveRowIteratorTransformer;
import org.locationtech.geowave.core.store.entities.GeoWaveRowMergingIterator;
import org.locationtech.geowave.core.store.util.RowConsumer;
import org.locationtech.geowave.datastore.kudu.KuduRow;
import org.locationtech.geowave.datastore.kudu.KuduRow.KuduField;
import org.locationtech.geowave.datastore.kudu.util.KuduUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.collect.Iterators;
import com.google.common.collect.Streams;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

public class KuduRangeRead<T> {
  private static final Logger LOGGER = LoggerFactory.getLogger(KuduRangeRead.class);
  private static final int MAX_CONCURRENT_READ = 100;
  private static final int MAX_BOUNDED_READS_ENQUEUED = 1000000;
  private final Collection<SinglePartitionQueryRanges> ranges;
  private final Schema schema;
  private final short[] adapterIds;
  final byte[][] dataIds;
  private final KuduTable table;
  private final KuduOperations operations;
  private final boolean isDataIndex;
  private final boolean visibilityEnabled;
  private final Predicate<GeoWaveRow> filter;
  private final GeoWaveRowIteratorTransformer<T> rowTransformer;
  private final boolean rowMerging;

  // only allow so many outstanding async reads or writes, use this semaphore
  // to control it
  private final Semaphore readSemaphore = new Semaphore(MAX_CONCURRENT_READ);

  protected KuduRangeRead(
      final Collection<SinglePartitionQueryRanges> ranges,
      final short[] adapterIds,
      final byte[][] dataIds,
      final KuduTable table,
      final KuduOperations operations,
      final boolean isDataIndex,
      final boolean visibilityEnabled,
      final Predicate<GeoWaveRow> filter,
      final GeoWaveRowIteratorTransformer<T> rowTransformer,
      final boolean rowMerging) {
    this.ranges = ranges;
    this.adapterIds = adapterIds;
    this.dataIds = dataIds;
    this.table = table;
    this.schema = table.getSchema();
    this.operations = operations;
    this.isDataIndex = isDataIndex;
    this.visibilityEnabled = visibilityEnabled;
    this.filter = filter;
    this.rowTransformer = rowTransformer;
    this.rowMerging = rowMerging;
  }

  public CloseableIterator<T> results() {
    final List<AsyncKuduScanner> scanners = new ArrayList<>();
    Iterator<GeoWaveRow> tmpIterator;
    for (final short adapterId : adapterIds) {
      KuduPredicate adapterIdPred =
          KuduPredicate.newComparisonPredicate(
              schema.getColumn(KuduField.GW_ADAPTER_ID_KEY.getFieldName()),
              ComparisonOp.EQUAL,
              adapterId);
      if ((ranges != null) && !ranges.isEmpty()) {
        for (final SinglePartitionQueryRanges r : ranges) {
          final byte[] partitionKey =
              ((r.getPartitionKey() == null) || (r.getPartitionKey().length == 0))
                  ? KuduUtils.EMPTY_KEY
                  : r.getPartitionKey();
          for (final ByteArrayRange range : r.getSortKeyRanges()) {
            final byte[] start = range.getStart() != null ? range.getStart() : new byte[0];
            final byte[] end =
                range.getEnd() != null ? range.getEndAsNextPrefix()
                    : new byte[] {
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF,
                        (byte) 0xFF};
            KuduPredicate lowerPred =
                KuduPredicate.newComparisonPredicate(
                    schema.getColumn(KuduField.GW_SORT_KEY.getFieldName()),
                    ComparisonOp.GREATER_EQUAL,
                    start);
            KuduPredicate upperPred =
                KuduPredicate.newComparisonPredicate(
                    schema.getColumn(KuduField.GW_SORT_KEY.getFieldName()),
                    ComparisonOp.LESS,
                    end);
            KuduPredicate partitionPred =
                KuduPredicate.newComparisonPredicate(
                    schema.getColumn(KuduField.GW_PARTITION_ID_KEY.getFieldName()),
                    ComparisonOp.EQUAL,
                    partitionKey);

            AsyncKuduScannerBuilder scannerBuilder = operations.getAsyncScannerBuilder(table);
            AsyncKuduScanner scanner =
                scannerBuilder.addPredicate(lowerPred).addPredicate(upperPred).addPredicate(
                    partitionPred).addPredicate(adapterIdPred).build();
            scanners.add(scanner);
          }
        }
      } else if (dataIds != null) {
        for (final byte[] dataId : dataIds) {
          KuduPredicate partitionPred =
              KuduPredicate.newComparisonPredicate(
                  schema.getColumn(KuduField.GW_PARTITION_ID_KEY.getFieldName()),
                  ComparisonOp.EQUAL,
                  dataId);
          AsyncKuduScannerBuilder scannerBuilder = operations.getAsyncScannerBuilder(table);
          AsyncKuduScanner scanner =
              scannerBuilder.addPredicate(partitionPred).addPredicate(adapterIdPred).build();
          scanners.add(scanner);
        }
      } else {
        AsyncKuduScannerBuilder scannerBuilder = operations.getAsyncScannerBuilder(table);
        AsyncKuduScanner scanner = scannerBuilder.addPredicate(adapterIdPred).build();
        scanners.add(scanner);
      }
    }

    return executeQueryAsync(scanners.toArray(new AsyncKuduScanner[] {}));
  }

  public CloseableIterator<T> executeQueryAsync(final AsyncKuduScanner... scanners) {
    final BlockingQueue<Object> results = new LinkedBlockingQueue<>(MAX_BOUNDED_READS_ENQUEUED);
    new Thread(new Runnable() {
      @Override
      public void run() {
        final AtomicInteger queryCount = new AtomicInteger(1);
        for (final AsyncKuduScanner scanner : scanners) {
          try {
            readSemaphore.acquire();
            executeScanner(
                scanner,
                readSemaphore,
                results,
                queryCount,
                dataIds,
                isDataIndex,
                visibilityEnabled,
                filter,
                rowTransformer,
                rowMerging,
                adapterIds);
          } catch (final InterruptedException e) {
            LOGGER.warn("Exception while executing query", e);
            readSemaphore.release();
          }
        }
        // then decrement
        if (queryCount.decrementAndGet() <= 0) {
          // and if there are no queries, there may not have
          // been any
          // statements submitted
          try {
            results.put(RowConsumer.POISON);
          } catch (final InterruptedException e) {
            LOGGER.error(
                "Interrupted while finishing blocking queue, this may result in deadlock!");
          }
        }
      }
    }, "Kudu Query Executor").start();
    return new CloseableIteratorWrapper<T>(() -> {
    }, new RowConsumer(results));
  }

  public Deferred<Integer> executeScanner(
      final AsyncKuduScanner scanner,
      final Semaphore semaphore,
      final BlockingQueue<Object> resultQueue,
      final AtomicInteger queryCount,
      final byte[][] dataIds,
      final boolean isDataIndex,
      final boolean visibilityEnabled,
      final Predicate<GeoWaveRow> filter,
      final GeoWaveRowIteratorTransformer<T> rowTransformer,
      final boolean rowMerging,
      final short[] adapterIds) {
    // callback class
    class QueryCallback implements Callback<Deferred<Integer>, RowResultIterator> {
      public Deferred<Integer> call(RowResultIterator rs) {
        Iterator<GeoWaveRow> tmpIterator;

        if (rs == null) {
          checkFinalize();
          return Deferred.fromResult(0);
        }

        if (rs.getNumRows() > 0) {
          if (dataIds == null) {
            if (visibilityEnabled) {
              if (isDataIndex) {
                tmpIterator =
                    Streams.stream(rs.iterator()).map(
                        r -> KuduRow.deserializeDataIndexRow(r, visibilityEnabled)).filter(
                            filter).iterator();
              } else {
                tmpIterator =
                    Streams.stream(rs.iterator()).map(r -> (GeoWaveRow) new KuduRow(r)).filter(
                        filter).iterator();
              }
            } else {
              if (isDataIndex) {
                tmpIterator =
                    Iterators.transform(
                        rs.iterator(),
                        r -> KuduRow.deserializeDataIndexRow(r, visibilityEnabled));
              } else {
                tmpIterator = Iterators.transform(rs.iterator(), r -> (GeoWaveRow) new KuduRow(r));
              }
            }
            rowTransformer.apply(
                rowMerging ? new GeoWaveRowMergingIterator(tmpIterator)
                    : tmpIterator).forEachRemaining(row -> {
                      try {
                        resultQueue.put(row);
                      } catch (final InterruptedException e) {
                        LOGGER.warn("interrupted while waiting to enqueue a cassandra result", e);
                      }
                    });
          } else {
            Iterator<RowResult> rowResultIterator = rs.iterator();
            // Order the rows for data index query
            final Map<ByteArray, GeoWaveRow> resultsMap = new HashMap<>();
            while (rowResultIterator.hasNext()) {
              RowResult r = rowResultIterator.next();
              final byte[] d = r.getBinaryCopy(KuduField.GW_PARTITION_ID_KEY.getFieldName());
              resultsMap.put(
                  new ByteArray(d),
                  DataIndexUtils.deserializeDataIndexRow(
                      d,
                      adapterIds[0],
                      r.getBinaryCopy(KuduField.GW_VALUE_KEY.getFieldName()),
                      visibilityEnabled));
            }
            tmpIterator =
                Arrays.stream(dataIds).map(d -> resultsMap.get(new ByteArray(d))).filter(
                    r -> r != null).iterator();
            tmpIterator.forEachRemaining(row -> {
              try {
                resultQueue.put(row);
              } catch (final InterruptedException e) {
                LOGGER.warn("interrupted while waiting to enqueue a cassandra result", e);
              }
            });

          }
        }

        if (scanner.hasMoreRows()) {
          return scanner.nextRows().addCallbackDeferring(this);
        }

        checkFinalize();
        return Deferred.fromResult(0);
      }

      private void checkFinalize() {
        semaphore.release();
        if (queryCount.decrementAndGet() <= 0) {
          try {
            resultQueue.put(RowConsumer.POISON);
          } catch (final InterruptedException e) {
            LOGGER.error(
                "Interrupted while finishing blocking queue, this may result in deadlock!");
          }
        }
      }
    }

    queryCount.incrementAndGet();
    return scanner.nextRows().addCallbackDeferring(new QueryCallback());
  }
}
