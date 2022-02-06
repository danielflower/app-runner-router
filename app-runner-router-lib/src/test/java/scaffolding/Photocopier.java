package scaffolding;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import static io.muserver.Mutils.fullPath;
import static org.apache.commons.io.FilenameUtils.separatorsToSystem;

public class Photocopier {

    public static File sampleDir() {
        return new File(projectRoot(), separatorsToSystem("app-runner-router-lib/sample-apps"));
    }

    public static File copySampleAppToTempDir(String sampleAppName) throws IOException {
        String pathname = FilenameUtils.concat(fullPath(sampleDir()), sampleAppName);
        File source = new File(pathname);
        if (!source.isDirectory()) {
            source = new File(separatorsToSystem("../") + pathname);
        }
        if (!source.isDirectory()) {
            throw new RuntimeException("Could not find module " + sampleAppName + " at " + new File(pathname) + " nor " + fullPath(source));
        }

        File target = folderForSampleProject(sampleAppName);
        FileUtils.copyDirectory(source, target);
        return target;
    }

    public static File folderForSampleProject(String moduleName) {
        return new File(projectRoot(), separatorsToSystem("target/samples/" + UUID.randomUUID() + "/" + moduleName));
    }

    public static File projectRoot() {
        File cur;
        try {
            cur = new File(".").getCanonicalFile();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load project root", e);
        }
        while (cur != null) {
            if (new File(cur, ".editorconfig").isFile()) {
                return cur;
            }
            cur = cur.getParentFile();
        }
        throw new RuntimeException("Could not find project root");
    }
}
