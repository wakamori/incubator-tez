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

package org.apache.tez.runtime.library.shuffle.common;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.SecretKey;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.security.ssl.SSLFactory;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.tez.common.TezJobConfig;
import org.apache.tez.runtime.library.common.InputAttemptIdentifier;
import org.apache.tez.runtime.library.common.shuffle.impl.ShuffleHeader;
import org.apache.tez.runtime.library.shuffle.common.FetchedInput.Type;
import org.apache.tez.runtime.library.shuffle.common.HttpConnection.HttpConnectionParams;

import com.google.common.base.Preconditions;

/**
 * Responsible for fetching inputs served by the ShuffleHandler for a single
 * host. Construct using {@link FetcherBuilder}
 */
public class Fetcher implements Callable<FetchResult> {

  private static final Log LOG = LogFactory.getLog(Fetcher.class);

  private static final AtomicInteger fetcherIdGen = new AtomicInteger(0);

  // Configurable fields.
  private CompressionCodec codec;

  private boolean ifileReadAhead = TezJobConfig.TEZ_RUNTIME_IFILE_READAHEAD_DEFAULT;
  private int ifileReadAheadLength = TezJobConfig.TEZ_RUNTIME_IFILE_READAHEAD_BYTES_DEFAULT;
  
  private final SecretKey shuffleSecret;

  private final FetcherCallback fetcherCallback;
  private final FetchedInputAllocator inputManager;
  private final ApplicationId appId;
  
  private final String logIdentifier;
  
  private final AtomicBoolean isShutDown = new AtomicBoolean(false);

  private final int fetcherIdentifier;

  // Parameters to track work.
  private List<InputAttemptIdentifier> srcAttempts;
  private String host;
  private int port;
  private int partition;

  // Maps from the pathComponents (unique per srcTaskId) to the specific taskId
  private final Map<String, InputAttemptIdentifier> pathToAttemptMap;
  private LinkedHashSet<InputAttemptIdentifier> remaining;

  private URL url;
  private volatile DataInputStream input;
  
  private HttpConnection httpConnection;
  private HttpConnectionParams httpConnectionParams;

  private Fetcher(FetcherCallback fetcherCallback, HttpConnectionParams params,
      FetchedInputAllocator inputManager, ApplicationId appId, SecretKey shuffleSecret,
      String srcNameTrimmed) {
    this.fetcherCallback = fetcherCallback;
    this.inputManager = inputManager;
    this.shuffleSecret = shuffleSecret;
    this.appId = appId;
    this.pathToAttemptMap = new HashMap<String, InputAttemptIdentifier>();
    this.httpConnectionParams = params;

    this.fetcherIdentifier = fetcherIdGen.getAndIncrement();
    this.logIdentifier = "fetcher [" + srcNameTrimmed +"] " + fetcherIdentifier;
  }

  @Override
  public FetchResult call() throws Exception {
    if (srcAttempts.size() == 0) {
      return new FetchResult(host, port, partition, srcAttempts);
    }

    for (InputAttemptIdentifier in : srcAttempts) {
      pathToAttemptMap.put(in.getPathComponent(), in);
    }

    remaining = new LinkedHashSet<InputAttemptIdentifier>(srcAttempts);

    try {
      StringBuilder baseURI = ShuffleUtils.constructBaseURIForShuffleHandler(host,
        port, partition, appId.toString(), httpConnectionParams.isSSLShuffleEnabled());
      this.url = ShuffleUtils.constructInputURL(baseURI.toString(), srcAttempts,
        httpConnectionParams.getKeepAlive());

      httpConnection = new HttpConnection(url, httpConnectionParams, logIdentifier, shuffleSecret);
      httpConnection.connect();
    } catch (IOException e) {
      // ioErrs.increment(1);
      // If connect did not succeed, just mark all the maps as failed,
      // indirectly penalizing the host
      if (isShutDown.get()) {
        LOG.info("Not reporting fetch failure, since an Exception was caught after shutdown");
      } else {
        for (Iterator<InputAttemptIdentifier> leftIter = remaining.iterator(); leftIter
            .hasNext();) {
          fetcherCallback.fetchFailed(host, leftIter.next(), true);
        }
      }
      return new FetchResult(host, port, partition, remaining);
    }
    if (isShutDown.get()) {
      // shutdown would have no effect if in the process of establishing the connection.
      shutdownInternal();
      LOG.info("Detected fetcher has been shutdown after connection establishment. Returning");
      return new FetchResult(host, port, partition, remaining);
    }

    try {
      input = httpConnection.getInputStream();
      httpConnection.validate();
      //validateConnectionResponse(msgToEncode, encHash);
    } catch (IOException e) {
      // ioErrs.increment(1);
      // If we got a read error at this stage, it implies there was a problem
      // with the first map, typically lost map. So, penalize only that map
      // and add the rest
      if (isShutDown.get()) {
        LOG.info("Not reporting fetch failure, since an Exception was caught after shutdown");
      } else {
        InputAttemptIdentifier firstAttempt = srcAttempts.get(0);
        LOG.warn("Fetch Failure from host while connecting: " + host + ", attempt: " + firstAttempt
            + " Informing ShuffleManager: ", e);
        fetcherCallback.fetchFailed(host, firstAttempt, false);
        return new FetchResult(host, port, partition, remaining);
      }
    }

    // By this point, the connection is setup and the response has been
    // validated.

    // Handle any shutdown which may have been invoked.
    if (isShutDown.get()) {
      // shutdown would have no effect if in the process of establishing the connection.
      shutdownInternal();
      LOG.info("Detected fetcher has been shutdown after opening stream. Returning");
      return new FetchResult(host, port, partition, remaining);
    }
    // After this point, closing the stream and connection, should cause a
    // SocketException,
    // which will be ignored since shutdown has been invoked.

    // Loop through available map-outputs and fetch them
    // On any error, faildTasks is not null and we exit
    // after putting back the remaining maps to the
    // yet_to_be_fetched list and marking the failed tasks.
    InputAttemptIdentifier[] failedInputs = null;
    while (!remaining.isEmpty() && failedInputs == null) {
      failedInputs = fetchInputs(input);
    }

    if (failedInputs != null && failedInputs.length > 0) {
      LOG.warn("copyInputs failed for tasks " + Arrays.toString(failedInputs));
      for (InputAttemptIdentifier left : failedInputs) {
        fetcherCallback.fetchFailed(host, left, false);
      }
    }

    shutdown();

    // Sanity check
    if (failedInputs == null && !remaining.isEmpty()) {
      throw new IOException("server didn't return all expected map outputs: "
          + remaining.size() + " left.");
    }

    return new FetchResult(host, port, partition, remaining);

  }

  public void shutdown() {
    if (!isShutDown.getAndSet(true)) {
      shutdownInternal();
    }
  }

  private void shutdownInternal() {
    // Synchronizing on isShutDown to ensure we don't run into a parallel close
    // Can't synchronize on the main class itself since that would cause the
    // shutdown request to block
    synchronized (isShutDown) {
      try {
        if (httpConnection != null) {
          httpConnection.cleanup(false);
        }
      } catch (IOException e) {
        LOG.info("Exception while shutting down fetcher on " + logIdentifier + " : "
            + e.getMessage());
        if (LOG.isDebugEnabled()) {
          LOG.debug(e);
        }
      }
    }
  }

  private InputAttemptIdentifier[] fetchInputs(DataInputStream input) {
    FetchedInput fetchedInput = null;
    InputAttemptIdentifier srcAttemptId = null;
    long decompressedLength = -1;
    long compressedLength = -1;

    try {
      long startTime = System.currentTimeMillis();
      int responsePartition = -1;
      // Read the shuffle header
      String pathComponent = null;
      try {
        ShuffleHeader header = new ShuffleHeader();
        header.readFields(input);
        pathComponent = header.getMapId();

        srcAttemptId = pathToAttemptMap.get(pathComponent);
        compressedLength = header.getCompressedLength();
        decompressedLength = header.getUncompressedLength();
        responsePartition = header.getPartition();
      } catch (IllegalArgumentException e) {
        // badIdErrs.increment(1);
        LOG.warn("Invalid src id ", e);
        // Don't know which one was bad, so consider all of them as bad
        return remaining.toArray(new InputAttemptIdentifier[remaining.size()]);
      }

      // Do some basic sanity verification
      if (!verifySanity(compressedLength, decompressedLength,
          responsePartition, srcAttemptId, pathComponent)) {
        if (srcAttemptId == null) {
          LOG.warn("Was expecting " + getNextRemainingAttempt() + " but got null");
          srcAttemptId = getNextRemainingAttempt();
        }
        assert(srcAttemptId != null);
        return new InputAttemptIdentifier[] { srcAttemptId };
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug("header: " + srcAttemptId + ", len: " + compressedLength
            + ", decomp len: " + decompressedLength);
      }

      // Get the location for the map output - either in-memory or on-disk
      
      // TODO TEZ-957. handle IOException here when Broadcast has better error checking
      fetchedInput = inputManager.allocate(decompressedLength, compressedLength, srcAttemptId);

      // TODO NEWTEZ No concept of WAIT at the moment.
      // // Check if we can shuffle *now* ...
      // if (fetchedInput.getType() == FetchedInput.WAIT) {
      // LOG.info("fetcher#" + id +
      // " - MergerManager returned Status.WAIT ...");
      // //Not an error but wait to process data.
      // return EMPTY_ATTEMPT_ID_ARRAY;
      // }

      // Go!
      LOG.info("fetcher" + " about to shuffle output of srcAttempt "
          + fetchedInput.getInputAttemptIdentifier() + " decomp: "
          + decompressedLength + " len: " + compressedLength + " to "
          + fetchedInput.getType());

      if (fetchedInput.getType() == Type.MEMORY) {
        ShuffleUtils.shuffleToMemory(((MemoryFetchedInput) fetchedInput).getBytes(),
          input, (int) decompressedLength, (int) compressedLength, codec,
          ifileReadAhead, ifileReadAheadLength, LOG,
          fetchedInput.getInputAttemptIdentifier().toString());
      } else {
        ShuffleUtils.shuffleToDisk(((DiskFetchedInput) fetchedInput).getOutputStream(),
          (host +":" +port), input, compressedLength, LOG,
          fetchedInput.getInputAttemptIdentifier().toString());
      }

      // Inform the shuffle scheduler
      long endTime = System.currentTimeMillis();
      fetcherCallback.fetchSucceeded(host, srcAttemptId, fetchedInput,
          compressedLength, decompressedLength, (endTime - startTime));

      // Note successful shuffle
      remaining.remove(srcAttemptId);
      // metrics.successFetch();
      return null;
    } catch (IOException ioe) {
      // ZZZ Add some shutdown code here
      // ZZZ Make sure any assigned memory inputs are aborted
      // ioErrs.increment(1);
      if (srcAttemptId == null || fetchedInput == null) {
        LOG.info("fetcher" + " failed to read map header" + srcAttemptId
            + " decomp: " + decompressedLength + ", " + compressedLength, ioe);
        if (srcAttemptId == null) {
          return remaining
              .toArray(new InputAttemptIdentifier[remaining.size()]);
        } else {
          return new InputAttemptIdentifier[] { srcAttemptId };
        }
      }
      LOG.warn("Failed to shuffle output of " + srcAttemptId + " from " + host,
          ioe);

      // Inform the shuffle-scheduler
      try {
        fetchedInput.abort();
      } catch (IOException e) {
        LOG.info("Failure to cleanup fetchedInput: " + fetchedInput);
      }
      // metrics.failedFetch();
      return new InputAttemptIdentifier[] { srcAttemptId };
    }
  }

  /**
   * Do some basic verification on the input received -- Being defensive
   * 
   * @param compressedLength
   * @param decompressedLength
   * @param fetchPartition
   * @param remaining
   * @param mapId
   * @return true/false, based on if the verification succeeded or not
   */
  private boolean verifySanity(long compressedLength, long decompressedLength,
      int fetchPartition, InputAttemptIdentifier srcAttemptId, String pathComponent) {
    if (compressedLength < 0 || decompressedLength < 0) {
      // wrongLengthErrs.increment(1);
      LOG.warn(" invalid lengths in input header -> headerPathComponent: "
          + pathComponent + ", nextRemainingSrcAttemptId: "
          + getNextRemainingAttempt() + ", mappedSrcAttemptId: " + srcAttemptId
          + " len: " + compressedLength + ", decomp len: " + decompressedLength);
      return false;
    }

    if (fetchPartition != this.partition) {
      // wrongReduceErrs.increment(1);
      LOG.warn(" data for the wrong reduce -> headerPathComponent: "
          + pathComponent + "nextRemainingSrcAttemptId: "
          + getNextRemainingAttempt() + ", mappedSrcAttemptId: " + srcAttemptId
          + " len: " + compressedLength + " decomp len: " + decompressedLength
          + " for reduce " + fetchPartition);
      return false;
    }

    // Sanity check
    if (!remaining.contains(srcAttemptId)) {
      // wrongMapErrs.increment(1);
      LOG.warn("Invalid input. Received output for headerPathComponent: "
          + pathComponent + "nextRemainingSrcAttemptId: "
          + getNextRemainingAttempt() + ", mappedSrcAttemptId: " + srcAttemptId);
      return false;
    }
    return true;
  }
  
  private InputAttemptIdentifier getNextRemainingAttempt() {
    if (remaining.size() > 0) {
      return remaining.iterator().next();
    } else {
      return null;
    }
  }

  /**
   * Builder for the construction of Fetchers
   */
  public static class FetcherBuilder {
    private Fetcher fetcher;
    private boolean workAssigned = false;

    public FetcherBuilder(FetcherCallback fetcherCallback, HttpConnectionParams params,
        FetchedInputAllocator inputManager, ApplicationId appId,
        SecretKey shuffleSecret, String srcNameTrimmed) {
      this.fetcher = new Fetcher(fetcherCallback, params, inputManager, appId,
          shuffleSecret, srcNameTrimmed);
    }

    public FetcherBuilder setHttpConnectionParameters(HttpConnectionParams httpParams) {
      fetcher.httpConnectionParams = httpParams;
      return this;
    }

    public FetcherBuilder setCompressionParameters(CompressionCodec codec) {
      fetcher.codec = codec;
      return this;
    }

    public FetcherBuilder setIFileParams(boolean readAhead, int readAheadBytes) {
      fetcher.ifileReadAhead = readAhead;
      fetcher.ifileReadAheadLength = readAheadBytes;
      return this;
    }

    public FetcherBuilder assignWork(String host, int port, int partition,
        List<InputAttemptIdentifier> inputs) {
      fetcher.host = host;
      fetcher.port = port;
      fetcher.partition = partition;
      fetcher.srcAttempts = inputs;
      workAssigned = true;
      return this;
    }

    public Fetcher build() {
      Preconditions.checkState(workAssigned == true,
          "Cannot build a fetcher withot assigning work to it");
      return fetcher;
    }
  }

  @Override
  public int hashCode() {
    return fetcherIdentifier;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    Fetcher other = (Fetcher) obj;
    if (fetcherIdentifier != other.fetcherIdentifier)
      return false;
    return true;
  }
}

