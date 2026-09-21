package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The non-core scan must resolve dependencies transitively. An extension family ships its RAG SQL
 * — or the pointer to it — in a single module, so an application that declares a sibling module
 * reaches it only through a transitive edge.
 *
 * <p>Driven through {@link RagSqlLoader#resolveDependencies} against a synthetic local repository,
 * so it runs in CI without forking Maven or needing any artifact to be published.
 */
class RagSqlLoaderTransitiveScanTest {

    private static final String RAG_SQL = """
            DELETE FROM rag_documents WHERE metadata->>'source' = 'acme-docs';
            INSERT INTO rag_documents (embedding_id, embedding, text, metadata) VALUES
            ('3f2504e0-4f89-11d3-9a0c-0305e82c3301', '[0.1]', 'Acme extension docs',
             '{"source":"acme-docs","version":"1.0"}');
            """;

    /**
     * Stands in for Maven: the extension carrying the RAG SQL appears in the dependency list only
     * when the scan asks for transitive resolution, which is exactly the regression in #280.
     */
    private static final class StubLoader extends RagSqlLoader {
        private Boolean askedForTransitive;

        @Override
        List<DependencyResolver.Dependency> resolveDependencies(String projectDir, boolean includeTransitive) {
            askedForTransitive = includeTransitive;
            return includeTransitive
                    ? List.of(new DependencyResolver.Dependency("org.acme", "acme-ext", "1.0"))
                    : List.of(new DependencyResolver.Dependency("org.acme", "acme-app", "1.0"));
        }
    }

    @Test
    void scansExtensionReachedOnlyTransitively(@TempDir Path tempDir) throws Exception {
        Path m2Repo = tempDir.resolve("m2");
        writeDeploymentJar(m2Repo, "org.acme", "acme-ext", "1.0");

        StubLoader loader = new StubLoader();
        loader.nonCoreScanBudgetMillis = 60_000;

        List<RagSqlLoader.RagFragment> fragments = loader.scanNonCoreExtensionJars(
                m2Repo, tempDir.toString(), "3.99.0");

        assertEquals(Boolean.TRUE, loader.askedForTransitive,
                "the non-core scan must resolve dependencies transitively");
        assertEquals(1, fragments.size(),
                "expected the RAG SQL of the transitively reached extension");
        assertEquals("acme-ext", fragments.get(0).source());
        assertTrue(fragments.get(0).sql().contains("\"extension\":\"acme-ext\""),
                "fragment metadata should be re-attributed to the extension it came from");
    }

    private static void writeDeploymentJar(Path m2Repo, String groupId, String artifactId, String version)
            throws Exception {
        Path dir = m2Repo.resolve(groupId.replace('.', '/'))
                .resolve(artifactId + "-deployment")
                .resolve(version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(artifactId + "-deployment-" + version + ".jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/quarkus-rag.sql"));
            jos.write(RAG_SQL.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }
}
