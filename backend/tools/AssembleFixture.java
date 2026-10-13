import brut.androlib.src.SmaliBuilder;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Assembles a directory of .smali files into one classes.dex with the smali/dexlib2 that apktool
 * bundles (brew's apktool jar), the way apktool itself builds a decoded project — no apktool
 * project layout, manifest or resources needed. Used once to produce the committed test fixtures
 * (backend/src/test/fixtures/params/params-fixture.dex); the tests never need apktool.
 *
 *   backend/tools/assemble-fixture.sh backend/src/test/fixtures/params/smali backend/src/test/fixtures/params/params-fixture.dex
 */
public class AssembleFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) { System.err.println("usage: AssembleFixture <smali dir> <out.dex>"); System.exit(2); }
        File dir = new File(args[0]), out = new File(args[1]);
        int api = 26;
        DexBuilder db = new DexBuilder(new Opcodes(api, -1));
        SmaliBuilder sb = new SmaliBuilder(dir, api);
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir.toPath())) { files = s.filter(p -> p.toString().endsWith(".smali")).sorted().toList(); }
        for (Path p : files) {
            String rel = dir.toPath().relativize(p).toString();
            sb.buildFile(rel, db);
            System.out.println("assembled " + rel);
        }
        db.writeTo(new FileDataStore(out));
        System.out.println("wrote " + out + " (" + out.length() + " bytes, " + files.size() + " classes)");
    }
}
