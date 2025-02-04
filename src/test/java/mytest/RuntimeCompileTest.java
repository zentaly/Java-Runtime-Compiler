package mytest;

import net.openhft.compiler.CachedCompiler;
import net.openhft.compiler.CompilerUtils;
import org.junit.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RuntimeCompileTest {
    static String code = "package mytest;\n" +
            "public class Test implements IntConsumer {\n" +
            "    public void accept(int num) {\n" +
            "        if ((byte) num != num)\n" +
            "            throw new IllegalArgumentException();\n" +
            "    }\n" +
            "}\n";

    @Test
    public void outOfBounds() throws Exception {
        ClassLoader cl = new URLClassLoader(new URL[0]);
        Class<?> aClass = CompilerUtils.CACHED_COMPILER.
                loadFromJava(cl, "mytest.Test", code);
        IntConsumer consumer = (IntConsumer) aClass.getDeclaredConstructor().newInstance();
        consumer.accept(1); // ok
        try {
            consumer.accept(128); // no ok
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testMultiThread() throws Exception {
        StringBuilder largeClass = new StringBuilder("package mytest;\n" +
                "public class Test2 implements IntConsumer, java.util.function.IntSupplier {\n" +
                "    static final java.util.concurrent.atomic.AtomicInteger called = new java.util.concurrent.atomic.AtomicInteger(0);\n" +
                "    public int getAsInt() { return called.get(); }\n" +
                "    public void accept(int num) {\n" +
                "        called.incrementAndGet();\n" +
                "    }\n");
        for (int j=0;j<1_000;j++) {
            largeClass.append("    public void accept"+j+"(int num) {\n" +
                    "        if ((byte) num != num)\n" +
                    "            throw new IllegalArgumentException();\n" +
                    "    }\n");
        }
        largeClass.append("}\n");
        final String code2 = largeClass.toString();

        final ClassLoader cl = new URLClassLoader(new URL[0]);
        final CachedCompiler cc = new CachedCompiler(null, null);
        final int nThreads = Runtime.getRuntime().availableProcessors();
        final AtomicInteger started = new AtomicInteger(0);
        final ExecutorService executor = Executors.newFixedThreadPool(nThreads);
        final List<Future<?>> futures = new ArrayList<>();
        for (int i=0; i<nThreads; i++) {
            final int value = i;
            futures.add(executor.submit(() -> {
                started.incrementAndGet();
                while (started.get() < nThreads)
                    ;
                try {
                    Class<?> aClass = cc.loadFromJava(cl, "mytest.Test2", code2);
                    IntConsumer consumer = (IntConsumer) aClass.getDeclaredConstructor().newInstance();
                    consumer.accept(value);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        executor.shutdown();
        for (Future<?> f : futures)
            f.get(10, TimeUnit.SECONDS);
        Class<?> aClass = cc.loadFromJava(cl, "mytest.Test2", code2);
        IntSupplier consumer = (IntSupplier) aClass.getDeclaredConstructor().newInstance();
        assertEquals(nThreads, consumer.getAsInt());
    }
}

