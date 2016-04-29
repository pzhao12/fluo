/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.core.worker;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Gauge;
import io.fluo.api.data.Column;
import io.fluo.api.data.RowColumn;
import io.fluo.core.impl.Environment;
import io.fluo.core.impl.Notification;
import io.fluo.core.util.FluoThreadFactory;
import io.fluo.core.util.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotificationProcessor implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);

  private NotificationTracker tracker;
  private ThreadPoolExecutor executor;
  private Environment env;
  private Observers observers;
  private LinkedBlockingQueue<Runnable> queue;

  public NotificationProcessor(Environment env) {
    int numThreads = env.getConfiguration().getWorkerThreads();
    this.env = env;
    this.queue = new LinkedBlockingQueue<>();
    this.executor =
        new ThreadPoolExecutor(numThreads, numThreads, 0L, TimeUnit.MILLISECONDS, queue,
            new FluoThreadFactory("ntfyProc"));
    this.tracker = new NotificationTracker();
    this.observers = new Observers(env);
    env.getSharedResources().getMetricRegistry()
        .register(env.getMetricNames().getNotificationQueued(), new Gauge<Integer>() {
          @Override
          public Integer getValue() {
            return queue.size();
          }
        });
  }

  // little utility class that tracks all notifications in queue
  private class NotificationTracker {
    private Map<RowColumn, Future<?>> queuedWork = new HashMap<>();
    private long sizeInBytes = 0;
    private static final long MAX_SIZE = 1 << 24;

    private long size(RowColumn rowCol) {
      Column col = rowCol.getColumn();
      return rowCol.getRow().length() + col.getFamily().length() + col.getQualifier().length()
          + col.getVisibility().length();
    }

    public synchronized boolean add(RowColumn rowCol, Future<?> task) {

      if (queuedWork.containsKey(rowCol)) {
        return false;
      }

      while (sizeInBytes > MAX_SIZE) {
        try {
          wait(1000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }

      if (queuedWork.containsKey(rowCol)) {
        return false;
      }

      queuedWork.put(rowCol, task);
      sizeInBytes += size(rowCol);
      return true;
    }

    public synchronized void remove(RowColumn rowCol) {
      if (queuedWork.remove(rowCol) != null) {
        sizeInBytes -= size(rowCol);
        notify();
      }
    }

    public synchronized void clear() {
      for (Future<?> task : queuedWork.values()) {
        task.cancel(false);
      }

      queuedWork.clear();
      sizeInBytes = 0;
      notify();
    }

    public boolean requeue(RowColumn rowCol, FutureTask<?> ft) {
      if (!queuedWork.containsKey(rowCol)) {
        return false;
      }

      queuedWork.put(rowCol, ft);

      return true;
    }

  }

  private static class NotificationProcessingTask implements Runnable {

    Notification notification;
    NotificationFinder notificationFinder;
    WorkTaskAsync workTask;

    NotificationProcessingTask(Notification n, NotificationFinder nf, WorkTaskAsync wt) {
      this.notification = n;
      this.notificationFinder = nf;
      this.workTask = wt;
    }

    @Override
    public void run() {
      try {
        // Its possible that while the notification was in the queue the situation changed and it
        // should no longer be processed by this worker. So ask as late as possible if this
        // notification should be processed.
        if (notificationFinder.shouldProcess(notification)) {
          workTask.run();
        }
      } catch (Exception e) {
        log.error("Failed to process work " + Hex.encNonAscii(notification), e);
      }
    }

  }

  public boolean addNotification(final NotificationFinder notificationFinder,
      final Notification notification) {

    final WorkTaskAsync workTask =
        new WorkTaskAsync(this, notificationFinder, env, notification, observers);
    FutureTask<?> ft =
        new FutureTask<Void>(new NotificationProcessingTask(notification, notificationFinder,
            workTask), null);

    if (!tracker.add(notification, ft)) {
      return false;
    }

    try {
      executor.execute(ft);
    } catch (RejectedExecutionException rje) {
      tracker.remove(notification);
      throw rje;
    }

    return true;
  }

  public void requeueNotification(final NotificationFinder notificationFinder,
      final Notification notification) {

    final WorkTaskAsync workTask =
        new WorkTaskAsync(this, notificationFinder, env, notification, observers);
    FutureTask<?> ft =
        new FutureTask<Void>(new NotificationProcessingTask(notification, notificationFinder,
            workTask), null);
    if (tracker.requeue(notification, ft)) {
      try {
        executor.execute(ft);
      } catch (RejectedExecutionException rje) {
        tracker.remove(notification);
        throw rje;
      }
    }
  }

  public void notificationProcessed(final Notification notification) {
    tracker.remove(notification);
  }

  public int size() {
    return queue.size();
  }

  public void clear() {
    tracker.clear();
    executor.purge();
  }

  @Override
  public void close() {
    executor.shutdownNow();
    observers.close();

    try {
      while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {

      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}