package hagrid.lausitz.freight;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reuses the identical existing tiny Lausitz fixture in separate old/new JVMs. */
public final class GoldenMigrationProbe {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Class<?> fixtureTest = Class.forName("hagrid.lausitz.freight.LausitzFreightPreprocessorTest");
        Method stage = fixtureTest.getDeclaredMethod("stageLmdFixture", Path.class);
        stage.setAccessible(true);
        var constructor = fixtureTest.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object fixture = stage.invoke(constructor.newInstance(), output);
        String[] inputs = new String[4];
        String[] names = {"demandShp", "depotCsv", "netFile", "typesFile"};
        for (int i = 0; i < names.length; i++) {
            Method accessor = fixture.getClass().getDeclaredMethod(names[i]);
            accessor.setAccessible(true);
            inputs[i] = accessor.invoke(fixture).toString();
        }
        Class.forName("hagrid.lausitz.freight.LausitzFreightPreprocessor").getMethod("run",
                String.class, String.class, String.class, String.class, String.class, int.class)
                .invoke(null, inputs[0], inputs[1], inputs[2], inputs[3],
                        output.resolve("carriers.xml").toString(), 5);
    }
}
