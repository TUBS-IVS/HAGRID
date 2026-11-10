package hagrid.core.routing.benchmark;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.util.*;

/** Benchmark-only reachable object sizing. No global heap difference or exclusive transient attribution. */
public final class RetainedGraphAgent {
    private static Instrumentation instrumentation;
    private static final Map<Class<?>, List<Field>> fields=new HashMap<>();
    public static void premain(String ignored, Instrumentation value) { instrumentation=value; }
    public record Graph(long bytes, Set<Object> identities) {}
    public static Graph measure(Collection<?> roots, Set<Object> excluded) {
        if(instrumentation==null)throw new IllegalStateException("Sizing agent is required");
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        var queue=new ArrayDeque<Object>();
        roots.stream().filter(Objects::nonNull).forEach(queue::add);
        long bytes=0;
        while(!queue.isEmpty()) {
            Object value=queue.removeFirst();
            if(value instanceof Class<?> || excluded.contains(value) || !seen.add(value))continue;
            bytes=Math.addExact(bytes,instrumentation.getObjectSize(value));
            Class<?> type=value.getClass();
            if(type.isArray()) {
                if(!type.getComponentType().isPrimitive())
                    for(Object item:(Object[])value)if(item!=null)queue.add(item);
            } else {
                for(Field field:fields.computeIfAbsent(type,RetainedGraphAgent::references)) {
                    try {Object item=field.get(value);if(item!=null)queue.add(item);}
                    catch(IllegalAccessException e){throw new IllegalStateException("Incomplete retained graph at "+field,e);}
                }
            }
        }
        return new Graph(bytes,seen);
    }
    private static List<Field> references(Class<?> type) {
        var result=new ArrayList<Field>();
        for(Class<?> current=type;current!=null;current=current.getSuperclass())
            for(Field field:current.getDeclaredFields())
                if(!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                    if(!field.trySetAccessible())throw new IllegalStateException("Incomplete retained graph; open module for "+field);
                    result.add(field);
                }
        return List.copyOf(result);
    }
}