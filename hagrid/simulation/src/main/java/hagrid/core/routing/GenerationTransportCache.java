/*******************************************************************************
 * Copyright (c) 2011 Stefan Schroeder.
 * eMail: stefan.schroeder@kit.edu
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     Stefan Schroeder - initial API and implementation
 ******************************************************************************/
package hagrid.core.routing;

import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Bounded completed values, shared in-flight work and isolated generations after travel-time changes. */
final class GenerationTransportCache<K,V> {
    private static final class Generation<K,V> {
        final ConcurrentHashMap<K,V> values=new ConcurrentHashMap<>();
        final ConcurrentHashMap<K,CompletableFuture<V>> inFlight=new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<K> insertionOrder=new ConcurrentLinkedQueue<>();
    }
    record Statistics(long uniqueEntries,long totalRequests,long totalHits,long totalMemoized,
                      long periodRequests,long periodHits,long periodMemoized) {}
    private volatile Generation<K,V> generation=new Generation<>();
    private final int maximumEntries;
    private final Runnable requestCallback,hitCallback,memoizedCallback;
    private final LongAdder requests=new LongAdder(),hits=new LongAdder(),memoized=new LongAdder();
    private long previousRequests,previousHits,previousMemoized;

    GenerationTransportCache(int maximumEntries,Runnable requestCallback,Runnable hitCallback,Runnable memoizedCallback) {
        if(maximumEntries<=0)throw new IllegalArgumentException("Maximum cache entries must be positive");
        this.maximumEntries=maximumEntries;
        this.requestCallback=requestCallback;this.hitCallback=hitCallback;this.memoizedCallback=memoizedCallback;
    }
    V get(K key,Supplier<V> calculate) {
        var current=generation;
        requests.increment();requestCallback.run();
        V result=current.values.get(key);
        if(result!=null){hit();return result;}
        var pending=new CompletableFuture<V>();
        var owner=current.inFlight.putIfAbsent(key,pending);
        if(owner!=null) {
            hit();
            try{return owner.join();}
            catch(CompletionException failure){throw propagate(failure.getCause());}
        }
        try {
            // The previous owner can finish between lookup and registration.
            result=current.values.get(key);
            if(result!=null)hit();
            else {
                result=calculate.get();
                current.values.put(key,result);current.insertionOrder.offer(key);
                while(current.values.size()>maximumEntries) {
                    K oldest=current.insertionOrder.poll();if(oldest==null)break;
                    current.values.remove(oldest);
                }
                memoized.increment();memoizedCallback.run();
            }
            pending.complete(result);return result;
        } catch(Throwable failure) {
            pending.completeExceptionally(failure);throw propagate(failure);
        } finally {current.inFlight.remove(key,pending);}
    }
    void clear(){generation=new Generation<>();}
    private void hit(){hits.increment();hitCallback.run();}
    synchronized Statistics statistics() {
        long totalRequests=requests.sum(),totalHits=hits.sum(),totalMemoized=memoized.sum();
        var result=new Statistics(generation.values.size(),totalRequests,totalHits,totalMemoized,
                Math.max(0,totalRequests-previousRequests),Math.max(0,totalHits-previousHits),Math.max(0,totalMemoized-previousMemoized));
        previousRequests=totalRequests;previousHits=totalHits;previousMemoized=totalMemoized;
        return result;
    }
    private static RuntimeException propagate(Throwable failure) {
        if(failure instanceof Error error)throw error;
        return failure instanceof RuntimeException runtime?runtime:new IllegalStateException(failure);
    }
}