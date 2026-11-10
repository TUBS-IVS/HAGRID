package hagrid.hannover.demand;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import org.slf4j.LoggerFactory;
/** Scoped observation of the actual SLF4J SimpleLogger used by the offline test classpath. */
final class SimpleInsertionLogCapture implements AutoCloseable {
 private final PrintStream previous=System.err;
 private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
 private final PrintStream capture=new PrintStream(bytes,true,StandardCharsets.UTF_8);
 private final Object fast,ordinary; private final Field level; private final int fastLevel,ordinaryLevel;
 SimpleInsertionLogCapture() throws Exception {
  fast=LoggerFactory.getLogger(com.graphhopper.jsprit.core.algorithm.recreate.RegretInsertionFast.class);
  ordinary=LoggerFactory.getLogger(com.graphhopper.jsprit.core.algorithm.recreate.RegretInsertion.class);
  if(!fast.getClass().getName().equals("org.slf4j.impl.SimpleLogger"))throw new IllegalStateException("Unexpected SLF4J provider: "+fast.getClass());
  level=fast.getClass().getDeclaredField("currentLogLevel");level.setAccessible(true);fastLevel=level.getInt(fast);ordinaryLevel=level.getInt(ordinary);
  level.setInt(fast,10);level.setInt(ordinary,10);System.setErr(capture);
 }
 String output(){capture.flush();return bytes.toString(StandardCharsets.UTF_8);}
 public void close() throws Exception {System.setErr(previous);level.setInt(fast,fastLevel);level.setInt(ordinary,ordinaryLevel);capture.close();}
}
