package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagSqlLoaderPointerTest {

    private static final String DOCS_RAG_JAR = "io/quarkiverse/langchain4j/quarkus-langchain4j-docs-rag/"
            + "999-SNAPSHOT/quarkus-langchain4j-docs-rag-999-SNAPSHOT.jar";

    /**
     * An application declares a model provider, not the module carrying the RAG pointer file,
     * so the pointer is only reachable through the transitive quarkus-langchain4j-core dependency.
     */
    @Test
    void discoversPointerReachedTransitively(@TempDir Path projectDir) throws IOException {
        Path m2Repo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        assumeTrue(Files.isRegularFile(m2Repo.resolve(DOCS_RAG_JAR)),
                "Skipped: no local quarkus-langchain4j 999-SNAPSHOT artifacts in ~/.m2/repository");

        Files.writeString(projectDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId>
                  <artifactId>rag-discovery</artifactId>
                  <version>1.0.0-SNAPSHOT</version>
                  <dependencies>
                    <dependency>
                      <groupId>io.quarkiverse.langchain4j</groupId>
                      <artifactId>quarkus-langchain4j-openai</artifactId>
                      <version>999-SNAPSHOT</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        RagSqlLoader loader = new RagSqlLoader();
        loader.nonCoreScanBudgetMillis = 120_000;

        List<RagSqlLoader.RagFragment> fragments = loader.discoverSqlFragments("999-SNAPSHOT", projectDir.toString());

        // Assert on the langchain4j fragment specifically, not on the list being non-empty:
        // discoverSqlFragments also contributes the core documentation fragment, which on a
        // machine holding a locally built Quarkus 999-SNAPSHOT would satisfy a non-empty
        // assertion whether or not the transitive scan works.
        List<String> sources = fragments.stream().map(RagSqlLoader.RagFragment::source).toList();
        assertTrue(sources.stream().anyMatch(source -> source.startsWith("quarkus-langchain4j")),
                "RAG fragment should be discovered through a transitive dependency, found: " + sources);
    }
}
