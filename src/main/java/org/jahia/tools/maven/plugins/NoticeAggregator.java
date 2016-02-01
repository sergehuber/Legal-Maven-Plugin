package org.jahia.tools.maven.plugins;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Created by loom on 29.01.16.
 */
public class NoticeAggregator {

    private final File rootDirectory;

    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession repositorySystemSession;
    private final List<RemoteRepository> remoteRepositories;

    private final Set<List<String>> seenNotices = new HashSet<List<String>>(19);
    private int numberOfDuplications;
    private final List<String> missingNotices = new LinkedList<String>();

    public NoticeAggregator(File rootDirectory, RepositorySystem repositorySystem, RepositorySystemSession repositorySystemSession, List<RemoteRepository> remoteRepositories) {
        this.rootDirectory = rootDirectory;
        this.repositorySystem = repositorySystem;
        this.repositorySystemSession = repositorySystemSession;
        this.remoteRepositories = remoteRepositories;
    }

    public void execute() {
        Collection<File> jarFiles = FileUtils.listFiles(rootDirectory, new String[] { "jar"}, true);
        List<String> allNoticeLines = new ArrayList<String>();
        for (File jarFile : jarFiles) {
            try {
                allNoticeLines.addAll(processJarFile(jarFile, true));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        System.out.println("Found " + seenNotices.size() + " unique NOTICEs.");
        System.out.println("Avoided " + numberOfDuplications + " duplications.");
        if (!missingNotices.isEmpty()) {
            System.out.println("Couldn't find any NOTICE in the following " + missingNotices.size() +
                    " JAR files or their sources. Please check them manually:");
            for (String missingNotice : missingNotices) {
                System.out.println("   " + missingNotice);
            }
        }

        FileWriter writer = null;
        try {
            File aggregatedNoticeFile = new File(rootDirectory, "NOTICE-aggregated");
            writer = new FileWriter(aggregatedNoticeFile);
            for (String noticeLine : allNoticeLines) {
                writer.append(noticeLine);
                writer.append("\n");
            }

            System.out.println("Aggregated NOTICE created at " + aggregatedNoticeFile.getPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        IOUtils.closeQuietly(writer);
    }

    private List<String> processJarFile(File jarFile, boolean processMavenPom) throws IOException {
        JarFile realJarFile = new JarFile(jarFile);
        Enumeration<JarEntry> jarEntries = realJarFile.entries();
        List<String> allNoticeLines = new ArrayList<String>();
        String pomFilePath = null;
        boolean bypassed = false;

        while (jarEntries.hasMoreElements()) {
            JarEntry curJarEntry = jarEntries.nextElement();
            if (!curJarEntry.isDirectory()) {
                final String fileName = curJarEntry.getName().toLowerCase();
                if (fileName.contains("notice")) {
                    InputStream noticeInputStream = realJarFile.getInputStream(curJarEntry);
                    List<String> noticeLines = IOUtils.readLines(noticeInputStream);

                    if (!seenNotices.contains(noticeLines)) {
                        // remember seen notices to avoid duplication
                        seenNotices.add(new ArrayList<String>(noticeLines));

                        // check if we don't have the standard Apache notice
                        final int i = noticeLines.indexOf("This product includes software developed at");
                        if (i >= 0) {
                            noticeLines.remove(i);
                            noticeLines.remove(i);
                        }

                        allNoticeLines.addAll(noticeLines);
                        IOUtils.closeQuietly(noticeInputStream);
                    } else {
                        bypassed = true;
                        numberOfDuplications++;
                    }
                } else if (fileName.contains("pom.xml")) {
                    // remember pom file path in case we need it
                    pomFilePath = curJarEntry.getName();
                }
            }
        }

        if (!bypassed && allNoticeLines.size() == 0 && processMavenPom && pomFilePath != null) {
            allNoticeLines = processPOM(realJarFile, pomFilePath);
        }

        realJarFile.close();

        return allNoticeLines;
    }

    private List<String> processPOM(JarFile realJarFile, String pomFilePath) throws IOException {
        JarEntry pom = new JarEntry(pomFilePath);
        InputStream pomInputStream = realJarFile.getInputStream(pom);
        final List<Artifact> embeddedArtifacts = new ArrayList<Artifact>();
        try {
            SAXBuilder jdomBuilder = new SAXBuilder();
            Document jdomDocument = jdomBuilder.build(pomInputStream);
            Namespace mavenNamespace = Namespace.getNamespace("http://maven.apache.org/POM/4.0.0");
            Element rootElement = jdomDocument.getRootElement();
            String groupId = null;
            Element groupIdElement = rootElement.getChild("groupId", mavenNamespace);
            if (groupIdElement != null) {
                groupId = groupIdElement.getTextTrim();
            } else {
                Element parentElement = rootElement.getChild("parent", mavenNamespace);
                if (parentElement != null) {
                    groupIdElement = parentElement.getChild("groupId", mavenNamespace);
                    if (groupIdElement != null) {
                        groupId = groupIdElement.getTextTrim();
                    }
                }
            }
            String artifactId = null;
            Element artifactIdElement = rootElement.getChild("artifactId", mavenNamespace);
            if (artifactIdElement != null) {
                artifactId = artifactIdElement.getTextTrim();
            }
            String version = null;
            Element versionElement = rootElement.getChild("version", mavenNamespace);
            if (versionElement != null) {
                version = versionElement.getTextTrim();
            } else {
                Element parentElement = rootElement.getChild("parent", mavenNamespace);
                if (parentElement != null) {
                    versionElement = parentElement.getChild("version", mavenNamespace);
                    if (versionElement != null) {
                        version = versionElement.getTextTrim();
                    }
                }
            }
            Artifact artifact = new DefaultArtifact(groupId, artifactId, "sources", "jar", version);
            embeddedArtifacts.add(artifact);
        } catch (JDOMException e) {
            e.printStackTrace();
        }

        final List<String> allNoticeLines = new LinkedList<String>();
        for (Artifact embeddedArtifact : embeddedArtifacts) {
            File sourceJar = getArtifactFile(embeddedArtifact);
            if (sourceJar != null && sourceJar.exists()) {
                allNoticeLines.addAll(processJarFile(sourceJar, false));
            }
        }

        if (allNoticeLines.size() == 0) {
            missingNotices.add(realJarFile.getName());
        }

        return allNoticeLines;
    }

    private File getArtifactFile(Artifact artifact) {
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(
                artifact );
        request.setRepositories( remoteRepositories );

        ArtifactResult artifactResult;
        try
        {
            artifactResult = repositorySystem.resolveArtifact( repositorySystemSession, request );
            File artifactFile = artifactResult.getArtifact().getFile();
            return artifactFile;
        } catch ( ArtifactResolutionException e ) {
            System.err.println("Couldn't find artifact " + artifact + " : " + e.getMessage());
        }
        return null;
    }

}
