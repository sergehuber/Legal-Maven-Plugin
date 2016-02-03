package org.jahia.tools.maven.plugins;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

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

    private final Map<String, SortedSet<LegalArtifact>> artifacts = new HashMap<>(201);

    private final Set<String> missingPOMs = new TreeSet<>();

    private final Set<LicenseText> seenLicenses = new HashSet<>(201);
    private final List<String> duplicatedLicenses = new LinkedList<>();
    private final List<String> missingLicenses = new LinkedList<>();

    private final Set<Notice> seenNotices = new HashSet<>(201);
    private final List<String> duplicatedNotices = new LinkedList<>();
    private final List<String> missingNotices = new LinkedList<>();

    private final boolean verbose;
    private final boolean outputDiagnostics;


    public NoticeAggregator(File rootDirectory, RepositorySystem repositorySystem, RepositorySystemSession repositorySystemSession, List<RemoteRepository> remoteRepositories,
                            boolean verbose, boolean outputDiagnostics) {
        this.rootDirectory = rootDirectory;
        this.repositorySystem = repositorySystem;
        this.repositorySystemSession = repositorySystemSession;
        this.remoteRepositories = remoteRepositories;
        this.verbose = verbose;
        this.outputDiagnostics = outputDiagnostics;
    }

    public void execute() {
        Collection<File> jarFiles = FileUtils.listFiles(rootDirectory, new String[]{"jar"}, true);
        List<String> allNoticeLines = new LinkedList<>();
        List<String> missing = new LinkedList<>();
        for (File jarFile : jarFiles) {
            try {
                processJarFile(jarFile, true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (verbose || outputDiagnostics) {
            outputDiagnostics(false);
            outputDiagnostics(true);
        }

        if (verbose) {
            System.out.println("Processed artifacts: ");
        }
        for (String groupId : artifacts.keySet()) {
            if (verbose) {
                System.out.println(groupId + ":");
            }
            final SortedSet<LegalArtifact> artifacts = this.artifacts.get(groupId);
            Set<Notice> noticesForGroup = new HashSet<>(artifacts.size());
            for (LegalArtifact artifact : artifacts) {
                if (verbose) {
                    System.out.println("   " + artifact.getArtifactGAV());
                }
                final Notice notice = artifact.getNotice();
                if (notice != null) {
                    noticesForGroup.add(notice);
                } else {
                    missing.add(artifact.getArtifactGAV());
                }
            }


            // notices
            if(!noticesForGroup.isEmpty()) {
                allNoticeLines.add("Notice for " + groupId);
                allNoticeLines.add("---------------------------------------------------------------------------------------------------");
                for (Notice notice : noticesForGroup) {
                    allNoticeLines.add(notice.toString());
                    allNoticeLines.add("\n");
                }
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

    private void outputDiagnostics(boolean forLicenses) {
        final String typeName = forLicenses ? "licenses" : "notices";

        Set seen = forLicenses ? seenLicenses : seenNotices;
        List<String> duplicated = forLicenses ? duplicatedLicenses : duplicatedNotices;
        List<String> missing = forLicenses ? missingLicenses : missingNotices;

        System.out.println("Found " + seen.size() + " unique " + typeName);

        if (!duplicated.isEmpty()) {
            System.out.println("Omitted duplicated " + typeName + " for the following " + duplicated.size() + " JAR files:");
            for (String duplicate : duplicated) {
                System.out.println("   " + duplicate);
            }
        }

        if (!missing.isEmpty()) {
            System.out.println("Couldn't find any " + typeName + " in the following " + missing.size() +
                    " JAR files or their sources. Please check them manually:");
            for (String missingNotice : missing) {
                System.out.println("   " + missingNotice);
            }
        }
    }

    private Notice processJarFile(File jarFile, boolean processMavenPom) throws IOException {
        JarFile realJarFile = new JarFile(jarFile);
        Enumeration<JarEntry> jarEntries = realJarFile.entries();
        String pomFilePath = null;
        Notice notice = null;
        LicenseText license = null;

        while (jarEntries.hasMoreElements()) {
            JarEntry curJarEntry = jarEntries.nextElement();
            if (!curJarEntry.isDirectory()) {
                final String fileName = curJarEntry.getName();
                if (isNotice(fileName, jarFile)) {
                    InputStream noticeInputStream = realJarFile.getInputStream(curJarEntry);
                    List<String> noticeLines = IOUtils.readLines(noticeInputStream);
                    notice = new Notice(noticeLines);

                    if (!seenNotices.contains(notice)) {
                        // remember seen notices to avoid duplication
                        seenNotices.add(notice);
                    } else {
                        duplicatedNotices.add(jarFile.getPath());
                        notice = null;
                    }

                    IOUtils.closeQuietly(noticeInputStream);
                } else if (fileName.endsWith("pom.xml")) {
                    // remember pom file path in case we need it
                    pomFilePath = curJarEntry.getName();
                } else if (isLicense(fileName, jarFile)) {
                    InputStream licenseInputStream = realJarFile.getInputStream(curJarEntry);
                    List<String> licenseLines = IOUtils.readLines(licenseInputStream);
                    license = new LicenseText(licenseLines);

                    if (!seenLicenses.contains(license)) {
                        // remember seen notices to avoid duplication
                        seenLicenses.add(license);
                    } else {
                        duplicatedLicenses.add(jarFile.getPath());
                        license = null;
                    }

                    IOUtils.closeQuietly(licenseInputStream);

                }
            }
        }

        if (processMavenPom) {
            if (pomFilePath == null) {
                missingPOMs.add(realJarFile.getName());
            } else {
                final LegalArtifact legalArtifact = processPOM(realJarFile, pomFilePath, notice, license);
                notice = legalArtifact.getNotice();
            }
        }


        realJarFile.close();

        return notice;
    }

    private boolean isNotice(String fileName, File jarFile) {
        boolean isNotice = fileName.endsWith("NOTICE");

        if (!isNotice) {
            String lowerCase = fileName.toLowerCase();
            // retrieve last part of name
            String separator = lowerCase.contains("\\") ? "\\" : "/";
            final String[] split = lowerCase.split(separator);
            if (split.length > 0) {
                String potential = split[split.length - 1];
                isNotice = potential.startsWith("notice") && !potential.endsWith(".class");

                if (verbose) {
                    if (!isNotice && lowerCase.contains("notice")) {
                        System.out.println("Potential notice file " + fileName + " in JAR " + jarFile.getName()
                                + " was NOT handled. You might want to check manually.");
                    }
                }
            }

        }

        return isNotice;
    }

    private boolean isLicense(String fileName, File jarFile) {
        boolean isLicense = fileName.endsWith("LICENSE");

        if (!isLicense) {
            String lowerCase = fileName.toLowerCase();
            // retrieve last part of name
            String separator = lowerCase.contains("\\") ? "\\" : "/";
            final String[] split = lowerCase.split(separator);
            if (split.length > 0) {
                String potential = split[split.length - 1];
                isLicense = potential.startsWith("license") && !potential.endsWith(".class");

                if (verbose) {
                    if (!isLicense && lowerCase.contains("license")) {
                        System.out.println("Potential license file " + fileName + " in JAR " + jarFile.getName()
                                + " was NOT handled. You might want to check manually.");
                    }
                }
            }
        }

        return isLicense;
    }

    private LegalArtifact processPOM(JarFile realJarFile, String pomFilePath, Notice notice, LicenseText license) throws IOException {
        JarEntry pom = new JarEntry(pomFilePath);
        InputStream pomInputStream = realJarFile.getInputStream(pom);

        MavenXpp3Reader reader = new MavenXpp3Reader();
        LegalArtifact legalArtifact;
        try {
            final Model model = reader.read(pomInputStream);
            final Parent parent = model.getParent();
            Artifact parentArtifact = null;
            String parentGroupId = null;
            String parentVersion = null;
            if (parent != null) {
                parentGroupId = parent.getGroupId();
                parentVersion = parent.getVersion();
                parentArtifact = new DefaultArtifact(parentGroupId, parent.getArtifactId(), "sources", "jar", parentVersion);
            }

            final String groupId = model.getGroupId() != null ? model.getGroupId() : parentGroupId;
            final String version = model.getVersion() != null ? model.getVersion() : parentVersion;
            final Artifact artifact = new DefaultArtifact(groupId, model.getArtifactId(), "sources", "jar", version);

            legalArtifact = new LegalArtifact(artifact, parentArtifact);

            final String artifactGroup = legalArtifact.getArtifact().getGroupId();
            SortedSet<LegalArtifact> legalArtifacts = artifacts.get(artifactGroup);
            if (legalArtifacts == null) {
                legalArtifacts = new TreeSet<>();
                artifacts.put(artifactGroup, legalArtifacts);
            }
            legalArtifacts.add(legalArtifact);

        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }

        if (notice == null) {
            File sourceJar = getArtifactFile(legalArtifact.getArtifact());
            if (sourceJar != null && sourceJar.exists()) {
                notice = processJarFile(sourceJar, false);
            }
        }

        legalArtifact.setNotice(notice);
        legalArtifact.setLicense(license);

        if (notice == null) {
            missingNotices.add(realJarFile.getName());
        }

        return legalArtifact;
    }

    private File getArtifactFile(Artifact artifact) {
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(remoteRepositories);

        ArtifactResult artifactResult;
        try {
            artifactResult = repositorySystem.resolveArtifact(repositorySystemSession, request);
            File artifactFile = artifactResult.getArtifact().getFile();
            return artifactFile;
        } catch (ArtifactResolutionException e) {
            System.err.println("Couldn't find artifact " + artifact + " : " + e.getMessage());
        }
        return null;
    }

}
