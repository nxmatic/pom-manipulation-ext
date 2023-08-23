package org.commonjava.maven.ext.manip;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.annotation.PostConstruct;
import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.maven.building.FileSource;
import org.apache.maven.building.Source;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.io.DefaultModelReader;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.core.ManipulationManager;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.io.PomIO;

import io.vavr.CheckedFunction0;

@Named
@Priority(Integer.MAX_VALUE)
@Singleton
public class ManipulationModelReader extends DefaultModelReader {

    @Override
    public Model read(File inputFile, Map<String, ?> options) throws IOException {
        CheckedFunction0<Model> fallback = () -> super.read(inputFile, options);
        return manipulate(inputFile, options, fallback.unchecked());
    }

    @Override
    public Model read(InputStream input, Map<String, ?> options) throws IOException {
        Source modelSource = (Source) options.get(ModelProcessor.SOURCE);
        if (Objects.isNull(modelSource)) {
            return super.read(input, options);
        }
        if (!(modelSource instanceof FileSource)) {
            return super.read(input, options);
        }
        FileSource fileSource = (FileSource) modelSource;
        CheckedFunction0<Model> fallback = () -> super.read(input, options);
        return manipulate(fileSource.getFile(), options, fallback.unchecked());
    }

    public ManipulationModelReader() {
        super(); // default constructor required for guice
    }

    @Inject
    ManipulationManager manager;

    @Inject
    PomIO pomIO;

    Model manipulate(File pomFile, Map<String, ?> options, Supplier<Model> fallback) throws IOException {
        // request for a session
        Optional<ManipulationSession> sessionHolder = manager.getSession(pomFile);
        if (sessionHolder.isEmpty()) {
            return fallback.get();
        }
        // ensure the session is enabled
        ManipulationSession session = sessionHolder.get();
        if (!session.isEnabled()) {
            return fallback.get();
        }
        // apply the manipulators
        Project project;
        try {
            project = manager.scanAndApply(session, pomFile).get();
        } catch (ManipulationException cause) {
            throw new IOException("Cannot manipulate " + pomFile, cause);
        }
        // read back using the maven XML parser and return it
        return pomIO.readBackInMaven(project, input -> super.read(input, options));
    }

    
    Provider<ExecutorService> writingPOMServiceProvider = () -> {
        throw new IllegalStateException("writing pom executor service not provided");
    };

    @PostConstruct
    public void initWritingPOMServiceProvider() {
        BasicThreadFactory factory = new BasicThreadFactory.Builder()
                     .namingPattern("pom-manipulating-writer-%05d")
                     .daemon(true)
                     .priority(Thread.MAX_PRIORITY)
                    .build();
        ExecutorService executor = Executors.newCachedThreadPool(factory);
        writingPOMServiceProvider = () -> executor;
    }
 
    Model readBackInMaven(Map<String, ?> options, Project project) throws IOException {
        try (PipedOutputStream output = new PipedOutputStream();
                PipedInputStream input = new PipedInputStream(output)) {
            writingPOMServiceProvider.get().submit(() -> {
                try (PipedOutputStream autoClosingOutput = output) {
                    pomIO.writeModel(project.getModel(), autoClosingOutput);
                } catch (IOException e) {
                    // Handle exception
                }
            });
            return super.read(input, options);
        }
    }

    class PomFileLocationInjector {

        final Model model;

        PomFileLocationInjector(Model thatModel) {
            this.model = thatModel;
        }

        Model inject() {
            model.setLocation("", inputLocation());
            return model;
        }

        InputLocation inputLocation() {
            return new InputLocation(0, 0, inputSource());
        }

        InputSource inputSource() {
            InputSource inputSource = new InputSource();
            inputSource.setLocation(model.getPomFile().getAbsolutePath());
            inputSource.setModelId(modelId());

            return inputSource;
        }

        String modelId() {
            if (model == null) {
                return "";
            }

            String groupId = model.getGroupId();
            if (groupId == null && model.getParent() != null) {
                groupId = model.getParent().getGroupId();
            }

            String artifactId = model.getArtifactId();

            String version = model.getVersion();
            if (version == null && model.getParent() != null) {
                version = model.getParent().getVersion();
            }
            if (version == null) {
                version = "[unknown-version]";
            }

            return toId(groupId, artifactId, version);
        }

        String toId(String groupId, String artifactId, String version) {
            StringBuilder buffer = new StringBuilder(128);

            buffer.append((groupId != null && groupId.length() > 0) ? groupId : "[unknown-group-id]");
            buffer.append(':');
            buffer.append((artifactId != null && artifactId.length() > 0) ? artifactId : "[unknown-artifact-id]");
            buffer.append(':');
            buffer.append((version != null && version.length() > 0) ? version : "[unknown-version]");

            return buffer.toString();
        }
    }

}
