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

import org.matsim.core.router.util.LeastCostPathCalculator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/** Legacy pool ownership, including its native per-thread compatibility fallback. */
final class LegacyRoutingWorkers {
    private record Handle(LeastCostPathCalculator router,boolean pooled) {}
    private final int maximumPooled;
    private final Supplier<LeastCostPathCalculator> factory;
    private final ConcurrentLinkedQueue<LeastCostPathCalculator> idle=new ConcurrentLinkedQueue<>();
    private final AtomicInteger created=new AtomicInteger();
    private final ThreadLocal<LeastCostPathCalculator> threadRouter;

    LegacyRoutingWorkers(int maximumPooled,Supplier<LeastCostPathCalculator> factory) {
        if(maximumPooled<=0)throw new IllegalArgumentException("Router pool size must be positive");
        this.maximumPooled=maximumPooled;this.factory=factory;
        threadRouter=ThreadLocal.withInitial(factory);
    }
    LeastCostPathCalculator getThreadRouter(){return threadRouter.get();}
    <T> T use(Function<LeastCostPathCalculator,T> calculation) {
        var handle=borrow();
        try {return calculation.apply(handle.router());}
        finally {if(handle.pooled())idle.offer(handle.router());}
    }
    private Handle borrow() {
        var worker=idle.poll();if(worker!=null)return new Handle(worker,true);
        if(created.get()<maximumPooled) {
            if(created.incrementAndGet()<=maximumPooled) {
                try {return new Handle(factory.get(),true);}
                catch(RuntimeException|Error failure){created.decrementAndGet();throw failure;}
            }
            created.decrementAndGet();
        }
        worker=idle.poll();
        return worker!=null?new Handle(worker,true):new Handle(getThreadRouter(),false);
    }
}