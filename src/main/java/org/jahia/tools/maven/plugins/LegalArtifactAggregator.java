package org.jahia.tools.maven.plugins;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.model.Dependency;
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

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Created by loom on 29.01.16.
 */
class LegalArtifactAggregator {

    private static final String START_INDENT = "";
    private static final String INDENT_STEP = "  ";
    private final File rootDirectory;

    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession repositorySystemSession;
    private final List<RemoteRepository> remoteRepositories;

    private final Map<String, LicenseText> seenLicenses = new HashMap<>(101);
    private final List<String> duplicatedLicenses = new LinkedList<>();
    private final List<String> missingLicenses = new LinkedList<>();


    private final SortedMap<String, Set<Notice>> projectToNotice = new TreeMap<>();
    private final List<String> duplicatedNotices = new LinkedList<>();
    private final List<String> missingNotices = new LinkedList<>();

    private final boolean verbose;
    private final boolean outputDiagnostics;


    LegalArtifactAggregator(File rootDirectory, RepositorySystem repositorySystem, RepositorySystemSession repositorySystemSession, List<RemoteRepository> remoteRepositories,
                            boolean verbose, boolean outputDiagnostics) {
        this.rootDirectory = rootDirectory;
        this.repositorySystem = repositorySystem;
        this.repositorySystemSession = repositorySystemSession;
        this.remoteRepositories = remoteRepositories;
        this.verbose = verbose;
        this.outputDiagnostics = outputDiagnostics;
    }

    void execute() {
        Collection<File> jarFiles = FileUtils.listFiles(rootDirectory, new String[]{"jar"}, true);
        List<String> allNoticeLines = new LinkedList<>();
        for (File jarFile : jarFiles) {
            try {
                processJarFile(jarFile, true, 0, true, true);
            } catch (IOException e) {
                output(START_INDENT, "Error handling JAR " + jarFile.getPath() + ". This file will be ignored.", true, true);
            }
        }

        if (verbose || outputDiagnostics) {
            outputDiagnostics(false);
            outputDiagnostics(true);
        }

        output(START_INDENT, "Processed projects: ");
        for (Map.Entry<String, Set<Notice>> entry : projectToNotice.entrySet()) {
            final String project = entry.getKey();
            output(START_INDENT, project);
            final Set<Notice> notices = entry.getValue();
            if (!notices.isEmpty()) {
                allNoticeLines.add("Notice for " + project);
                allNoticeLines.add("---------------------------------------------------------------------------------------------------");
                for (Notice notice : notices) {
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

            output(START_INDENT, "Aggregated NOTICE created at " + aggregatedNoticeFile.getPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        IOUtils.closeQuietly(writer);

        try {
            File aggregatedLicenseFile = new File(rootDirectory, "LICENSE-aggregated");
            writer = new FileWriter(aggregatedLicenseFile);
            for (Map.Entry<String, LicenseText> license : seenLicenses.entrySet()) {
                output(START_INDENT, "Adding license " + license.getKey());
                writer.append(license.getValue().toString());
                writer.append("\n\n\n");
            }

            output(START_INDENT, "Aggregated LICENSE created at " + aggregatedLicenseFile.getPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        IOUtils.closeQuietly(writer);
    }

    private void outputDiagnostics(boolean forLicenses) {
        final String typeName = forLicenses ? "licenses" : "notices";

        Set seen = forLicenses ? seenLicenses.entrySet() : projectToNotice.entrySet();
        List<String> duplicated = forLicenses ? duplicatedLicenses : duplicatedNotices;
        List<String> missingItems = forLicenses ? missingLicenses : missingNotices;

        output(START_INDENT, "Found " + seen.size() + " unique " + typeName, false, true);

        if (!duplicated.isEmpty()) {
            System.out.println("Omitted duplicated " + typeName + " for the following " + duplicated.size() + " JAR files:");
            for (String duplicate : duplicated) {
                output(INDENT_STEP, duplicate, false, true);
            }
        }

        if (!missingItems.isEmpty()) {
            output(START_INDENT, "Couldn't find any " + typeName + " in the following " + missingItems.size() +
                    " JAR files or their sources. Please check them manually:", false, true);
            for (String missing : missingItems) {
                output(INDENT_STEP, missing, false, true);
            }
        }
    }

    private void processJarFile(File jarFile, boolean processMavenPom, int level, boolean lookForNotice, boolean lookForLicense) throws IOException {
        // if we don't need to find either a license or notice, don't process the jar at all
        if (!lookForLicense && !lookForNotice) {
            return;
        }

        JarFile realJarFile = new JarFile(jarFile);
        Enumeration<JarEntry> jarEntries = realJarFile.entries();
        String pomFilePath = null;

        final String indent = getIndent(level);

        output(indent, "Processing " + jarFile.getName(), false, true);
        Notice notice;
        Set<JarMetadata> embeddedJars = new HashSet<>(7);
        while (jarEntries.hasMoreElements()) {
            JarEntry curJarEntry = jarEntries.nextElement();

            if (!curJarEntry.isDirectory()) {
                final String fileName = curJarEntry.getName();
                if (lookForNotice && isNotice(fileName, jarFile)) {
                    InputStream noticeInputStream = realJarFile.getInputStream(curJarEntry);
                    List<String> noticeLines = IOUtils.readLines(noticeInputStream);
                    notice = new Notice(noticeLines);

                    // compute project name
                    final String jarFileName = getJarFileName(jarFile.getName());
                    final JarMetadata jarMetadata = getJarMetadataIfMavenArtifact(jarFileName);

                    final String project = jarMetadata != null ? jarMetadata.project : JarMetadata.getDashSeparatedProjectName(jarFileName);
                    Set<Notice> notices = projectToNotice.get(project);
                    if (notices == null) {
                        notices = new HashSet<>(17);
                        notices.add(notice);
                        projectToNotice.put(project, notices);
                    } else if (!notices.contains(notice)) {
                        output(indent, "Found notice " + fileName);
                        notices.add(notice);
                    } else {
                        output(indent, "Duplicated notice");
                        duplicatedNotices.add(jarFile.getPath());
                    }

                    lookForNotice = false;

                    IOUtils.closeQuietly(noticeInputStream);
                } else if (processMavenPom && fileName.endsWith("pom.xml")) {
                    // remember pom file path in case we need it
                    pomFilePath = curJarEntry.getName();
                } else if (lookForLicense && isLicense(fileName, jarFile)) {
                    InputStream licenseInputStream = realJarFile.getInputStream(curJarEntry);
                    List<String> licenseLines = IOUtils.readLines(licenseInputStream);

                    LicenseText license = new LicenseText(licenseLines);
                    final String licenseName = license.getName();
                    if (seenLicenses.containsKey(licenseName)) {
                        output(indent, "Duplicated license " + licenseName);
                        duplicatedLicenses.add(jarFile.getPath());
                    } else {
                        output(indent, "Found license " + fileName);
                        seenLicenses.put(licenseName, license);
                    }

                    lookForLicense = false;

                    IOUtils.closeQuietly(licenseInputStream);

                } else if (fileName.endsWith(".jar")) {
                    final JarMetadata jarMetadata = getJarMetadataIfMavenArtifact(getJarFileName(fileName));

                    if (jarMetadata != null) {
                        embeddedJars.add(jarMetadata);
                    }
                }
            }
        }

        if (processMavenPom) {
            if (pomFilePath == null) {
                output(indent, "No POM found");
            } else {
                processPOM(realJarFile, pomFilePath, lookForNotice, lookForLicense, embeddedJars, level + 1);
            }
        }

        if (lookForLicense || lookForNotice) {
            if (lookForLicense) {
                output(indent, "No license found");
            }
            if (lookForNotice) {
                output(indent, "No notice found");
            }

            if (pomFilePath == null && lookForLicense && lookForNotice) {
                output(indent, "===>  Couldn't find nor POM, license or notice. Please check manually!", false, true);
            }
        }

        realJarFile.close();
    }

    private String getIndent(int level) {
        String indent = START_INDENT;
        int i = level;
        while (i-- != 0) {
            indent += INDENT_STEP;
        }
        return indent;
    }

    private JarMetadata getJarMetadataIfMavenArtifact(String file) {
        // look for beginning of version string if any
        int begVersion = -1;
        int dash = file.indexOf('-');
        while (dash > 0 && dash < file.length()) {
            if (Character.isDigit(file.charAt(dash + 1))) {
                begVersion = dash + 1;
                break;
            }
            dash = file.indexOf('-', dash + 1);
        }
        return begVersion != -1 ?
                new JarMetadata(file.substring(0, begVersion - 1), file.substring(begVersion)) :
                null;
    }

    private String getJarFileName(String fileName) {
        final int lastSlash = fileName.lastIndexOf('/');
        fileName = lastSlash > 0 ? fileName.substring(lastSlash + 1) : fileName;
        final int endVersion = fileName.lastIndexOf('.');
        return fileName.substring(0, endVersion);
    }

    private void output(String indent, String message) {
        output(indent, message, false, false);
    }

    private void output(String indent, String message, boolean error, boolean force) {
        PrintStream out = error ? System.err : System.out;
        if (force || error || verbose) {
            out.println(indent + message);
        }
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

    private void processPOM(JarFile realJarFile, String pomFilePath, boolean lookForNotice, boolean lookForLicense, Set<JarMetadata> embeddedJarNames, int level) throws
            IOException {
        // if we're not looking for notice or license and don't have embedded jars, don't process at all
        if (embeddedJarNames.isEmpty() && !lookForNotice && !lookForLicense) {
            return;
        }

        JarEntry pom = new JarEntry(pomFilePath);
        InputStream pomInputStream = realJarFile.getInputStream(pom);

        MavenXpp3Reader reader = new MavenXpp3Reader();
        try {
            final Model model = reader.read(pomInputStream);
            final Parent parent = model.getParent();
            String parentGroupId = null;
            String parentVersion = null;
            if (parent != null) {
                parentGroupId = parent.getGroupId();
                parentVersion = parent.getVersion();
            }

            if (!embeddedJarNames.isEmpty()) {
                final List<Dependency> dependencies = model.getDependencies();
                Map<String, Dependency> artifactToDep = new HashMap<>(dependencies.size());
                for (Dependency dependency : dependencies) {
                    artifactToDep.put(dependency.getArtifactId(), dependency);
                }

                for (JarMetadata jarName : embeddedJarNames) {
                    final Dependency dependency = artifactToDep.get(jarName.name);
                    if (dependency != null) {
                        File jar = getArtifactFile(new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(), null, "jar", jarName.version), level);
                        if (jar != null && jar.exists()) {
                            processJarFile(jar, true, level, true, true);
                        }
                    }
                }
            }

            final String groupId = model.getGroupId() != null ? model.getGroupId() : parentGroupId;
            final String version = model.getVersion() != null ? model.getVersion() : parentVersion;
            final Artifact artifact = new DefaultArtifact(groupId, model.getArtifactId(), "sources", "jar", version);

            File sourceJar = getArtifactFile(artifact, level);
            if (sourceJar != null && sourceJar.exists()) {
                processJarFile(sourceJar, false, level, lookForNotice, lookForLicense);
            }
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }

    }

    private File getArtifactFile(Artifact artifact, int level) {
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(remoteRepositories);

        ArtifactResult artifactResult;
        try {
            artifactResult = repositorySystem.resolveArtifact(repositorySystemSession, request);
            return artifactResult.getArtifact().getFile();
        } catch (ArtifactResolutionException e) {
            output(getIndent(level), "Couldn't find artifact " + artifact + " : " + e.getMessage(), true, true);
        }
        return null;
    }


    private static class JarMetadata {
        final String name;
        final String version;
        final String project;

        private JarMetadata(String name, String version) {
            this.name = name;
            this.version = version;

            // compute project name heuristically
            String project = name;
            final int dot = name.indexOf('.');
            if (dot > 0) {
                // look for tld.hostname.project pattern
                boolean standard = false;
                int host = name.indexOf('.', dot + 1);
                if (host > dot) {
                    int proj = name.indexOf('.', host + 1);
                    project = name.substring(0, proj);
                    standard = true;
                }

                if (!standard) {
                    project = getDashSeparatedProjectName(name);
                }
            } else {
                // assume project is project-subproject-blah...
                project = getDashSeparatedProjectName(name);
            }
            this.project = project;
        }

        static String getDashSeparatedProjectName(String name) {
            final int dash = name.indexOf('-');
            if (dash > 0) {
                return name.substring(0, dash);
            } else {
                return name;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            JarMetadata that = (JarMetadata) o;

            if (name != null ? !name.equals(that.name) : that.name != null) return false;
            return version != null ? version.equals(that.version) : that.version == null;

        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            result = 31 * result + (version != null ? version.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return name + "-" + version;
        }
    }
}
