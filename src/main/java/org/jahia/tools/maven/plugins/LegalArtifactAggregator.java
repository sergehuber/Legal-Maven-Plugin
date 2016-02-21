package org.jahia.tools.maven.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.scm.manager.ScmManager;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.glassfish.jersey.client.ClientProperties;

import javax.net.ssl.*;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.*;
import java.io.ByteArrayOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by loom on 29.01.16.
 */
class LegalArtifactAggregator {

    private static final String START_INDENT = "";
    private static final String INDENT_STEP = "  ";
    private static final int EDIT_DISTANCE_THRESHOLD = 1000;

    private final File scanDirectory;
    private final File outputDirectory;

    private static Map<String,Client> clients = new TreeMap<String,Client>();
    public static final String MAVEN_SEARCH_HOST_URL = "http://search.maven.org";
    public static final String NETWORK_ERROR_PREFIX = "NETWORK ERROR: ";

    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession repositorySystemSession;
    private final List<RemoteRepository> remoteRepositories;
    private final ScmManager scmManager;

    private final Map<KnownLicense, SortedSet<LicenseFile>> knownLicensesFound = new HashMap<>();
    private final Map<String,Set<LicenseFile>> projectToLicenseFiles = new TreeMap<>();
    private final List<String> missingLicenses = new LinkedList<>();

    private final SortedMap<String, Set<Notice>> projectToNotice = new TreeMap<>();
    private final List<String> duplicatedNotices = new LinkedList<>();
    private final List<String> missingNotices = new LinkedList<>();

    private final SortedMap<String,SortedSet<PackageInfo>> jarPackages = new TreeMap<>();

    private final boolean verbose;
    private final boolean outputDiagnostics;
    private final boolean updateKnownLicenses = true;

    private Set<String> forbiddenKeyWords = new HashSet<>();

    KnownLicenses knownLicenses = null;
    ObjectMapper mapper = new ObjectMapper();

    LegalArtifactAggregator(File scanDirectory, File outputDirectory, RepositorySystem repositorySystem, RepositorySystemSession repositorySystemSession, List<RemoteRepository> remoteRepositories, ScmManager scmManager,
                            boolean verbose, boolean outputDiagnostics) {
        this.scanDirectory = scanDirectory;
        this.outputDirectory = outputDirectory;
        this.repositorySystem = repositorySystem;
        this.repositorySystemSession = repositorySystemSession;
        this.remoteRepositories = remoteRepositories;
        this.scmManager = scmManager;
        this.verbose = verbose;
        this.outputDiagnostics = outputDiagnostics;
        forbiddenKeyWords.add("gpl");

        JaxbAnnotationModule jaxbAnnotationModule = new JaxbAnnotationModule();
        // configure as necessary
        mapper.registerModule(jaxbAnnotationModule);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        loadKnownLicenses();
    }

    void execute() {
        Collection<File> jarFiles = FileUtils.listFiles(scanDirectory, new String[]{"jar"}, true);
        for (File jarFile : jarFiles) {
            FileInputStream jarInputStream = null;
            try {
                jarInputStream = new FileInputStream(jarFile);
                processJarFile(jarInputStream, jarFile.getPath(), true, 0, true, true);
            } catch (IOException e) {
                output(START_INDENT, "Error handling JAR " + jarFile.getPath() + ":" + e.getMessage() + ". This file will be ignored.", true, true);
                e.printStackTrace();
            } finally {
                IOUtils.closeQuietly(jarInputStream);
            }
        }

        if (verbose || outputDiagnostics) {
            outputDiagnostics(false);
            outputDiagnostics(true);
        }

        output(START_INDENT, "Processed projects: ");
        List<String> allNoticeLines = new LinkedList<>();
        for (Map.Entry<String, Set<Notice>> entry : projectToNotice.entrySet()) {
            final String project = entry.getKey();
            output(START_INDENT, project);
            final Set<Notice> notices = entry.getValue();
            if (!notices.isEmpty()) {
                allNoticeLines.add("");
                allNoticeLines.add(getStartTitle("Notice for " + project));
                for (Notice notice : notices) {
                    allNoticeLines.add(notice.toString());
                }
                allNoticeLines.add(getEndTitle("End of notice for " + project));
            }
        }

        FileWriter writer = null;
        try {
            File aggregatedNoticeFile = new File(outputDirectory, "NOTICE-aggregated");
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
            File aggregatedLicenseFile = new File(outputDirectory, "LICENSE-aggregated");
            writer = new FileWriter(aggregatedLicenseFile);
            for (Map.Entry<KnownLicense, SortedSet<LicenseFile>> foundKnownLicenseEntry : knownLicensesFound.entrySet()) {
                output(START_INDENT, "Adding license " + foundKnownLicenseEntry.getKey().getName());
                SortedSet<LicenseFile> licenseFiles = foundKnownLicenseEntry.getValue();
                writer.append("License for:\n");
                for (LicenseFile licenseFile : licenseFiles) {
                    writer.append("  " + licenseFile.getProjectOrigin() + "\n");
                }
                writer.append(getStartTitle(foundKnownLicenseEntry.getKey().getName()));
                writer.append("\n");
                writer.append(foundKnownLicenseEntry.getKey().getTextToUse());
                writer.append(getEndTitle("End of " + foundKnownLicenseEntry.getKey().getName()));
                writer.append("\n\n");
            }

            output(START_INDENT, "Aggregated LICENSE created at " + aggregatedLicenseFile.getPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        IOUtils.closeQuietly(writer);

        if (updateKnownLicenses) {
            saveKnownLicenses();
        }

        File jarPackagesFile = new File(outputDirectory, "jar-packages.json");
        try {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(jarPackagesFile, jarPackages);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void loadKnownLicenses() {
        URL knownLicensesJSONURL = this.getClass().getClassLoader().getResource("known-licenses.json");
        if (knownLicensesJSONURL != null) {
            try {
                knownLicenses = mapper.readValue(knownLicensesJSONURL, KnownLicenses.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (KnownLicense knownLicense : knownLicenses.getLicenses().values()) {
            for (TextVariant textVariant : knownLicense.getTextVariants()) {
                URL textVariantURL = this.getClass().getClassLoader().getResource("known-licenses/" + knownLicense.getId() + "/variants/" + textVariant.getId() + ".txt");
                if (textVariantURL != null) {
                    try {
                        String textVariantText = IOUtils.toString(textVariantURL);
                        textVariant.setText(textVariantText);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            knownLicense.setTextVariants(knownLicense.getTextVariants());
            if (knownLicense.getTextToUse() != null && knownLicense.getTextToUse().startsWith("classpath:")) {
                String textToUseLocation = knownLicense.getTextToUse().substring("classpath:".length());
                URL textToUseURL = this.getClass().getClassLoader().getResource("known-licenses/" + knownLicense.getId() + "/" + textToUseLocation);
                if (textToUseURL != null) {
                    try {
                        String realTextToUse = IOUtils.toString(textToUseURL);
                        knownLicense.setTextToUse(realTextToUse);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void saveKnownLicenses() {

        File knownLicensesDirectory = new File(outputDirectory, "known-licenses");
        knownLicensesDirectory.mkdirs();
        for (KnownLicense knownLicense : knownLicenses.getLicenses().values()) {
            File knownLicenseDir = new File(knownLicensesDirectory, knownLicense.getId());
            if (!knownLicenseDir.exists()) {
                knownLicenseDir.mkdirs();
            }
            File knownLicenseVariantsDir = new File(knownLicenseDir, "variants");
            if (!knownLicenseVariantsDir.exists()) {
                knownLicenseVariantsDir.mkdirs();
            }
            for (TextVariant textVariant : knownLicense.getTextVariants()) {
                String variantFileName = textVariant.getId() + ".txt";
                File variantFile = new File(knownLicenseVariantsDir, variantFileName);
                FileWriter variantFileWriter = null;
                try {
                    variantFileWriter = new FileWriter(variantFile);
                    IOUtils.write(textVariant.getText(), variantFileWriter);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    IOUtils.closeQuietly(variantFileWriter);
                }
            }
            if (knownLicense.getTextToUse() != null) {
                String textToUseFileName = "LICENSE.txt";
                File textToUseFile = new File(knownLicenseDir, textToUseFileName);
                FileWriter textToUseFileWriter = null;
                try {
                    textToUseFileWriter = new FileWriter(textToUseFile);
                    IOUtils.write(knownLicense.getTextToUse(), textToUseFileWriter);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    IOUtils.closeQuietly(textToUseFileWriter);
                }
                knownLicense.setTextToUse("classpath:" + textToUseFileName);
            }
        }

        File knownLicensesFile = new File(outputDirectory, "known-licenses.json");
        try {
            mapper.writeValue(knownLicensesFile, knownLicenses);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getStartTitle(String title) {
        StringBuilder result = new StringBuilder();
        result.append("---[ ");
        result.append(title);
        result.append(" ]");
        while (result.length() < 78) {
            result.append("-");
        }
        return result.toString();
    }

    private String getEndTitle(String title) {
        StringBuilder result = new StringBuilder();
        while (result.length() < 78 - title.length() - 2 - 5) {
            result.append("-");
        }
        result.append("[ ");
        result.append(title);
        result.append(" ]---");
        return result.toString();
    }

    private void outputDiagnostics(boolean forLicenses) {
        final String typeName = forLicenses ? "licenses" : "notices";

        Set seen = projectToNotice.entrySet();
        List<String> duplicated = duplicatedNotices;
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

    private void processJarFile(InputStream inputStream, String jarFilePath, boolean processMavenPom, int level, boolean lookForNotice, boolean lookForLicense) throws IOException {
        // if we don't need to find either a license or notice, don't process the jar at all
        if (!lookForLicense && !lookForNotice) {
            return;
        }

        final String indent = getIndent(level);
        output(indent, "Processing JAR " + jarFilePath + "...", false, true);

        // JarFile realJarFile = new JarFile(jarFile);
        JarInputStream jarInputStream = new JarInputStream(inputStream);
        String bundleLicense = null;
        Manifest manifest = jarInputStream.getManifest();
        if (manifest != null && manifest.getMainAttributes() != null) {
            bundleLicense = manifest.getMainAttributes().getValue("Bundle-License");
            if (bundleLicense != null) {
                output(indent, "Found Bundle-License attribute with value:" + bundleLicense);
                KnownLicense knownLicense = getKnowLicenseByName(bundleLicense);
                // this data is not reliable, especially on the ServiceMix repackaged bundles
            }
        }
        String pomFilePath = null;
        byte[] pomByteArray = null;


        Notice notice;
        Set<JarMetadata> embeddedJars = new HashSet<>(7);
        JarEntry curJarEntry = null;
        while ((curJarEntry = jarInputStream.getNextJarEntry()) != null) {

            if (!curJarEntry.isDirectory()) {
                final String fileName = curJarEntry.getName();
                if (lookForNotice && isNotice(fileName, jarFilePath)) {

                    output(indent, "Processing notice found in " + curJarEntry + "...");

                    InputStream noticeInputStream = jarInputStream;
                    List<String> noticeLines = IOUtils.readLines(noticeInputStream);
                    notice = new Notice(noticeLines);

                    // compute project name
                    final String jarFileName = getJarFileName(jarFilePath);
                    final JarMetadata jarMetadata = new JarMetadata(jarFileName);

                    final String project = jarMetadata.getProject() != null ? jarMetadata.getProject() : JarMetadata.getDashSeparatedProjectName(jarFileName);
                    Set<Notice> notices = projectToNotice.get(project);
                    if (notices == null) {
                        notices = new HashSet<>(17);
                        notices.add(notice);
                        output(indent, "Found first notice " + curJarEntry);
                        projectToNotice.put(project, notices);
                    } else if (!notices.contains(notice)) {
                        output(indent, "Found additional notice " + curJarEntry);
                        notices.add(notice);
                    } else {
                        output(indent, "Duplicated notice in " + curJarEntry);
                        duplicatedNotices.add(jarFilePath);
                    }

                    lookForNotice = false;

                    // IOUtils.closeQuietly(noticeInputStream);
                } else if (processMavenPom && fileName.endsWith("pom.xml")) {
                    // remember pom file path in case we need it
                    pomFilePath = curJarEntry.getName();
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    IOUtils.copy(jarInputStream, byteArrayOutputStream);
                    pomByteArray = byteArrayOutputStream.toByteArray();

                } else if (lookForLicense && isLicense(fileName, jarFilePath)) {

                    output(indent, "Processing license found in " + curJarEntry + "...");
                    InputStream licenseInputStream = jarInputStream;
                    List<String> licenseLines = IOUtils.readLines(licenseInputStream);

                    LicenseFile licenseFile = new LicenseFile(jarFilePath, licenseLines);

                    resolveKnownLicensesByText(licenseFile);

                    if (StringUtils.isNotBlank(licenseFile.getAdditionalLicenseText())) {
                        KnownLicense knownLicense = new KnownLicense();
                        knownLicense.setId(jarFilePath + "-additional-terms");
                        knownLicense.setName("Additional license terms from " + jarFilePath);
                        List<TextVariant> textVariants = new ArrayList<>();
                        TextVariant textVariant = new TextVariant();
                        textVariant.setId("default");
                        textVariant.setDefaultVariant(true);
                        textVariant.setText(Pattern.quote(licenseFile.getAdditionalLicenseText()));
                        textVariants.add(textVariant);
                        knownLicense.setTextVariants(textVariants);
                        knownLicense.setTextToUse(licenseFile.getAdditionalLicenseText());
                        knownLicense.setViral(licenseFile.getText().toLowerCase().contains("gpl"));
                        knownLicenses.getLicenses().put(knownLicense.getId(), knownLicense);
                        licenseFile.getKnownLicenses().add(knownLicense);
                    }

                    for (KnownLicense knownLicense : licenseFile.getKnownLicenses()) {
                        SortedSet<LicenseFile> licenseFiles = knownLicensesFound.get(knownLicense);
                        if (licenseFiles != null) {
                            if (!licenseFiles.contains(licenseFile)) {
                                licenseFiles.add(licenseFile);
                            }
                            knownLicensesFound.put(knownLicense, licenseFiles);
                        } else {
                            licenseFiles = new TreeSet<>();
                            licenseFiles.add(licenseFile);
                            knownLicensesFound.put(knownLicense, licenseFiles);
                        }
                    }

                    Set<LicenseFile> licenseFiles = projectToLicenseFiles.get(jarFilePath);
                    if (licenseFiles == null) {
                        licenseFiles = new HashSet<>();
                    }
                    if (licenseFiles.contains(licenseFile)) {
                        // warning we already have a license file here, what should we do ?
                        output(indent, "License file already exists for " + jarFilePath + " will override it !", true, false);
                        licenseFiles.remove(licenseFile);
                    }
                    licenseFiles.add(licenseFile);

                    lookForLicense = false;

                    // IOUtils.closeQuietly(licenseInputStream);

                } else if (fileName.endsWith(".jar")) {
                    InputStream embeddedJarInputStream = jarInputStream;
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    IOUtils.copy(embeddedJarInputStream, byteArrayOutputStream);
                    final JarMetadata jarMetadata = new JarMetadata(getJarFileName(fileName));

                    if (jarMetadata != null) {
                        jarMetadata.setJarContents(byteArrayOutputStream.toByteArray());
                        embeddedJars.add(jarMetadata);
                    }
                } else if (fileName.endsWith(".class")) {
                    String className = fileName.substring(0, fileName.length() - ".class".length()).replaceAll("/", ".");
                    int lastPoint = className.lastIndexOf(".");
                    String packageName = null;
                    if (lastPoint > 0) {
                        packageName = className.substring(0, lastPoint);
                        SortedSet<PackageInfo> currentJarPackages = jarPackages.get(FilenameUtils.getBaseName(jarFilePath));
                        if (currentJarPackages == null) {
                            currentJarPackages = new TreeSet<>();
                        }
                        PackageInfo packageInfo = new PackageInfo(jarFilePath, packageName);
                        packageInfo.setLicenseKey("");
                        packageInfo.setVersion("");
                        packageInfo.setCopyrightStartYear(0);
                        packageInfo.setCopyrightEndYear(0);
                        packageInfo.setCopyrightOwner("");
                        currentJarPackages.add(packageInfo);
                        jarPackages.put(FilenameUtils.getBaseName(jarFilePath), currentJarPackages);
                    }
                }

            }
            jarInputStream.closeEntry();
        }

        jarInputStream.close();
        jarInputStream = null;

        if (!embeddedJars.isEmpty()) {
            for (JarMetadata jarMetadata : embeddedJars) {
                if (jarMetadata.getJarContents() != null) {
                    ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(jarMetadata.getJarContents());
                    processJarFile(byteArrayInputStream, jarMetadata.toString(), true, level, true, true);
                } else {
                    output(indent, "Couldn't find dependency for embedded JAR " + jarMetadata, true, false);
                }
            }
        }


        if (processMavenPom) {
            if (pomFilePath == null) {
                output(indent, "No POM found in " + jarFilePath);
            } else {
                output(indent, "Processing POM found at " + pomFilePath + " in " + jarFilePath + "...");
                ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(pomByteArray);
                processJarPOM(byteArrayInputStream, pomFilePath, jarFilePath, lookForNotice, lookForLicense, embeddedJars, level + 1);
            }
        }

        if (lookForLicense || lookForNotice) {
            if (lookForLicense) {
                output(indent, "No license found in " + jarFilePath);
            }
            if (lookForNotice) {
                output(indent, "No notice found in " + jarFilePath);
            }

            if (pomFilePath == null && lookForLicense && lookForNotice) {
                final JarMetadata jarMetadata = new JarMetadata(FilenameUtils.getBaseName(jarFilePath));
                if (StringUtils.isBlank(jarMetadata.getVersion())) {
                    output(indent, "Couldn't resolve version for JAR " + jarMetadata + ", can't query Maven Central repository without version !");
                } else {
                    List<Artifact> mavenCentralArtifacts = findArtifactInMavenCentral(jarMetadata.getName(), jarMetadata.getVersion(), jarMetadata.getClassifier());
                    if (mavenCentralArtifacts != null && mavenCentralArtifacts.size() == 1) {
                        Artifact mavenCentralArtifact = mavenCentralArtifacts.get(0);
                        Artifact resolvedArtifact = resolveArtifact(mavenCentralArtifact, level);
                        if (resolvedArtifact != null) {
                            // we have a copy of the local artifact, let's request the sources for it.
                            if (!"sources".equals(jarMetadata.getClassifier())) {
                                final Artifact artifact = new DefaultArtifact(resolvedArtifact.getGroupId(), resolvedArtifact.getArtifactId(), "sources", "jar", resolvedArtifact.getVersion());
                                File sourceJar = getArtifactFile(artifact, level);
                                if (sourceJar != null && sourceJar.exists()) {
                                    FileInputStream sourceJarInputStream = new FileInputStream(sourceJar);
                                    processJarFile(sourceJarInputStream, sourceJar.getPath(), false, level + 1, lookForNotice, lookForLicense);
                                    IOUtils.closeQuietly(sourceJarInputStream);
                                }
                            } else {
                                // we are already processing a sources artifact, we need to load the pom artifact to extract information from there
                                final Artifact artifact = new DefaultArtifact(resolvedArtifact.getGroupId(), resolvedArtifact.getArtifactId(), null, "pom", resolvedArtifact.getVersion());
                                File artifactPom = getArtifactFile(artifact, level);
                                if (artifactPom != null && artifactPom.exists()) {
                                    output(indent, "Processing POM for " + artifact + "...");
                                    processPOM(lookForNotice, lookForLicense, jarFilePath, embeddedJars, level + 1, new FileInputStream(artifactPom), false);
                                }
                            }
                        } else {
                            output(indent, "===>  Couldn't resolve artifact " + mavenCentralArtifact + " in Maven Central. Please resolve license and notice files manually!", false, true);
                        }
                    } else {
                        output(indent, "===>  Couldn't find nor POM, license or notice. Please check manually!", false, true);
                    }
                }
            }
        }

        output(indent, "Done processing JAR " + jarFilePath + ".", false, true);

    }

    private String getIndent(int level) {
        String indent = START_INDENT;
        int i = level;
        while (i-- != 0) {
            indent += INDENT_STEP;
        }
        return indent;
    }

    private String getJarFileName(String fileName) {
        final int lastSlash = fileName.lastIndexOf('/');
        fileName = lastSlash > 0 ? fileName.substring(lastSlash + 1) : fileName;
        final int endVersion = fileName.lastIndexOf('.');
        if (endVersion > 0) {
            return fileName.substring(0, endVersion);
        } else {
            return fileName;
        }
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

    private boolean isNotice(String fileName, String jarFilePath) {
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
                        System.out.println("Potential notice file " + fileName + " in JAR " + jarFilePath
                                + " was NOT handled. You might want to check manually.");
                    }
                }
            }

        }

        return isNotice;
    }

    private boolean isLicense(String fileName, String jarFilePath) {
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
                        System.out.println("Potential license file " + fileName + " in JAR " + jarFilePath
                                + " was NOT handled. You might want to check manually.");
                    }
                }
            }
        }

        return isLicense;
    }

    private void processJarPOM(InputStream pomInputStream, String pomFilePath, String jarFilePath, boolean lookForNotice, boolean lookForLicense, Set<JarMetadata> embeddedJarNames, int level) throws
            IOException {
        // if we're not looking for notice or license and don't have embedded jars, don't process at all
        if (embeddedJarNames.isEmpty() && !lookForNotice && !lookForLicense) {
            return;
        }

        processPOM(lookForNotice, lookForLicense, jarFilePath, embeddedJarNames, level, pomInputStream, true);

    }

    private void processPOM(boolean lookForNotice, boolean lookForLicense, String jarFilePath, Set<JarMetadata> embeddedJarNames, int level, InputStream pomInputStream, boolean retrieveSourceJar) throws IOException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        final String indent = getIndent(level);
        try {
            final Model model = reader.read(pomInputStream);
            final Parent parent = model.getParent();
            String parentGroupId = null;
            String parentVersion = null;
            if (parent != null) {
                parentGroupId = parent.getGroupId();
                parentVersion = parent.getVersion();
            }

            if (model.getLicenses() != null && model.getLicenses().size() > 0) {
                for (License license : model.getLicenses()) {
                    output(indent, "Found license in POM for " + model.getId());
                    String licenseName = license.getName();
                    // let's try to resolve the license by name
                    KnownLicense knownLicense = getKnowLicenseByName(licenseName);
                    if (knownLicense != null) {
                        SortedSet<LicenseFile> licenseFiles = knownLicensesFound.get(knownLicense);
                        if (licenseFiles == null) {
                            licenseFiles = new TreeSet<>();
                        }
                        LicenseFile licenseFile = new LicenseFile(jarFilePath, knownLicense.getTextToUse());
                        licenseFile.getKnownLicenses().add(knownLicense);
                        licenseFiles.add(licenseFile);
                        knownLicensesFound.put(knownLicense, licenseFiles);
                        // found a license for this project, let's see if we can resolve it
                        Set<LicenseFile> projectLicenseFiles = projectToLicenseFiles.get(jarFilePath);
                        if (projectLicenseFiles == null) {
                            projectLicenseFiles = new HashSet<>();
                        }
                        if (projectLicenseFiles.size() > 0) {
                            LicenseFile firstLicenseFile = licenseFiles.iterator().next();
                            firstLicenseFile.getKnownLicenses().add(knownLicense);
                        }
                        projectToLicenseFiles.put(jarFilePath, projectLicenseFiles);
                    } else if (license.getUrl() != null) {
                        try {
                            URL licenseURL = new URL(license.getUrl());
                            String licenseText = IOUtils.toString(licenseURL);
                            if (StringUtils.isNotBlank(licenseText)) {
                                // found a license for this project, let's see if we can resolve it
                                Set<LicenseFile> licenseFiles = projectToLicenseFiles.get(jarFilePath);
                                if (licenseFiles == null) {
                                    licenseFiles = new HashSet<>();
                                }
                                LicenseFile newLicenseFile = new LicenseFile(jarFilePath, licenseText);
                                if (licenseFiles.contains(newLicenseFile)) {
                                    for (LicenseFile licenseFile : licenseFiles) {
                                        if (licenseFile.equals(newLicenseFile)) {
                                            newLicenseFile = licenseFile;
                                            break;
                                        }
                                    }
                                }
                                resolveKnownLicensesByText(newLicenseFile);
                                licenseFiles.add(newLicenseFile);
                                projectToLicenseFiles.put(jarFilePath, licenseFiles);
                            }
                        } catch (MalformedURLException mue) {
                            output(indent, "Invalid license URL : " + license.getUrl() + ": " + mue.getMessage());
                        }
                    } else {
                        // couldn't resolve the license
                    }
                }
            } else {
                if (parent != null) {
                    Artifact parentArtifact = new DefaultArtifact(parent.getGroupId(), parent.getArtifactId(), "pom", parent.getVersion());
                    Artifact resolvedParentArtifact = resolveArtifact(parentArtifact, level);
                    if (resolvedParentArtifact != null) {
                        output(indent, "Processing parent POM " + parentArtifact + "...");
                        processPOM(lookForNotice, lookForLicense, jarFilePath, embeddedJarNames, level + 1, new FileInputStream(resolvedParentArtifact.getFile()), false);
                    } else {
                        output(indent, "Couldn't resolve parent POM " + parentArtifact + " !");
                    }
                }
            }

            String scmConnection = null;
            if (model.getScm() != null) {
                scmConnection = model.getScm().getDeveloperConnection();
                if (scmConnection == null) {
                    model.getScm().getConnection();
                }
                if (scmConnection == null) {
                    // @todo let's try to resolve in the parent hierarcy
                }
            }

            /*
            if (scmManager != null && scmConnection != null) {
                ScmProvider scmProvider;
                File checkoutDir = new File(outputDirectory, "source-checkouts");
                checkoutDir.mkdirs();
                File wcDir = new File(checkoutDir, model.getArtifactId());
                wcDir.mkdirs();
                try {
                    scmProvider = scmManager.getProviderByUrl(scmConnection);
                    ScmRepository scmRepository = scmManager.makeScmRepository(scmConnection);
                    CheckOutScmResult scmResult = scmProvider.checkOut(scmRepository, new ScmFileSet(wcDir));
                    if (!scmResult.isSuccess()) {
                    }
                } catch (ScmRepositoryException e) {
                    e.printStackTrace();
                } catch (NoSuchScmProviderException e) {
                    e.printStackTrace();
                } catch (ScmException e) {
                    e.printStackTrace();
                }
            }
            */
            if (!embeddedJarNames.isEmpty()) {
                final List<Dependency> dependencies = model.getDependencies();
                Map<String, Dependency> artifactToDep = new HashMap<String, Dependency>(dependencies.size());
                for (Dependency dependency : dependencies) {
                    artifactToDep.put(dependency.getArtifactId(), dependency);
                }

                for (JarMetadata jarName : embeddedJarNames) {
                    final Dependency dependency = artifactToDep.get(jarName.name);
                    if (dependency != null) {
                        File jarFile = getArtifactFile(new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(), null, "jar", jarName.version), level);
                        if (jarFile != null && jarFile.exists()) {
                            FileInputStream jarInputStream = new FileInputStream(jarFile);
                            processJarFile(jarInputStream, jarFile.getPath(), true, level, true, true);
                            IOUtils.closeQuietly(jarInputStream);
                        } else {
                            output(indent, "Couldn't find dependency for embedded JAR " + jarName, true, false);
                        }
                    }
                }
            }

            if (retrieveSourceJar) {
                final String groupId = model.getGroupId() != null ? model.getGroupId() : parentGroupId;
                final String version = model.getVersion() != null ? model.getVersion() : parentVersion;
                final Artifact artifact = new DefaultArtifact(groupId, model.getArtifactId(), "sources", "jar", version);

                File sourceJar = getArtifactFile(artifact, level);
                if (sourceJar != null && sourceJar.exists()) {
                    FileInputStream sourceJarInputStream = new FileInputStream(sourceJar);
                    processJarFile(sourceJarInputStream, sourceJar.getPath(), false, level, lookForNotice, lookForLicense);
                    IOUtils.closeQuietly(sourceJarInputStream);
                }
            }
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }

    private Artifact resolveArtifact(Artifact artifact, int level) {
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(remoteRepositories);

        ArtifactResult artifactResult;
        try {
            artifactResult = repositorySystem.resolveArtifact(repositorySystemSession, request);
            return artifactResult.getArtifact();
        } catch (ArtifactResolutionException e) {
            output(getIndent(level), "Couldn't find artifact " + artifact + " : " + e.getMessage(), true, true);
        }
        return null;
    }

    private File getArtifactFile(Artifact artifact, int level) {
        Artifact resolvedArtifact = resolveArtifact(artifact, level);
        if (resolvedArtifact == null) {
            return null;
        }
        return resolvedArtifact.getFile();
    }

    private static Client getRestClient(String targetUrl) {

        if (clients.containsKey(targetUrl)) {
            return clients.get(targetUrl);
        }

        Client client = null;
        if (targetUrl != null) {
            if (targetUrl.startsWith("https://")) {
                try {
                    // Create a trust manager that does not validate certificate chains
                    TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
                    };
                    // Create all-trusting host name verifier
                    HostnameVerifier allHostsValid = new HostnameVerifier() {
                        public boolean verify(String hostname, SSLSession session) {
                            return true;
                        }
                    };
                    SSLContext sslContext = SSLContext.getInstance("SSL");
                    sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                    client = ClientBuilder.newBuilder().
                            sslContext(sslContext).
                            hostnameVerifier(allHostsValid).build();
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                } catch (KeyManagementException e) {
                    e.printStackTrace();
                }
            } else {
                client = ClientBuilder.newClient();

            }
        }
        if (client == null) {
            return null;
        }

        client.property(ClientProperties.CONNECT_TIMEOUT, 1000);
        client.property(ClientProperties.READ_TIMEOUT,    3000);
        /*
        HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(contextServerSettings.getContextServerUsername(), contextServerSettings.getContextServerPassword());
        client.register(feature);
        */
        clients.put(targetUrl, client);
        return client;
    }

    /**
     * This method will use the public REST API at search.maven.org to search for Maven dependencies that contain
     * a package using an URL such as :
     *
     * http://search.maven.org/solrsearch/select?q=fc:%22com.mchange.v2.c3p0%22&rows=20&wt=json
     *
     * @param packageName
     */
    public static List<String> findPackageInMavenCentral(String packageName) {
        List<String> artifactResults = new ArrayList<String>();
        Client client = getRestClient(MAVEN_SEARCH_HOST_URL);

        WebTarget target = client.target(MAVEN_SEARCH_HOST_URL).path("solrsearch/select")
                .queryParam("q", "fc:\"" + packageName + "\"")
                .queryParam("rows", "5")
                .queryParam("wt", "json");

        Invocation.Builder invocationBuilder =
                target.request(MediaType.APPLICATION_JSON_TYPE);

        Map<String, Object> searchResults = null;
        try {
            Response response = invocationBuilder.get();
            searchResults= (Map<String, Object>) response.readEntity(Map.class);
        } catch (ProcessingException pe) {
            artifactResults.add(NETWORK_ERROR_PREFIX + pe.getMessage());
        }

        if (searchResults != null) {
            Map<String,Object> searchResponse = (Map<String,Object>) searchResults.get("response");
            Integer searchResultCount = (Integer) searchResponse.get("numFound");
            List<Map<String,Object>> docs = (List<Map<String,Object>>) searchResponse.get("docs");
            for (Map<String,Object> doc : docs) {
                String artifactId = (String) doc.get("id");
                artifactResults.add(artifactId);
            }
        }

        return artifactResults;
    }

    /**
     * This method will use the public REST API at search.maven.org to search for Maven dependencies that match
     * the artifactId and version ID as in the following example
     *
     * http://search.maven.org/solrsearch/select?q=g:%22com.google.inject%22%20AND%20a:%22guice%22%20AND%20v:%223.0%22%20AND%20l:%22javadoc%22%20AND%20p:%22jar%22&rows=20&wt=json
     *
     * @param artifactId
     * @param version
     * @return
     */
    public List<Artifact> findArtifactInMavenCentral(String artifactId, String version, String classifier) {
        List<Artifact> artifactResults = new ArrayList<Artifact>();
        Client client = getRestClient(MAVEN_SEARCH_HOST_URL);

        StringBuilder query = new StringBuilder();
        if (artifactId != null) {
            query.append("a:\"" + artifactId + "\"");
        }
        if (version != null) {
            query.append(" AND v:\"" + version + "\"");
        }
        if (classifier != null) {
            query.append(" AND l:\"" + classifier + "\"");
        }

        WebTarget target = client.target(MAVEN_SEARCH_HOST_URL).path("solrsearch/select")
                .queryParam("q", query.toString())
                .queryParam("rows", "5")
                .queryParam("wt", "json");

        Invocation.Builder invocationBuilder =
                target.request(MediaType.APPLICATION_JSON_TYPE);

        Map<String, Object> searchResults = null;
        try {
            Response response = invocationBuilder.get();
            searchResults= (Map<String, Object>) response.readEntity(Map.class);
        } catch (ProcessingException pe) {
            pe.printStackTrace();
        }

        if (searchResults != null) {
            Map<String,Object> searchResponse = (Map<String,Object>) searchResults.get("response");
            Integer searchResultCount = (Integer) searchResponse.get("numFound");
            List<Map<String,Object>> docs = (List<Map<String,Object>>) searchResponse.get("docs");
            for (Map<String,Object> doc : docs) {
                String foundId = (String) doc.get("id");
                artifactResults.add(new DefaultArtifact(foundId));
            }
        }

        return artifactResults;
    }

    /**
     * Find the closest matching license using a LevenshteinDistance edit distance algorithm because the two license
     * texts. If the edit distance is larger than the EDIT_DISTANCE_THRESHOLD it is possible that no license matches,
     * which is what we want if we are actually not matching a real license.
     * @param licenseFile the license we want to match against the known licenses.
     * @return
     */
    public KnownLicense findClosestMatchingKnownLicense(LicenseFile licenseFile) {
        KnownLicense closestMatchingKnownLicense = null;
        int smallestEditDistance = Integer.MAX_VALUE;
        for (KnownLicense knownLicense : knownLicenses.getLicenses().values()) {
            for (TextVariant textVariant : knownLicense.getTextVariants()) {
                int editDistance = StringUtils.getLevenshteinDistance(textVariant.getText(), licenseFile.getText(), EDIT_DISTANCE_THRESHOLD);
                if (editDistance >= 0 && editDistance < smallestEditDistance) {
                    smallestEditDistance = editDistance;
                    closestMatchingKnownLicense = knownLicense;
                }
            }
        }
        return closestMatchingKnownLicense;
    }

    public void resolveKnownLicensesByText(LicenseFile licenseFile) {
        List<KnownLicense> foundLicenses = new ArrayList<>();
        String licenseText = licenseFile.getText();
        if (knownLicenses.getLicenses() == null) {
            return;
        }
        SortedMap<TextVariant,KnownLicense> textVariantsBySize = new TreeMap<>(new Comparator<TextVariant>() {
            @Override
            public int compare(TextVariant o1, TextVariant o2) {
                return o2.getText().length() - o1.getText().length();
            }
        });
        for (KnownLicense knownLicense : knownLicenses.getLicenses().values()) {
            for (TextVariant textVariant : knownLicense.getTextVariants()) {
                textVariantsBySize.put(textVariant, knownLicense);
            }
        }
        for (SortedMap.Entry<TextVariant,KnownLicense> textVariantBySizeEntry : textVariantsBySize.entrySet()) {
            Matcher textVariantMatcher = textVariantBySizeEntry.getKey().getCompiledTextPattern().matcher(licenseText);
            if (textVariantMatcher.find()) {
                foundLicenses.add(textVariantBySizeEntry.getValue());
                licenseText = licenseText.substring(textVariantMatcher.end());
                break;
            }
        }
        if (foundLicenses.size() == 0) {
            System.out.println("No known license found for license file " + licenseFile);
        }
        licenseFile.setKnownLicenses(foundLicenses);
        if (StringUtils.isNotBlank(licenseText)) {
            licenseFile.setAdditionalLicenseText(licenseText);
        }
    }

    public KnownLicense getKnowLicenseByName(String licenseName) {
        for (KnownLicense knownLicense : knownLicenses.getLicenses().values()) {
            if (knownLicense.getName().equals(licenseName)) {
                return knownLicense;
            }
            if (knownLicense.getAliases() != null && knownLicense.getAliases().size() > 0) {
                for (String alias : knownLicense.getAliases()) {
                    if (alias.equals(licenseName)) {
                        return knownLicense;
                    }
                }
            }
        }
        return null;
    }
}
