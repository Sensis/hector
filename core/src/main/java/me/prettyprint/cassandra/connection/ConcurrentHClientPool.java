package me.prettyprint.cassandra.connection;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import me.prettyprint.cassandra.service.CassandraHost;
import me.prettyprint.cassandra.utils.ThriftExceptionUtils;
import me.prettyprint.hector.api.exceptions.HInactivePoolException;
import me.prettyprint.hector.api.exceptions.HectorException;
import me.prettyprint.hector.api.exceptions.HectorTransportException;
import me.prettyprint.hector.api.exceptions.HPoolExhaustedException;

import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConcurrentHClientPool implements HClientPool {

  private static final Logger log = LoggerFactory.getLogger(ConcurrentHClientPool.class);

  private final ArrayBlockingQueue<HThriftClient> availableClientQueue;
  private final AtomicInteger activeClientsCount;
  private final AtomicInteger realActiveClientsCount;

  private final CassandraHost cassandraHost;

  /** Total threads waiting for connections */
  private final AtomicInteger numBlocked;
  private final AtomicBoolean active;

  private final long maxWaitTimeWhenExhausted;

  public ConcurrentHClientPool(CassandraHost host) {
    this.cassandraHost = host;

    availableClientQueue = new ArrayBlockingQueue<HThriftClient>(cassandraHost.getMaxActive(), true);
    // This counter can be offset by as much as the number of threads.
    activeClientsCount = new AtomicInteger(0);
    realActiveClientsCount = new AtomicInteger(0);
    numBlocked = new AtomicInteger();
    active = new AtomicBoolean(true);

    maxWaitTimeWhenExhausted = cassandraHost.getMaxWaitTimeWhenExhausted() < 0 ? 0 : cassandraHost.getMaxWaitTimeWhenExhausted();

    for (int i = 0; i < cassandraHost.getMaxActive() / 3; i++) {
      availableClientQueue.add(new HThriftClient(cassandraHost).open());
    }

    if ( log.isDebugEnabled() ) {
      log.debug("Concurrent Host pool started with {} active clients; max: {} exhausted wait: {}",
          new Object[]{getNumIdle(),
          cassandraHost.getMaxActive(),
          maxWaitTimeWhenExhausted});
    }

  }


  @Override
  public HThriftClient borrowClient() throws HectorException {
    if ( !active.get() ) {
      throw new HInactivePoolException("Attempt to borrow on in-active pool: " + getName());
    }

    HThriftClient cassandraClient = cassandraHost.getUseStaleConnectionCheck()
            ? getNextNonStaleClient() : availableClientQueue.poll();
    int currentActiveClients = activeClientsCount.incrementAndGet();

    try {

      if ( cassandraClient == null ) {

        if (currentActiveClients <= cassandraHost.getMaxActive()) {
          cassandraClient = createClient();
        } else {
          // We can't grow so let's wait for a connection to become available.
          cassandraClient = waitForConnection();
        }

      }

      if ( cassandraClient == null ) {
        throw new HectorException("HConnectionManager returned a null client after aquisition - are we shutting down?");
      }
    } catch (RuntimeException e) {
      activeClientsCount.decrementAndGet();
      throw e;
    }

    realActiveClientsCount.incrementAndGet();
    return cassandraClient;
  }


  private HThriftClient waitForConnection() {
    HThriftClient cassandraClient = null;
    numBlocked.incrementAndGet();

    // blocked take on the queue if we are configured to wait forever
    if ( log.isDebugEnabled() ) {
      log.debug("blocking on queue - current block count {}", numBlocked.get());
    }

    try {
      // wait and catch, creating a new one if the counts have changed. Infinite wait should just recurse.
      if (maxWaitTimeWhenExhausted == 0) {
        while (cassandraClient == null && active.get()) {
          try {
            cassandraClient = availableClientQueue.poll(100, TimeUnit.MILLISECONDS);
          } catch (InterruptedException ie) {
            log.error("InterruptedException poll operation on retry forever", ie);
            break;
          }
        }
      } else {
        try {
          cassandraClient = availableClientQueue.poll(maxWaitTimeWhenExhausted, TimeUnit.MILLISECONDS);
          if (cassandraClient == null) {
            throw new HPoolExhaustedException(String.format(
                "maxWaitTimeWhenExhausted exceeded for thread %s on host %s",
                new Object[] { Thread.currentThread().getName(), cassandraHost.getName() }));
          }
        } catch (InterruptedException ie) {
          // monitor.incCounter(Counter.POOL_EXHAUSTED);
          log.error("Cassandra client acquisition interrupted", ie);
        }
      }
    } finally {
      numBlocked.decrementAndGet();
    }

    return cassandraClient;
  }

  /**
   * Retrieves the first non stale client from the pool.
   * @return The first non-stale thrift client.
   */
  private HThriftClient getNextNonStaleClient() {
    HThriftClient candidate;
    boolean staleCandidate;
    do {
      candidate = availableClientQueue.poll();
      staleCandidate = isStale(candidate);
      if ( staleCandidate ){
        log.info ("Discarding stale connection from Cassandra Pool");
      }
    } while ( staleCandidate && candidate!=null );
    return staleCandidate ? null : candidate;
  }

  /**
   * Performs a rudimentary staleness check on the supplied thrift client.
   * @param activeClient The active cassandra client.
   * @return True if a broken pipe exception is thrown, indicating stale connection.  False otherwise.
   * @throws HectorTransportException when a TException occurs that is not a broken pipe.  Wraps the TException.
   */
  private boolean isStale(HThriftClient activeClient) {
    boolean stale = false;
    if( activeClient != null ) {
      try {
        log.debug("Performing connection stale check");
        activeClient.getCassandra().describe_cluster_name();
        log.debug("Connection is not stale");
      } catch (TException thriftException) {
        if ( ThriftExceptionUtils.isBrokenSocket(thriftException) ) {
          stale = true;
        }
        else {
          throw new HectorTransportException(thriftException);
        }
      }
    }
    return stale;
  }

  /**
   * Used when we still have room to grow. Return an HThriftClient without
   * having to wait on polling logic. (But still increment all the counters)
   * @return
   */
  private HThriftClient createClient() {
    if ( log.isDebugEnabled() ) {
      log.debug("Creation of new client");
    }
    return new HThriftClient(cassandraHost).open();
  }

  /**
   * Controlled shutdown of pool. Go through the list of available clients
   * in the queue and call {@link HThriftClient#close()} on each. Toggles
   * a flag to indicate we are going into shutdown mode. Any subsequent calls
   * will throw an IllegalArgumentException.
   *
   *
   */
  @Override
  public void shutdown() {
    if (!active.compareAndSet(true, false) ) {
      throw new IllegalArgumentException("shutdown() called for inactive pool: " + getName());
    }
    log.info("Shutdown triggered on {}", getName());
    Set<HThriftClient> clients = new HashSet<HThriftClient>();
    availableClientQueue.drainTo(clients);
    if ( clients.size() > 0 ) {
      for (HThriftClient hThriftClient : clients) {
        hThriftClient.close();
      }
    }
    log.info("Shutdown complete on {}", getName());
  }

@Override
public CassandraHost getCassandraHost() {
    return cassandraHost;
  }

@Override
  public String getName() {
    return String.format("<ConcurrentCassandraClientPoolByHost>:{%s}", cassandraHost.getName());
  }

@Override
  public int getNumActive() {
    return realActiveClientsCount.get();
  }


@Override
public int getNumBeforeExhausted() {
    return cassandraHost.getMaxActive() - realActiveClientsCount.get();
  }


@Override
  public int getNumBlockedThreads() {
    return numBlocked.intValue();
  }


@Override
  public int getNumIdle() {
    return availableClientQueue.size();
  }


@Override
public boolean isExhausted() {
    return getNumBeforeExhausted() == 0;
  }

@Override
public int getMaxActive() {
    return cassandraHost.getMaxActive();
  }

@Override
public boolean getIsActive() {
    return active.get();
  }

@Override
public String getStatusAsString() {
    return String.format("%s; IsActive?: %s; Active: %d; Blocked: %d; Idle: %d; NumBeforeExhausted: %d",
        getName(), getIsActive(), getNumActive(), getNumBlockedThreads(), getNumIdle(), getNumBeforeExhausted());
  }

@Override
public void releaseClient(HThriftClient client) throws HectorException {
    boolean open = client.isOpen();
    if ( open ) {
      if ( active.get() ) {
        addClientToPoolGently(client);
      } else {
        log.info("Open client {} released to in-active pool for host {}. Closing.", client, cassandraHost);
        client.close();
      }
    } else {
      try {
        addClientToPoolGently(createClient());
      } catch (HectorTransportException e) {
        // if unable to open client then don't add one back to the pool
        log.error("Transport exception in re-opening client in release on {}", getName());
      }
    }

    realActiveClientsCount.decrementAndGet();
    activeClientsCount.decrementAndGet();

    if ( log.isDebugEnabled() ) {
      log.debug("Status of releaseClient {} to queue: {}", client.toString(), open);
    }
  }

  /**
   * Avoids a race condition on adding clients back to the pool if pool is almost full.
   * Almost always a result of batch operation startup and shutdown (when multiple threads
   * are releasing at the same time).
   * @param client
   */
  private void addClientToPoolGently(HThriftClient client) {
    try {
      availableClientQueue.add(client);
    } catch (IllegalStateException ise) {
      log.warn("Capacity hit adding client back to queue. Closing extra");
      client.close();
    }
  }



}
